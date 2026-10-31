package com.batchauto.monitor;

import java.util.List;
import java.util.Map;

/**
 * status: COMPLETED | FAILED | TIMED_OUT | NOT_FOUND | AMBIGUOUS.
 * NOT_FOUND (no execution ever appeared) is never treated as success.
 */
public record MonitorOutcome(
        String status,
        Object executionId,
        String appStatus,
        String startedAt,
        String completedAt,
        Double durationSeconds,
        Map<String, Object> counts,
        String errorMessage,
        int polls,
        double waitedSeconds,
        String query,
        List<String> notes) { }
