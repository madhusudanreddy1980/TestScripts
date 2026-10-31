package com.batchauto.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MonitoringSpec(
        String statusTable,
        String executionIdColumn,
        String fileNameColumn,
        String statusColumn,
        String startTimeColumn,
        String endTimeColumn,
        String errorColumn,
        Map<String, String> countColumns,
        List<String> runningStatuses,
        List<String> successStatuses,
        List<String> failureStatuses,
        Double pollingIntervalSeconds,
        Double timeoutSeconds) {

    public MonitoringSpec {
        countColumns = countColumns == null ? Map.of() : countColumns;
        runningStatuses = runningStatuses == null ? List.of() : runningStatuses;
        successStatuses = successStatuses == null ? List.of() : successStatuses;
        failureStatuses = failureStatuses == null ? List.of() : failureStatuses;
        pollingIntervalSeconds = pollingIntervalSeconds == null ? 10.0 : pollingIntervalSeconds;
        timeoutSeconds = timeoutSeconds == null ? 1800.0 : timeoutSeconds;
    }

    void validate() {
        Check.ident(statusTable, "monitoring.status_table");
        Check.ident(executionIdColumn, "monitoring.execution_id_column");
        Check.ident(fileNameColumn, "monitoring.file_name_column");
        Check.ident(statusColumn, "monitoring.status_column");
        Check.ident(startTimeColumn, "monitoring.start_time_column");
        Check.ident(endTimeColumn, "monitoring.end_time_column");
        if (errorColumn != null) {
            Check.ident(errorColumn, "monitoring.error_column");
        }
        countColumns.forEach((k, v) -> Check.ident(v, "monitoring.count_columns." + k));
        Check.that(!successStatuses.isEmpty() && !failureStatuses.isEmpty(), "monitoring needs success and failure statuses");
        Set<String> overlap = new HashSet<>(successStatuses);
        overlap.retainAll(failureStatuses);
        Check.that(overlap.isEmpty(), "statuses both success and failure: " + overlap);
    }
}
