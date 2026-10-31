package com.batchauto.reconcile;

import java.util.List;
import java.util.Map;

/** Target state captured just before submission. */
public record Baseline(
        Map<String, Map<String, Object>> rows,
        int targetCount,
        String unrelatedFingerprint,
        Integer unrelatedRows,
        String capturedAt,
        List<String> queries) { }
