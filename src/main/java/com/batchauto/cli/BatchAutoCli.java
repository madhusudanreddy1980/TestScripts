package com.batchauto.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import com.batchauto.Framework;
import com.batchauto.config.FieldSpec;
import com.batchauto.data.EligibleRow;
import com.batchauto.errors.BatchAutoException;
import com.batchauto.errors.ExitCodes;
import com.batchauto.report.ReportWriter;
import com.batchauto.runner.RunResult;
import com.batchauto.runner.TestRun;
import com.batchauto.security.Masking;
import com.batchauto.simulator.SeedData;
import com.batchauto.simulator.SimulatedBatchProcessor;
import com.batchauto.simulator.SimulatedBatchProcessor.Behaviour;
import com.batchauto.ui.UiServer;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * Exit codes: 0 pass · 1 test failure · 2 config/blocked · 3 infrastructure · 4 timeout/not found · 5 data shortage.
 */
@Command(name = "batchauto", mixinStandardHelpOptions = true, version = "1.0.0",
        description = "Metadata-driven batch job automation tests",
        subcommands = {BatchAutoCli.Catalogue.class, BatchAutoCli.Eligible.class, BatchAutoCli.Generate.class,
                BatchAutoCli.Run.class, BatchAutoCli.Reports.class, BatchAutoCli.Ui.class, BatchAutoCli.Seed.class,
                BatchAutoCli.Demo.class})
public final class BatchAutoCli {

    @Option(names = "--root", defaultValue = ".", description = "Workspace root (contains config/ and jobs/)")
    Path root;

    @Option(names = "--env", description = "Environment from config/environment.yaml (default: BATCHAUTO_ENV or local)")
    String env;

    @Option(names = "--data-dir", description = "Data folder for local runs (default: <root>/var)")
    Path dataDir;

    Framework framework() {
        return new Framework(root, env, dataDir);
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String... args) {
        CommandLine cl = new CommandLine(new BatchAutoCli());
        cl.setExecutionExceptionHandler((ex, cmd, parse) -> {
            if (ex instanceof BatchAutoException b) {
                cmd.getErr().println(b.category() + ": " + Masking.sanitize(b.getMessage()));
                return b.exitCode();
            }
            throw ex;
        });
        return cl.execute(args);
    }

    static String table(List<Map<String, Object>> rows, List<String> cols) {
        if (rows.isEmpty()) {
            return "(none)";
        }
        Map<String, Integer> w = new LinkedHashMap<>();
        for (String c : cols) {
            int max = c.length();
            for (Map<String, Object> r : rows) {
                max = Math.max(max, String.valueOf(r.getOrDefault(c, "")).length());
            }
            w.put(c, max);
        }
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            sb.append(String.format("%-" + w.get(c) + "s  ", c.toUpperCase()));
        }
        sb.append('\n');
        for (String c : cols) {
            sb.append("-".repeat(w.get(c))).append("  ");
        }
        for (Map<String, Object> r : rows) {
            sb.append('\n');
            for (String c : cols) {
                sb.append(String.format("%-" + w.get(c) + "s  ", String.valueOf(r.getOrDefault(c, ""))));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ commands
    @Command(name = "catalogue", description = "List configured batch jobs and their configuration status")
    static final class Catalogue implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;

        @Override
        public Integer call() {
            System.out.println(table(top.framework().catalogueRows(), List.of("job_id", "file_format", "cloud_provider",
                    "inbound_location", "database_profile", "batch_status_table", "schema_version", "configuration_status")));
            return 0;
        }
    }

    @Command(name = "eligible", description = "Show records the eligibility SQL returns (sensitive columns masked)")
    static final class Eligible implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;
        @Option(names = "--job", required = true) String job;

        @Override
        public Integer call() {
            Framework fw = top.framework();
            TestRun probe = new TestRun(fw, job, TestRun.Options.defaults().runId("probe-" + TestRun.newRunId()));
            List<EligibleRow> rows = probe.fetchEligible();
            Map<String, String> sens = new LinkedHashMap<>();
            for (FieldSpec f : probe.job().fields()) {
                if (f.sensitive() && f.sourceColumn() != null) {
                    sens.put(f.sourceColumn(), f.mask());
                }
            }
            List<Map<String, Object>> shown = new ArrayList<>();
            List<String> cols = new ArrayList<>(List.of("key", "eligible", "reasons"));
            for (EligibleRow r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", r.key());
                m.put("eligible", r.eligible() ? "yes" : "NO");
                m.put("reasons", String.join("; ", r.reasons()));
                r.row().forEach((k, v) -> m.put(k, sens.containsKey(k) ? Masking.mask(v, sens.get(k)) : v));
                shown.add(m);
            }
            if (!rows.isEmpty()) {
                cols.addAll(rows.get(0).row().keySet());
            }
            System.out.println(table(shown, cols));
            deleteTree(probe.runDir());
            return 0;
        }
    }

    abstract static class RunOptions {
        @ParentCommand BatchAutoCli top;
        @Option(names = "--job", required = true) String job;
        @Option(names = "--scenario", defaultValue = "positive") String scenario;
        @Option(names = "--count") Integer count;
        @Option(names = "--keys", split = ",") List<String> keys;
        @Option(names = "--override", description = "all.field=value or 0.field=value (repeatable)") List<String> overrides;
        @Option(names = "--run-id") String runId;

        TestRun.Options options() {
            Map<String, Map<String, Object>> ov = new LinkedHashMap<>();
            for (String o : overrides == null ? List.<String>of() : overrides) {
                int eq = o.indexOf('=');
                int dot = o.indexOf('.');
                if (eq < 0 || dot < 0 || dot > eq) {
                    throw new CommandLine.ParameterException(new CommandLine(this), "--override must look like all.field=value, got " + o);
                }
                ov.computeIfAbsent(o.substring(0, dot), k -> new LinkedHashMap<>()).put(o.substring(dot + 1, eq), o.substring(eq + 1));
            }
            return TestRun.Options.defaults().scenario(scenario).count(count).keys(keys).overrides(ov).runId(runId);
        }
    }

    @Command(name = "generate", description = "Generate and validate a file; print a masked preview. Never submits.")
    static final class Generate extends RunOptions implements Callable<Integer> {
        @Override
        public Integer call() {
            TestRun run = new TestRun(top.framework(), job, options());
            run.generate();
            boolean ok = run.validate();
            System.out.println(run.preview(5).get("file_preview"));
            System.out.println("file:  " + run.filePath());
            System.out.println("valid: " + ok);
            run.allErrors().forEach(e -> System.out.println("  - " + e));
            return ok ? 0 : ExitCodes.TEST_FAILURE;
        }
    }

    @Command(name = "run", description = "Full lifecycle. Without --submit --yes it stops after validation.")
    static final class Run extends RunOptions implements Callable<Integer> {
        @Option(names = "--submit", description = "Upload to the inbound location") boolean submit;
        @Option(names = "--yes", description = "Confirm submission") boolean yes;

        @Override
        public Integer call() {
            if (submit && !yes) {
                System.err.println("Refusing to submit without --yes (explicit confirmation).");
                return ExitCodes.CONFIG_ERROR;
            }
            RunResult r = new TestRun(top.framework(), job, options()).execute(submit, yes);
            System.out.printf("run_id: %s%nresult: %s%nstate:  %s%nsummary: %s%nreport: %s%n",
                    r.runId(), r.result(), r.state(), r.summary(), r.reportHtml());
            return r.exitCode();
        }
    }

    @Command(name = "reports", description = "List past runs, filtered")
    static final class Reports implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;
        @Option(names = "--job") String job;
        @Option(names = "--result") String result;
        @Option(names = "--scenario") String scenario;
        @Option(names = "--date") String date;

        @Override
        public Integer call() {
            Framework fw = top.framework();
            Map<String, String> c = new LinkedHashMap<>();
            c.put("job", job);
            c.put("environment", top.env);
            c.put("result", result);
            c.put("scenario", scenario);
            c.put("date", date);
            System.out.println(table(ReportWriter.filterIndex(fw.reportsDir(), c),
                    List.of("run_id", "job", "environment", "scenario", "result", "summary")));
            return 0;
        }
    }

    @Command(name = "ui", description = "Start the tester UI (optionally with the simulated batch processor)")
    static final class Ui implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "7070") int port;
        @Option(names = "--simulator", description = "Seed var/demo.db and run the simulated batch application") boolean simulator;

        @Override
        public Integer call() throws InterruptedException {
            Framework fw = top.framework();
            SimulatedBatchProcessor sim = null;
            if (simulator) {
                SeedData.seed(fw.dataDir().resolve("demo.db"));
                sim = new SimulatedBatchProcessor(fw.dataDir().resolve("demo.db"), Path.of(fw.cloudProfile().root()),
                        Behaviour.normal()).start(200);
            }
            try (UiServer ui = new UiServer(fw).start(host, port)) {
                System.out.println("Batch Test Console: http://" + host + ":" + ui.port() + "/  (Ctrl+C to stop)");
                new CountDownLatch(1).await();
            } finally {
                if (sim != null) {
                    sim.close();
                }
            }
            return 0;
        }
    }

    @Command(name = "seed", description = "Create the demo SQLite database")
    static final class Seed implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;

        @Override
        public Integer call() {
            System.out.println(SeedData.seed(top.framework().dataDir().resolve("demo.db")));
            return 0;
        }
    }

    @Command(name = "demo", description = "Seed, start the simulator and run a representative scenario set (AC12)")
    static final class Demo implements Callable<Integer> {
        @ParentCommand BatchAutoCli top;

        record Step(String job, String scenario, int count, String bug) { }

        @Override
        public Integer call() {
            Framework fw0 = top.framework();
            deleteTree(fw0.dataDir().resolve("storage"));
            deleteTree(fw0.dataDir().resolve("reports"));
            Path db = SeedData.seed(fw0.dataDir().resolve("demo.db"));
            Framework fw = top.framework();
            List<Step> plan = List.of(
                    new Step("customer-import", "positive", 3, null),
                    new Step("customer-import", "partial_rejection", 3, null),
                    new Step("customer-import", "special_characters", 1, null),
                    new Step("customer-import", "missing_mandatory", 2, null),
                    new Step("customer-import", "malformed_file", 2, null),
                    new Step("payment-import", "positive", 3, null),
                    new Step("payment-import", "partial_rejection", 2, null),
                    new Step("payment-import", "invalid_type", 1, null),
                    new Step("customer-import", "positive", 8, "wrong_segment")); // defect injection → must FAIL
            List<String> results = new ArrayList<>();
            for (Step s : plan) {
                Behaviour b = s.bug() == null ? Behaviour.normal() : Behaviour.normal().withBug(s.bug());
                try (SimulatedBatchProcessor sim = new SimulatedBatchProcessor(db, Path.of(fw.cloudProfile().root()), b).start(50)) {
                    RunResult r = new TestRun(fw, s.job(), TestRun.Options.defaults().scenario(s.scenario()).count(s.count())
                            .tester("demo")).execute(true, true);
                    results.add(r.result());
                    System.out.printf("%-16s %-36s %-8s exit=%d  %s%n", s.job(),
                            s.scenario() + (s.bug() == null ? "" : " + bug:" + s.bug()), r.result(), r.exitCode(), r.summary());
                }
            }
            System.out.println("\nReports: " + fw.reportsDir());
            List<String> expected = new ArrayList<>(java.util.Collections.nCopies(8, "PASS"));
            expected.add("FAIL");
            return results.equals(expected) ? 0 : ExitCodes.TEST_FAILURE;
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
