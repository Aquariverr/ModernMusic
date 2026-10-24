package dev.modernmusic.auth;

/** Only fixed translation keys; never wrap upstream exception messages or response bodies. */
public final class AuthException extends RuntimeException {
    private final String key;
    public AuthException(String category) { super("Account operation failed"); key = "modernmusic.auth." + category; }
    public String translationKey() { return key; }
}
