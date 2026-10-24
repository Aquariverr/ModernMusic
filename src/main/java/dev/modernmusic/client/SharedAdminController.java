package dev.modernmusic.client;

import javax.annotation.Nonnull;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.shared.*;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

/** Client-thread admin view. Secrets are transient and are never copied from the personal account. */
public final class SharedAdminController {
    public record Request(UUID view, long sequence, SharedAdminAction action, String value) {
        @Nonnull @Override public String toString() { return "SharedAdminRequest[action=" + action + ", value=<redacted>]"; }
    }
    private final Consumer<Request> send;
    private final Clock clock;
    private final dev.modernmusic.domain.MusicPlatform platform;
    private boolean allowed;
    private UUID view;
    private long sequence, pending, lastCapability, lastSnapshot, nextKeepalive, requestStarted;
    private SharedAdminSnapshot snapshot;
    public SharedAdminController(Consumer<Request> send, Clock clock) { this(send, clock, dev.modernmusic.domain.MusicPlatform.NETEASE); }
    public SharedAdminController(Consumer<Request> send, Clock clock, dev.modernmusic.domain.MusicPlatform platform) { this.send = send; this.clock = clock; this.platform = platform; }
    public dev.modernmusic.domain.MusicPlatform platform() { return platform; }
    public boolean allowed() { return allowed; }
    public boolean waiting() { return pending != 0; }
    public SharedAdminSnapshot snapshot() { return snapshot; }
    public void capability(boolean value) {
        allowed = value; lastCapability = clock.millis();
        if (!value && view != null) {
            pending = 0; snapshot = SharedAdminSnapshot.unavailable(view, sequence, false, "modernmusic.admin.denied");
        }
    }
    public void open() {
        close(); view = UUID.randomUUID(); sequence = pending = 0; snapshot = null; lastSnapshot = clock.millis();
        request(SharedAdminAction.OPEN, "");
    }
    public void request(SharedAdminAction action, String value) {
        if (view == null || !allowed || waiting() && action != SharedAdminAction.CLOSE) return;
        if (action != SharedAdminAction.OPEN && action != SharedAdminAction.CLOSE && (snapshot == null || !snapshot.editing())) return;
        if (action == SharedAdminAction.COOKIE_LOGIN) {
            if (!snapshot.cookieUpload()) return;
            value = SessionCookie.parse(value, platform).header();
        } else value = "";
        pending = ++sequence; requestStarted = clock.millis(); nextKeepalive = requestStarted + 3_000;
        if (action == SharedAdminAction.OPEN) lastSnapshot = requestStarted;
        try { send.accept(new Request(view, sequence, action, value)); }
        catch (RuntimeException failure) {
            pending = 0; snapshot = SharedAdminSnapshot.unavailable(view, sequence, allowed, "modernmusic.admin.connection_lost");
        }
    }
    public void receive(SharedAdminSnapshot value) {
        if (view == null || !view.equals(value.view()) || value.acknowledgement() > sequence
                || snapshot != null && value.acknowledgement() < snapshot.acknowledgement()) return;
        if (!allowed && value.allowed()) return;
        snapshot = value; lastSnapshot = clock.millis();
        if (value.acknowledgement() >= pending) pending = 0;
    }
    public void tick() {
        if (allowed && clock.millis() - lastCapability > 10_000) capability(false);
        if (view == null) return;
        if (clock.millis() - lastSnapshot > 10_000 || waiting() && clock.millis() - requestStarted > 10_000) {
            pending = 0; snapshot = SharedAdminSnapshot.unavailable(view, sequence, allowed, "modernmusic.admin.connection_lost");
        }
        if (!waiting() && snapshot != null && snapshot.editing() && clock.millis() >= nextKeepalive)
            request(SharedAdminAction.KEEPALIVE, "");
    }
    public void close() {
        if (view != null) request(SharedAdminAction.CLOSE, "");
        view = null; snapshot = null; sequence = pending = 0;
    }
    public void disconnect() { allowed = false; view = null; snapshot = null; pending = sequence = 0; }
}
