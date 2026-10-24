package dev.modernmusic.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Official website aliases and the query-string fields inside its KuGoo cookie. */
final class KugouCookieFields {
    private KugouCookieFields() {}
    static String canonicalName(String name) {
        return switch (name) {
            case "KugooID" -> "userid";
            case "t" -> "token";
            case "kg_mid", "KUGOU_API_MID" -> "mid";
            case "kg_dfid" -> "dfid";
            case "a_id" -> "appid";
            default -> name;
        };
    }
    static Map<String, String> unpack(String value) {
        if (value == null || value.length() > SessionCookie.MAX_LENGTH) throw invalid();
        try {
            // Some exporters encode the whole packed value; normally only individual fields are escaped.
            if (!value.contains("=")) value = decode(value);
            var fields = new LinkedHashMap<String, String>();
            for (String pair : value.split("&")) {
                int separator = pair.indexOf('=');
                if (separator < 0) continue;
                String name = canonicalName(pair.substring(0, separator));
                if (!java.util.Set.of("userid", "token", "appid", "mid", "dfid", "ct").contains(name)) continue;
                String decoded = decode(pair.substring(separator + 1));
                if (!decoded.isEmpty()) merge(fields, name, decoded);
            }
            return fields;
        } catch (RuntimeException failure) { throw invalid(); }
    }
    static void merge(Map<String, String> fields, String name, String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        String previous = fields.putIfAbsent(name, value);
        if (previous != null && !previous.equals(value)) throw invalid();
    }
    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
    private static AuthException invalid() { return new AuthException("invalid_cookie"); }
}
