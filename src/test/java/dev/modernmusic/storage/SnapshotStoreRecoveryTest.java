package dev.modernmusic.storage;

import dev.modernmusic.client.QueueStore;
import dev.modernmusic.domain.*;
import dev.modernmusic.room.RoomService;
import dev.modernmusic.room.RoomSnapshot;
import dev.modernmusic.server.RoomStore;
import dev.modernmusic.server.TeamQueueStore;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreRecoveryTest {
    @TempDir Path directory;

    private Path blockedPath() throws Exception {
        Path parent = directory.resolve("blocked"); Files.writeString(parent, "temporary disk obstruction");
        return parent.resolve("queues.json");
    }
    private void recover(Path file, BooleanSupplier failed) throws Exception {
        await(failed);
        Files.delete(file.getParent());
        // No second save, load, or close may be needed to recover the pending snapshot.
        await(() -> Files.isRegularFile(file) && !failed.getAsBoolean());
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Storage did not recover within the retry window");
    }
    @Test void publicRoomQueueAndPermissionsRecoverWithoutAnotherRevision() throws Exception {
        Path file = blockedPath(); UUID member = UUID.randomUUID();
        var expected = new RoomService.PersistentRoom(RoomService.PUBLIC_ROOM_ID, "Public", RoomService.SERVER_OWNER,
                List.of(new RoomSnapshot.Member(member, "Member", false, true)), List.of(new SongId(42)), 0, PlaybackMode.REPEAT_ALL);
        try (var store = new RoomStore(file)) { store.save(List.of(expected)); recover(file, store::failed); }
        try (var store = new RoomStore(file)) { assertEquals(List.of(expected), store.load()); }
    }
    @Test void teamQueueRecoversWithoutAnotherRevision() throws Exception {
        Path file = blockedPath();
        var expected = new RoomService.TeamQueue(UUID.randomUUID(), List.of(new SongId(42)), 0, PlaybackMode.SHUFFLE);
        try (var store = new TeamQueueStore(file)) { store.save(List.of(expected)); recover(file, store::failed); }
        try (var store = new TeamQueueStore(file)) { assertEquals(List.of(expected), store.load()); }
    }
    @Test void personalQueueRecoversWithoutAnotherEdit() throws Exception {
        Path file = blockedPath(); var library = new QueueLibrary(); library.active().queue().add(new SongId(42));
        var expected = library.snapshot(List.of());
        try (var store = new QueueStore(file)) { store.save(expected); recover(file, store::failed); }
        try (var store = new QueueStore(file)) { assertEquals(expected, store.load().join()); }
    }
}
