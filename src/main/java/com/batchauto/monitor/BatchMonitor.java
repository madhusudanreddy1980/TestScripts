package com.batchauto.monitor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

import com.batchauto.config.MonitoringSpec;
import com.batchauto.storage.StorageSupport.Sleeper;

/** Polls the application's status table — the authoritative completion source — with bounded polling. */
public final class BatchMonitor {

    /** The only database call the monitor makes; lets unit tests script the status table. */
    @FunctionalInterface
    public interface StatusSource {
        List<Map<String, Object>> query(String sql, Map<String, ?> params);
    }

    private final StatusSource db;
    private final MonitoringSpec spec;
    private final double interval;
    private final double timeout;
    private final Sleeper sleeper;
    private final DoubleSupplier clock;
    private final String sql;

    public BatchMonitor(StatusSource db, MonitoringSpec spec, Double pollingInterval, Double timeout,
                        Sleeper sleeper, DoubleSupplier clockSeconds) {
        this.db = db;
        this.spec = spec;
        this.interval = pollingInterval != null ? pollingInterval : spec.pollingIntervalSeconds();
        this.timeout = timeout != null ? timeout : spec.timeoutSeconds();
        this.sleeper = sleeper;
        this.clock = clockSeconds;
        Set<String> cols = new LinkedHashSet<>(List.of(spec.executionIdColumn(), spec.statusColumn(),
                spec.startTimeColumn(), spec.endTimeColumn()));
        if (spec.errorColumn() != null) {
            cols.add(spec.errorColumn());
        }
        cols.addAll(spec.countColumns().values());
        this.sql = "SELECT " + String.join(", ", cols) + " FROM " + spec.statusTable()
                + " WHERE " + spec.fileNameColumn() + " = :file_name";
    }

    public static DoubleSupplier systemClock() {
        return () -> System.nanoTime() / 1e9;
    }

    public String query() { return sql; }

    public MonitorOutcome waitFor(String fileName) {
        double start = clock.getAsDouble();
        int polls = 0;
        Map<String, Object> last = null;
        Set<String> unknown = new LinkedHashSet<>();
        while (true) {
            List<Map<String, Object>> rows = db.query(sql, Map.of("file_name", fileName));
            polls++;
            double elapsed = clock.getAsDouble() - start;
            if (rows.size() > 1) {
                return new MonitorOutcome("AMBIGUOUS", null, null, null, null, null, Map.of(), null, polls, elapsed, sql,
                        List.of(rows.size() + " executions matched " + fileName));
            }
            if (!rows.isEmpty()) {
                last = rows.get(0);
                String app = String.valueOf(last.get(spec.statusColumn()));
                if (spec.successStatuses().contains(app)) {
                    return outcome("COMPLETED", last, polls, elapsed, unknown);
                }
                if (spec.failureStatuses().contains(app)) {
                    return outcome("FAILED", last, polls, elapsed, unknown);
                }
                if (!spec.runningStatuses().contains(app)) {
                    unknown.add(app);
                }
            }
            if (elapsed >= timeout) {
                if (last == null) {
                    return new MonitorOutcome("NOT_FOUND", null, null, null, null, null, Map.of(), null, polls, elapsed, sql,
                            List.of("no execution for " + fileName + " after " + String.format("%.1f", timeout) + "s"));
                }
                return outcome("TIMED_OUT", last, polls, elapsed, unknown);
            }
            double remaining = timeout - elapsed;
            sleeper.sleep(remaining > 0 ? Math.min(interval, remaining) : interval);
        }
    }

    private MonitorOutcome outcome(String status, Map<String, Object> row, int polls, double elapsed, Set<String> unknown) {
        String started = row.get(spec.startTimeColumn()) == null ? null : String.valueOf(row.get(spec.startTimeColumn()));
        String completed = row.get(spec.endTimeColumn()) == null ? null : String.valueOf(row.get(spec.endTimeColumn()));
        Double duration = null;
        if (started != null && completed != null) {
            try {
                duration = Duration.between(parse(started), parse(completed)).toMillis() / 1000.0;
            } catch (DateTimeParseException ignored) {
                // non-ISO timestamps: duration simply unavailable
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        spec.countColumns().forEach((logical, col) -> counts.put(logical, row.get(col)));
        List<String> notes = new ArrayList<>();
        if (!unknown.isEmpty()) {
            notes.add("unrecognised status values seen: " + unknown);
        }
        return new MonitorOutcome(status, row.get(spec.executionIdColumn()), String.valueOf(row.get(spec.statusColumn())),
                started, completed, duration, counts,
                spec.errorColumn() == null || row.get(spec.errorColumn()) == null ? null : String.valueOf(row.get(spec.errorColumn())),
                polls, elapsed, sql, notes);
    }

    private static LocalDateTime parse(String ts) {
        return LocalDateTime.parse(ts.replace(' ', 'T'));
    }
}
