package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Main-window queue edits must never accidentally control or overwrite the room queue. */
class PersonalQueueInRoomTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private static class Provider implements MusicProvider {
        final CompletableFuture<Playlist> playlist = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { return false; }
        };
        Track reference;
        public CompletableFuture<Track> getTrack(SongId id) {
            return CompletableFuture.completedFuture(new Track(id, "Song " + id, List.of("Artist"), 1_000));
        }
        public CompletableFuture<Track> getReference(String value) { return CompletableFuture.completedFuture(reference); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { throw new AssertionError("Personal edits must not start audio in a room"); }
        public CompletableFuture<Playlist> getPlaylist(SongId id) { return playlist; }
        public void close() {}
    }

    private RoomSnapshot room() {
        return new RoomSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Together", 1, 1, 1, UUID.randomUUID(), List.of(),
                List.of("42", "43"), 0, PlaybackMode.REPEAT_ALL, RoomSnapshot.State.STOPPED, 0, 0, 0);
    }
    private PlaybackController player(MusicProvider provider, SharedPlaybackTest.Output output) {
        var player = new PlaybackController(provider, (uri, max) -> { throw new AssertionError("No audio download"); }, output, Runnable::run);
        player.roomCommands((action, value) -> fail("Personal operation sent room command " + action));
        player.roomImporter(request -> { throw new AssertionError("Personal import submitted to room"); });
        player.applyRoom(room());
        return player;
    }

    @Test void personalAddRemoveClearAndPlayKeepRoomQueueAndAudioUntouched() {
        var output = new SharedPlaybackTest.Output();
        try (var player = player(new Provider(), output)) {
            var roomQueue = player.queue();
            int stops = output.stops;
            assertTrue(player.addPersonal("1")); assertTrue(player.addPersonal("2"));
            assertFalse(player.addPersonal("1"));
            assertEquals(List.of(new SongId(1), new SongId(2)), player.personalQueue().entries());
            assertEquals("Song 1", player.track(new SongId(1)).title());
            player.playPersonal(1);
            assertTrue(player.removePersonalTrack(0)); assertFalse(player.removePersonalTrack(99));
            assertNull(player.track(new SongId(1)));
            assertEquals(List.of(new SongId(2)), player.personalQueue().entries());
            player.clearPersonalQueue();
            assertTrue(player.personalQueue().entries().isEmpty());
            assertSame(roomQueue, player.queue());
            assertEquals(List.of(new SongId(42), new SongId(43)), roomQueue.entries());
            assertEquals(0, roomQueue.index()); assertEquals(PlaybackMode.REPEAT_ALL, roomQueue.mode());
            assertEquals(stops, output.stops); assertEquals(0, output.loads);
            assertEquals(new SongId(42), player.track().id());
        }
    }

    @Test void personalQueueManagerWorksWithoutSuspendingRoomAndRestoresSelectedQueueOnLeave() {
        var output = new SharedPlaybackTest.Output();
        try (var player = player(new Provider(), output)) {
            var first = player.library().active().id();
            player.addPersonal("1");
            int stops = output.stops;
            assertTrue(player.createQueue("Second"));
            assertTrue(player.addPersonal("2")); player.renameQueue("Personal mix");
            var second = player.library().active().id();
            player.switchQueue(first);
            assertEquals(List.of(new SongId(1)), player.personalQueue().entries());
            player.switchQueue(second);
            assertEquals("Personal mix", player.library().active().name());
            assertEquals(List.of(new SongId(2)), player.personalQueue().entries());
            assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            assertEquals(stops, output.stops);
            var selected = player.personalQueue(); player.applyRoom(null);
            assertSame(selected, player.queue()); assertEquals("Song 2", player.track().title());
            assertEquals(PlaybackController.State.STOPPED, player.state());
        }
    }

    @Test void personalPlaylistImportKeepsMetadataAndNeverSubmitsRoomBatch() {
        var provider = new Provider();
        try (var player = player(provider, new SharedPlaybackTest.Output())) {
            assertTrue(player.importPersonalPlaylist("99"));
            var song = new Track(new SongId(7), "Seven", List.of("Artist"), 1_000);
            provider.playlist.complete(new Playlist("Mine", List.of(song.id(), new SongId(8)), List.of(song), 2));
            assertEquals(List.of(new SongId(7), new SongId(8)), player.personalQueue().entries());
            assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            assertEquals(song, player.track(song.id()));
            player.tick(1); assertEquals("Song 8", player.track(new SongId(8)).title());
            assertEquals(2, player.importedCount()); assertFalse(player.importing());
        }
    }

    @Test void clearingPersonalQueueRejectsLatePlaylistWithoutStoppingRoom() {
        var provider = new Provider(); var output = new SharedPlaybackTest.Output();
        try (var player = player(provider, output)) {
            assertTrue(player.importPersonalPlaylist("99"));
            int stops = output.stops; player.clearPersonalQueue();
            provider.playlist.complete(new Playlist("Late", List.of(new SongId(7)), List.of(), 1));
            assertTrue(player.personalQueue().entries().isEmpty());
            assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            assertFalse(player.importing()); assertNull(player.importMessage()); assertEquals(stops, output.stops);
        }
    }

    @Test void switchingPersonalQueueRejectsLatePlaylistWithoutTouchingEitherQueueOrRoom() {
        var provider = new Provider(); var output = new SharedPlaybackTest.Output();
        try (var player = player(provider, output)) {
            player.addPersonal("7");
            var original = player.personalQueue();
            assertTrue(player.importPersonalPlaylist("99"));
            int stops = output.stops; assertTrue(player.createQueue("Other"));
            provider.playlist.complete(new Playlist("Late", List.of(new SongId(8)), List.of(), 1));
            assertEquals(List.of(new SongId(7)), original.entries());
            assertTrue(player.personalQueue().entries().isEmpty());
            assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            assertFalse(player.importing()); assertNull(player.importMessage()); assertEquals(stops, output.stops);
        }
    }

    @Test void crossPlatformReferenceImportsStayPersonalInRoom() {
        for (var platform : List.of(MusicPlatform.QQ, MusicPlatform.KUGOU)) {
            var provider = new Provider();
            provider.reference = platform == MusicPlatform.QQ ? LinkImportTest.QQ_SONG : LinkImportTest.KUGOU_SONG;
            var multi = new MultiPlatformProvider(provider, provider, provider); multi.select(platform);
            try (var player = player(multi, new SharedPlaybackTest.Output())) {
                assertTrue(player.addPersonal(platform == MusicPlatform.QQ ? LinkImportTest.QQ_ID_LINK : LinkImportTest.KUGOU_LINK));
                assertEquals(List.of(provider.reference.id()), player.personalQueue().entries());
                assertEquals(provider.reference, player.track(provider.reference.id()));
                assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            }
        }
    }

    @Test void personalEditsPersistDuringRoomWithoutSavingSharedSongs() throws Exception {
        var file = directory.resolve("queues.json");
        var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(),
                callbacks::add, new QueueStore(file))) {
            SharedPlaybackTest.pump(callbacks, player::queuesReady);
            player.applyRoom(room());
            player.addPersonal("7"); player.addPersonal("8"); player.removePersonalTrack(0);
            player.renameQueue("Saved in group");
            SharedPlaybackTest.pump(callbacks, () -> { player.tick(1); return player.track(new SongId(8)) != null; });
        }
        try (var store = new QueueStore(file)) {
            var saved = store.load().join();
            assertEquals("Saved in group", saved.queues().get(0).name());
            assertEquals(List.of(new SongId(8)), saved.queues().get(0).songs());
            assertEquals(List.of(new SongId(8)), saved.tracks().stream().map(Track::id).toList());
        }
    }

    @Test void mutedRoomUpdatesCapacityAndImportsWithoutStartingAudioOrTouchingPersonalQueue() {
        var provider = new Provider(); var output = new SharedPlaybackTest.Output();
        try (var player = player(provider, output)) {
            player.addPersonal("7");
            var original = room();
            var full = new RoomSnapshot(original.id(), original.epoch(), original.name(), 2, 2, 2, original.owner(), List.of(),
                    java.util.stream.IntStream.rangeClosed(1, PlaybackQueue.MAX_TRACKS).mapToObj(Integer::toString).toList(),
                    0, PlaybackMode.SEQUENTIAL, RoomSnapshot.State.PLAYING, 0, 0, 0);
            player.applyRoom(full, false);
            assertEquals(PlaybackQueue.MAX_TRACKS, player.queue().entries().size());
            assertEquals(PlaybackController.State.STOPPED, player.state()); assertEquals(0, output.loads);
            var cleared = new RoomSnapshot(full.id(), full.epoch(), full.name(), 3, 3, 3, full.owner(), List.of(),
                    List.of(), -1, PlaybackMode.SHUFFLE, RoomSnapshot.State.STOPPED, 0, 0, 0);
            player.applyRoom(cleared, false);
            assertTrue(player.queue().entries().isEmpty()); assertEquals(PlaybackMode.SHUFFLE, player.queue().mode());
            var requests = new ArrayList<RoomImportRequest>();
            player.roomImporter(request -> {
                requests.add(request);
                return CompletableFuture.completedFuture(new RoomImportResult(request.request(), 1, 1, ""));
            });
            assertTrue(player.importPlaylist("99"));
            player.applyRoom(cleared, false); // A muted heartbeat must not cancel a pending import.
            provider.playlist.complete(new Playlist("Added", List.of(new SongId(9)), List.of(), 1));
            assertEquals(1, requests.size()); assertEquals(full.id(), requests.get(0).room());
            assertEquals(List.of("9"), requests.get(0).songs()); assertEquals(1, player.importedCount());
            assertEquals(List.of(new SongId(7)), player.personalQueue().entries()); assertEquals(0, output.loads);
        }
    }
}
