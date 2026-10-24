package dev.modernmusic.room;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Unchanged rooms need only a heartbeat; their timeline advances from the existing monotonic anchor. */
public final class RoomUpdateGate {
    // Keep well inside RoomClient's ten-second timeout, without changing the wire protocol.
    public static final long HEARTBEAT_MS = 5_000;
    private record Sent(RoomUpdate update, long at) {}
    private final Map<UUID, Sent> sent = new HashMap<>();

    public boolean shouldSend(UUID player, RoomUpdate update, long now, boolean force) {
        Sent previous = sent.get(player);
        if (!force && update.message().isEmpty() && previous != null && now - previous.at < HEARTBEAT_MS
                && Objects.equals(previous.update.room(), update.room())
                && previous.update.invitations().equals(update.invitations())
                && previous.update.team().equals(update.team())
                && previous.update.savedRooms().equals(update.savedRooms())
                && previous.update.roomLimit() == update.roomLimit()) return false;
        sent.put(player, new Sent(update, now));
        return true;
    }

    public void forget(UUID player) { sent.remove(player); }
    public void clear() { sent.clear(); }
}
