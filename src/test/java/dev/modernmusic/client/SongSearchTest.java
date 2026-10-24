package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SongSearchTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    static class Provider implements MusicProvider {
        final List<CompletableFuture<SongSearchResult>> requests = new ArrayList<>();
        public CompletableFuture<Track> getTrack(SongId song) { return new CompletableFuture<>(); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId song) { return new CompletableFuture<>(); }
        public CompletableFuture<SongSearchResult> searchSongs(String query, int offset, int limit) {
            var future = new CompletableFuture<SongSearchResult>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            requests.add(future); return future;
        }
        public void close() {}
    }
    static Track song(long id) { return new Track(new SongId(id), "Song " + id, List.of("Artist"), 123000); }
    static SongSearchResult results(long id) { return new SongSearchResult(List.of(song(id)), 1, false); }
    static RoomSnapshot room(UUID id, UUID epoch, List<String> songs) {
        return new RoomSnapshot(id, epoch, "Room", 1, 1, 1, UUID.randomUUID(), List.of(), songs, songs.isEmpty() ? -1 : 0,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.STOPPED, 0, 0, 0);
    }
    @Test void staleResponsesAndResetNeverReplaceNewQuery() {
        var provider = new Provider(); var callbacks = new ArrayDeque<Runnable>();
        var search = new SongSearchController(provider, callbacks::add);
        search.search("old", 0, 10); search.search("new", 0, 10);
        provider.requests.get(1).complete(results(2)); provider.requests.get(0).complete(results(1));
        while (!callbacks.isEmpty()) callbacks.remove().run();
        assertEquals(new SongId(2), search.result().tracks().get(0).id()); assertFalse(search.loading());
        search.search("third", 0, 10); search.reset(); provider.requests.get(2).complete(results(3));
        while (!callbacks.isEmpty()) callbacks.remove().run();
        assertNull(search.result()); assertFalse(search.loading()); assertEquals("", search.query());
    }
    @Test void errorsAreRetryableAndInvalidInputCancelsPreviousRequest() {
        var provider = new Provider(); var search = new SongSearchController(provider, Runnable::run);
        search.search("song", 0, 10); provider.requests.get(0).completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
        assertEquals("modernmusic.error.network_error", search.message()); assertFalse(search.loading());
        search.search("song", 0, 10); search.search(" ", 0, 10); dev.modernmusic.compat.Java17.last(provider.requests).complete(results(1));
        assertNull(search.result()); assertEquals("modernmusic.search.invalid_query", search.message());
        search.search("retry", 0, 10); dev.modernmusic.compat.Java17.last(provider.requests).complete(results(2)); assertNotNull(search.result());
    }
    @Test void selectedInactiveQueueReceivesMetadataWithoutInterruptingPlaybackAndIsPersisted() throws Exception {
        var provider = new Provider(); var output = new SharedPlaybackTest.Output();
        var file = directory.resolve("queues.json"); var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, callbacks::add, new QueueStore(file))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!player.queuesReady() && System.nanoTime() < deadline) {
                Runnable callback; while ((callback = callbacks.poll()) != null) callback.run(); Thread.sleep(5);
            }
            assertTrue(player.queuesReady()); var first = player.library().active(); player.createQueue("Other");
            var other = dev.modernmusic.compat.Java17.last(player.searchDestinations()); player.switchQueue(first.id()); player.add("1");
            output.value = new dev.modernmusic.client.audio.AudioOutput.Snapshot(dev.modernmusic.client.audio.AudioOutput.State.PLAYING, 1234, 10000);
            player.tick(1);
            var playingState = player.state(); var selected = player.queue().index();
            var result = player.addSearchTracks(other, List.of(song(2), song(3))).join();
            assertEquals(2, result.added()); assertSame(first, player.library().active());
            assertEquals(playingState, player.state()); assertEquals(selected, player.queue().index());
            assertEquals(PlaybackController.State.PLAYING, player.state()); assertEquals(1234, player.audio().positionMs());
            assertEquals(List.of(new SongId(1)), player.queue().entries()); assertEquals("Song 2", player.track(new SongId(2)).title());
        }
        try (var store = new QueueStore(file)) {
            var saved = store.load().join(); assertEquals(List.of(new SongId(2), new SongId(3)), dev.modernmusic.compat.Java17.last(saved.queues()).songs());
            assertTrue(saved.tracks().stream().anyMatch(track -> track.id().equals(new SongId(2))));
        }
    }
    @Test void capacityAndMissingDestinationCannotModifyWrongQueue() {
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            var target = player.searchDestinations().get(0);
            for (int i = 100; i < 499; i++) player.queue().add(new SongId(i));
            var result = player.addSearchTracks(target, List.of(song(2), song(3))).join();
            assertEquals(1, result.added()); assertEquals("modernmusic.search.partial", result.message());
            assertNull(player.track(new SongId(3))); assertEquals(400, player.queue().entries().size());
            assertEquals(0, player.addSearchTracks(target, List.of(song(3))).join().added());
            assertEquals(0, player.addSearchTracks(new PlaybackController.SearchDestination("missing", "Missing", null), List.of(song(3))).join().added());
        }
    }
    @Test void localDestinationWhileInRoomSavesLocallyAndRoomDestinationWaitsForAck() {
        var ack = new CompletableFuture<RoomImportResult>(); var sent = new ArrayList<RoomImportRequest>();
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            var local = player.searchDestinations().get(0); var id = UUID.randomUUID(); var epoch = UUID.randomUUID();
            player.applyRoom(room(id, epoch, List.of("42"))); var shared = player.searchDestinations().get(0);
            assertEquals(1, player.addSearchTracks(local, List.of(song(3))).join().added());
            assertEquals(List.of(new SongId(42)), player.queue().entries());
            assertEquals(List.of(new SongId(3)), player.library().active().queue().entries());
            player.roomImporter(request -> { sent.add(request); return ack; });
            var result = player.addSearchTracks(shared, List.of(song(4), song(5)));
            assertFalse(result.isDone()); assertEquals(List.of("4", "5"), sent.get(0).songs());
            assertEquals(List.of(new SongId(42)), player.queue().entries());
            ack.complete(new RoomImportResult(sent.get(0).request(), 1, 2, ""));
            assertEquals(1, result.join().added()); assertEquals("modernmusic.search.partial", result.join().message());
        }
    }
    @Test void staleRoomDestinationAndLateAckCannotAffectAnotherRoom() {
        var ack = new CompletableFuture<RoomImportResult>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        var sent = new ArrayList<RoomImportRequest>();
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            var id = UUID.randomUUID(); player.applyRoom(room(id, UUID.randomUUID(), List.of("42")));
            var target = player.searchDestinations().get(0); player.roomImporter(request -> { sent.add(request); return ack; });
            var result = player.addSearchTracks(target, List.of(song(4)));
            player.applyRoom(room(id, UUID.randomUUID(), List.of("50")));
            ack.complete(new RoomImportResult(sent.get(0).request(), 1, 1, ""));
            assertTrue(result.isCancelled()); assertNull(player.track(new SongId(4)));
            assertEquals(0, player.addSearchTracks(target, List.of(song(4))).join().added()); assertEquals(1, sent.size());
        }
    }
    @Test void sharedPlaybackSourceAlwaysUsesPersonalSearchWithoutServerRequests() {
        var provider = new Provider(); var source = new SharedPlaybackSource(provider, request -> fail("Playback request from search"), () -> {}, Clock.systemUTC());
        source.state(1, null); source.select(true);
        var result = source.searchSongs("song", 0, 10); provider.requests.get(0).complete(results(1));
        assertEquals(1, result.join().tracks().size()); source.close();
    }
    @Test void searchDuplicatesAreScopedToDestinationAndDoNotConsumeCapacityOrStopBatch() {
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            var first = player.searchDestinations().get(0);
            player.addSearchTracks(first, List.of(song(1))).join(); player.createQueue("Other");
            var other = dev.modernmusic.compat.Java17.last(player.searchDestinations()); player.addSearchTracks(other, List.of(song(2))).join();
            var result = player.addSearchTracks(other, List.of(song(2), song(1), song(1), song(3))).join();
            assertEquals(2, result.added()); assertEquals(2, result.skipped()); assertEquals(4, result.processed());
            assertEquals("modernmusic.search.deduplicated", result.message());
            assertEquals(List.of(new SongId(2), new SongId(1), new SongId(3)), player.queue().entries());
            assertEquals(List.of(new SongId(1)), player.library().queues().get(0).queue().entries());
            var repeat = player.addSearchTracks(other, List.of(song(1), song(2))).join();
            assertEquals(0, repeat.added()); assertEquals(2, repeat.processed()); assertEquals(2, repeat.skipped());
        }
    }
    @Test void partialSearchReceiptIncludesDuplicatesSoOnlyUnprocessedSongsRemainSelected() {
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            for (int i = 1; i < 400; i++) player.queue().add(new SongId(i));
            var result = player.addSearchTracks(player.searchDestinations().get(0), List.of(song(1), song(500), song(500), song(501))).join();
            assertEquals(1, result.added()); assertEquals(2, result.skipped()); assertEquals(3, result.processed());
            assertEquals("modernmusic.search.partial_deduplicated", result.message()); assertNull(player.track(new SongId(501)));
        }
    }
    @Test void roomReceiptsHandleConcurrentDuplicatesAndStillWorkWithOldServers() {
        for (boolean legacy : List.of(false, true)) {
            try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
                player.applyRoom(room(UUID.randomUUID(), UUID.randomUUID(), List.of("42")));
                player.roomImporter(request -> CompletableFuture.completedFuture(legacy
                        ? new RoomImportResult(request.request(), 1, 3, "")
                        : new RoomImportResult(request.request(), 1, 3, "", 2)));
                var result = player.addSearchTracks(player.searchDestinations().get(0), List.of(song(43), song(44), song(45))).join();
                assertEquals(1, result.added()); assertEquals(legacy ? 1 : 2, result.processed());
                assertEquals(legacy ? 0 : 1, result.skipped());
            }
        }
    }
}
