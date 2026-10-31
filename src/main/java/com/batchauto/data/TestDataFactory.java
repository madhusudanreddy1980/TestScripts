package com.batchauto.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.batchauto.config.EligibilitySpec;
import com.batchauto.config.FieldSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.db.ReadOnlyDatabase;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.DataShortageException;
import com.batchauto.expr.Expressions;

/** Eligible-data retrieval and record building, driven entirely by job metadata. */
public final class TestDataFactory {

    private final JobConfig job;
    private final Path jobDir;
    private final ReadOnlyDatabase db;
    private final String runId;

    public TestDataFactory(JobConfig job, Path jobDir, ReadOnlyDatabase db, String runId) {
        this.job = job;
        this.jobDir = jobDir;
        this.db = db;
        this.runId = runId;
    }

    public String eligibilitySql() {
        try {
            String sql = Files.readString(jobDir.resolve(job.eligibility().queryFile())).strip();
            while (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).strip();
            }
            return sql;
        } catch (java.io.IOException e) {
            throw new ConfigException("cannot read eligibility SQL", e);
        }
    }

    /** Runs the eligibility query (paged) and re-checks every row against the configured assertions. */
    public List<EligibleRow> fetchEligible() {
        EligibilitySpec spec = job.eligibility();
        String paged = "SELECT * FROM (" + eligibilitySql() + ") eligible_q LIMIT :_limit OFFSET :_offset";
        List<Map<String, Object>> rows = new ArrayList<>();
        int offset = 0;
        while (rows.size() < spec.maxRows()) {
            int page = Math.min(spec.pageSize(), spec.maxRows() - rows.size());
            Map<String, Object> params = new HashMap<>(spec.params());
            params.put("_limit", page);
            params.put("_offset", offset);
            List<Map<String, Object>> batch = db.query(paged, params);
            rows.addAll(batch);
            if (batch.size() < page) {
                break;
            }
            offset += page;
        }
        List<EligibleRow> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!r.containsKey(spec.keyColumn())) {
                throw new ConfigException("eligibility query does not return key column " + spec.keyColumn());
            }
            List<String> reasons = new ArrayList<>();
            for (String a : spec.assertions()) {
                try {
                    if (!Expressions.test(a, r)) {
                        reasons.add("failed: " + a);
                    }
                } catch (Expressions.ExpressionException e) {
                    reasons.add("error: " + e.getMessage());
                }
            }
            out.add(new EligibleRow(r.get(spec.keyColumn()), r, reasons.isEmpty(), reasons));
        }
        return out;
    }

    public List<EligibleRow> select(List<EligibleRow> eligible, Integer count, List<String> keys) {
        List<EligibleRow> pool = eligible.stream().filter(EligibleRow::eligible).toList();
        if (keys != null && !keys.isEmpty()) {
            Map<String, EligibleRow> byKey = new LinkedHashMap<>();
            pool.forEach(e -> byKey.put(String.valueOf(e.key()), e));
            List<String> missing = keys.stream().filter(k -> !byKey.containsKey(k)).toList();
            if (!missing.isEmpty()) {
                throw new DataShortageException("requested records are not eligible or not found: " + missing);
            }
            return keys.stream().map(byKey::get).toList();
        }
        int need = count == null ? 1 : count;
        if (pool.size() < need) {
            long ineligible = eligible.size() - pool.size();
            throw new DataShortageException(job.jobId() + ": needed " + need + " eligible records, found " + pool.size()
                    + (ineligible > 0 ? " (" + ineligible + " rows returned by SQL failed eligibility assertions)" : ""));
        }
        return pool.subList(0, need);
    }

    private Object generated(FieldSpec f, int i) {
        return switch (f.generator()) {
            case "uuid" -> UUID.nameUUIDFromBytes((runId + ":" + f.name() + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
            case "run_sequence" -> runId + "-" + String.format("%05d", i + 1);
            default -> null;
        };
    }

    public List<GeneratedRecord> build(List<EligibleRow> rows) {
        List<GeneratedRecord> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            EligibleRow er = rows.get(i);
            Map<String, Object> values = new LinkedHashMap<>();
            for (FieldSpec f : job.fields()) {
                Object v;
                if (f.sourceColumn() != null) {
                    if (!er.row().containsKey(f.sourceColumn())) {
                        throw new ConfigException("field " + f.name() + ": column " + f.sourceColumn() + " not in eligibility result");
                    }
                    v = er.row().get(f.sourceColumn());
                } else if (f.generator() != null) {
                    v = generated(f, i);
                } else {
                    v = null;
                }
                if (v == null && f.defaultValue() != null) {
                    v = f.defaultValue();
                }
                try {
                    values.put(f.name(), Coercion.coerce(v, f.type()));
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("source data for " + f.name() + " (key " + er.key() + "): " + e.getMessage(), e);
                }
            }
            out.add(new GeneratedRecord(values, er.key(), "database"));
        }
        return out;
    }

    /** Synthetic records for negative/boundary scenarios — clearly labelled, never a stand-in for missing data. */
    public List<GeneratedRecord> synthesize(int count) {
        String shortRun = runId.substring(Math.max(0, runId.length() - 6));
        List<GeneratedRecord> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (FieldSpec f : job.fields()) {
                Object v;
                if (f.synthetic() != null) {
                    v = f.synthetic() instanceof String s ? fill(s, i + 1, shortRun) : f.synthetic();
                } else if (f.generator() != null) {
                    v = generated(f, i);
                } else if (f.defaultValue() != null) {
                    v = f.defaultValue();
                } else if (f.name().equals(job.target().recordKeyField())) {
                    v = "SYN-" + shortRun + "-" + String.format("%04d", i + 1);
                } else {
                    v = switch (f.type()) {
                        case "integer" -> 1;
                        case "decimal" -> "100.00";
                        case "date" -> "2000-01-01";
                        case "boolean" -> true;
                        default -> "SYN" + (i + 1);
                    };
                }
                values.put(f.name(), Coercion.coerce(v, f.type()));
            }
            out.add(new GeneratedRecord(values, null, "synthetic"));
        }
        return out;
    }

    /** Pattern placeholders: {i}, {i:05d} (zero-padded), {run}. */
    static String fill(String pattern, int i, String run) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{i(?::0(\\d+)d)?\\}").matcher(pattern.replace("{run}", run));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = m.group(1) == null ? String.valueOf(i) : String.format("%0" + m.group(1) + "d", i);
            m.appendReplacement(sb, rep);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** overrides: {"all": {...}} or {"0": {...}} keyed by record index. Only overridable fields. */
    public List<GeneratedRecord> applyOverrides(List<GeneratedRecord> records, Map<String, Map<String, Object>> overrides) {
        if (overrides == null) {
            return records;
        }
        for (Map.Entry<String, Map<String, Object>> o : overrides.entrySet()) {
            List<Integer> idx = new ArrayList<>();
            if ("all".equals(o.getKey())) {
                for (int i = 0; i < records.size(); i++) {
                    idx.add(i);
                }
            } else {
                int i;
                try {
                    i = Integer.parseInt(o.getKey());
                } catch (NumberFormatException e) {
                    throw new ConfigException("override target must be 'all' or an index, got " + o.getKey());
                }
                if (i < 0 || i >= records.size()) {
                    throw new ConfigException("override index " + i + " out of range (" + records.size() + " records)");
                }
                idx.add(i);
            }
            for (Map.Entry<String, Object> change : o.getValue().entrySet()) {
                if (!job.hasField(change.getKey())) {
                    throw new ConfigException("override for unknown field " + change.getKey());
                }
                FieldSpec f = job.field(change.getKey());
                if (!f.overridable()) {
                    throw new ConfigException("field " + f.name() + " is not overridable for " + job.jobId());
                }
                Object coerced;
                try {
                    coerced = Coercion.coerce(change.getValue(), f.type());
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("override " + f.name() + ": " + e.getMessage(), e);
                }
                for (int i : idx) {
                    records.get(i).values().put(f.name(), coerced);
                    records.get(i).notes().add("override " + f.name());
                }
            }
        }
        return records;
    }
}
