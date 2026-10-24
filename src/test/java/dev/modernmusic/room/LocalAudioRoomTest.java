package dev.modernmusic.room;

import dev.modernmusic.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAudioRoomTest {
    @Test void localAudioIsRejectedAtomicallyWhileServerAudioCanBeQueued() {
        var rooms = new RoomService(); var owner = UUID.randomUUID();
        rooms.execute(owner, "Owner", new RoomCommand(RoomCommand.Action.CREATE, null, null, -1, "Music", 0), null, 1000);
        var room = rooms.current(owner);
        var local = new SongId("local_" + "a".repeat(32), MusicPlatform.LOCAL);
        var server = new SongId("server_" + "b".repeat(32), MusicPlatform.LOCAL);
        var bad = new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of("42", local.toString()), 2);
        assertEquals("modernmusic.room.error.local_audio", assertThrows(RoomService.Rejected.class,
                () -> rooms.append(owner, bad, 2000)).key);
        assertTrue(rooms.current(owner).songs().isEmpty());
        var good = new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of(server.toString()), 1);
        assertEquals(1, rooms.append(owner, good, 3000).imported());
        assertEquals(List.of(server.toString()), rooms.current(owner).songs());
        assertThrows(IllegalArgumentException.class, () -> new RoomService.TeamQueue(UUID.randomUUID(), List.of(local), 0, PlaybackMode.SEQUENTIAL));
    }
}
