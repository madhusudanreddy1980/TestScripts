package com.batchauto.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.batchauto.Framework;
import com.batchauto.config.FieldSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.config.JobLoader.LoadedJob;
import com.batchauto.data.EligibleRow;
import com.batchauto.errors.BatchAutoException;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.errors.BatchAutoException.DataShortageException;
import com.batchauto.errors.BatchAutoException.SubmissionBlockedException;
import com.batchauto.reconcile.Values;
import com.batchauto.report.ReportWriter;
import com.batchauto.runner.RunState;
import com.batchauto.runner.TestRun;
import com.batchauto.security.Masking;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;

/**
 * Tester UI + REST API. Screens: 1 Batch Job Catalogue · 2 Test Data Generation ·
 * 3 Batch Execution Monitor · 4 Database Validation (+ report history).
 * Binds to 127.0.0.1 by default — put it behind your SSO proxy before sharing.
 */
public final class UiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(UiServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Framework fw;
    private final Map<String, TestRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "run-monitor");
        t.setDaemon(true);
        return t;
    });
    private Javalin app;

    public UiServer(Framework fw) {
        this.fw = fw;
    }

    public UiServer start(String host, int port) {
        app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/ui", Location.CLASSPATH);
            cfg.showJavalinBanner = false;
        });
        app.exception(BatchAutoException.class, (e, ctx) -> {
            int status = e instanceof SubmissionBlockedException ? 409
                    : e instanceof DataShortageException ? 422
                    : e instanceof ConfigException ? 400 : 502;
            json(ctx.status(status), Map.of("error", e.category(), "message", Masking.sanitize(String.valueOf(e.getMessage()))));
        });
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                json(ctx.status(400), Map.of("error", "BAD_REQUEST", "message", String.valueOf(e.getMessage()))));

        app.get("/api/catalogue", this::catalogue);
        app.get("/api/jobs/{id}/config", this::jobConfig);
        app.get("/api/jobs/{id}/eligible", this::eligible);
        app.post("/api/runs", this::createRun);
        app.get("/api/runs/{id}", ctx -> json(ctx, view(run(ctx))));
        app.post("/api/runs/{id}/validate", this::validate);
        app.post("/api/runs/{id}/submit", this::submit);
        app.get("/api/runs/{id}/download", this::download);
        app.get("/api/runs/{id}/report", this::report);
        app.get("/api/reports", this::reports);
        app.start(host, port);
        LOG.info("Batch automation UI on http://{}:{}/", host, app.port());
        return this;
    }

    public int port() { return app.port(); }

    @Override
    public void close() {
        if (app != null) {
            app.stop();
        }
        background.shutdownNow();
    }

    // ------------------------------------------------------------------ handlers
    private void catalogue(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", fw.envName());
        out.put("cloud_profile", fw.cloudProfileName());
        out.put("cloud_provider", fw.cloudProfile().provider());
        out.put("submission_allowed", fw.env().allowSubmission() && !fw.env().isProduction());
        out.put("jobs", fw.catalogueRows());
        List<Map<String, Object>> scenarios = new ArrayList<>();
        fw.catalogue().entries().forEach(e -> {
            if (e.loaded() != null) {
                e.loaded().job().scenarios().forEach((name, s) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("job_id", e.loaded().job().jobId());
                    m.put("name", name);
                    m.put("description", s.description());
                    m.put("expect", s.expect());
                    m.put("synthetic", s.synthetic());
                    m.put("force_submit_invalid", s.forceSubmitInvalid());
                    scenarios.add(m);
                });
            }
        });
        out.put("scenarios", scenarios);
        json(ctx, out);
    }

    private void jobConfig(Context ctx) throws IOException {
        LoadedJob lj = fw.catalogue().get(ctx.pathParam("id"));
        Path d = lj.dir();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job_id", lj.job().jobId());
        out.put("job_yaml", Files.readString(d.resolve("job.yaml")));
        out.put("validation_rules_yaml", Files.readString(d.resolve("validation-rules.yaml")));
        out.put("eligibility_sql", Files.readString(d.resolve(lj.job().eligibility().queryFile())));
        out.put("template_name", lj.job().file().template());
        out.put("template", Files.readString(d.resolve(lj.job().file().template())));
        out.put("schema_name", lj.job().file().schemaFile());
        out.put("schema", Files.readString(d.resolve(lj.job().file().schemaFile())));
        json(ctx, out);
    }

    private void eligible(Context ctx) {
        TestRun probe = new TestRun(fw, ctx.pathParam("id"), TestRun.Options.defaults().tester("ui-probe").runId("probe-" + TestRun.newRunId()));
        List<EligibleRow> rows = probe.fetchEligible();
        JobConfig job = probe.job();
        Map<String, String> sensitive = new LinkedHashMap<>();
        for (FieldSpec f : job.fields()) {
            if (f.sensitive() && f.sourceColumn() != null) {
                sensitive.put(f.sourceColumn(), f.mask());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (EligibleRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", Values.plain(r.key()));
            m.put("eligible", r.eligible());
            m.put("reasons", r.reasons());
            Map<String, Object> values = new LinkedHashMap<>();
            r.row().forEach((k, v) -> values.put(k, sensitive.containsKey(k) ? Masking.mask(v, sensitive.get(k)) : Values.plain(v)));
            m.put("row", values);
            out.add(m);
        }
        deleteQuietly(probe.runDir());
        json(ctx, Map.of("job_id", job.jobId(), "rows", out, "sql", probe.job().eligibility().queryFile()));
    }

    private void createRun(Context ctx) throws IOException {
        JsonNode body = JSON.readTree(ctx.body());
        String jobId = text(body, "job_id");
        if (jobId == null) {
            throw new IllegalArgumentException("job_id is required");
        }
        List<String> keys = new ArrayList<>();
        body.path("keys").forEach(k -> {
            if (!k.asText().isBlank()) {
                keys.add(k.asText().trim());
            }
        });
        Map<String, Map<String, Object>> overrides = body.hasNonNull("overrides")
                ? JSON.convertValue(body.get("overrides"), new TypeReference<Map<String, Map<String, Object>>>() { }) : Map.of();
        TestRun.Options o = TestRun.Options.defaults()
                .scenario(text(body, "scenario"))
                .count(body.hasNonNull("count") ? body.get("count").asInt() : null)
                .keys(keys.isEmpty() ? null : keys)
                .overrides(overrides)
                .tester("ui");
        TestRun run = new TestRun(fw, jobId, o);
        runs.put(run.runId(), run);
        try {
            run.generate();
        } catch (BatchAutoException e) {
            run.fail(e);
            run.finish();
            throw e;
        }
        json(ctx.status(201), view(run));
    }

    private void validate(Context ctx) {
        TestRun run = run(ctx);
        run.validate();
        if ("blocked".equals(run.scenario().expect())) {
            run.finish(); // negative scenario ends here: result recorded
        }
        json(ctx, view(run));
    }

    private void submit(Context ctx) throws IOException {
        TestRun run = run(ctx);
        JsonNode body = ctx.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(ctx.body());
        boolean confirmed = body.path("confirm").asBoolean(false);
        try {
            run.submit(confirmed);
        } catch (SubmissionBlockedException e) {
            throw e; // nothing left the framework; the run stays where it was
        } catch (BatchAutoException e) {
            run.fail(e);
            run.finish();
            throw e;
        }
        background.submit(run::monitorAndReconcile);
        json(ctx.status(202), view(run));
    }

    private void download(Context ctx) throws IOException {
        TestRun run = run(ctx);
        if (run.filePath() == null) {
            throw new ConfigException("no file generated yet");
        }
        ctx.header("Content-Disposition", "attachment; filename=\"" + run.fileName() + "\"");
        ctx.contentType("xml".equals(run.job().file().format()) ? "application/xml" : "application/json");
        ctx.result(Files.readAllBytes(run.filePath()));
    }

    private void report(Context ctx) throws IOException {
        TestRun run = run(ctx);
        Path html = run.runDir().resolve("report.html");
        if (!Files.isRegularFile(html)) {
            throw new ConfigException("report not ready");
        }
        ctx.html(Files.readString(html));
    }

    private void reports(Context ctx) {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (String k : List.of("job", "environment", "result", "scenario", "date")) {
            criteria.put(k, ctx.queryParam(k));
        }
        json(ctx, ReportWriter.filterIndex(fw.reportsDir(), criteria));
    }

    // ------------------------------------------------------------------ helpers
    private TestRun run(Context ctx) {
        TestRun r = runs.get(ctx.pathParam("id"));
        if (r == null) {
            throw new ConfigException("unknown run " + ctx.pathParam("id"));
        }
        return r;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asText().isBlank() ? n.get(field).asText() : null;
    }

    private static void json(Context ctx, Object body) {
        try {
            ctx.contentType("application/json").result(JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Everything the four screens need about one run. */
    static Map<String, Object> view(TestRun run) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("run_id", run.runId());
        v.put("job_id", run.job().jobId());
        v.put("job_name", run.job().name());
        v.put("scenario", run.scenarioName());
        v.put("expect", run.scenario().expect());
        v.put("force_submit_invalid", run.scenario().forceSubmitInvalid());
        v.put("state", run.state().name());
        v.put("file_name", run.fileName());
        v.put("sha256", run.sha256());
        v.put("records", run.records().size());
        if (run.state() != RunState.CREATED && run.content() != null) {
            v.put("preview", run.preview(10));
        }
        if (run.validation() != null) {
            v.put("validation", Map.of("valid", run.validation().valid() && run.ruleErrors().isEmpty(), "errors", run.allErrors(),
                    "schema", run.validation().schema()));
        }
        if (run.upload() != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("uri", run.upload().uri());
            u.put("destination", run.destination());
            u.put("verified", run.uploadVerified());
            v.put("upload", u);
        }
        v.put("cloud_provider", run.framework().cloudProfile().provider());
        v.put("outcome", run.outcome() == null ? null : JSON.convertValue(run.outcome(), new TypeReference<Map<String, Object>>() { }));
        v.put("expectations", run.expectationsView());
        List<Map<String, Object>> asserts = new ArrayList<>();
        run.assertions().forEach(a -> {
            Map<String, Object> m = JSON.convertValue(a, new TypeReference<Map<String, Object>>() { });
            m.put("expected", Values.plain(a.expected()));
            m.put("actual", Values.plain(a.actual()));
            asserts.add(m);
        });
        v.put("assertions", asserts);
        if (run.result() != null) {
            v.put("result", Map.of("result", run.result().result(), "exit_code", run.result().exitCode(),
                    "summary", run.result().summary()));
        }
        if (run.error() != null) {
            v.put("error", Map.of("category", run.error().category(), "message", Masking.sanitize(String.valueOf(run.error().getMessage()))));
        }
        v.put("events", run.events());
        return v;
    }
}
