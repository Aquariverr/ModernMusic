package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QueueStoreTest {
    @TempDir Path directory;
    @Test void roundTripsNamedQueuesDuplicateSongsSelectionModesAndMetadata() throws Exception {
        Path file = directory.resolve("config/queues.json"); var library = new QueueLibrary(); library.rename("旅途");
        String first = library.active().id(); var queue = library.active().queue();
        queue.restore(List.of(new SongId(1), new SongId(1), new SongId(2)), 1, PlaybackMode.SHUFFLE);
        library.create("安静"); library.active().queue().add(new SongId(3)); library.select(first);
        var track = new Track(new SongId(1), "Song", List.of("Artist"), 200000, URI.create("https://p1.music.126.net/test.jpg"));
        try (var store = new QueueStore(file)) { store.save(library.snapshot(List.of(track))); }
        try (var store = new QueueStore(file)) {
            var snapshot = store.load().join(); var restored = new QueueLibrary(); restored.restore(snapshot);
            assertEquals("旅途", restored.active().name()); assertEquals(2, restored.queues().size());
            assertEquals(List.of(new SongId(1), new SongId(1), new SongId(2)), restored.active().queue().entries());
            assertEquals(1, restored.active().queue().index()); assertEquals(PlaybackMode.SHUFFLE, restored.active().queue().mode());
            assertFalse(restored.active().queue().add(new SongId(1)));
            assertEquals(track, snapshot.tracks().getFirst());
        }
    }
    @Test void closeFlushesNewestSnapshotAfterRapidMutations() throws Exception {
        Path file = directory.resolve("queues.json"); var library = new QueueLibrary();
        try (var store = new QueueStore(file)) {
            for (int i = 1; i <= 400; i++) { library.active().queue().add(new SongId(i)); store.save(library.snapshot(List.of())); }
            library.rename("Final name"); store.save(library.snapshot(List.of()));
        }
        try (var store = new QueueStore(file)) {
            var queue = store.load().join().queues().getFirst(); assertEquals(400, queue.songs().size()); assertEquals("Final name", queue.name());
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void versionOneQueuesUpgradeWithLocalAndServerAudioWithoutLosingOnlineSongs() throws Exception {
        Path file = directory.resolve("queues.json");
        Files.writeString(file, """
                {"version":1,"active":"existing","queues":[{"id":"existing","name":"已有队列",
                "songs":["123","qq:456","kugou:0123456789ABCDEF0123456789ABCDEF"],"selected":1,"mode":"SHUFFLE"}],
                "tracks":[{"id":"123","title":"Online song","artists":["Artist"],"duration":180000}]}
                """);
        var local = new SongId("local_0123456789abcdef0123456789abcdef", MusicPlatform.LOCAL);
        var server = new SongId("server_fedcba9876543210fedcba9876543210", MusicPlatform.LOCAL);
        var localTrack = new Track(local, "本地演示.wav", List.of(), 1050);
        var serverTrack = new Track(server, "共享演示.mp3", List.of(), 225500);
        QueueLibrary.Snapshot expected;
        try (var store = new QueueStore(file)) {
            var original = store.load().join(); var library = new QueueLibrary(); library.restore(original);
            assertEquals(3, library.active().queue().entries().size());
            library.active().queue().add(local); library.active().queue().add(server);
            var metadata = new ArrayList<>(original.tracks()); metadata.add(localTrack); metadata.add(serverTrack);
            expected = library.snapshot(metadata); store.save(expected);
        }
        try (var store = new QueueStore(file)) {
            var restored = store.load().join();
            assertEquals(expected, restored); assertFalse(store.failed());
            assertEquals(1, restored.queues().getFirst().selected());
            assertEquals(PlaybackMode.SHUFFLE, restored.queues().getFirst().mode());
            assertTrue(restored.tracks().contains(localTrack)); assertTrue(restored.tracks().contains(serverTrack));
            assertTrue(restored.queues().getFirst().songs().get(3).isClientLocal());
            assertTrue(restored.queues().getFirst().songs().get(4).isServerAudio());
        }
    }
    @Test void corruptOriginalIsPreservedBeforeNewEditsReplaceIt() throws Exception {
        Path file = directory.resolve("queues.json"); Files.writeString(file, "broken-original");
        try (var store = new QueueStore(file)) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> store.load().join()); assertTrue(store.failed());
            assertEquals("broken-original", Files.readString(file)); store.save(new QueueLibrary().snapshot(List.of()));
        }
        try (var files = Files.list(directory)) {
            var backup = files.filter(p -> p.getFileName().toString().startsWith("modernmusic-queues-recovery-")).findFirst().orElseThrow();
            assertEquals("broken-original", Files.readString(backup));
        }
        try (var store = new QueueStore(file)) { assertEquals(1, store.load().join().queues().size()); }
    }
    @Test void selectedTrackRemovalAndDuplicateRemovalPreserveValidIndices() {
        var queue = new PlaybackQueue(); queue.restore(List.of(new SongId(1), new SongId(1), new SongId(2)), 1, PlaybackMode.SEQUENTIAL);
        queue.remove(0); assertEquals(0, queue.index()); assertEquals(new SongId(1), queue.current().orElseThrow());
        queue.remove(0); assertEquals(new SongId(2), queue.current().orElseThrow());
        queue.remove(0); assertEquals(-1, queue.index()); assertFalse(queue.remove(0));
    }
    @Test void invalidNamesAndOversizedLibrariesAreRejectedWithoutMutation() {
        var library = new QueueLibrary();
        assertThrows(IllegalArgumentException.class, () -> library.rename(" \n§ "));
        assertThrows(IllegalArgumentException.class, () -> library.rename("x".repeat(49)));
        for (int i = 1; i < 64; i++) library.create("Queue " + i);
        assertThrows(IllegalStateException.class, () -> library.create("overflow")); assertEquals(64, library.queues().size());
    }
}
