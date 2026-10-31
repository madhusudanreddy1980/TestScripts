package com.batchauto.config;

import java.util.List;
import java.util.Map;

public record EligibilitySpec(
        String queryFile,
        String keyColumn,
        Map<String, Object> params,
        Integer pageSize,
        Integer maxRows,
        List<String> assertions) {

    public EligibilitySpec {
        params = params == null ? Map.of() : params;
        pageSize = pageSize == null ? 500 : pageSize;
        maxRows = maxRows == null ? 10_000 : maxRows;
        assertions = assertions == null ? List.of() : assertions;
    }

    void validate() {
        Check.notBlank(queryFile, "eligibility.query_file");
        Check.notBlank(keyColumn, "eligibility.key_column");
        Check.that(pageSize > 0 && maxRows > 0, "eligibility page_size and max_rows must be positive");
    }
}
