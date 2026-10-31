package com.batchauto.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.batchauto.config.JobConfig;
import com.batchauto.config.ScenarioSpec;
import com.batchauto.errors.BatchAutoException.ConfigException;

/** Generic scenario mutations. The framework knows how to set/drop/duplicate/empty/corrupt — never what a job means by them. */
public final class Scenarios {

    private Scenarios() { }

    static List<Integer> indexes(ScenarioSpec spec, int n) {
        if (n == 0) {
            return List.of();
        }
        Object r = spec.records();
        if (r instanceof List<?> list) {
            List<Integer> out = new ArrayList<>();
            for (Object o : list) {
                int i = ((Number) o).intValue();
                if (i < 0 || i >= n) {
                    throw new ConfigException("scenario targets record index " + i + " but only " + n + " records were generated");
                }
                out.add(i);
            }
            return out;
        }
        return switch (String.valueOf(r)) {
            case "all" -> java.util.stream.IntStream.range(0, n).boxed().toList();
            case "last" -> List.of(n - 1);
            default -> List.of(0);
        };
    }

    public static List<GeneratedRecord> apply(JobConfig job, List<GeneratedRecord> records, ScenarioSpec spec) {
        if (spec.empty()) {
            return new ArrayList<>();
        }
        List<GeneratedRecord> out = new ArrayList<>(records);
        for (int i : indexes(spec, out.size())) {
            GeneratedRecord rec = out.get(i);
            for (Map.Entry<String, Object> e : spec.setValues().entrySet()) {
                Object v;
                try {
                    v = Coercion.coerce(e.getValue(), job.field(e.getKey()).type());
                } catch (IllegalArgumentException ex) {
                    v = e.getValue(); // deliberately invalid type — that is the test
                }
                rec.values().put(e.getKey(), v);
                rec.notes().add("scenario set " + e.getKey());
            }
            for (String f : spec.drop()) {
                rec.values().put(f, null);
                rec.notes().add("scenario drop " + f);
            }
        }
        for (int i : spec.duplicate()) {
            if (i < 0 || i >= records.size()) {
                throw new ConfigException("duplicate index " + i + " out of range");
            }
            GeneratedRecord dup = out.get(i).copy();
            dup.origin("duplicate");
            dup.notes().add("duplicate of record " + i);
            out.add(dup);
        }
        return out;
    }

    /** Truncates the rendered file so it is no longer well-formed. */
    public static byte[] corrupt(byte[] content) {
        return Arrays.copyOf(content, Math.max(1, (int) (content.length * 0.6)));
    }
}
