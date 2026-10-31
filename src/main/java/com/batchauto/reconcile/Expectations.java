package com.batchauto.reconcile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.batchauto.config.JobConfig;
import com.batchauto.config.TargetSpec;
import com.batchauto.config.ValidationRules;
import com.batchauto.config.ValidationRules.CaseRule;
import com.batchauto.config.ValidationRules.RejectionRule;
import com.batchauto.config.ValidationRules.TargetColumnRule;
import com.batchauto.data.GeneratedRecord;
import com.batchauto.expr.Expressions;

/** Predicts, before submission, what the batch should do with every record. */
public final class Expectations {

    private Expectations() { }

    public static Object transform(Object value, String transform) {
        if (value == null || transform == null) {
            return value;
        }
        return switch (transform) {
            case "upper" -> String.valueOf(value).toUpperCase();
            case "lower" -> String.valueOf(value).toLowerCase();
            case "trim" -> String.join(" ", String.valueOf(value).trim().split("\\s+"));
            case "decimal2" -> new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
            default -> throw new IllegalArgumentException(transform);
        };
    }

    public static Map<String, Object> mapTargetRow(Map<String, TargetColumnRule> mapping, Map<String, Object> values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, TargetColumnRule> e : mapping.entrySet()) {
            TargetColumnRule rule = e.getValue();
            Object v;
            if (!rule.cases().isEmpty()) {
                v = rule.defaultValue();
                for (CaseRule c : rule.cases()) {
                    if (Expressions.test(c.when(), values)) {
                        v = c.value();
                        break;
                    }
                }
            } else if (rule.format() != null) {
                String s = rule.format();
                for (Map.Entry<String, Object> kv : values.entrySet()) {
                    Object x = kv.getValue();
                    s = s.replace("{" + kv.getKey() + "}", x == null ? "" : x instanceof BigDecimal d ? d.toPlainString() : String.valueOf(x));
                }
                v = s;
            } else if (rule.field() != null) {
                v = values.get(rule.field());
                if (v == null) {
                    v = rule.defaultValue();
                }
            } else {
                v = rule.value();
            }
            row.put(e.getKey(), transform(v, rule.transform()));
        }
        return row;
    }

    public static List<RecordExpectation> build(JobConfig job, ValidationRules rules, List<GeneratedRecord> records,
                                                Set<String> baselineKeys) {
        TargetSpec t = job.target();
        Set<String> seen = new HashSet<>();
        Set<String> accepted = new HashSet<>();
        List<RecordExpectation> out = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            GeneratedRecord rec = records.get(i);
            Object key = rec.values().get(t.recordKeyField());
            String k = String.valueOf(key);
            String reason = null;
            String ruleId = null;
            boolean unknown = false;
            if (seen.contains(k) && "reject".equals(t.duplicateInFile())) {
                reason = t.duplicateReason();
                ruleId = "DUPLICATE_IN_FILE";
            }
            if (reason == null) {
                for (RejectionRule rr : rules.expectedRejections()) {
                    try {
                        if (Expressions.test(rr.when(), rec.values())) {
                            reason = rr.reason();
                            ruleId = rr.id();
                            break;
                        }
                    } catch (Expressions.ExpressionException e) {
                        unknown = true;
                    }
                }
            }
            boolean exists = baselineKeys.contains(k) || accepted.contains(k);
            if (reason == null && "insert".equals(t.operation()) && exists) {
                reason = t.existingReason();
                ruleId = "EXISTING_KEY";
            }
            if (reason == null && "update".equals(t.operation()) && !exists) {
                reason = t.missingReason();
                ruleId = "MISSING_KEY";
            }
            String action;
            Map<String, Object> expected = null;
            if (reason != null) {
                action = "reject";
            } else if (unknown) {
                action = "unknown";
            } else {
                action = exists ? "update" : "insert";
                try {
                    expected = mapTargetRow(rules.targetMapping(), rec.values());
                    accepted.add(k);
                } catch (Expressions.ExpressionException | IllegalArgumentException | ArithmeticException e) {
                    action = "unknown";
                }
            }
            seen.add(k);
            out.add(new RecordExpectation(i, key, action, reason, ruleId, expected, rec.sourceKey(), rec.origin()));
        }
        return out;
    }

    public static Map<String, Integer> summarize(List<RecordExpectation> exps) {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("expected_inserted", 0);
        s.put("expected_updated", 0);
        s.put("expected_rejected", 0);
        s.put("expected_unknown", 0);
        for (RecordExpectation e : exps) {
            String k = switch (e.action()) {
                case "insert" -> "expected_inserted";
                case "update" -> "expected_updated";
                case "reject" -> "expected_rejected";
                default -> "expected_unknown";
            };
            s.merge(k, 1, Integer::sum);
        }
        return s;
    }
}
