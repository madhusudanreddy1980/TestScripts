package com.batchauto.runner;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.batchauto.errors.BatchAutoException.InfraException;
import com.batchauto.errors.BatchAutoException.SubmissionBlockedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Idempotency for submissions. A claim is an exclusively created file (CREATE_NEW), so two
 * pipeline retries racing on the same run ID cannot both submit.
 */
public final class SubmissionLedger {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() { };
    private final Path dir;

    public SubmissionLedger(Path reportsDir) {
        this.dir = reportsDir.resolve(".ledger");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new InfraException("cannot create ledger", e);
        }
    }

    private Path file(String runId) { return dir.resolve(runId + ".json"); }

    public String findChecksum(String jobId, String env, String sha256) {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                try {
                    Map<String, Object> d = JSON.readValue(Files.readString(p), MAP);
                    if (jobId.equals(d.get("job_id")) && env.equals(d.get("env")) && sha256.equals(d.get("sha256"))) {
                        return String.valueOf(d.get("run_id"));
                    }
                } catch (IOException ignored) {
                    // unreadable entry: skip
                }
            }
            return null;
        } catch (IOException e) {
            throw new InfraException("cannot read ledger", e);
        }
    }

    public synchronized void claim(String runId, String jobId, String env, String sha256, boolean allowDuplicateContent) {
        if (!allowDuplicateContent) {
            String other = findChecksum(jobId, env, sha256);
            if (other != null) {
                throw new SubmissionBlockedException("identical file already submitted by run " + other);
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("run_id", runId);
        d.put("job_id", jobId);
        d.put("env", env);
        d.put("sha256", sha256);
        d.put("state", "CLAIMED");
        d.put("claimed_at", System.currentTimeMillis());
        try {
            Files.writeString(file(runId), JSON.writeValueAsString(d), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new SubmissionBlockedException("run " + runId + " was already submitted; resume monitoring instead of resubmitting");
        } catch (IOException e) {
            throw new InfraException("cannot write ledger", e);
        }
    }

    public synchronized void update(String runId, Map<String, Object> fields) {
        try {
            Map<String, Object> d = JSON.readValue(Files.readString(file(runId)), MAP);
            d.putAll(fields);
            Files.writeString(file(runId), JSON.writeValueAsString(d));
        } catch (IOException e) {
            throw new InfraException("cannot update ledger", e);
        }
    }
}
