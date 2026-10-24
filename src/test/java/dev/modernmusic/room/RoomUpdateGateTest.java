package dev.modernmusic.room;

import dev.modernmusic.domain.PlaybackMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomUpdateGateTest {
    final UUID player = UUID.randomUUID(), other = UUID.randomUUID(), room = UUID.randomUUID(), epoch = UUID.randomUUID();
    final RoomUpdateGate gate = new RoomUpdateGate();
    RoomSnapshot snapshot(long revision) {
        return new RoomSnapshot(room, epoch, "Room", revision, 1, 1, player,
                List.of(new RoomSnapshot.Member(player, "Owner", true)),
                LongStream.range(1_000_000_000L, 1_000_000_400L).mapToObj(Long::toString).toList(), 0,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.PLAYING, 0, 2_000, 240_000);
    }
    RoomUpdate update(long revision) { return new RoomUpdate(0, snapshot(revision), List.of(), ""); }

    @Test void unchangedFullQueueUsesEightyPercentLessHeartbeatPayload() {
        RoomUpdate state = update(1);
        int bytes = RoomJson.encode(state).getBytes(StandardCharsets.UTF_8).length;
        int sends = 0;
        for (long time = 0; time < 60_000; time += 1_000)
            if (gate.shouldSend(player, state, time, false)) sends++;
        assertEquals(12, sends);
        assertEquals(60L * bytes / 5, (long) sends * bytes);
        assertTrue(RoomUpdateGate.HEARTBEAT_MS < 10_000, "Heartbeats must precede the client timeout");
    }

    @Test void roomChangesOnlySendToAffectedPlayersAndAdvanceSequenceAloneIsIgnored() {
        var initial = update(1);
        var idle = new RoomUpdate(0, null, List.of(), "");
        assertTrue(gate.shouldSend(player, initial, 0, false));
        assertTrue(gate.shouldSend(other, idle, 0, false));
        assertTrue(gate.shouldSend(player, update(2), 50, false));
        assertFalse(gate.shouldSend(other, idle, 50, false));
        assertFalse(gate.shouldSend(player, new RoomUpdate(99, snapshot(2), List.of(), ""), 100, false));
        assertTrue(gate.shouldSend(player, idle, 150, false), "Leaving must immediately clear the room");
    }

    @Test void invitationsAndTeamStateAreDeliveredWithoutAQueueChange() {
        var initial = new RoomUpdate(0, null, List.of(), "");
        assertTrue(gate.shouldSend(player, initial, 0, false));
        var invitation = new RoomUpdate(0, null, List.of(new RoomUpdate.Invitation(room, epoch, "Room")), "");
        assertTrue(gate.shouldSend(player, invitation, 50, false));
        assertTrue(gate.shouldSend(player, initial, 100, false), "Expiry must remove the invitation");
        assertTrue(gate.shouldSend(player, new RoomUpdate(0, null, List.of(), "",
                new TeamStatus(TeamStatus.State.READY, "Party")), 150, false));
    }

    @Test void recoveryAndImportOrderingCanForceAResponseAndDisconnectForgetsState() {
        var state = update(1);
        assertTrue(gate.shouldSend(player, state, 0, false));
        assertTrue(gate.shouldSend(player, state, 1, true));
        var error = new RoomUpdate(0, state.room(), List.of(), "modernmusic.room.error.stale");
        assertTrue(gate.shouldSend(player, error, 2, true));
        assertFalse(gate.shouldSend(player, state, 3, false), "An error need not cause a second identical queue broadcast");
        gate.forget(player);
        assertTrue(gate.shouldSend(player, state, 4, false));
        gate.clear();
        assertTrue(gate.shouldSend(player, state, 5, false));
    }
    @Test void savedDirectoryCountsRevocationAndConfiguredLimitBypassHeartbeatDelay() {
        var initial = new RoomUpdate(0, null, List.of(), "");
        assertTrue(gate.shouldSend(player, initial, 0, false));
        var saved = new RoomUpdate.SavedRoom(room, epoch, "Saved", player, 0, 1);
        var listed = new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT, List.of(saved), 2);
        assertTrue(gate.shouldSend(player, listed, 1, false));
        assertFalse(gate.shouldSend(player, listed, 2, false));
        var active = new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT,
                List.of(new RoomUpdate.SavedRoom(room, epoch, "Saved", player, 1, 2)), 2);
        assertTrue(gate.shouldSend(player, active, 3, false));
        assertTrue(gate.shouldSend(player, new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT, active.savedRooms(), 0), 4, false));
        assertTrue(gate.shouldSend(player, new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT, List.of(), 0), 5, false));
    }
}
