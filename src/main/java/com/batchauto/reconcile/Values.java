package com.batchauto.reconcile;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import com.batchauto.security.Masking;

public final class Values {
    private Values() { }

    /** Compares an expected value (from metadata) with what the database returned. */
    public static boolean equal(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == null && actual == null;
        }
        if (expected instanceof Boolean b) {
            String a = String.valueOf(actual).toLowerCase();
            return b ? a.matches("1|true|t|y") : a.matches("0|false|f|n");
        }
        if (expected instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(expected)).compareTo(new BigDecimal(String.valueOf(actual))) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    public static Object plain(Object v) {
        return v instanceof BigDecimal d ? d.toPlainString() : v;
    }

    public static Map<String, Object> masked(Map<String, Object> row, Map<String, String> mask) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(k, mask.containsKey(k) ? Masking.mask(v, mask.get(k)) : plain(v)));
        return out;
    }
}
