package com.batchauto.reconcile;

import java.util.Map;

/** Predicted outcome for one record, fixed before the file is submitted. action: insert | update | reject | unknown. */
public record RecordExpectation(
        int index,
        Object key,
        String action,
        String reason,
        String ruleId,
        Map<String, Object> expectedRow,
        Object sourceKey,
        String origin) { }
