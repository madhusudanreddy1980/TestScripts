package com.batchauto.config;

import java.util.Map;

public record EnvironmentSpec(
        String description,
        String cloudProfile,
        Map<String, String> databaseProfiles,
        Boolean allowSubmission,
        Boolean allowInvalidSubmission,
        Boolean isProduction,
        Map<String, Double> monitorOverrides,
        String reportsDir) {

    public EnvironmentSpec {
        description = description == null ? "" : description;
        databaseProfiles = databaseProfiles == null ? Map.of() : databaseProfiles;
        allowSubmission = Boolean.TRUE.equals(allowSubmission);
        allowInvalidSubmission = Boolean.TRUE.equals(allowInvalidSubmission);
        isProduction = Boolean.TRUE.equals(isProduction);
        monitorOverrides = monitorOverrides == null ? Map.of() : monitorOverrides;
        reportsDir = reportsDir == null ? "{data_dir}/reports" : reportsDir;
    }
}
