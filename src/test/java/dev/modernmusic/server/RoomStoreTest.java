package dev.modernmusic.server;

import dev.modernmusic.domain.*;
import dev.modernmusic.room.*;
import dev.modernmusic.room.RoomService.PersistentRoom;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoomStoreTest {
    @TempDir Path directory;
    private PersistentRoom room(UUID id, UUID owner, List<SongId> songs) {
        return new PersistentRoom(id, "晚风音乐", owner, List.of(new RoomSnapshot.Member(owner, "Owner", true)),
                songs, songs.isEmpty() ? -1 : 0, PlaybackMode.SHUFFLE);
    }
    @Test void flushesLatestQueueIdentityAndPermissionsAndRestoresReentry() throws Exception {
        var file = directory.resolve("data/modernmusic-rooms.json");
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), dj = UUID.randomUUID();
        var expected = new PersistentRoom(id, "晚风音乐", owner,
                List.of(new RoomSnapshot.Member(owner, "Owner", true), new RoomSnapshot.Member(dj, "Guest", true)),
                List.of(new SongId(42), SongId.stored("qq:123"), SongId.stored("kugou:0123456789abcdef0123456789abcdef")),
                1, PlaybackMode.REPEAT_ALL);
        try (var store = new RoomStore(file)) {
            assertTrue(store.load().isEmpty());
            for (int i = 1; i < 32; i++) store.save(List.of(room(id, owner, List.of(new SongId(i)))));
            store.save(List.of(expected));
        }
        try (var store = new RoomStore(file)) {
            assertEquals(List.of(expected), store.load()); assertFalse(store.failed());
            var service = new RoomService(); service.restoreRooms(store.load());
            var entry = service.updatesFor(List.of(dj), 0).get(dj).savedRooms().get(0);
            service.execute(dj, "Guest", new RoomCommand(RoomCommand.Action.JOIN, entry.room(), entry.epoch(), -1, "", 0), null, 0);
            var restored = service.current(dj);
            assertEquals(id, restored.id()); assertEquals(owner, restored.owner());
            assertTrue(restored.canControl(dj)); assertEquals(RoomSnapshot.State.STOPPED, restored.state());
            assertEquals(expected.songs().stream().map(SongId::toString).toList(), restored.songs());
            assertEquals(expected.selected(), restored.index()); assertEquals(expected.mode(), restored.mode());
        }
        String saved = Files.readString(file);
        assertFalse(saved.contains("anchor")); assertFalse(saved.contains("cookie")); assertFalse(saved.contains("PLAYING"));
    }
    @Test void deletingLastRoomDoesNotResurrectItOnReload() {
        var file = directory.resolve("rooms.json");
        try (var store = new RoomStore(file)) {
            store.save(List.of(room(UUID.randomUUID(), UUID.randomUUID(), List.of(new SongId(42)))));
            store.save(List.of());
        }
        try (var store = new RoomStore(file)) { assertTrue(store.load().isEmpty()); assertFalse(store.failed()); }
    }
    @Test void publicRoomAndFullPrivateRoomDirectorySurviveDiskRoundTrip() throws Exception {
        var file = directory.resolve("rooms.json"); UUID admin = UUID.randomUUID(), dj = UUID.randomUUID();
        var publicRoom = new PersistentRoom(RoomService.PUBLIC_ROOM_ID, "Server Public Room", RoomService.SERVER_OWNER,
                List.of(new RoomSnapshot.Member(admin, "Admin", false), new RoomSnapshot.Member(dj, "DJ", true)),
                List.of(new SongId(42)), 0, PlaybackMode.REPEAT_ALL);
        var saved = new ArrayList<PersistentRoom>();
        for (int i = 0; i < RoomService.MAX_ROOMS; i++) saved.add(room(UUID.randomUUID(), admin, List.of()));
        saved.add(publicRoom);
        try (var store = new RoomStore(file)) { store.save(saved); }
        assertFalse(Files.readString(file).contains("administrator"));
        try (var store = new RoomStore(file)) {
            assertEquals(saved, store.load()); assertFalse(store.failed());
            var service = new RoomService(); service.restoreRooms(store.load()); service.openPublicRoom();
            var entry = service.updatesFor(List.of(dj), 0).get(dj).savedRooms().get(0);
            assertTrue(entry.isPublicRoom());
            service.execute(dj, "DJ", new RoomCommand(RoomCommand.Action.JOIN, entry.room(), entry.epoch(), -1, "", 0), null, 0);
            assertTrue(service.current(dj).canControl(dj)); assertFalse(service.current(dj).canManage(dj));
            assertEquals(List.of("42"), service.current(dj).songs());
        }
    }
    @Test void historicalPermissionsAreNotTruncatedToSimultaneousMemberLimit() {
        var file = directory.resolve("rooms.json"); UUID owner = UUID.randomUUID();
        var members = new ArrayList<RoomSnapshot.Member>(); members.add(new RoomSnapshot.Member(owner, "Owner", true));
        for (int i = 0; i < 24; i++) members.add(new RoomSnapshot.Member(UUID.randomUUID(), "Listener" + i, false, i % 2 == 0));
        var expected = new PersistentRoom(UUID.randomUUID(), "History", owner, members, List.of(), -1, PlaybackMode.SEQUENTIAL);
        try (var store = new RoomStore(file)) { store.save(List.of(expected)); }
        try (var store = new RoomStore(file)) { assertEquals(List.of(expected), store.load()); }
    }
    @Test void corruptOriginalIsPreservedBeforeReplacement() throws Exception {
        var file = directory.resolve("modernmusic-rooms.json"); Files.writeString(file, "corrupt original");
        var expected = room(UUID.randomUUID(), UUID.randomUUID(), List.of());
        try (var store = new RoomStore(file)) {
            assertTrue(store.load().isEmpty()); assertTrue(store.failed()); store.save(List.of(expected));
        }
        try (var paths = Files.list(directory)) {
            var backup = paths.filter(p -> p.getFileName().toString().startsWith("modernmusic-rooms-recovery-")).findFirst().orElseThrow();
            assertEquals("corrupt original", Files.readString(backup));
        }
        try (var store = new RoomStore(file)) { assertEquals(List.of(expected), store.load()); }
    }
    @Test void rejectsDuplicateRoomIdsInsteadOfSilentlyDroppingRooms() throws Exception {
        var file = directory.resolve("rooms.json"); var expected = room(UUID.randomUUID(), UUID.randomUUID(), List.of());
        try (var store = new RoomStore(file)) { store.save(List.of(expected, expected)); }
        try (var store = new RoomStore(file)) { assertTrue(store.load().isEmpty()); assertTrue(store.failed()); }
    }
}
