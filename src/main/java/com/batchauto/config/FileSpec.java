package com.batchauto.config;

import java.util.Map;
import java.util.Set;

public record FileSpec(
        String format,
        String template,
        String schemaFile,
        String schemaVersion,
        String naming,
        String recordXpath,
        Map<String, String> namespaces,
        String recordsPath,
        Integer maxRecords,
        Long maxBytes) {

    public FileSpec {
        naming = naming == null ? "{job_id}_{run_id}.{ext}" : naming;
        namespaces = namespaces == null ? Map.of() : namespaces;
        maxRecords = maxRecords == null ? 10_000 : maxRecords;
        maxBytes = maxBytes == null ? 50_000_000L : maxBytes;
    }

    void validate() {
        Check.that(Set.of("xml", "json").contains(format), "file.format must be xml or json");
        Check.notBlank(template, "file.template");
        Check.notBlank(schemaFile, "file.schema_file");
        Check.notBlank(schemaVersion, "file.schema_version");
    }
}
