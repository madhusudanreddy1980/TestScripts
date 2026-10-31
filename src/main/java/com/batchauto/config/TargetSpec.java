package com.batchauto.config;

import java.util.List;
import java.util.Set;

public record TargetSpec(
        String table,
        String keyColumn,
        String recordKeyField,
        String operation,
        String existingReason,
        String missingReason,
        String duplicateInFile,
        String duplicateReason,
        String lineageColumn,
        List<String> auditColumns,
        RejectSpec reject) {

    public TargetSpec {
        existingReason = existingReason == null ? "DUPLICATE_KEY" : existingReason;
        missingReason = missingReason == null ? "NOT_FOUND" : missingReason;
        duplicateInFile = duplicateInFile == null ? "reject" : duplicateInFile;
        duplicateReason = duplicateReason == null ? "DUPLICATE_IN_FILE" : duplicateReason;
        auditColumns = auditColumns == null ? List.of() : auditColumns;
    }

    void validate() {
        Check.ident(table, "target.table");
        Check.ident(keyColumn, "target.key_column");
        Check.notBlank(recordKeyField, "target.record_key_field");
        Check.that(Set.of("insert", "update", "upsert").contains(operation), "target.operation must be insert, update or upsert");
        Check.that(Set.of("reject", "last_wins").contains(duplicateInFile), "target.duplicate_in_file must be reject or last_wins");
        if (lineageColumn != null) {
            Check.ident(lineageColumn, "target.lineage_column");
        }
        auditColumns.forEach(c -> Check.ident(c, "target.audit_columns"));
        if (reject != null) {
            reject.validate();
        }
    }
}
