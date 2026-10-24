package dev.modernmusic.auth;

/** RFC 6265 cookie-octet, including QQ's @ prefix and padded token values. */
public final class CookieValue {
    private CookieValue() {}
    public static boolean valid(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.chars().allMatch(c -> c == 0x21 || c >= 0x23 && c <= 0x2b || c >= 0x2d && c <= 0x3a
                        || c >= 0x3c && c <= 0x5b || c >= 0x5d && c <= 0x7e);
    }
}
