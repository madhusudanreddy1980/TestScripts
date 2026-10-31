package com.batchauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.batchauto.catalogue.JobCatalogue;
import com.batchauto.config.CloudProfile;
import com.batchauto.config.DatabaseProfile;
import com.batchauto.config.EnvironmentSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.config.Yaml;
import com.batchauto.db.ReadOnlyDatabase;
import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.storage.InboundStorageAdapter;
import com.batchauto.storage.StorageRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Workspace + environment wiring: which cloud profile, physical database and guard-rails apply.
 * This is the only place environment selection happens.
 */
public final class Framework {

    private final Path root;
    private final Path dataDir;
    private final String envName;
    private final EnvironmentSpec env;
    private final String cloudProfileName;
    private final CloudProfile cloudProfile;
    private final Map<String, DatabaseProfile> dbProfiles = new HashMap<>();
    private final JobCatalogue catalogue;
    private final Path reportsDir;
    private final Map<String, ReadOnlyDatabase> dbs = new ConcurrentHashMap<>();
    private volatile Map<String, Double> monitorOverrides;
    private InboundStorageAdapter storage;

    public Framework(Path root, String envName, Path dataDir) {
        this.root = root.toAbsolutePath().normalize();
        this.envName = envName != null ? envName : envOr("BATCHAUTO_ENV", "local");
        String dd = dataDir != null ? dataDir.toString() : envOr("BATCHAUTO_DATA_DIR", this.root.resolve("var").toString());
        this.dataDir = Path.of(dd).toAbsolutePath().normalize();
        Map<String, String> vars = Map.of("workspace", this.root.toString(), "data_dir", this.dataDir.toString());
        Path cfg = this.root.resolve("config");

        JsonNode envs = interpolate(Yaml.readTree(cfg.resolve("environment.yaml")), vars).path("environments");
        if (!envs.has(this.envName)) {
            throw new ConfigException("unknown environment '" + this.envName + "'");
        }
        this.env = Yaml.convert(envs.get(this.envName), EnvironmentSpec.class, "environment " + this.envName);

        JsonNode clouds = interpolate(Yaml.readTree(cfg.resolve("cloud-profiles.yaml")), vars).path("profiles");
        this.cloudProfileName = envOr("BATCHAUTO_CLOUD_PROFILE", env.cloudProfile());
        if (!clouds.has(cloudProfileName)) {
            throw new ConfigException("unknown cloud profile '" + cloudProfileName + "'");
        }
        this.cloudProfile = Yaml.convert(clouds.get(cloudProfileName), CloudProfile.class, "cloud profile " + cloudProfileName);
        this.cloudProfile.validate();

        JsonNode dbsNode = interpolate(Yaml.readTree(cfg.resolve("database-profiles.yaml")), vars).path("profiles");
        for (Iterator<Map.Entry<String, JsonNode>> it = dbsNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            dbProfiles.put(e.getKey(), Yaml.convert(e.getValue(), DatabaseProfile.class, "database profile " + e.getKey()));
        }
        this.catalogue = new JobCatalogue(this.root.resolve("jobs"));
        this.reportsDir = Path.of(env.reportsDir().replace("{workspace}", this.root.toString())
                .replace("{data_dir}", this.dataDir.toString()));
        this.monitorOverrides = env.monitorOverrides();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name);
        }
        return v == null || v.isBlank() ? fallback : v;
    }

    private static JsonNode interpolate(JsonNode node, Map<String, String> vars) {
        if (node.isTextual()) {
            String s = node.asText();
            for (Map.Entry<String, String> v : vars.entrySet()) {
                s = s.replace("{" + v.getKey() + "}", v.getValue());
            }
            return TextNode.valueOf(s);
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), interpolate(e.getValue(), vars)));
            return copy;
        }
        if (node.isArray()) {
            var arr = node.deepCopy();
            var out = Yaml.MAPPER.createArrayNode();
            arr.forEach(n -> out.add(interpolate(n, vars)));
            return out;
        }
        return node;
    }

    public Path root() { return root; }
    public Path dataDir() { return dataDir; }
    public String envName() { return envName; }
    public EnvironmentSpec env() { return env; }
    public CloudProfile cloudProfile() { return cloudProfile; }
    public String cloudProfileName() { return cloudProfileName; }
    public JobCatalogue catalogue() { return catalogue; }

    public Path reportsDir() {
        try {
            Files.createDirectories(reportsDir);
        } catch (java.io.IOException e) {
            throw new ConfigException("cannot create reports dir " + reportsDir, e);
        }
        return reportsDir;
    }

    public Map<String, Double> monitorOverrides() { return monitorOverrides; }

    /** Tests and the UI may tighten polling for local runs. */
    public void setMonitorOverrides(Map<String, Double> overrides) { this.monitorOverrides = Map.copyOf(overrides); }

    public synchronized InboundStorageAdapter storage() {
        if (storage == null) {
            storage = StorageRegistry.create(cloudProfile);
        }
        return storage;
    }

    public ReadOnlyDatabase databaseFor(JobConfig job) {
        String physical = env.databaseProfiles().get(job.database());
        if (physical == null) {
            throw new ConfigException("environment " + envName + " has no mapping for database " + job.database());
        }
        DatabaseProfile p = dbProfiles.get(physical);
        if (p == null) {
            throw new ConfigException("unknown database profile " + physical);
        }
        return dbs.computeIfAbsent(physical, k -> new ReadOnlyDatabase(p.resolveUrl(), p.queryTimeoutSeconds(), p.readOnly()));
    }

    public List<Map<String, Object>> catalogueRows() {
        return catalogue.rows(cloudProfile.provider().toUpperCase(), cloudProfile.displayRoot(), env.databaseProfiles());
    }
}
