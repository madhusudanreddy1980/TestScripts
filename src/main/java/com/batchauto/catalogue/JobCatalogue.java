package com.batchauto.catalogue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.batchauto.config.JobLoader;
import com.batchauto.config.JobLoader.LoadedJob;
import com.batchauto.errors.BatchAutoException.ConfigException;

/** Discovers jobs/&lt;job-id&gt;/. A broken job never breaks the catalogue: it is listed INVALID with the reason. */
public final class JobCatalogue {

    public record Entry(String key, Path dir, LoadedJob loaded, String status) { }

    private final Path jobsRoot;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public JobCatalogue(Path jobsRoot) {
        this.jobsRoot = jobsRoot;
        reload();
    }

    public synchronized void reload() {
        entries.clear();
        if (!Files.isDirectory(jobsRoot)) {
            throw new ConfigException("jobs folder not found: " + jobsRoot);
        }
        try (Stream<Path> dirs = Files.list(jobsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                try {
                    LoadedJob lj = JobLoader.load(dir);
                    String id = lj.job().jobId();
                    if (entries.containsKey(id)) {
                        throw new ConfigException("duplicate job_id " + id);
                    }
                    entries.put(id, new Entry(id, dir, lj, lj.job().enabled() ? "VALID" : "DISABLED"));
                } catch (ConfigException e) {
                    String key = dir.getFileName().toString();
                    entries.put(key, new Entry(key, dir, null, "INVALID: " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new ConfigException("cannot list " + jobsRoot, e);
        }
    }

    public synchronized LoadedJob get(String jobId) {
        Entry e = entries.get(jobId);
        if (e == null) {
            throw new ConfigException("unknown job '" + jobId + "'; known: " + entries.keySet());
        }
        if (e.loaded() == null) {
            throw new ConfigException("job '" + jobId + "' is not usable — " + e.status());
        }
        if (!e.loaded().job().enabled()) {
            throw new ConfigException("job '" + jobId + "' is disabled");
        }
        return e.loaded();
    }

    public synchronized List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /** Screen 1 rows. */
    public List<Map<String, Object>> rows(String cloudProvider, String inboundRoot, Map<String, String> dbProfiles) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : entries()) {
            Map<String, Object> r = new LinkedHashMap<>();
            var j = e.loaded() == null ? null : e.loaded().job();
            r.put("job_name", j == null ? e.key() : j.name());
            r.put("job_id", j == null ? e.key() : j.jobId());
            r.put("description", j == null ? "" : j.description());
            r.put("file_format", j == null ? "" : j.file().format().toUpperCase());
            r.put("cloud_provider", cloudProvider);
            r.put("inbound_location", j == null ? "" : inboundRoot + "/" + j.inbound().prefix());
            r.put("database_profile", j == null ? "" : dbProfiles.getOrDefault(j.database(), "UNMAPPED(" + j.database() + ")"));
            r.put("batch_status_table", j == null ? "" : j.monitoring().statusTable());
            r.put("test_template", j == null ? "" : j.file().template());
            r.put("schema_version", j == null ? "" : j.file().schemaVersion());
            r.put("scenarios", j == null ? List.of() : new ArrayList<>(j.scenarios().keySet()));
            r.put("configuration_status", e.status());
            out.add(r);
        }
        return out;
    }
}
