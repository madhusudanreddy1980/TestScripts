package com.batchauto.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One database validation with its evidence. status: PASS | FAIL | SKIPPED. */
public record Assertion(
        String id,
        String category,
        String rule,
        String query,
        Map<String, Object> params,
        Object expected,
        Object actual,
        String status,
        List<Object> recordIds,
        String details,
        List<Map<String, Object>> diffs,
        String timestamp) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String SKIPPED = "SKIPPED";

    public static Assertion of(String id, String category, String rule, String query, Map<String, Object> params,
                               Object expected, Object actual, boolean ok, List<Object> ids) {
        return new Assertion(id, category, rule, query, params, expected, actual, ok ? PASS : FAIL, ids, "", List.of(),
                Instant.now().toString());
    }

    public Assertion withDetails(String d) {
        return new Assertion(id, category, rule, query, params, expected, actual, status, recordIds, d, diffs, timestamp);
    }

    public Assertion withDiffs(List<Map<String, Object>> d) {
        return new Assertion(id, category, rule, query, params, expected, actual, status, recordIds, details, d, timestamp);
    }

    public Assertion skipped(String why) {
        return new Assertion(id, category, rule, query, params, expected, actual, SKIPPED, recordIds, why, diffs, timestamp);
    }
}
