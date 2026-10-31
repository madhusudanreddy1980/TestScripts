package com.batchauto.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.batchauto.expr.Expressions;

/** Everything needed to test one batch job. Lives in jobs/&lt;job-id&gt;/job.yaml. */
public record JobConfig(
        String jobId,
        String name,
        String description,
        Boolean enabled,
        String database,
        FileSpec file,
        List<FieldSpec> fields,
        EligibilitySpec eligibility,
        InboundSpec inbound,
        MonitoringSpec monitoring,
        TargetSpec target,
        Map<String, ScenarioSpec> scenarios) {

    public JobConfig {
        description = description == null ? "" : description;
        enabled = enabled == null ? Boolean.TRUE : enabled;
        if (scenarios == null || scenarios.isEmpty()) {
            scenarios = new LinkedHashMap<>(Map.of("positive", ScenarioSpec.POSITIVE));
        }
    }

    public FieldSpec field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown field " + fieldName));
    }

    public boolean hasField(String fieldName) {
        return fields.stream().anyMatch(f -> f.name().equals(fieldName));
    }

    public Set<String> fieldNames() {
        return fields.stream().map(FieldSpec::name).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    void validate() {
        Check.notBlank(jobId, "job_id");
        Check.notBlank(name, "name");
        Check.notBlank(database, "database");
        Check.that(file != null && fields != null && !fields.isEmpty() && eligibility != null && inbound != null
                && monitoring != null && target != null, "job needs file, fields, eligibility, inbound, monitoring and target");
        file.validate();
        fields.forEach(FieldSpec::validate);
        Set<String> names = fieldNames();
        Check.that(names.size() == fields.size(), "duplicate field names");
        eligibility.validate();
        inbound.validate();
        monitoring.validate();
        target.validate();
        Check.that(names.contains(target.recordKeyField()), "target.record_key_field " + target.recordKeyField() + " is not a field");
        scenarios.forEach((k, v) -> v.validate(k, names));
        for (String a : eligibility.assertions()) {
            Expressions.referencedNames(a);
        }
    }
}
