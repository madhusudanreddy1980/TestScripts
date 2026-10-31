package com.batchauto.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.batchauto.config.FieldSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.config.RejectSpec;
import com.batchauto.config.TargetSpec;
import com.batchauto.config.ValidationRules;
import com.batchauto.config.ValidationRules.TargetColumnRule;
import com.batchauto.db.ReadOnlyDatabase;
import com.batchauto.expr.Expressions;
import com.batchauto.monitor.MonitorOutcome;
import com.batchauto.security.Masking;

/**
 * Baseline capture and post-batch reconciliation (Validations A–F). Every assertion stores the
 * rule, the parameterised SQL that produced the evidence, expected vs actual, status, timestamp
 * and record identifiers. Sensitive columns are compared raw but reported masked.
 */
public final class Reconciler {

    private final JobConfig job;
    private final ValidationRules rules;
    private final ReadOnlyDatabase db;
    private final TargetSpec t;
    private final Map<String, String> mask = new HashMap<>();

    public Reconciler(JobConfig job, ValidationRules rules, ReadOnlyDatabase db) {
        this.job = job;
        this.rules = rules;
        this.db = db;
        this.t = job.target();
        for (Map.Entry<String, TargetColumnRule> e : rules.targetMapping().entrySet()) {
            TargetColumnRule r = e.getValue();
            FieldSpec f = r.field() != null ? job.field(r.field()) : null;
            boolean sensitive = r.sensitive() != null ? r.sensitive() : (f != null && f.sensitive());
            if (sensitive) {
                mask.put(e.getKey(), f != null ? f.mask() : "full");
            }
        }
    }

    public Map<String, String> mask() { return Map.copyOf(mask); }

    // ------------------------------------------------------------------ queries
    private record KeyRows(Map<String, Map<String, Object>> rows, Map<String, Integer> counts, String sql) { }

    private static Map<String, Object> inParams(List<String> keys) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            p.put("k" + i, keys.get(i));
        }
        return p;
    }

    private static String placeholders(int n) {
        List<String> ph = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ph.add(":k" + i);
        }
        return String.join(", ", ph);
    }

    private KeyRows rowsForKeys(Connection c, List<String> keys) throws SQLException {
        if (keys.isEmpty()) {
            return new KeyRows(Map.of(), Map.of(), "");
        }
        String sql = "SELECT * FROM " + t.table() + " WHERE " + t.keyColumn() + " IN (" + placeholders(keys.size()) + ")";
        Map<String, Map<String, Object>> rows = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> r : db.query(c, sql, inParams(keys))) {
            String k = String.valueOf(r.get(t.keyColumn()));
            rows.put(k, r);
            counts.merge(k, 1, Integer::sum);
        }
        return new KeyRows(rows, counts, sql);
    }

    private record Fingerprint(String hash, Integer rows, String sql) { }

    private Fingerprint fingerprint(Connection c, List<String> keys) throws SQLException {
        if (!rules.guardUnrelatedRows()) {
            return new Fingerprint(null, null, "");
        }
        String where = keys.isEmpty() ? "" : " WHERE " + t.keyColumn() + " NOT IN (" + placeholders(keys.size()) + ")";
        String sql = "SELECT * FROM " + t.table() + where + " ORDER BY " + t.keyColumn() + " LIMIT :_lim";
        Map<String, Object> p = inParams(keys);
        p.put("_lim", rules.unrelatedRowsLimit() + 1);
        List<Map<String, Object>> rows = db.query(c, sql, p);
        if (rows.size() > rules.unrelatedRowsLimit()) {
            return new Fingerprint(null, rows.size(), sql);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> r : rows) {
                TreeMap<String, String> sorted = new TreeMap<>();
                r.forEach((k, v) -> sorted.put(k, String.valueOf(v)));
                md.update(sorted.toString().getBytes(StandardCharsets.UTF_8));
            }
            return new Fingerprint(HexFormat.of().formatHex(md.digest()), rows.size(), sql);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(Connection c) throws SQLException {
        return ((Number) db.query(c, countSql(), Map.of()).get(0).get("n")).intValue();
    }

    private String countSql() {
        return "SELECT COUNT(*) AS n FROM " + t.table();
    }

    // ----------------------------------------------------------------- baseline
    public Baseline captureBaseline(List<Object> keys) {
        List<String> k = new ArrayList<>(new TreeSet<>(keys.stream().filter(Objects::nonNull).map(o -> String.valueOf(o)).toList()));
        return db.snapshot(c -> {
            KeyRows kr = rowsForKeys(c, k);
            int n = count(c);
            Fingerprint fp = fingerprint(c, k);
            List<String> qs = new ArrayList<>();
            for (String q : new String[] {kr.sql(), countSql(), fp.sql()}) {
                if (!q.isEmpty()) {
                    qs.add(q);
                }
            }
            return new Baseline(kr.rows(), n, fp.hash(), fp.rows(), Instant.now().toString(), qs);
        });
    }

    // ---------------------------------------------------------------- reconcile
    private record FinalState(KeyRows rows, int targetAfter, Fingerprint fp, List<String> lineageKeys, String lineageSql,
                              List<Map<String, Object>> rejects, String rejectSql) { }

    public List<Assertion> reconcile(String fileName, MonitorOutcome outcome, List<RecordExpectation> expectations,
                                     Baseline baseline, int submitted, String expectedTerminal) {
        List<Assertion> out = new ArrayList<>();
        Set<String> keySet = new TreeSet<>(baseline.rows().keySet());
        expectations.forEach(e -> keySet.add(String.valueOf(e.key())));
        List<String> keys = new ArrayList<>(keySet);
        List<Object> execIds = new ArrayList<>();
        execIds.add(outcome.executionId());

        // A — status and identity
        String note = outcome.errorMessage() != null ? outcome.errorMessage() : String.join("; ", outcome.notes());
        out.add(Assertion.of("A1", "BATCH_STATUS", "execution for this run's file reaches " + expectedTerminal,
                outcome.query(), Map.of("file_name", fileName), expectedTerminal, outcome.status(),
                expectedTerminal.equals(outcome.status()), execIds).withDetails(note));
        out.add(Assertion.of("A2", "BATCH_STATUS", "exactly one execution is correlated to the submitted file name",
                outcome.query(), Map.of("file_name", fileName), "1 execution", outcome.executionId() != null ? "found" : "none",
                outcome.executionId() != null && !"AMBIGUOUS".equals(outcome.status()), execIds));

        FinalState fin = db.snapshot(c -> {
            KeyRows kr = rowsForKeys(c, keys);
            int after = count(c);
            Fingerprint fp = fingerprint(c, keys);
            List<String> lineage = new ArrayList<>();
            String lineageSql = "";
            if (t.lineageColumn() != null) {
                lineageSql = "SELECT " + t.keyColumn() + " AS k FROM " + t.table() + " WHERE " + t.lineageColumn() + " = :file_name";
                for (Map<String, Object> r : db.query(c, lineageSql, Map.of("file_name", fileName))) {
                    lineage.add(String.valueOf(r.get("k")));
                }
            }
            List<Map<String, Object>> rejects = null;
            String rejectSql = "";
            RejectSpec r = t.reject();
            if (r != null && outcome.executionId() != null) {
                rejectSql = "SELECT " + r.keyColumn() + " AS record_key, " + r.reasonColumn() + " AS reason FROM "
                        + r.table() + " WHERE " + r.executionIdColumn() + " = :execution_id";
                rejects = db.query(c, rejectSql, Map.of("execution_id", outcome.executionId()));
            }
            return new FinalState(kr, after, fp, lineage, lineageSql, rejects, rejectSql);
        });

        if (!"COMPLETED".equals(expectedTerminal)) {
            out.addAll(noChangeChecks(keys, baseline, fin, fileName));
        } else {
            out.addAll(countChecks(outcome, expectations, submitted, baseline.targetCount(), fin));
            out.addAll(recordChecks(expectations, baseline, fin, fileName));
            out.addAll(rejectChecks(expectations, fin, outcome.executionId()));
            if (t.lineageColumn() != null) {
                Set<String> accepted = new LinkedHashSet<>();
                expectations.stream().filter(e -> e.action().equals("insert") || e.action().equals("update"))
                        .forEach(e -> accepted.add(String.valueOf(e.key())));
                List<Object> unexpected = new ArrayList<>(new TreeSet<>(fin.lineageKeys()).stream()
                        .filter(k -> !accepted.contains(k)).toList());
                out.add(Assertion.of("C9", "UNEXPECTED_RECORDS", "no target rows stamped with this file other than expected keys",
                        fin.lineageSql(), Map.of("file_name", fileName), 0, unexpected.size(), unexpected.isEmpty(), unexpected));
            }
        }
        out.add(unrelatedCheck(baseline, fin.fp()));
        return out;
    }

    // ----------------------------------------------------------------- sections
    private List<Assertion> countChecks(MonitorOutcome outcome, List<RecordExpectation> exps, int submitted,
                                        int before, FinalState fin) {
        Map<String, Object> names = new LinkedHashMap<>(Expectations.summarize(exps));
        names.put("submitted", submitted);
        names.put("generated", submitted);
        names.put("target_rows_before", before);
        names.put("target_rows_after", fin.targetAfter());
        names.put("reject_rows", fin.rejects() == null ? null : fin.rejects().size());
        outcome.counts().forEach((k, v) -> names.put(k, v == null ? null : ((Number) v).longValue()));
        List<Assertion> out = new ArrayList<>();
        int i = 1;
        for (String eq : rules.countReconciliation()) {
            String id = "B" + i++;
            String query = outcome.query() + " | " + countSql();
            try {
                boolean ok = Expressions.test(eq, names);
                out.add(Assertion.of(id, "COUNT_RECONCILIATION", eq, query, Map.of(), "true", render(eq, names), ok, List.of()));
            } catch (Expressions.ExpressionException e) {
                out.add(Assertion.of(id, "COUNT_RECONCILIATION", eq, query, Map.of(), "true", null, false, List.of())
                        .withDetails("cannot evaluate: " + e.getMessage()));
            }
        }
        return out;
    }

    private List<Assertion> recordChecks(List<RecordExpectation> exps, Baseline baseline, FinalState fin, String fileName) {
        List<Assertion> out = new ArrayList<>();
        Map<String, List<RecordExpectation>> byKey = new LinkedHashMap<>();
        exps.forEach(e -> byKey.computeIfAbsent(String.valueOf(e.key()), x -> new ArrayList<>()).add(e));
        Set<String> mapped = rules.targetMapping().keySet();
        Set<String> ignore = new LinkedHashSet<>(t.auditColumns());
        if (t.lineageColumn() != null) {
            ignore.add(t.lineageColumn());
        }
        for (Map.Entry<String, List<RecordExpectation>> en : byKey.entrySet()) {
            String k = en.getKey();
            List<RecordExpectation> list = en.getValue();
            List<RecordExpectation> accepted = list.stream()
                    .filter(e -> e.action().equals("insert") || e.action().equals("update")).toList();
            Map<String, Object> params = Map.of("k0", k);
            List<Object> ids = new ArrayList<>();
            ids.add(k);
            list.stream().map(RecordExpectation::sourceKey).filter(Objects::nonNull)
                    .filter(s -> !String.valueOf(s).equals(k)).forEach(ids::add);
            String sql = fin.rows().sql();
            Map<String, Object> row = fin.rows().rows().get(k);
            Map<String, Object> base = baseline.rows().get(k);

            if (accepted.isEmpty() && list.stream().anyMatch(e -> e.action().equals("unknown"))) {
                out.add(Assertion.of("C-" + k, "DATA_INTEGRITY", "outcome predictable", sql, params, "known", "unknown",
                        false, ids).skipped("expectation could not be computed (invalid input values)"));
                continue;
            }
            if (accepted.isEmpty()) {
                if (base == null) {
                    out.add(Assertion.of("D-" + k, "REJECTED_NOT_WRITTEN", "rejected key not inserted", sql, params,
                            "absent", row == null ? "absent" : "present", row == null, ids));
                } else {
                    List<Map<String, Object>> diffs = diff(base, row == null ? Map.of() : row, base.keySet(), null);
                    out.add(Assertion.of("D-" + k, "REJECTED_NOT_WRITTEN", "rejected key left unchanged", sql, params,
                            "unchanged", diffs.isEmpty() ? "unchanged" : "changed", row != null && diffs.isEmpty(), ids)
                            .withDiffs(diffs));
                }
                continue;
            }
            RecordExpectation fin2 = accepted.get(accepted.size() - 1);
            if (row == null) {
                out.add(Assertion.of("C-" + k, "DATA_INTEGRITY", "expected " + fin2.action() + "ed row exists", sql, params,
                        "present", "absent", false, ids));
                continue;
            }
            int n = fin.rows().counts().getOrDefault(k, 0);
            if (n > 1) {
                out.add(Assertion.of("D-" + k + "-dup", "DUPLICATES", "exactly one target row per key", sql, params, 1, n, false, ids));
            }
            boolean actionOk = (fin2.action().equals("insert") && base == null) || (fin2.action().equals("update") && base != null);
            Map<String, Object> expRow = new LinkedHashMap<>(fin2.expectedRow());
            if (t.lineageColumn() != null) {
                expRow.put(t.lineageColumn(), fileName);
            }
            List<Map<String, Object>> diffs = diff(expRow, row, expRow.keySet(), null);
            if (fin2.action().equals("update") && base != null) {
                Set<String> untouched = new LinkedHashSet<>(base.keySet());
                untouched.removeAll(mapped);
                untouched.removeAll(ignore);
                untouched.remove(t.keyColumn());
                diffs.addAll(diff(base, row, untouched, "unexpected_change"));
            }
            String rule = fin2.action() + ": mapped fields match" + (fin2.action().equals("update") ? " and non-mapped fields unchanged" : "");
            out.add(Assertion.of("C-" + k, "DATA_INTEGRITY", rule, sql, params,
                    fin2.action() + ", 0 differences", (base == null ? "insert" : "update") + ", " + diffs.size() + " differences",
                    actionOk && diffs.isEmpty(), ids).withDiffs(diffs));
        }
        return out;
    }

    private List<Assertion> rejectChecks(List<RecordExpectation> exps, FinalState fin, Object executionId) {
        List<RecordExpectation> expected = exps.stream().filter(e -> e.action().equals("reject")).toList();
        if (fin.rejects() == null) {
            if (expected.isEmpty()) {
                return List.of();
            }
            return List.of(Assertion.of("F-rej", "REJECTS", "reject details available", fin.rejectSql(), Map.of(),
                    expected.size(), null, false, List.of()).skipped("no reject table configured or execution id unknown"));
        }
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : fin.rejects()) {
            byKey.computeIfAbsent(String.valueOf(r.get("record_key")), x -> new ArrayList<>()).add(String.valueOf(r.get("reason")));
        }
        List<Assertion> out = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        params.put("execution_id", executionId);
        for (RecordExpectation e : expected) {
            List<String> reasons = byKey.getOrDefault(String.valueOf(e.key()), List.of());
            boolean ok = e.reason() != null && reasons.stream().anyMatch(r -> r.contains(e.reason()));
            List<Object> ids = new ArrayList<>();
            ids.add(e.key());
            out.add(Assertion.of("F-" + e.key() + "-" + e.index(), "REJECTS",
                    "record " + e.index() + " rejected with " + e.reason() + " (" + e.ruleId() + ")",
                    fin.rejectSql(), params, e.reason(), reasons.isEmpty() ? null : reasons, ok, ids));
        }
        Set<String> expectedKeys = new LinkedHashSet<>();
        expected.forEach(e -> expectedKeys.add(String.valueOf(e.key())));
        List<Object> unexpected = new ArrayList<>(byKey.keySet().stream().filter(k -> !expectedKeys.contains(k)).sorted().toList());
        out.add(Assertion.of("F-unexpected", "REJECTS", "no unexpected rejections", fin.rejectSql(), params, List.of(),
                unexpected, unexpected.isEmpty(), unexpected)
                .withDetails(String.join("; ", unexpected.stream().map(k -> k + ": " + byKey.get(String.valueOf(k))).toList())));
        return out;
    }

    private List<Assertion> noChangeChecks(List<String> keys, Baseline baseline, FinalState fin, String fileName) {
        List<Object> changed = new ArrayList<>();
        for (String k : keys) {
            Map<String, Object> base = baseline.rows().get(k);
            Map<String, Object> row = fin.rows().rows().get(k);
            if ((base == null) != (row == null) || (base != null && !diff(base, row, base.keySet(), null).isEmpty())) {
                changed.add(k);
            }
        }
        List<Assertion> out = new ArrayList<>();
        out.add(Assertion.of("D-nochange", "NO_PARTIAL_WRITES", "failed batch left all test keys unchanged",
                fin.rows().sql(), Map.of(), 0, changed.size(), changed.isEmpty(), changed));
        if (!fin.lineageSql().isEmpty()) {
            out.add(Assertion.of("C9", "UNEXPECTED_RECORDS", "no rows stamped with the failed file", fin.lineageSql(),
                    Map.of("file_name", fileName), 0, fin.lineageKeys().size(), fin.lineageKeys().isEmpty(),
                    new ArrayList<>(fin.lineageKeys())));
        }
        return out;
    }

    private Assertion unrelatedCheck(Baseline baseline, Fingerprint after) {
        String rule = "rows outside the test keys unchanged (SHA-256 fingerprint)";
        if (!rules.guardUnrelatedRows()) {
            return Assertion.of("C10", "UNRELATED_ROWS", rule, "", Map.of(), null, null, true, List.of())
                    .skipped("guard_unrelated_rows is off");
        }
        if (baseline.unrelatedFingerprint() == null || after.hash() == null) {
            return Assertion.of("C10", "UNRELATED_ROWS", rule, after.sql(), Map.of(), null, after.rows(), true, List.of())
                    .skipped("table larger than unrelated_rows_limit (" + rules.unrelatedRowsLimit() + ")");
        }
        boolean ok = baseline.unrelatedFingerprint().equals(after.hash());
        return Assertion.of("C10", "UNRELATED_ROWS", rule, after.sql(), Map.of(),
                baseline.unrelatedRows() + " rows / " + baseline.unrelatedFingerprint().substring(0, 12),
                after.rows() + " rows / " + after.hash().substring(0, 12), ok, List.of());
    }

    private List<Map<String, Object>> diff(Map<String, Object> expected, Map<String, Object> actual, Set<String> cols, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : new TreeSet<>(cols)) {
            Object ev = expected.get(c);
            Object av = actual.get(c);
            if (!Values.equal(ev, av)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("column", c);
                d.put("expected", show(c, ev));
                d.put("actual", show(c, av));
                d.put("kind", kind == null ? "mapped" : kind);
                out.add(d);
            }
        }
        return out;
    }

    private Object show(String col, Object v) {
        return mask.containsKey(col) && v != null ? Masking.mask(v, mask.get(col)) : Values.plain(v);
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    static String render(String eq, Map<String, Object> names) {
        Matcher m = NAME.matcher(eq);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String n = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(names.containsKey(n) ? String.valueOf(names.get(n)) : n));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
