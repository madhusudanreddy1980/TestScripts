package com.batchauto.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A data-driven test scenario. {@code records} is "all", "first", "last" or a list of indexes.
 * {@code expect}: processed | partial_rejection | blocked | batch_failed.
 */
public record ScenarioSpec(
        String description,
        @JsonProperty("set") Map<String, Object> setValues,
        List<String> drop,
        Object records,
        List<Integer> duplicate,
        Boolean empty,
        Boolean malformed,
        Boolean synthetic,
        Boolean forceSubmitInvalid,
        Boolean allowDuplicateSubmission,
        String expect) {

    public static final ScenarioSpec POSITIVE = new ScenarioSpec("Eligible records, unchanged",
            null, null, null, null, null, null, null, null, null, null);

    public ScenarioSpec {
        description = description == null ? "" : description;
        setValues = setValues == null ? Map.of() : setValues;
        drop = drop == null ? List.of() : drop;
        records = records == null ? "first" : records;
        duplicate = duplicate == null ? List.of() : duplicate;
        empty = Boolean.TRUE.equals(empty);
        malformed = Boolean.TRUE.equals(malformed);
        synthetic = Boolean.TRUE.equals(synthetic);
        forceSubmitInvalid = Boolean.TRUE.equals(forceSubmitInvalid);
        allowDuplicateSubmission = Boolean.TRUE.equals(allowDuplicateSubmission);
        expect = expect == null ? "processed" : expect;
    }

    void validate(String name, Set<String> fieldNames) {
        Check.that(Set.of("processed", "partial_rejection", "blocked", "batch_failed").contains(expect),
                "scenario " + name + ": unknown expect " + expect);
        Check.that(records instanceof List || Set.of("all", "first", "last").contains(String.valueOf(records)),
                "scenario " + name + ": records must be all, first, last or a list of indexes");
        for (String f : setValues.keySet()) {
            Check.that(fieldNames.contains(f), "scenario " + name + " references unknown field " + f);
        }
        for (String f : drop) {
            Check.that(fieldNames.contains(f), "scenario " + name + " references unknown field " + f);
        }
    }
}
