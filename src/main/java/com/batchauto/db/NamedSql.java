package com.batchauto.db;

import java.util.ArrayList;
import java.util.List;

/** Converts ":name" parameters to JDBC "?" placeholders, ignoring string literals and "::" casts. */
public record NamedSql(String jdbcSql, List<String> names) {

    public static NamedSql parse(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        boolean inString = false;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inString = !inString;
                out.append(c);
                i++;
            } else if (!inString && c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
            } else if (!inString && c == ':' && i + 1 < sql.length()
                    && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int j = i + 1;
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                names.add(sql.substring(i + 1, j));
                out.append('?');
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return new NamedSql(out.toString(), List.copyOf(names));
    }
}
