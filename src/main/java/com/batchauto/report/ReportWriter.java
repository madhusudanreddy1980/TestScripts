package com.batchauto.report;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.batchauto.config.FieldSpec;
import com.batchauto.data.GeneratedRecord;
import com.batchauto.errors.BatchAutoException.InfraException;
import com.batchauto.monitor.MonitorOutcome;
import com.batchauto.reconcile.Assertion;
import com.batchauto.reconcile.Values;
import com.batchauto.runner.TestRun;
import com.batchauto.security.Masking;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import freemarker.core.HTMLOutputFormat;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

/** report.json (machine contract), report.html (human review) and a filterable index across runs. */
public final class ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private static final Configuration FTL = new Configuration(Configuration.VERSION_2_3_33);

    static {
        FTL.setClassForTemplateLoading(ReportWriter.class, "/templates");
        FTL.setDefaultEncoding("UTF-8");
        FTL.setOutputFormat(HTMLOutputFormat.INSTANCE);
        FTL.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        FTL.setLogTemplateExceptions(false);
        FTL.setNumberFormat("computer");
        FTL.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
    }

    private ReportWriter() { }

    public static Map<String, Object> build(TestRun run, String result, int exitCode, String summary) {
        Map<String, String> sensitive = new LinkedHashMap<>();
        for (FieldSpec f : run.job().fields()) {
            if (f.sensitive()) {
                sensitive.put(f.name(), f.mask());
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("report_version", "1.0");
        r.put("generated_at", Instant.now().toString());
        r.put("test_run_id", run.runId());
        r.put("result", result);
        r.put("exit_code", exitCode);
        r.put("summary", summary);
        r.put("final_state", run.state().name());
        r.put("job", Map.of("job_id", run.job().jobId(), "name", run.job().name(), "format", run.job().file().format(),
                "schema_version", run.job().file().schemaVersion()));
        r.put("scenario", Map.of("name", run.scenarioName(), "expect", run.scenario().expect(),
                "description", run.scenario().description()));
        r.put("environment", run.framework().envName());
        r.put("cloud_provider", run.framework().cloudProfile().provider());
        r.put("tester", run.tester());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", run.fileName());
        input.put("sha256", run.sha256());
        input.put("size_bytes", run.content() == null ? null : run.content().length);
        input.put("records", run.records().size());
        if (run.validation() != null) {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("valid", run.validation().valid());
            sv.put("schema", run.validation().schema());
            sv.put("errors", run.validation().errors());
            sv.put("record_count", run.validation().recordCount());
            input.put("schema_validation", sv);
        } else {
            input.put("schema_validation", null);
        }
        input.put("business_rule_errors", run.ruleErrors());
        r.put("input_file", input);
        r.put("source_record_ids", run.records().stream().map(GeneratedRecord::sourceKey).filter(Objects::nonNull).toList());

        List<Map<String, Object>> trace = new ArrayList<>();
        for (int i = 0; i < run.records().size(); i++) {
            GeneratedRecord g = run.records().get(i);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("index", i);
            t.put("key", Values.plain(g.values().get(run.job().target().recordKeyField())));
            t.put("source_key", g.sourceKey());
            t.put("origin", g.origin());
            t.put("notes", g.notes());
            Map<String, Object> vals = new LinkedHashMap<>();
            g.values().forEach((k, v) -> vals.put(k, sensitive.containsKey(k) ? Masking.mask(v, sensitive.get(k)) : Values.plain(v)));
            t.put("values", vals);
            trace.add(t);
        }
        r.put("record_traceability", trace);

        if (run.upload() != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("destination", run.destination());
            u.put("uri", run.upload().uri());
            u.put("size", run.upload().size());
            u.put("sha256", run.upload().sha256());
            u.put("verified", run.uploadVerified());
            r.put("upload", u);
        } else {
            r.put("upload", null);
        }
        MonitorOutcome o = run.outcome();
        r.put("batch", o == null ? null : JSON.convertValue(o, MAP));
        r.put("expectations", run.expectationsView());

        List<Map<String, Object>> asserts = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Assertion a : run.assertions()) {
            Map<String, Object> m = JSON.convertValue(a, MAP);
            m.put("expected", Values.plain(a.expected()));
            m.put("actual", Values.plain(a.actual()));
            asserts.add(m);
            counts.merge(a.status(), 1, Integer::sum);
            for (Map<String, Object> d : a.diffs()) {
                Map<String, Object> dd = new LinkedHashMap<>(d);
                dd.put("assertion", a.id());
                diffs.add(dd);
            }
        }
        r.put("assertion_counts", counts);
        r.put("assertions", asserts);
        r.put("field_differences", diffs);
        r.put("error", run.error() == null ? null
                : Map.of("category", run.error().category(), "message", Masking.sanitize(String.valueOf(run.error().getMessage()))));
        r.put("events", run.events());
        return r;
    }

    /** Returns {report.json, report.html}. */
    public static Path[] write(TestRun run, String result, int exitCode, String summary) {
        try {
            String raw = Masking.sanitize(JSON.writeValueAsString(build(run, result, exitCode, summary)));
            Path json = run.runDir().resolve("report.json");
            Files.writeString(json, raw);
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("r", JSON.readValue(raw, MAP));
            Template t = FTL.getTemplate("report.ftlh");
            StringWriter w = new StringWriter();
            t.process(model, w);
            Path html = run.runDir().resolve("report.html");
            Files.writeString(html, w.toString());
            updateIndex(run.framework().reportsDir());
            return new Path[] {json, html};
        } catch (IOException | TemplateException e) {
            throw new InfraException("cannot write report: " + e.getMessage(), e);
        }
    }

    public static synchronized List<Map<String, Object>> updateIndex(Path reportsDir) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(reportsDir)) {
            for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path p = d.resolve("report.json");
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    Map<String, Object> rep = JSON.readValue(Files.readString(p), MAP);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("run_id", rep.get("test_run_id"));
                    row.put("job", ((Map<?, ?>) rep.get("job")).get("job_id"));
                    row.put("environment", rep.get("environment"));
                    row.put("scenario", ((Map<?, ?>) rep.get("scenario")).get("name"));
                    row.put("result", rep.get("result"));
                    row.put("date", String.valueOf(rep.get("generated_at")).substring(0, 10));
                    row.put("summary", rep.get("summary"));
                    row.put("html", d.getFileName() + "/report.html");
                    rows.add(row);
                } catch (IOException | RuntimeException ignored) {
                    // partially written report: skip
                }
            }
        }
        Files.writeString(reportsDir.resolve("index.json"), JSON.writeValueAsString(rows));
        return rows;
    }

    /** Filter by job, environment, result, scenario, date (null = any). */
    public static List<Map<String, Object>> filterIndex(Path reportsDir, Map<String, String> criteria) {
        try {
            Path idx = reportsDir.resolve("index.json");
            if (!Files.isRegularFile(idx)) {
                return List.of();
            }
            List<Map<String, Object>> rows = JSON.readValue(Files.readString(idx), new TypeReference<List<Map<String, Object>>>() { });
            return rows.stream().filter(r -> criteria.entrySet().stream()
                    .allMatch(c -> c.getValue() == null || c.getValue().isBlank() || c.getValue().equals(String.valueOf(r.get(c.getKey())))))
                    .toList();
        } catch (IOException e) {
            throw new InfraException("cannot read report index", e);
        }
    }
}
