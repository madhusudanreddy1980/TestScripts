package com.batchauto.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import com.batchauto.config.FieldSpec;
import com.batchauto.config.JobConfig;
import com.batchauto.config.ValidationRules;
import com.batchauto.config.ValidationRules.PreSubmitRule;

/** Record-level pre-submit checks: field definitions + configured business rules. Runs alongside schema validation. */
public final class RecordRules {

    private RecordRules() { }

    public static List<String> check(JobConfig job, ValidationRules rules, List<GeneratedRecord> records) {
        List<String> errors = new ArrayList<>();
        if (records.size() > job.file().maxRecords()) {
            errors.add(records.size() + " records exceeds max_records " + job.file().maxRecords());
        }
        for (int idx = 0; idx < records.size(); idx++) {
            GeneratedRecord rec = records.get(idx);
            String where = "record " + idx + " (key " + rec.values().get(job.target().recordKeyField()) + ")";
            for (FieldSpec f : job.fields()) {
                Object v = rec.values().get(f.name());
                if (v == null) {
                    if (f.required()) {
                        errors.add(where + ": " + f.name() + " is required");
                    }
                    continue;
                }
                Object typed;
                try {
                    typed = Coercion.coerce(v, f.type());
                } catch (IllegalArgumentException e) {
                    errors.add(where + ": " + f.name() + ": " + e.getMessage());
                    continue;
                }
                if (f.maxLength() != null && String.valueOf(typed).length() > f.maxLength()) {
                    errors.add(where + ": " + f.name() + " longer than " + f.maxLength());
                }
                if (typed instanceof BigDecimal || typed instanceof Long) {
                    BigDecimal d = new BigDecimal(String.valueOf(typed));
                    if (f.min() != null && d.compareTo(BigDecimal.valueOf(f.min())) < 0) {
                        errors.add(where + ": " + f.name() + " below minimum " + f.min());
                    }
                    if (f.max() != null && d.compareTo(BigDecimal.valueOf(f.max())) > 0) {
                        errors.add(where + ": " + f.name() + " above maximum " + f.max());
                    }
                }
            }
            for (PreSubmitRule r : rules.preSubmitRules()) {
                Object v = rec.values().get(r.field());
                if (v == null) {
                    if ("not_null".equals(r.check())) {
                        errors.add(where + ": [" + r.id() + "] " + r.field() + " must not be null");
                    }
                    continue;
                }
                String msg = check(r.check(), v, r.value());
                if (msg != null) {
                    errors.add(where + ": [" + r.id() + "] " + r.field() + " " + msg);
                }
            }
        }
        return errors;
    }

    static String check(String check, Object v, Object expected) {
        String s = v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v);
        try {
            return switch (check) {
                case "regex" -> Pattern.compile(String.valueOf(expected)).matcher(s).matches() ? null : "does not match " + expected;
                case "allowed" -> expected instanceof Collection<?> c && c.stream().map(o -> String.valueOf(o)).anyMatch(s::equals)
                        ? null : "value '" + s + "' not in " + expected;
                case "max_length" -> s.length() <= Integer.parseInt(String.valueOf(expected)) ? null : "longer than " + expected;
                case "min" -> new BigDecimal(s).compareTo(new BigDecimal(String.valueOf(expected))) >= 0 ? null : "below " + expected;
                case "max" -> new BigDecimal(s).compareTo(new BigDecimal(String.valueOf(expected))) <= 0 ? null : "above " + expected;
                case "max_decimals" -> Math.max(0, new BigDecimal(s).scale()) <= Integer.parseInt(String.valueOf(expected))
                        ? null : "more than " + expected + " decimals";
                case "not_null" -> null;
                default -> "unknown check " + check;
            };
        } catch (RuntimeException e) {
            return "cannot check " + check + ": " + e.getMessage();
        }
    }
}
