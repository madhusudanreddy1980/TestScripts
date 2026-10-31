package com.batchauto.db;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.batchauto.errors.BatchAutoException.UnsafeSqlException;

/**
 * The framework never writes to the application database. Every statement must be a single
 * SELECT/WITH with no write keywords (checked outside string literals). This is defence in depth
 * on top of a read-only connection and a least-privilege database user.
 */
public final class SqlGuard {
    private static final Pattern COMMENTS = Pattern.compile("(--[^\\n]*)|(/\\*.*?\\*/)", Pattern.DOTALL);
    private static final Pattern STRINGS = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern STARTS = Pattern.compile("^(select|with)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke|replace|attach|detach|"
                    + "pragma|vacuum|reindex|exec|execute|call|lock|copy|into)\\b", Pattern.CASE_INSENSITIVE);

    private SqlGuard() { }

    public static String assertReadOnly(String sql) {
        String stripped = COMMENTS.matcher(sql).replaceAll(" ").strip();
        while (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        String noLiterals = STRINGS.matcher(stripped).replaceAll("''");
        if (noLiterals.contains(";")) {
            throw new UnsafeSqlException("multiple SQL statements are not allowed");
        }
        if (!STARTS.matcher(noLiterals).find()) {
            throw new UnsafeSqlException("only SELECT / WITH statements are allowed");
        }
        Matcher m = FORBIDDEN.matcher(noLiterals);
        if (m.find()) {
            throw new UnsafeSqlException("forbidden keyword in read-only query: '" + m.group(1) + "'");
        }
        return stripped;
    }

    public static String redactUrl(String url) {
        return url.replaceAll("(://[^:/@]+:)[^@]+@", "$1****@").replaceAll("(?i)(password=)[^&;]+", "$1****");
    }
}
