package com.batchauto.data;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Converts DB / YAML / UI values into the canonical Java type of a field. */
public final class Coercion {

    private Coercion() { }

    public static Object coerce(Object value, String type) {
        if (value == null) {
            return null;
        }
        try {
            return switch (type) {
                case "string" -> value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
                case "integer" -> toInteger(value);
                case "decimal" -> toDecimal(value);
                case "date" -> toDate(value);
                case "boolean" -> toBoolean(value);
                default -> throw new IllegalArgumentException("unknown type " + type);
            };
        } catch (NumberFormatException | DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("cannot convert '" + value + "' to " + type + ": " + e.getMessage(), e);
        }
    }

    private static Long toInteger(Object v) {
        if (v instanceof Boolean) {
            throw new IllegalArgumentException("boolean is not an integer");
        }
        return new BigDecimal(String.valueOf(v).trim()).longValueExact();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof Boolean) {
            throw new IllegalArgumentException("boolean is not a decimal");
        }
        if (v instanceof BigDecimal d) {
            return d;
        }
        return new BigDecimal(String.valueOf(v).trim());
    }

    private static String toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof LocalDateTime dt) {
            return dt.toLocalDate().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toLocalDate().toString();
        }
        String s = String.valueOf(v).trim();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s).toString();
    }

    private static Boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (Set.of("true", "1", "y", "yes").contains(s)) {
            return true;
        }
        if (Set.of("false", "0", "n", "no").contains(s)) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean: " + v);
    }
}
