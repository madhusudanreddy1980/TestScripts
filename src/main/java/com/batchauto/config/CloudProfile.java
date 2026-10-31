package com.batchauto.config;

import java.util.Map;

/** Where inbound files go. Credentials are never configured here — adapters use the platform's default chain. */
public record CloudProfile(
        String provider,
        String root,
        String bucket,
        String container,
        String accountUrl,
        String project,
        String region,
        Integer retryAttempts,
        Double retryBackoffSeconds) {

    public CloudProfile {
        retryAttempts = retryAttempts == null ? 3 : retryAttempts;
        retryBackoffSeconds = retryBackoffSeconds == null ? 0.5 : retryBackoffSeconds;
    }

    public void validate() {
        Map<String, String> needs = Map.of("local", "root", "gcs", "bucket", "s3", "bucket", "azure_blob", "container");
        Check.that(needs.containsKey(provider), "unknown cloud provider " + provider);
        String need = needs.get(provider);
        String value = switch (need) {
            case "root" -> root;
            case "bucket" -> bucket;
            default -> container;
        };
        Check.notBlank(value, provider + " profile: " + need);
        if ("azure_blob".equals(provider)) {
            Check.notBlank(accountUrl, "azure_blob profile: account_url");
        }
    }

    public String displayRoot() {
        return switch (provider) {
            case "local" -> java.nio.file.Path.of(root).toUri().toString().replaceAll("/$", "");
            case "gcs" -> "gs://" + bucket;
            case "s3" -> "s3://" + bucket;
            default -> accountUrl + "/" + container;
        };
    }
}
