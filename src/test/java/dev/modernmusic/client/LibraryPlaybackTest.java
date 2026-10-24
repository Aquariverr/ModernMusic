package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LibraryPlaybackTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private static class Provider implements MusicProvider {
        CompletableFuture<Playlist> list = uncancellable();
        Map<SongId, CompletableFuture<Lyrics>> lyrics = new HashMap<>();
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Song", List.of(), 1000)); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return new CompletableFuture<>(); }
        public CompletableFuture<Playlist> getPlaylist(SongId id) { return list; }
        public CompletableFuture<Lyrics> getLyrics(SongId id) { var task = LibraryPlaybackTest.<Lyrics>uncancellable(); lyrics.put(id, task); return task; }
        public void close() {}
    }
    private static <T> CompletableFuture<T> uncancellable() { return new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } }; }
    private static class Output implements AudioOutput {
        State state = State.STOPPED;
        public void load(PcmAudio audio) { state = State.PLAYING; }
        public void pause(boolean pause) { state = pause ? State.PAUSED : State.PLAYING; }
        public void seek(long position) {}
        public void volume(float volume) {}
        public void stop() { state = State.STOPPED; }
        public Snapshot snapshot() { return new Snapshot(state, 0, 1000); }
        public void close() {}
    }
    private PlaybackController player(Provider provider, Output output) { return new PlaybackController(provider, (uri, max) -> new byte[0], output, Runnable::run); }
    @Test void idsAndUrlsForSameSongCannotBeAddedTwiceOrInterruptPlayback() {
        Provider provider = new Provider(); Output output = new Output();
        try (var player = player(provider, output)) {
            assertTrue(player.add("42")); output.state = AudioOutput.State.PLAYING; player.tick(1);
            assertFalse(player.add("https://music.163.com/song?id=42"));
            assertEquals("modernmusic.status.duplicate", player.message()); assertEquals(PlaybackController.State.PLAYING, player.state());
            assertEquals(List.of(new SongId(42)), player.queue().entries()); assertEquals(0, player.queue().index());
        }
    }
    @Test void playlistSkipsDuplicatesAndContinuesWithLaterNewSongsWithoutRestarting() {
        Provider provider = new Provider(); Output output = new Output();
        try (var player = player(provider, output)) {
            player.add("1"); player.stop();
            player.importPlaylist("42");
            provider.list.complete(new Playlist("Fixture", List.of(new SongId(1), new SongId(2), new SongId(2), new SongId(3)), List.of(), 4));
            assertEquals(List.of(new SongId(1), new SongId(2), new SongId(3)), player.queue().entries());
            assertEquals(2, player.importedCount()); assertEquals(2, player.importSkipped());
            assertEquals("modernmusic.playlist.deduplicated", player.importMessage()); assertEquals(PlaybackController.State.STOPPED, player.state());
            player.importPlaylist("42");
            assertEquals(0, player.importedCount()); assertEquals(4, player.importSkipped());
            assertEquals(3, player.queue().entries().size()); assertEquals(PlaybackController.State.STOPPED, player.state());
        }
    }
    @Test void playlistAppendsInOrderReportsTruncationAndKeepsCurrentSelection() {
        Provider provider = new Provider();
        try (var player = player(provider, new Output())) {
            for (int i = 1; i < 400; i++) player.add(Integer.toString(i));
            player.importPlaylist("42");
            provider.list.complete(new Playlist("Fixture", List.of(new SongId(500), new SongId(501)), List.of(), 2));
            assertEquals(400, player.queue().entries().size()); assertEquals(new SongId(500), dev.modernmusic.compat.Java17.last(player.queue().entries()));
            assertEquals(0, player.queue().index()); assertEquals(1, player.importedCount());
            assertEquals("modernmusic.playlist.partial", player.importMessage());
        }
    }
    @Test void clearedOrLoggedOutQueueCannotBeRepopulatedByLateImport() {
        Provider provider = new Provider();
        try (var player = player(provider, new Output())) {
            player.importPlaylist("42"); assertTrue(player.importing()); player.clear();
            provider.list.complete(new Playlist("Fixture", List.of(new SongId(1)), List.of(), 1));
            assertTrue(player.queue().entries().isEmpty()); assertFalse(player.importing()); assertNull(player.track());
        }
    }
    @Test void switchedTrackDiscardsLateLyricsAndLyricFailureDoesNotStopPlayback() {
        Provider provider = new Provider(); Output output = new Output();
        try (var player = player(provider, output)) {
            player.add("1"); player.add("2"); player.play(1);
            provider.lyrics.get(new SongId(1)).complete(Lyrics.parse("[00:00]old", ""));
            assertTrue(player.lyrics().lines().isEmpty());
            output.state = AudioOutput.State.PLAYING; player.tick(1);
            provider.lyrics.get(new SongId(2)).completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
            assertEquals(PlaybackController.State.PLAYING, player.state());
            assertEquals("modernmusic.lyrics.failed", player.lyricMessage());
        }
    }
    @Test void naturalEndRepeatsCurrentTrackButManualNextSkipsIt() {
        Provider provider = new Provider(); Output output = new Output();
        try (var player = player(provider, output)) {
            player.add("1"); player.add("2"); player.queue().mode(PlaybackMode.REPEAT_ONE);
            output.state = AudioOutput.State.FINISHED; player.tick(1); assertEquals(0, player.queue().index());
            player.next(); assertEquals(1, player.queue().index());
            player.queue().mode(PlaybackMode.REPEAT_ALL); output.state = AudioOutput.State.FINISHED; player.tick(1);
            assertEquals(0, player.queue().index());
        }
    }
    @Test void repeatingLoadedTrackReusesPcmWithoutNetworkRequests() throws Exception {
        var callbacks = new ConcurrentLinkedQueue<Runnable>(); var requests = new java.util.concurrent.atomic.AtomicInteger();
        Provider provider = new Provider() {
            @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
                requests.incrementAndGet();
                return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/test.mp3"), Instant.now().plusSeconds(60)));
            }
        };
        Output output = new Output();
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, callbacks::add)) {
            player.add("1"); player.queue().mode(PlaybackMode.REPEAT_ONE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (output.state != AudioOutput.State.PLAYING && System.nanoTime() < deadline) {
                Runnable callback; while ((callback = callbacks.poll()) != null) callback.run(); Thread.sleep(5);
            }
            assertEquals(AudioOutput.State.PLAYING, output.state);
            output.state = AudioOutput.State.FINISHED; player.tick(1);
            assertEquals(AudioOutput.State.PLAYING, output.state); assertEquals(1, requests.get());
        }
    }
    @Test void queueSwitchCancelsOldImportAndPreservesPreviousSongs() {
        Provider provider = new Provider();
        try (var player = player(provider, new Output())) {
            player.add("1"); player.importPlaylist("42"); String first = player.library().active().id();
            player.createQueue("Other"); provider.list.complete(new Playlist("Old", List.of(new SongId(9)), List.of(), 1));
            assertTrue(player.queue().entries().isEmpty()); player.add("2"); player.switchQueue(first);
            assertEquals(List.of(new SongId(1)), player.queue().entries()); assertEquals(PlaybackController.State.STOPPED, player.state());
        }
    }
    @Test void removeOtherRowKeepsPlaybackAndRemoveCurrentRejectsOldLyrics() {
        Provider provider = new Provider(); Output output = new Output();
        try (var player = player(provider, output)) {
            player.add("1"); player.add("2"); player.add("3"); output.state = AudioOutput.State.PLAYING; player.tick(1);
            player.removeTrack(1); assertEquals(PlaybackController.State.PLAYING, player.state()); assertEquals(new SongId(1), player.track().id());
            var oldLyrics = provider.lyrics.get(new SongId(1)); player.removeTrack(0);
            oldLyrics.complete(Lyrics.parse("[00:00]old", "")); assertTrue(player.lyrics().lines().isEmpty()); assertEquals(new SongId(3), player.track().id());
            player.removeTrack(0); assertNull(player.track()); assertEquals(PlaybackController.State.IDLE, player.state());
        }
    }
    @Test void restartAndWorldExitKeepQueueAndDoNotStartAudioOrNetwork() throws Exception {
        var file = directory.resolve("queues.json"); var callbacks = new ConcurrentLinkedQueue<Runnable>();
        try (var first = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new Output(), callbacks::add, new QueueStore(file))) {
            awaitRestore(first, callbacks); first.add("1"); first.add("2"); first.renameQueue("Saved"); first.cycleMode();
            first.suspend(); assertEquals(2, first.queue().entries().size());
        }
        var provider = new Provider() { @Override public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError("Restore must not autoplay"); } };
        try (var second = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), callbacks::add, new QueueStore(file))) {
            awaitRestore(second, callbacks); assertEquals("Saved", second.library().active().name()); assertEquals(2, second.queue().entries().size());
            assertEquals(PlaybackMode.REPEAT_ONE, second.queue().mode()); assertEquals(PlaybackController.State.STOPPED, second.state());
            second.clear();
        }
        try (var store = new QueueStore(file)) { assertTrue(store.load().join().queues().get(0).songs().isEmpty()); }
    }
    private void awaitRestore(PlaybackController player, Queue<Runnable> callbacks) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!player.queuesReady() && System.nanoTime() < deadline) {
            Runnable callback; while ((callback = callbacks.poll()) != null) callback.run(); Thread.sleep(5);
        }
        assertTrue(player.queuesReady());
    }
}
