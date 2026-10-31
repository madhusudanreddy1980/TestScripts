package com.batchauto.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import com.batchauto.Framework;
import com.batchauto.runner.RunResult;
import com.batchauto.runner.TestRun;
import com.batchauto.simulator.SeedData;
import com.batchauto.simulator.SimulatedBatchProcessor;
import com.batchauto.simulator.SimulatedBatchProcessor.Behaviour;

/** A seeded, isolated data folder per test plus helpers to run jobs against the simulator. */
public final class TestWorkspace {

    public static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    public final Path dataDir;
    public final Framework fw;

    public TestWorkspace(Path tempDir) {
        this(tempDir, ROOT, "local");
    }

    public TestWorkspace(Path tempDir, Path root, String env) {
        this.dataDir = tempDir;
        SeedData.seed(tempDir.resolve("demo.db"));
        this.fw = new Framework(root, env, tempDir);
        fw.setMonitorOverrides(Map.of("polling_interval_seconds", 0.02, "timeout_seconds", 5.0));
    }

    public SimulatedBatchProcessor simulator(Behaviour b) {
        return new SimulatedBatchProcessor(dataDir.resolve("demo.db"), Path.of(fw.cloudProfile().root()), b);
    }

    public record Executed(TestRun run, RunResult result) { }

    /** Deterministic: every monitor "sleep" gives the simulated application one processing pass. */
    public Executed run(String jobId, TestRun.Options options, Behaviour behaviour) {
        SimulatedBatchProcessor sim = simulator(behaviour);
        TestRun run = new TestRun(fw, jobId, options.sleeper(s -> {
            sim.processPending();
            try {
                Thread.sleep(Math.min(10, (long) (s * 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        return new Executed(run, run.execute(true, true));
    }

    public Executed run(String jobId, String scenario, int count) {
        return run(jobId, TestRun.Options.defaults().scenario(scenario).count(count), Behaviour.normal());
    }

    /** A writable copy of config/ and jobs/ for onboarding tests. */
    public static Path copyWorkspace(Path target) throws IOException {
        copyTree(ROOT.resolve("config"), target.resolve("config"));
        copyTree(ROOT.resolve("jobs"), target.resolve("jobs"));
        return target;
    }

    public static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.sorted(Comparator.naturalOrder()).toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t);
                }
            }
        }
    }
}
