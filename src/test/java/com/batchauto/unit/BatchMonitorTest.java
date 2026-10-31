package com.batchauto.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.batchauto.config.MonitoringSpec;
import com.batchauto.monitor.BatchMonitor;
import com.batchauto.monitor.MonitorOutcome;

/** AC08: COMPLETED, FAILED, TIMED_OUT, NOT_FOUND and AMBIGUOUS are distinct. Fake clock → instant + deterministic. */
class BatchMonitorTest {

    static final MonitoringSpec SPEC = new MonitoringSpec("batch_execution_status", "execution_id", "file_name", "status",
            "started_at", "completed_at", "error_message", Map.of("records_read", "records_read"),
            List.of("QUEUED", "RUNNING"), List.of("COMPLETED"), List.of("FAILED"), 10.0, 60.0);

    static final class Scripted implements BatchMonitor.StatusSource {
        final List<List<Map<String, Object>>> script;
        int calls;
        String lastSql;
        Map<String, Object> lastParams;

        Scripted(List<List<Map<String, Object>>> script) { this.script = new ArrayList<>(script); }

        @Override
        public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
            calls++;
            lastSql = sql;
            lastParams = new HashMap<>(params);
            return script.size() > 1 ? script.remove(0) : script.get(0);
        }
    }

    static Map<String, Object> row(String status, String error) {
        Map<String, Object> r = new HashMap<>();
        r.put("execution_id", "EX1");
        r.put("status", status);
        r.put("started_at", "2026-10-24T10:00:00");
        r.put("completed_at", status.equals("COMPLETED") || status.equals("FAILED") ? "2026-10-24T10:00:30" : null);
        r.put("error_message", error);
        r.put("records_read", 3);
        return r;
    }

    double[] clock = {0};

    MonitorOutcome waitWith(Scripted db) {
        clock[0] = 0;
        return new BatchMonitor(db, SPEC, null, null, s -> clock[0] += s, () -> clock[0]).waitFor("F.xml");
    }

    @Test
    void completedAfterDelayedStatusRow() {
        Scripted db = new Scripted(List.of(List.of(), List.of(), List.of(row("QUEUED", null)), List.of(row("RUNNING", null)),
                List.of(row("COMPLETED", null))));
        MonitorOutcome o = waitWith(db);
        assertThat(o.status()).isEqualTo("COMPLETED");
        assertThat(o.executionId()).isEqualTo("EX1");
        assertThat(o.durationSeconds()).isEqualTo(30.0);
        assertThat(o.counts()).containsEntry("records_read", 3);
        assertThat(db.calls).isEqualTo(5);
        assertThat(db.lastSql).contains(":file_name");
        assertThat(db.lastParams).containsEntry("file_name", "F.xml");
    }

    @Test
    void failedIsNotTimeout() {
        MonitorOutcome o = waitWith(new Scripted(List.of(List.of(row("RUNNING", null)), List.of(row("FAILED", "MALFORMED_FILE")))));
        assertThat(o.status()).isEqualTo("FAILED");
        assertThat(o.errorMessage()).isEqualTo("MALFORMED_FILE");
    }

    @Test
    void timedOutWhenNeverTerminalAndPollingIsBounded() {
        Scripted db = new Scripted(List.of(List.of(row("RUNNING", null))));
        MonitorOutcome o = waitWith(db);
        assertThat(o.status()).isEqualTo("TIMED_OUT");
        assertThat(o.waitedSeconds()).isGreaterThanOrEqualTo(60);
        assertThat(db.calls).isEqualTo(7);
    }

    @Test
    void missingStatusIsNotFoundNeverSuccess() {
        MonitorOutcome o = waitWith(new Scripted(List.of(List.of())));
        assertThat(o.status()).isEqualTo("NOT_FOUND");
        assertThat(o.executionId()).isNull();
    }

    @Test
    void ambiguousCorrelation() {
        assertThat(waitWith(new Scripted(List.of(List.of(row("RUNNING", null), row("RUNNING", null))))).status())
                .isEqualTo("AMBIGUOUS");
    }

    @Test
    void unknownStatusIsReported() {
        MonitorOutcome o = waitWith(new Scripted(List.of(List.of(row("PAUSED", null)))));
        assertThat(o.status()).isEqualTo("TIMED_OUT");
        assertThat(o.notes().get(0)).contains("PAUSED");
    }
}
