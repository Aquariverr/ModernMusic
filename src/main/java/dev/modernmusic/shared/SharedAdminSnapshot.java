package dev.modernmusic.shared;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.UUID;

/** Administrator-only view. Never contains a session cookie, even when an account is bound. */
public record SharedAdminSnapshot(UUID view, long acknowledgement, boolean allowed, boolean editing, boolean cookieUpload,
                                  boolean enabled, boolean operatorsOnly, boolean bound, boolean busy, boolean saved,
                                  boolean qrActive, boolean verificationRequired, String nickname, String status,
                                  String qrKey, long qrExpiresAt, String verificationUrl) {
    public SharedAdminSnapshot {
        if (view == null || acknowledgement < 0 || nickname == null || nickname.length() > 64 || status == null || status.length() > 128
                || qrKey == null || !qrKey.isEmpty() && !qrKey.matches("[A-Za-z0-9_-]{8,256}")
                || verificationUrl == null || verificationUrl.length() > 4096) throw new IllegalArgumentException("Invalid admin state");
        nickname = nickname.replaceAll("[\\p{Cntrl}§]", "");
        if (!status.matches("modernmusic\\.[a-z0-9_.]+")) throw new IllegalArgumentException("Invalid status");
        if (!verificationUrl.isEmpty() && verificationUri(verificationUrl) == null) throw new IllegalArgumentException("Invalid verification link");
        if ((!allowed || !editing) && (!qrKey.isEmpty() || !verificationUrl.isEmpty() || !nickname.isEmpty()))
            throw new IllegalArgumentException("Private state requires an editor");
    }
    public static SharedAdminSnapshot unavailable(UUID view, long acknowledgement, boolean allowed, String status) {
        return new SharedAdminSnapshot(view, acknowledgement, allowed, false, false, false, false, false, false, false,
                false, false, "", status, "", 0, "");
    }
    public static URI verificationUri(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawFragment() == null
                    && "music.163.com".equals(uri.getHost()) && (uri.getPath().startsWith("/st/") || uri.getPath().equals("/login")) ? uri : null;
        } catch (RuntimeException invalid) { return null; }
    }
    @Nonnull @Override public String toString() { return "SharedAdminSnapshot[allowed=" + allowed + ", editing=" + editing + ", private fields redacted]"; }
}
