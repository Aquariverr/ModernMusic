package dev.modernmusic.server;

import dev.modernmusic.domain.*;
import dev.modernmusic.room.RoomService.TeamQueue;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TeamQueueStoreTest {
    @TempDir Path directory;
    TeamQueue queue(UUID id, long song) { return new TeamQueue(id, List.of(new SongId(song)), 0, PlaybackMode.REPEAT_ALL); }
    @Test void coalescesEditsFlushesOnShutdownAndRestoresAllTeams() throws Exception {
        var file = directory.resolve("data/modernmusic-team-queues.json");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var expected = List.of(queue(first, 99), queue(second, 100));
        try (var store = new TeamQueueStore(file)) {
            assertTrue(store.load().isEmpty());
            for (int i = 1; i < 99; i++) store.save(List.of(queue(first, i)));
            store.save(expected);
        }
        try (var store = new TeamQueueStore(file)) { assertEquals(expected, store.load()); assertFalse(store.failed()); }
        String saved = Files.readString(file);
        assertFalse(saved.contains("PLAYING")); assertFalse(saved.contains("cookie")); assertFalse(saved.contains("owner"));
    }
    @Test void corruptOriginalIsBackedUpBeforeFirstReplacement() throws Exception {
        var file = directory.resolve("modernmusic-team-queues.json");
        Files.writeString(file, "corrupt original");
        try (var store = new TeamQueueStore(file)) {
            assertTrue(store.load().isEmpty()); assertTrue(store.failed());
            store.save(List.of(queue(UUID.randomUUID(), 42)));
        }
        try (var paths = Files.list(directory)) {
            var backup = paths.filter(p -> p.getFileName().toString().startsWith("modernmusic-team-queues-recovery-")).findFirst().orElseThrow();
            assertEquals("corrupt original", Files.readString(backup));
        }
        try (var store = new TeamQueueStore(file)) { assertEquals(1, store.load().size()); }
    }
    @Test void clearingPlaylistPersistsInsteadOfResurrectingOldSongs() {
        var file = directory.resolve("queues.json"); UUID id = UUID.randomUUID();
        var cleared = new TeamQueue(id, List.of(), -1, PlaybackMode.SHUFFLE);
        try (var store = new TeamQueueStore(file)) { store.save(List.of(queue(id, 42))); store.save(List.of(cleared)); }
        try (var store = new TeamQueueStore(file)) { assertEquals(List.of(cleared), store.load()); }
    }
}
