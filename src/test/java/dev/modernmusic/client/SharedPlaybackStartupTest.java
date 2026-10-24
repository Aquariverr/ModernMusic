package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.RoomSnapshot;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedPlaybackStartupTest {
    @TempDir Path directory;
    private final UUID roomId = UUID.randomUUID(), epoch = UUID.randomUUID(), owner = UUID.randomUUID();
    private static Track song(long id) { return new Track(new SongId(id), "Track " + id, List.of(), 1000); }
    private RoomSnapshot room(UUID id, UUID roomEpoch, long revision, long song, RoomSnapshot.State state) {
        return new RoomSnapshot(id, roomEpoch, "Shared", revision, revision, revision, owner, List.of(),
                List.of(Long.toString(song)), 0, PlaybackMode.SEQUENTIAL, state, 0, 10_000, 1000);
    }
    private QueueStore savedQueues(String name) {
        Path file = directory.resolve(name + ".json");
        var library = new QueueLibrary(); library.rename("Personal");
        library.active().queue().restore(List.of(new SongId(1)), 0, PlaybackMode.REPEAT_ALL);
        try (var store = new QueueStore(file)) { store.save(library.snapshot(List.of(song(1)))); }
        return new QueueStore(file);
    }
    private static final class Provider implements MusicProvider {
        final Map<SongId, CompletableFuture<Track>> details = new LinkedHashMap<>();
        final List<SongId> resolved = new ArrayList<>();
        public CompletableFuture<Track> getTrack(SongId id) {
            var future = new CompletableFuture<Track>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            assertNull(details.put(id, future), "The same room playback revision must not restart");
            return future;
        }
        public CompletableFuture<List<Track>> getTracks(List<SongId> ids) { return CompletableFuture.completedFuture(List.of()); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { resolved.add(id); return new CompletableFuture<>(); }
        public void close() {}
    }
    private static void drain(Queue<Runnable> callbacks) { Runnable task; while ((task = callbacks.poll()) != null) task.run(); }

    @Test void playingSnapshotStartsBeforePersonalQueueLoadAndSurvivesItsCompletion() throws Exception {
        var callbacks = new ConcurrentLinkedQueue<Runnable>(); var provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(),
                callbacks::add, savedQueues("startup"))) {
            // The real QueueStore callback is held on the client executor, without mutating readiness.
            assertFalse(player.queuesReady()); assertFalse(player.add("2")); player.play(0);
            assertTrue(provider.details.isEmpty());
            var snapshot = room(roomId, epoch, 1, 42, RoomSnapshot.State.PLAYING);
            player.applyRoom(snapshot);
            assertEquals(Set.of(new SongId(42)), provider.details.keySet());
            assertEquals(PlaybackController.State.LOADING, player.state());
            SharedPlaybackTest.pump(callbacks, player::queuesReady);
            assertEquals(List.of(new SongId(1)), player.personalQueue().entries());
            assertEquals(PlaybackMode.REPEAT_ALL, player.personalQueue().mode());
            assertEquals(song(1), player.track(new SongId(1)));
            assertEquals(List.of(new SongId(42)), player.queue().entries());
            provider.details.get(new SongId(42)).complete(song(42)); drain(callbacks);
            for (int i = 0; i < 100; i++) { player.applyRoom(snapshot); player.tick(1); }
            assertEquals(List.of(new SongId(42)), provider.resolved);
            assertEquals(song(42), player.track());
        }
        try (var store = new QueueStore(directory.resolve("startup.json"))) {
            var saved = store.load().join();
            assertEquals(List.of(new SongId(1)), saved.queues().get(0).songs());
            assertEquals(List.of(song(1)), saved.tracks());
        }
    }

    @Test void leavingMutingStoppingAndReplacingRoomBeforeQueueLoadRejectStalePlayback() throws Exception {
        for (String action : List.of("leave", "mute", "stop", "room", "epoch", "queue")) {
            var callbacks = new ConcurrentLinkedQueue<Runnable>(); var provider = new Provider();
            try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(),
                    callbacks::add, savedQueues(action))) {
                var original = room(roomId, epoch, 1, 42, RoomSnapshot.State.PLAYING);
                player.applyRoom(original);
                assertTrue(provider.details.containsKey(new SongId(42)), action);
                switch (action) {
                    case "leave" -> player.applyRoom(null);
                    case "mute" -> player.applyRoom(original, false);
                    case "stop" -> player.applyRoom(room(roomId, epoch, 2, 42, RoomSnapshot.State.STOPPED));
                    case "room" -> player.applyRoom(room(UUID.randomUUID(), epoch, 2, 43, RoomSnapshot.State.PLAYING));
                    case "epoch" -> player.applyRoom(room(roomId, UUID.randomUUID(), 2, 43, RoomSnapshot.State.PLAYING));
                    case "queue" -> player.applyRoom(room(roomId, epoch, 2, 43, RoomSnapshot.State.PLAYING));
                }
                provider.details.get(new SongId(42)).complete(song(42));
                SharedPlaybackTest.pump(callbacks, player::queuesReady); drain(callbacks);
                assertTrue(provider.resolved.isEmpty(), action);
                assertEquals(List.of(new SongId(1)), player.personalQueue().entries(), action);
                if (List.of("room", "epoch", "queue").contains(action)) {
                    assertEquals(List.of(new SongId(43)), player.queue().entries(), action);
                    provider.details.get(new SongId(43)).complete(song(43)); drain(callbacks);
                    assertEquals(List.of(new SongId(43)), provider.resolved, action);
                } else {
                    assertEquals(PlaybackController.State.STOPPED, player.state(), action);
                    assertFalse(player.shared() && player.track() != null && player.track().id().equals(new SongId(42)), action);
                }
            }
        }
    }

    @Test void closingBeforeQueueLoadDiscardsStorageAndPlaybackCallbacks() {
        var callbacks = new ConcurrentLinkedQueue<Runnable>(); var provider = new Provider(); var output = new SharedPlaybackTest.Output();
        var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, callbacks::add, savedQueues("closed"));
        try {
            player.applyRoom(room(roomId, epoch, 1, 42, RoomSnapshot.State.PLAYING));
            assertTrue(provider.details.containsKey(new SongId(42)));
            player.close(); provider.details.get(new SongId(42)).complete(song(42)); drain(callbacks);
            assertTrue(provider.resolved.isEmpty()); assertEquals(0, output.loads);
            assertNull(player.track()); assertNull(player.track(new SongId(1)));
        } finally { player.close(); }
    }
}
