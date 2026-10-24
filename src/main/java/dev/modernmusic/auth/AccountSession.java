package dev.modernmusic.auth;

import javax.annotation.Nonnull;
import java.time.Instant;

public record AccountSession(String userId, String nickname, SessionCookie cookie, Instant validatedAt, java.net.URI avatar) {
    public AccountSession(String userId, String nickname, SessionCookie cookie, Instant validatedAt) {
        this(userId, nickname, cookie, validatedAt, null);
    }
    @Nonnull @Override public String toString() { return "AccountSession[redacted]"; }
}
