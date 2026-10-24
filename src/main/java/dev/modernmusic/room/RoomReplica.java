package dev.modernmusic.room;

import java.util.List;

/** Sequence covers joins, leaves and invitations too; a late room snapshot cannot undo a leave. */
public final class RoomReplica {
    private RoomUpdate current = new RoomUpdate(0, null, List.of(), "");
    private long sequence = -1;
    public RoomUpdate current() { return current; }
    public boolean accept(RoomUpdate update) {
        if (update.sequence() <= sequence) return false;
        var previous = current.room(); var next = update.room();
        if (previous != null && next != null && previous.id().equals(next.id()) && previous.epoch().equals(next.epoch())
                && next.revision() < previous.revision()) return false;
        sequence = update.sequence(); current = update; return true;
    }
}
