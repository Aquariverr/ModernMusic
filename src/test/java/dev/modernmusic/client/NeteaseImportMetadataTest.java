package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseImportMetadataTest {
    @TempDir Path directory;
    static Track song(long id) { return new Track(new SongId(id), "歌曲 " + id, List.of("歌手"), 200000); }
    static class Provider implements MusicProvider {
        final List<List<SongId>> batches = new ArrayList<>();
        final List<CompletableFuture<List<Track>>> results = new ArrayList<>();
        int resolves, details;
        public CompletableFuture<Track> getTrack(SongId id) { details++; return CompletableFuture.completedFuture(song(id.value())); }
        public CompletableFuture<List<Track>> getTracks(List<SongId> ids) {
            batches.add(List.copyOf(ids));
            var result = new CompletableFuture<List<Track>>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            results.add(result); return result;
        }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { resolves++; return new CompletableFuture<>(); }
        public void close() {}
        void complete(int batch) { results.get(batch).complete(batches.get(batch).stream().map(id -> song(id.value())).toList()); }
    }
    private PlaybackController player(Provider provider, SharedPlaybackTest.Output output) {
        return new PlaybackController(provider, (uri, max) -> { throw new AssertionError("No audio download for queued metadata"); }, output, Runnable::run);
    }
    @Test void idsAndFullUrlsLoadNamesBeforePlaybackWithoutInterruptingTheCurrentSong() {
        for (String input : List.of("42", "https://music.163.com/song?id=42", "https://music.163.com/#/song?id=42",
                "分享歌曲 https://y.music.163.com/m/song?id=42")) {
            var provider = new Provider(); var output = new SharedPlaybackTest.Output();
            try (var player = player(provider, output)) {
                player.add("1"); int stops = output.stops;
                assertEquals(1, provider.resolves); // The first song still starts normally.
                assertTrue(player.add(input)); assertEquals(List.of(List.of(new SongId(42))), provider.batches);
                provider.complete(0);
                assertEquals("歌曲 42", player.track(new SongId(42)).title());
                assertEquals(List.of(new SongId(1), new SongId(42)), player.queue().entries());
                assertEquals(0, player.queue().index()); assertEquals(song(1), player.track());
                assertEquals(stops, output.stops); assertEquals(1, provider.resolves); assertEquals(1, provider.details);
                for (int i = 0; i < 10; i++) player.tick(1);
                assertEquals(1, provider.batches.size());
            }
        }
    }
    @Test void rapidImportsUseOnePendingBatchAndAtMostFiftySongsPerRequest() {
        var provider = new Provider();
        try (var player = player(provider, new SharedPlaybackTest.Output())) {
            player.add("1");
            for (int id = 2; id <= 104; id++) player.add(Integer.toString(id));
            assertEquals(1, provider.batches.size());
            for (int batch = 0; batch < 4; batch++) {
                assertTrue(provider.batches.get(batch).size() <= 50);
                provider.complete(batch); player.tick(1);
            }
            assertEquals(List.of(1, 50, 50, 2), provider.batches.stream().map(List::size).toList());
            assertEquals(104, player.queue().entries().size());
            for (var id : player.queue().entries()) assertNotNull(player.track(id));
            assertEquals(1, provider.resolves);
        }
    }
    @Test void failedMetadataDoesNotStopPlaybackOrCauseRequestsEveryTick() {
        var provider = new Provider();
        try (var player = player(provider, new SharedPlaybackTest.Output())) {
            player.add("1"); player.add("42");
            provider.results.getFirst().completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
            for (int i = 0; i < 60; i++) player.tick(1);
            assertEquals(1, provider.batches.size()); assertEquals(PlaybackController.State.LOADING, player.state());
            assertEquals(song(1), player.track()); assertEquals(1, provider.resolves);
        }
    }
    @Test void clearRemovalQueueSwitchAndSuspendDiscardLateMetadata() {
        for (String action : List.of("clear", "remove", "switch", "suspend")) {
            var provider = new Provider();
            try (var player = player(provider, new SharedPlaybackTest.Output())) {
                player.add("1"); player.add("42");
                switch (action) {
                    case "clear" -> player.clear();
                    case "remove" -> player.removeTrack(1);
                    case "switch" -> player.createQueue("Other");
                    case "suspend" -> player.suspend();
                }
                provider.complete(0); player.tick(1);
                assertNull(player.track(new SongId(42)), action);
                assertEquals(1, provider.batches.size(), action);
            }
        }
    }
    @Test void duplicateImportRepairsAndPersistsOldIdOnlyRowsWithoutPlaying() throws Exception {
        Path file = directory.resolve("queues.json"); var library = new QueueLibrary();
        library.active().queue().restore(List.of(new SongId(42)), 0, PlaybackMode.SEQUENTIAL);
        try (var store = new QueueStore(file)) { store.save(library.snapshot(List.of())); }
        var provider = new Provider(); var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), callbacks::add, new QueueStore(file))) {
            SharedPlaybackTest.pump(callbacks, player::queuesReady);
            player.tick(1); assertTrue(provider.batches.isEmpty());
            assertFalse(player.add("https://music.163.com/song?id=42"));
            assertEquals("modernmusic.status.duplicate", player.message());
            provider.complete(0); SharedPlaybackTest.pump(callbacks, () -> player.track(new SongId(42)) != null);
            assertEquals(song(42), player.track()); assertEquals(List.of(new SongId(42)), player.queue().entries());
            assertEquals(0, provider.resolves); assertEquals(0, provider.details);
        }
        try (var store = new QueueStore(file)) { assertEquals(List.of(song(42)), store.load().join().tracks()); }
    }
}
