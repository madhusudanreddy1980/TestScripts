package com.batchauto.runner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.batchauto.Framework;
import com.batchauto.config.EnvironmentSpec;
import com.batchauto.config.FieldSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.config.JobLoader.LoadedJob;
import com.batchauto.config.ScenarioSpec;
import com.batchauto.config.ValidationRules;
import com.batchauto.data.EligibleRow;
import com.batchauto.data.GeneratedRecord;
import com.batchauto.data.RecordRules;
import com.batchauto.data.Scenarios;
import com.batchauto.data.TestDataFactory;
import com.batchauto.db.ReadOnlyDatabase;
import com.batchauto.errors.BatchAutoException;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.StorageException;
import com.batchauto.errors.BatchAutoException.SubmissionBlockedException;
import com.batchauto.errors.ExitCodes;
import com.batchauto.monitor.BatchMonitor;
import com.batchauto.monitor.MonitorOutcome;
import com.batchauto.reconcile.Assertion;
import com.batchauto.reconcile.Baseline;
import com.batchauto.reconcile.Expectations;
import com.batchauto.reconcile.Reconciler;
import com.batchauto.reconcile.RecordExpectation;
import com.batchauto.reconcile.Values;
import com.batchauto.report.ReportWriter;
import com.batchauto.security.Masking;
import com.batchauto.storage.InboundStorageAdapter;
import com.batchauto.storage.StorageSupport;
import com.batchauto.storage.StorageSupport.Sleeper;
import com.batchauto.storage.UploadResult;
import com.batchauto.template.FileValidationResult;
import com.batchauto.template.FormatHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * One test execution of one batch job: the lifecycle state machine.
 * Generation never implies submission — {@link #submit(boolean)} requires explicit confirmation.
 */
public final class TestRun {

    private static final Logger LOG = LoggerFactory.getLogger(TestRun.class);
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Run parameters. Anything null takes the default. */
    public record Options(String scenario, Integer count, List<String> keys,
                          Map<String, Map<String, Object>> overrides, String runId, String tester, Sleeper sleeper) {
        public static Options defaults() { return new Options(null, null, null, null, null, null, null); }
        public Options scenario(String s) { return new Options(s, count, keys, overrides, runId, tester, sleeper); }
        public Options count(Integer c) { return new Options(scenario, c, keys, overrides, runId, tester, sleeper); }
        public Options keys(List<String> k) { return new Options(scenario, count, k, overrides, runId, tester, sleeper); }
        public Options overrides(Map<String, Map<String, Object>> o) { return new Options(scenario, count, keys, o, runId, tester, sleeper); }
        public Options runId(String r) { return new Options(scenario, count, keys, overrides, r, tester, sleeper); }
        public Options tester(String t) { return new Options(scenario, count, keys, overrides, runId, t, sleeper); }
        public Options sleeper(Sleeper s) { return new Options(scenario, count, keys, overrides, runId, tester, s); }
    }

    private final Framework fw;
    private final JobConfig job;
    private final ValidationRules rules;
    private final Path jobDir;
    private final String scenarioName;
    private final ScenarioSpec scenario;
    private final Integer count;
    private final List<String> keys;
    private final Map<String, Map<String, Object>> overrides;
    private final String runId;
    private final String tester;
    private final Path runDir;
    private final FormatHandler handler;
    private final ReadOnlyDatabase db;
    private final Sleeper sleeper;

    private volatile RunState state = RunState.CREATED;
    private final List<Map<String, String>> events = new CopyOnWriteArrayList<>();
    private List<EligibleRow> eligible = List.of();
    private List<GeneratedRecord> records = List.of();
    private byte[] content;
    private String fileName;
    private Path filePath;
    private String sha256;
    private FileValidationResult validation;
    private List<String> ruleErrors = List.of();
    private String destination;
    private UploadResult upload;
    private Boolean uploadVerified;
    private Baseline baseline;
    private List<RecordExpectation> expectations = List.of();
    private volatile MonitorOutcome outcome;
    private volatile List<Assertion> assertions = List.of();
    private volatile BatchAutoException error;
    private volatile boolean submitted;
    private volatile RunResult result;
    private Map<String, String> mask = Map.of();

    public TestRun(Framework fw, String jobId, Options o) {
        this.fw = fw;
        LoadedJob lj = fw.catalogue().get(jobId);
        this.job = lj.job();
        this.rules = lj.rules();
        this.jobDir = lj.dir();
        this.scenarioName = o.scenario() == null ? "positive" : o.scenario();
        if (!job.scenarios().containsKey(scenarioName)) {
            throw new ConfigException("job " + jobId + " has no scenario '" + scenarioName + "'; available: " + job.scenarios().keySet());
        }
        this.scenario = job.scenarios().get(scenarioName);
        this.count = o.count();
        this.keys = o.keys();
        this.overrides = o.overrides() == null ? Map.of() : o.overrides();
        this.runId = o.runId() == null ? newRunId() : o.runId();
        if (!this.runId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new ConfigException("run id may only contain letters, digits, '.', '_' and '-'");
        }
        this.tester = o.tester() == null ? "cli" : o.tester();
        this.sleeper = o.sleeper() == null ? StorageSupport.REAL_SLEEP : o.sleeper();
        this.runDir = fw.reportsDir().resolve(this.runId);
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new ConfigException("cannot create run folder " + runDir, e);
        }
        this.handler = FormatHandler.forJob(job, jobDir);
        this.db = fw.databaseFor(job);
        event("CREATED", "job=" + job.jobId() + " scenario=" + scenarioName + " env=" + fw.envName()
                + " cloud=" + fw.cloudProfile().provider());
    }

    public TestRun(Framework fw, String jobId) {
        this(fw, jobId, Options.defaults());
    }

    public static String newRunId() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    // ------------------------------------------------------------------ util
    private void event(String st, String message) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("at", java.time.Instant.now().toString());
        e.put("state", st);
        e.put("message", Masking.sanitize(message));
        events.add(e);
        LOG.info("[{}] {} {}", runId, st, e.get("message"));
    }

    private void set(RunState s, String message) {
        state = s;
        event(s.name(), message);
    }

    private void require(RunState... allowed) {
        for (RunState s : allowed) {
            if (state == s) {
                return;
            }
        }
        throw new ConfigException("operation not allowed in state " + state);
    }

    private Map<String, Object> context() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("job_id", job.jobId());
        ctx.put("run_id", runId);
        ctx.put("schema_version", job.file().schemaVersion());
        return ctx;
    }

    // -------------------------------------------------------------- generate
    public List<EligibleRow> fetchEligible() {
        TestDataFactory f = new TestDataFactory(job, jobDir, db, runId);
        eligible = f.fetchEligible();
        long ok = eligible.stream().filter(EligibleRow::eligible).count();
        event("ELIGIBILITY", eligible.size() + " rows returned, " + ok + " eligible");
        return eligible;
    }

    public synchronized void generate() {
        require(RunState.CREATED);
        TestDataFactory f = new TestDataFactory(job, jobDir, db, runId);
        List<GeneratedRecord> recs;
        if (scenario.synthetic()) {
            recs = f.synthesize(count == null ? 1 : count);
            event("SYNTHETIC", recs.size() + " synthetic records (synthetic mode)");
        } else {
            if (eligible.isEmpty()) {
                fetchEligible();
            }
            recs = f.build(f.select(eligible, count, keys));
        }
        recs = f.applyOverrides(new ArrayList<>(recs), overrides);
        records = Scenarios.apply(job, recs, scenario);
        byte[] rendered = handler.render(records.stream().map(GeneratedRecord::values).toList(), context());
        content = scenario.malformed() ? Scenarios.corrupt(rendered) : rendered;
        sha256 = StorageSupport.sha256(content);
        fileName = job.file().naming()
                .replace("{job_id}", job.jobId())
                .replace("{run_id}", runId)
                .replace("{ext}", handler.ext())
                .replace("{date}", LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE));
        filePath = runDir.resolve(fileName);
        try {
            Files.write(filePath, content);
        } catch (IOException e) {
            throw new ConfigException("cannot write generated file", e);
        }
        set(RunState.DATA_GENERATED, records.size() + " records → " + fileName + " sha256=" + sha256.substring(0, 12));
    }

    // -------------------------------------------------------------- validate
    public synchronized boolean validate() {
        require(RunState.DATA_GENERATED);
        validation = handler.validate(content);
        List<String> errs = new ArrayList<>(RecordRules.check(job, rules, records));
        if (content.length > job.file().maxBytes()) {
            errs.add("file size " + content.length + " exceeds max_bytes " + job.file().maxBytes());
        }
        if (validation.valid() && validation.recordCount() != null && validation.recordCount() != records.size()) {
            errs.add("file contains " + validation.recordCount() + " records, expected " + records.size());
        }
        ruleErrors = errs;
        boolean ok = validation.valid() && errs.isEmpty();
        List<String> all = allErrors();
        set(ok ? RunState.VALIDATED : RunState.VALIDATION_FAILED,
                ok ? "schema + business rules passed" : all.size() + " errors: " + all.subList(0, Math.min(3, all.size())));
        return ok;
    }

    public List<String> allErrors() {
        List<String> all = new ArrayList<>();
        if (validation != null) {
            all.addAll(validation.errors());
        }
        all.addAll(ruleErrors);
        return all;
    }

    /** Masked preview: a records table and the file rendered from masked values. */
    public Map<String, Object> preview(int limit) {
        List<Map<String, Object>> masked = new ArrayList<>();
        for (GeneratedRecord r : records.subList(0, Math.min(limit, records.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (FieldSpec f : job.fields()) {
                Object v = r.values().get(f.name());
                row.put(f.name(), f.sensitive() ? Masking.mask(v, f.mask()) : v);
            }
            masked.add(row);
        }
        String text;
        try {
            text = new String(handler.render(masked, context()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (ConfigException e) {
            text = "(preview unavailable)";
        }
        List<Map<String, Object>> table = masked.stream().map(m -> {
            Map<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, v) -> o.put(k, Values.plain(v)));
            return o;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", table);
        out.put("total_records", records.size());
        out.put("file_name", fileName);
        out.put("file_preview", text);
        return out;
    }

    // ---------------------------------------------------------------- submit
    public synchronized UploadResult submit(boolean confirmed) {
        if (!confirmed) {
            throw new SubmissionBlockedException("submission requires explicit confirmation");
        }
        EnvironmentSpec env = fw.env();
        if (env.isProduction() || !env.allowSubmission()) {
            throw new SubmissionBlockedException("file submission is disabled in environment '" + fw.envName() + "'");
        }
        if (state == RunState.VALIDATION_FAILED) {
            if (!(scenario.forceSubmitInvalid() && env.allowInvalidSubmission())) {
                throw new SubmissionBlockedException("file failed validation; submission blocked");
            }
            event("FORCED", "submitting an invalid file on purpose (negative scenario, non-prod env)");
        } else {
            require(RunState.VALIDATED);
        }

        SubmissionLedger ledger = new SubmissionLedger(fw.reportsDir());
        ledger.claim(runId, job.jobId(), fw.envName(), sha256, scenario.allowDuplicateSubmission());

        // Baseline and expectations are fixed before the file leaves.
        Reconciler rec = new Reconciler(job, rules, db);
        mask = rec.mask();
        List<Object> recordKeys = records.stream().map(r -> r.values().get(job.target().recordKeyField()))
                .filter(Objects::nonNull).map(o -> (Object) o).toList();
        baseline = rec.captureBaseline(recordKeys);
        expectations = Expectations.build(job, rules, records, new HashSet<>(baseline.rows().keySet()));
        writeExpectations();
        event("EXPECTATIONS", String.valueOf(Expectations.summarize(expectations)));

        destination = StorageSupport.safeDestination(job.inbound().prefix(), fileName);
        InboundStorageAdapter storage = fw.storage();
        Map<String, String> meta = Map.of("run_id", runId, "job_id", job.jobId(), "tester", tester);
        upload = StorageSupport.withRetries(() -> storage.uploadFile(filePath, destination, meta),
                fw.cloudProfile().retryAttempts(), fw.cloudProfile().retryBackoffSeconds(), "upload " + destination, sleeper);
        submitted = true;
        uploadVerified = storage.verifyFile(destination, sha256);
        ledger.update(runId, Map.of("state", "UPLOADED", "destination", destination, "verified", uploadVerified));
        if (!uploadVerified) {
            throw new StorageException("uploaded file not visible or checksum mismatch at " + destination);
        }
        set(RunState.UPLOADED, upload.uri() + " verified (sha256 match)");
        return upload;
    }

    private void writeExpectations() {
        Map<String, Object> doc = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> rows = new LinkedHashMap<>();
        baseline.rows().forEach((k, v) -> rows.put(k, Values.masked(v, mask)));
        b.put("rows", rows);
        b.put("target_count", baseline.targetCount());
        b.put("unrelated_fingerprint", baseline.unrelatedFingerprint());
        b.put("captured_at", baseline.capturedAt());
        b.put("queries", baseline.queries());
        doc.put("baseline", b);
        doc.put("summary", Expectations.summarize(expectations));
        doc.put("records", expectationsView());
        try {
            Files.writeString(runDir.resolve("expectations.json"), JSON.writeValueAsString(doc));
        } catch (IOException e) {
            throw new ConfigException("cannot write expectations", e);
        }
    }

    /** Expectations with sensitive target columns masked (for files and reports). */
    public List<Map<String, Object>> expectationsView() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RecordExpectation e : expectations) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", e.index());
            m.put("key", e.key());
            m.put("action", e.action());
            m.put("reason", e.reason());
            m.put("rule_id", e.ruleId());
            m.put("expected_row", Values.masked(e.expectedRow(), mask));
            m.put("source_key", e.sourceKey());
            m.put("origin", e.origin());
            out.add(m);
        }
        return out;
    }

    // --------------------------------------------------------------- monitor
    public MonitorOutcome monitor() {
        require(RunState.UPLOADED);
        set(RunState.PROCESSING, "polling batch status table");
        Map<String, Double> ov = fw.monitorOverrides();
        BatchMonitor m = new BatchMonitor(db::query, job.monitoring(), ov.get("polling_interval_seconds"),
                ov.get("timeout_seconds"), sleeper, BatchMonitor.systemClock());
        MonitorOutcome o = m.waitFor(fileName);
        outcome = o;
        RunState s = switch (o.status()) {
            case "COMPLETED" -> RunState.COMPLETED;
            case "FAILED" -> RunState.FAILED;
            case "TIMED_OUT" -> RunState.TIMED_OUT;
            case "NOT_FOUND" -> RunState.NOT_FOUND;
            default -> RunState.ERROR;
        };
        set(s, "app_status=" + o.appStatus() + " execution=" + o.executionId() + " polls=" + o.polls()
                + " waited=" + String.format("%.1f", o.waitedSeconds()) + "s counts=" + o.counts()
                + (o.errorMessage() != null ? " error=" + o.errorMessage() : ""));
        return o;
    }

    // ------------------------------------------------------------- reconcile
    public String expectedTerminal() {
        return "batch_failed".equals(scenario.expect()) ? "FAILED" : "COMPLETED";
    }

    public List<Assertion> reconcile() {
        Reconciler rec = new Reconciler(job, rules, db);
        List<Assertion> list = new ArrayList<>();
        list.add(scenarioIntent());
        list.addAll(rec.reconcile(fileName, outcome, expectations, baseline, records.size(), expectedTerminal()));
        assertions = List.copyOf(list);
        long fails = list.stream().filter(a -> Assertion.FAIL.equals(a.status())).count();
        event("RECONCILED", list.size() + " assertions, " + fails + " failed");
        return assertions;
    }

    private Assertion scenarioIntent() {
        Map<String, Integer> s = Expectations.summarize(expectations);
        boolean ok;
        String expected;
        switch (scenario.expect()) {
            case "processed" -> {
                ok = s.get("expected_rejected") == 0 && s.get("expected_unknown") == 0;
                expected = "no rejections predicted";
            }
            case "partial_rejection" -> {
                ok = s.get("expected_rejected") > 0 && s.get("expected_inserted") + s.get("expected_updated") > 0;
                expected = "some rejections and some accepted records predicted";
            }
            default -> {
                ok = true;
                expected = scenario.expect();
            }
        }
        return Assertion.of("S1", "SCENARIO_INTENT", "scenario '" + scenarioName + "' produces the intended data shape",
                "expectations.json", Map.of(), expected, s.toString(), ok, List.of());
    }

    // --------------------------------------------------------------- execute
    /** Generate → validate → (submit → monitor → reconcile) → report. Never throws for framework errors. */
    public RunResult execute(boolean submit, boolean confirmed) {
        try {
            generate();
            boolean valid = validate();
            if (!"blocked".equals(scenario.expect()) && submit && (valid || scenario.forceSubmitInvalid())) {
                submit(confirmed);
                monitorAndReconcile();
                return result;
            }
        } catch (BatchAutoException e) {
            fail(e);
        }
        return finish();
    }

    /** Second half of the lifecycle (the UI runs this in the background after Submit). */
    public RunResult monitorAndReconcile() {
        try {
            monitor();
            if (outcome != null && ("COMPLETED".equals(outcome.status()) || "FAILED".equals(outcome.status()))) {
                reconcile();
            }
        } catch (BatchAutoException e) {
            fail(e);
        }
        return finish();
    }

    public void fail(BatchAutoException e) {
        error = e;
        set(RunState.ERROR, e.category() + ": " + e.getMessage());
    }

    public synchronized RunResult finish() {
        String[] v = verdict();
        int code = Integer.parseInt(v[1]);
        event("RESULT", v[0] + " (exit " + code + ") — " + v[2]);
        Path[] paths = ReportWriter.write(this, v[0], code, v[2]);
        result = new RunResult(runId, v[0], code, state.name(), paths[0], paths[1], v[2]);
        return result;
    }

    private String[] verdict() {
        if (error != null) {
            return new String[] {"ERROR", String.valueOf(error.exitCode()), error.category() + ": " + error.getMessage()};
        }
        if ("blocked".equals(scenario.expect())) {
            return state == RunState.VALIDATION_FAILED
                    ? new String[] {"PASS", "0", "invalid file was blocked before submission, as intended"}
                    : new String[] {"FAIL", "1", "scenario expected validation to block the file, but it passed"};
        }
        if (state == RunState.VALIDATION_FAILED && !submitted) {
            return new String[] {"FAIL", "1", "generated file failed validation"};
        }
        if (!submitted) {
            return new String[] {"NOT_SUBMITTED", "0", "file generated and validated; not submitted"};
        }
        if (state == RunState.ERROR) {
            return new String[] {"ERROR", "1", outcome == null ? "run error" : String.join("; ", outcome.notes())};
        }
        if (state == RunState.TIMED_OUT || state == RunState.NOT_FOUND) {
            return new String[] {state.name(), String.valueOf(ExitCodes.TIMEOUT), outcome == null ? "" : String.join("; ", outcome.notes())};
        }
        long fails = assertions.stream().filter(a -> Assertion.FAIL.equals(a.status())).count();
        if (assertions.isEmpty()) {
            return new String[] {"FAIL", "1", "no assertions were evaluated"};
        }
        if (fails > 0) {
            return new String[] {"FAIL", "1", fails + " of " + assertions.size() + " assertions failed"};
        }
        return new String[] {"PASS", "0", "all " + assertions.size() + " assertions passed"};
    }

    // --------------------------------------------------------------- getters
    public Framework framework() { return fw; }
    public JobConfig job() { return job; }
    public String scenarioName() { return scenarioName; }
    public ScenarioSpec scenario() { return scenario; }
    public String runId() { return runId; }
    public String tester() { return tester; }
    public Path runDir() { return runDir; }
    public RunState state() { return state; }
    public List<Map<String, String>> events() { return List.copyOf(events); }
    public List<EligibleRow> eligible() { return eligible; }
    public List<GeneratedRecord> records() { return records; }
    public byte[] content() { return content; }
    public String fileName() { return fileName; }
    public Path filePath() { return filePath; }
    public String sha256() { return sha256; }
    public FileValidationResult validation() { return validation; }
    public List<String> ruleErrors() { return ruleErrors; }
    public String destination() { return destination; }
    public UploadResult upload() { return upload; }
    public Boolean uploadVerified() { return uploadVerified; }
    public List<RecordExpectation> expectations() { return expectations; }
    public MonitorOutcome outcome() { return outcome; }
    public List<Assertion> assertions() { return assertions; }
    public BatchAutoException error() { return error; }
    public boolean submitted() { return submitted; }
    public RunResult result() { return result; }

    static Object plain(Object v) {
        return v instanceof BigDecimal d ? d.toPlainString() : v;
    }
}
