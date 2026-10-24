package dev.modernmusic.provider.netease;

import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import java.net.HttpCookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** First-party cookie jar. NMTID is needed by playback too; no cookies may be sent to a CDN. */
final class LoginCookies {
    private static final Set<String> ALLOWED = Set.of("MUSIC_U", "__csrf", "MUSIC_A", "NMTID", "__remember_me");
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final ReentrantLock playback = new ReentrantLock();
    LoginCookies() {}
    LoginCookies(SessionCookie cookie) {
        if (cookie != null) for (String pair : cookie.header().split(";\\s*")) {
            String[] parts = pair.split("=", 2); values.put(parts[0], parts[1]);
        }
    }
    void accept(List<String> headers) { accept(headers, null); }
    synchronized void accept(List<String> headers, Snapshot sent) {
        try {
            var updated = new LinkedHashMap<>(values);
            if (headers.size() > 64) throw new IllegalArgumentException();
            for (String header : headers) {
                if (header.length() > SessionCookie.MAX_LENGTH) throw new IllegalArgumentException();
                for (HttpCookie cookie : HttpCookie.parse(header)) {
                    if (!ALLOWED.contains(cookie.getName())) continue;
                    String domain = cookie.getDomain();
                    if (domain != null && !domain.equalsIgnoreCase("music.163.com") && !domain.equalsIgnoreCase(".music.163.com")) continue;
                    String value = cookie.getValue();
                    if (value.chars().anyMatch(c -> c <= 32 || c > 126 || c == ';' || c == ',' || c == '"' || c == '\\')) throw new IllegalArgumentException();
                    // A slow response must not overwrite a cookie already rotated by another request.
                    if (sent != null && !Objects.equals(values.get(cookie.getName()), sent.values().get(cookie.getName()))) continue;
                    if (cookie.hasExpired() || value.isEmpty()) updated.remove(cookie.getName()); else updated.put(cookie.getName(), value);
                }
            }
            if (header(updated).length() > SessionCookie.MAX_LENGTH) throw new IllegalArgumentException();
            values.clear(); values.putAll(updated);
        } catch (IllegalArgumentException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    synchronized String csrf() { return values.getOrDefault("__csrf", ""); }
    synchronized String playbackToken() { return values.getOrDefault("NMTID", ""); }
    synchronized void clearSession() { values.remove("MUSIC_U"); }
    synchronized String header() { return header(values); }
    private static String header(Map<String, String> values) {
        // This jar is for the web login flow; don't advertise a desktop app version to the browser endpoints.
        return values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
    }
    SessionCookie session() { return SessionCookie.parse(header()); }
    synchronized Snapshot snapshot() { return new Snapshot(header(), csrf(), playbackToken(), Map.copyOf(values)); }
    record Snapshot(String header, String csrf, String playbackToken, Map<String, String> values) {
        SessionCookie session() { return SessionCookie.parse(header); }
        @Override public String toString() { return "CookieSnapshot[redacted]"; }
    }
    // Only resource resolutions share the initialization/rotation sequence. Waiting for another
    // resolution is interruptible; metadata and search never hold this lock during HTTP.
    void lockPlayback() throws InterruptedException { playback.lockInterruptibly(); }
    void unlockPlayback() { playback.unlock(); }
    @Override public String toString() { return "LoginCookies[redacted]"; }
}
