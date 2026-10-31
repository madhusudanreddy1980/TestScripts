package com.batchauto.config;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One field of the generated file: where its value comes from, its type, and how it is protected. */
public record FieldSpec(
        String name,
        String type,
        Boolean required,
        String sourceColumn,
        String generator,
        @JsonProperty("default") Object defaultValue,
        Object synthetic,
        Boolean overridable,
        Boolean sensitive,
        String mask,
        Double min,
        Double max,
        Integer maxLength) {

    public static final Set<String> TYPES = Set.of("string", "integer", "decimal", "date", "boolean");

    public FieldSpec {
        type = type == null ? "string" : type;
        required = required == null ? Boolean.TRUE : required;
        overridable = overridable == null ? Boolean.FALSE : overridable;
        sensitive = sensitive == null ? Boolean.FALSE : sensitive;
        mask = mask == null ? "partial" : mask;
    }

    void validate() {
        Check.that(name != null && !name.isBlank(), "field without a name");
        Check.that(TYPES.contains(type), "field " + name + ": unknown type " + type);
        Check.that(sourceColumn == null || generator == null, "field " + name + ": use source_column or generator, not both");
        Check.that(generator == null || Set.of("uuid", "run_sequence").contains(generator), "field " + name + ": unknown generator " + generator);
        Check.that(Set.of("partial", "full", "email").contains(mask), "field " + name + ": unknown mask " + mask);
    }
}
