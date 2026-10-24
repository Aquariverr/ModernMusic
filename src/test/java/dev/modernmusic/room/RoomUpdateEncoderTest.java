package dev.modernmusic.room;

import dev.modernmusic.domain.PlaybackMode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomUpdateEncoderTest {
    private RoomSnapshot room(long revision) {
        return new RoomSnapshot(RoomService.PUBLIC_ROOM_ID, new UUID(2, 3), "Public \"room\" <&>", revision, 1, 1,
                RoomService.SERVER_OWNER,
                IntStream.range(0, 256).mapToObj(i -> new RoomSnapshot.Member(new UUID(1, i), "Player" + i, false)).toList(),
                IntStream.range(1, 401).mapToObj(Integer::toString).toList(), 0, PlaybackMode.SEQUENTIAL,
                RoomSnapshot.State.PLAYING, 0, 2_000, 240_000);
    }
    private RoomUpdate state(RoomSnapshot room, String message) {
        return new RoomUpdate(0, room, List.of(), message, TeamStatus.ABSENT,
                List.of(new RoomUpdate.SavedRoom(room.id(), room.epoch(), room.name(), room.owner(), room.members().size(), 400)), 2);
    }

    @Test void allRecipientsReuseTheSameCompletedPacketAndOnlyEncodeMembersOnce() {
        var calls = new AtomicInteger();
        var encoder = new RoomUpdateEncoder(91, room -> { calls.incrementAndGet(); return RoomJson.encode(room); });
        var room = room(1);
        String first = encoder.encode(state(room, ""));
        for (int i = 0; i < 256; i++) assertSame(first, encoder.encode(state(room, "")));
        assertEquals(1, calls.get());
        var decoded = RoomJson.decode(first, RoomUpdate.class);
        assertEquals(room, decoded.room()); assertEquals(91, decoded.sequence());
    }

    @Test void personalizedFieldsStayPrivateWhileRoomEncodingIsShared() {
        var calls = new AtomicInteger();
        var encoder = new RoomUpdateEncoder(7, room -> { calls.incrementAndGet(); return RoomJson.encode(room); });
        var room = room(1);
        var ordinary = state(room, "");
        var privateState = new RoomUpdate(0, room,
                List.of(new RoomUpdate.Invitation(UUID.randomUUID(), room.epoch(), "Invitation \"<&>")), "private message",
                new TeamStatus(TeamStatus.State.READY, "Party"), List.of(), 0);
        var first = RoomJson.decode(encoder.encode(ordinary), RoomUpdate.class);
        var second = RoomJson.decode(encoder.encode(privateState), RoomUpdate.class);
        assertEquals(ordinary.invitations(), first.invitations()); assertEquals("", first.message());
        assertEquals(privateState.invitations(), second.invitations()); assertEquals("private message", second.message());
        assertEquals(privateState.team(), second.team()); assertEquals(List.of(), second.savedRooms()); assertEquals(0, second.roomLimit());
        assertEquals(room, second.room()); assertEquals(1, calls.get());
    }

    @Test void independentSnapshotsAndBatchesCannotReuseStaleRevisionOrSequence() {
        var encoder = new RoomUpdateEncoder(1);
        assertEquals(1, RoomJson.decode(encoder.encode(state(room(1), "")), RoomUpdate.class).room().revision());
        assertEquals(2, RoomJson.decode(encoder.encode(state(room(2), "")), RoomUpdate.class).room().revision());
        var next = RoomJson.decode(new RoomUpdateEncoder(2).encode(state(room(2), "")), RoomUpdate.class);
        assertEquals(2, next.sequence());
        var lobby = new RoomUpdate(0, null, List.of(), "left");
        var decoded = RoomJson.decode(encoder.encode(lobby), RoomUpdate.class);
        assertNull(decoded.room()); assertEquals("left", decoded.message()); assertEquals(1, decoded.sequence());
    }
}
