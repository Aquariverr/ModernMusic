package dev.modernmusic.server;

import java.util.UUID;

/** One editor at a time. View IDs and sequence numbers reject closed-screen and duplicate commands. */
public final class SharedAdminLease {
    public static final long TIMEOUT_MS = 15_000;
    private UUID owner, view;
    private long expiresAt, sequence;
    public UUID view() { return view; }
    public long sequence() { return sequence; }
    public boolean valid(UUID player, UUID screen, long now) {
        return owner != null && owner.equals(player) && view.equals(screen) && now < expiresAt;
    }
    public boolean open(UUID player, UUID screen, long seq, long now) {
        if (owner != null && !valid(player, screen, now) || seq <= 0) return false;
        if (owner == null) { owner = player; view = screen; sequence = 0; }
        return accept(player, screen, seq, now);
    }
    public boolean accept(UUID player, UUID screen, long seq, long now) {
        if (owner == null || !owner.equals(player) || !view.equals(screen) || seq <= sequence || now >= expiresAt && sequence > 0) return false;
        sequence = seq; expiresAt = now + TIMEOUT_MS; return true;
    }
    public boolean expired(long now) { return owner != null && now >= expiresAt; }
    public void clear() { owner = view = null; sequence = expiresAt = 0; }
}
