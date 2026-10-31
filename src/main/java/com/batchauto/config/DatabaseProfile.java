package com.batchauto.config;

import com.batchauto.errors.BatchAutoException.ConfigException;

/** A physical database. Prefer url_env so the connection string comes from a secret, not from git. */
public record DatabaseProfile(String url, String urlEnv, Boolean readOnly, Double queryTimeoutSeconds) {

    public DatabaseProfile {
        readOnly = readOnly == null ? Boolean.TRUE : readOnly;
        queryTimeoutSeconds = queryTimeoutSeconds == null ? 30.0 : queryTimeoutSeconds;
    }

    public String resolveUrl() {
        if (urlEnv != null) {
            String v = System.getenv(urlEnv);
            if (v == null || v.isBlank()) {
                v = System.getProperty(urlEnv);
            }
            if (v == null || v.isBlank()) {
                throw new ConfigException("database URL secret " + urlEnv + " is not set");
            }
            return v;
        }
        if (url == null || url.isBlank()) {
            throw new ConfigException("database profile needs 'url' or 'url_env'");
        }
        return url;
    }
}
