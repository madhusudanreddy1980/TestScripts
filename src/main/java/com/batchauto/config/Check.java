package com.batchauto.config;

import java.util.regex.Pattern;

import com.batchauto.errors.BatchAutoException.ConfigException;

/** Small validation helpers for configuration objects. */
public final class Check {
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private Check() { }

    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new ConfigException(message);
        }
    }

    public static void notBlank(String value, String what) {
        that(value != null && !value.isBlank(), what + " is required");
    }

    /** SQL identifiers come from config and are concatenated into SQL, so they must be plain names. */
    public static String ident(String value, String what) {
        that(value != null && IDENT.matcher(value).matches(), what + " '" + value + "' is not a valid SQL identifier");
        return value;
    }
}
