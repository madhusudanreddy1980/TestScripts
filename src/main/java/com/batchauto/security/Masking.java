package com.batchauto.security;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masking for previews and reports, and redaction of secret-shaped text in logs and reports. */
public final class Masking {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|apikey|api_key|accountkey|sharedaccesssignature|sig)(\\s*[=:]\\s*)([^\\s;&,\"]+)");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(://[^:/@\\s]+:)([^@\\s]+)(@)");
    private static final Pattern BEARER = Pattern.compile("(Bearer\\s+)([A-Za-z0-9\\-_.=]+)");

    private Masking() { }

    public static Object mask(Object value, String strategy) {
        if (value == null) {
            return null;
        }
        String s = value instanceof BigDecimal d ? d.toPlainString() : String.valueOf(value);
        if ("full".equals(strategy)) {
            return "****";
        }
        if ("email".equals(strategy) && s.contains("@")) {
            int at = s.indexOf('@');
            return (at > 0 ? s.substring(0, 1) : "") + "***" + s.substring(at);
        }
        if (s.length() <= 4) {
            return "****";
        }
        return "*".repeat(s.length() - 4) + s.substring(s.length() - 4);
    }

    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }
        String out = KEY_VALUE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + "****"));
        out = URL_CREDENTIALS.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "****" + m.group(3)));
        out = BEARER.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "****"));
        return out;
    }
}
