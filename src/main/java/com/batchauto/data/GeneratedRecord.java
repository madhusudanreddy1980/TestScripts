package com.batchauto.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One record in the generated file, with traceability back to its source row. */
public final class GeneratedRecord {
    private final Map<String, Object> values;
    private final Object sourceKey;
    private String origin;
    private final List<String> notes;

    public GeneratedRecord(Map<String, Object> values, Object sourceKey, String origin) {
        this(values, sourceKey, origin, new ArrayList<>());
    }

    private GeneratedRecord(Map<String, Object> values, Object sourceKey, String origin, List<String> notes) {
        this.values = values;
        this.sourceKey = sourceKey;
        this.origin = origin;
        this.notes = notes;
    }

    public Map<String, Object> values() { return values; }
    public Object sourceKey() { return sourceKey; }
    public String origin() { return origin; }
    public List<String> notes() { return notes; }

    public void origin(String o) { this.origin = o; }

    public GeneratedRecord copy() {
        return new GeneratedRecord(new LinkedHashMap<>(values), sourceKey, origin, new ArrayList<>(notes));
    }
}
