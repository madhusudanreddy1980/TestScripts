package com.batchauto.config;

public record RejectSpec(String table, String executionIdColumn, String keyColumn, String reasonColumn) {
    void validate() {
        Check.ident(table, "target.reject.table");
        Check.ident(executionIdColumn, "target.reject.execution_id_column");
        Check.ident(keyColumn, "target.reject.key_column");
        Check.ident(reasonColumn, "target.reject.reason_column");
    }
}
