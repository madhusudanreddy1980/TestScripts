package com.batchauto.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.batchauto.errors.BatchAutoException.ConfigException;
import com.batchauto.expr.Expressions;
import com.fasterxml.jackson.annotation.JsonProperty;

/** jobs/&lt;job-id&gt;/validation-rules.yaml */
public record ValidationRules(
        List<PreSubmitRule> preSubmitRules,
        List<RejectionRule> expectedRejections,
        Map<String, TargetColumnRule> targetMapping,
        List<String> countReconciliation,
        Boolean guardUnrelatedRows,
        Integer unrelatedRowsLimit) {

    public static final Set<String> CHECKS = Set.of("regex", "min", "max", "allowed", "max_length", "max_decimals", "not_null");
    public static final Set<String> TRANSFORMS = Set.of("upper", "lower", "trim", "decimal2");

    public ValidationRules {
        preSubmitRules = preSubmitRules == null ? List.of() : preSubmitRules;
        expectedRejections = expectedRejections == null ? List.of() : expectedRejections;
        targetMapping = targetMapping == null ? Map.of() : targetMapping;
        countReconciliation = countReconciliation == null ? List.of() : countReconciliation;
        guardUnrelatedRows = guardUnrelatedRows == null ? Boolean.TRUE : guardUnrelatedRows;
        unrelatedRowsLimit = unrelatedRowsLimit == null ? 50_000 : unrelatedRowsLimit;
    }

    /** Blocking check before the file leaves the framework. */
    public record PreSubmitRule(String id, String description, String field, String check, Object value) { }

    /** What the batch application is documented to reject, and with which reason code. */
    public record RejectionRule(String id, String description, String when, String reason) { }

    public record CaseRule(String when, Object value) { }

    /** Expected value of one target column after processing. */
    public record TargetColumnRule(
            String field,
            String format,
            Object value,
            List<CaseRule> cases,
            @JsonProperty("default") Object defaultValue,
            String transform,
            Boolean sensitive) {
        public TargetColumnRule {
            cases = cases == null ? List.of() : cases;
        }
    }

    public void checkAgainst(JobConfig job) {
        Set<String> names = job.fieldNames();
        Check.that(!targetMapping.isEmpty(), job.jobId() + ": target_mapping is empty");
        for (PreSubmitRule r : preSubmitRules) {
            Check.that(names.contains(r.field()), "pre_submit rule " + r.id() + ": unknown field " + r.field());
            Check.that(CHECKS.contains(r.check()), "pre_submit rule " + r.id() + ": unknown check " + r.check());
        }
        for (RejectionRule r : expectedRejections) {
            Set<String> unknown = new HashSet<>(refs(r.when(), "rejection rule " + r.id()));
            unknown.removeAll(names);
            Check.that(unknown.isEmpty(), "rejection rule " + r.id() + ": unknown names " + unknown);
        }
        targetMapping.forEach((col, rule) -> {
            Check.ident(col, "target_mapping column");
            Check.that(rule.field() == null || names.contains(rule.field()), "target_mapping." + col + ": unknown field " + rule.field());
            Check.that(rule.transform() == null || TRANSFORMS.contains(rule.transform()),
                    "target_mapping." + col + ": unknown transform " + rule.transform());
            rule.cases().forEach(c -> refs(c.when(), "target_mapping." + col));
        });
        countReconciliation.forEach(eq -> refs(eq, "count_reconciliation"));
    }

    private static Set<String> refs(String expr, String where) {
        try {
            return Expressions.referencedNames(expr);
        } catch (Expressions.ExpressionException e) {
            throw new ConfigException(where + ": " + e.getMessage());
        }
    }
}
