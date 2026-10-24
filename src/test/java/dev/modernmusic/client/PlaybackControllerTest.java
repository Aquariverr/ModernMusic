package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackControllerTest {
    private static final class Provider implements MusicProvider {
        final Map<SongId, CompletableFuture<Track>> requests = new HashMap<>();
        int resolves;
        @Override public CompletableFuture<Track> getTrack(SongId id) {
            // Deliberately uncancellable: old transport completions must still be harmless.
            var request = new CompletableFuture<Track>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            requests.put(id, request); return request;
        }
        @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            resolves++;
            return CompletableFuture.failedFuture(new MusicException(MusicError.UNAVAILABLE));
        }
        @Override public void close() {}
        void complete(long id) { requests.get(new SongId(id)).complete(new Track(new SongId(id), "Song " + id, List.of("Artist"), 1000)); }
    }
    private static final class Output implements AudioOutput {
        Snapshot value = new Snapshot(State.STOPPED, 0, 0);
        @Override public void load(PcmAudio audio) { value = new Snapshot(State.PLAYING, 0, audio.durationMs()); }
        @Override public void pause(boolean paused) { value = new Snapshot(paused ? State.PAUSED : State.PLAYING, value.positionMs(), value.durationMs()); }
        @Override public void seek(long position) { value = new Snapshot(value.state(), position, value.durationMs()); }
        @Override public void volume(float volume) {}
        @Override public void stop() { value = new Snapshot(State.STOPPED, 0, 0); }
        @Override public Snapshot snapshot() { return value; }
        @Override public void close() {}
    }
    @Test void lateDetailsCannotReplaceNewSelectionOrRestartAfterClear() {
        Provider provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), Runnable::run)) {
            player.add("1"); player.add("2"); player.play(1);
            provider.complete(1);
            assertEquals(0, provider.resolves); assertNull(player.track());
            provider.complete(2);
            assertEquals("Song 2", player.track().title());
            assertEquals(1, provider.resolves); assertEquals(PlaybackController.State.ERROR, player.state());
            assertEquals("modernmusic.error.unavailable", player.message());
            player.play(0); player.clear(); provider.complete(1);
            assertEquals(1, provider.resolves); assertNull(player.track());
            assertTrue(player.queue().entries().isEmpty()); assertEquals(PlaybackController.State.IDLE, player.state());
        }
    }

    @Test void missingRuntimeLibraryIsReportedAsInternalErrorInsteadOfNetworkFailure() {
        Provider provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), Runnable::run)) {
            player.add("42");
            provider.requests.get(new SongId(42)).completeExceptionally(new NoClassDefFoundError("decoder"));
            assertEquals(PlaybackController.State.ERROR, player.state());
            assertEquals("modernmusic.error.internal_error", player.message());
        }
    }
    @Test void completesPipelineAndControlsWithoutRealNetworkOrDevice() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        Output output = new Output();
        MusicProvider provider = new MusicProvider() {
            public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Test", List.of(), 1000)); }
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/test.mp3"), Instant.now().plusSeconds(60))); }
            public void close() {}
        };
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            assertFalse(player.suppressesGameMusic());
            player.add("42");
            assertFalse(player.suppressesGameMusic());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (output.snapshot().state() != AudioOutput.State.PLAYING && System.nanoTime() < deadline) {
                Runnable callback; while ((callback = main.poll()) != null) callback.run();
                Thread.sleep(5);
            }
            player.tick(1); assertEquals(PlaybackController.State.PLAYING, player.state());
            assertTrue(player.suppressesGameMusic());
            player.togglePause(); player.tick(1); assertEquals(PlaybackController.State.PAUSED, player.state());
            assertFalse(player.suppressesGameMusic());
            player.seek(500); assertEquals(500, player.audio().positionMs());
            player.togglePause(); player.tick(1); assertEquals(PlaybackController.State.PLAYING, player.state());
            assertTrue(player.suppressesGameMusic());
            player.audioReloaded(); assertEquals(PlaybackController.State.STOPPED, player.state());
            assertFalse(player.suppressesGameMusic());
            assertEquals("modernmusic.status.audio_reset", player.message());
        }
    }

    private static class ResourceProvider implements MusicProvider {
        int resolves;
        boolean firstExpired;
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Test", List.of(), 1000)); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            resolves++;
            return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/attempt-" + resolves + ".mp3"),
                    Instant.now().plusSeconds(firstExpired && resolves == 1 ? -1 : 60)));
        }
        public void close() {}
    }
    private void settle(PlaybackController player, Queue<Runnable> main) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (player.state() == PlaybackController.State.LOADING && System.nanoTime() < deadline) {
            Runnable task; while ((task = main.poll()) != null) task.run();
            player.tick(1); Thread.sleep(5);
        }
        assertNotEquals(PlaybackController.State.LOADING, player.state());
    }
    @Test void rejectedUrlIsResolvedAgainAndOnlyFreshUrlIsRetried() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); ResourceProvider provider = new ResourceProvider();
        var downloads = new java.util.concurrent.atomic.AtomicInteger();
        try (var player = new PlaybackController(provider, (uri, max) -> {
            int attempt = downloads.incrementAndGet();
            assertEquals("/attempt-" + attempt + ".mp3", uri.getPath());
            if (attempt == 1) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
            return Mp3DecoderTest.silentMp3();
        }, new Output(), main::add)) {
            player.add("42"); settle(player, main);
            assertEquals(PlaybackController.State.PLAYING, player.state());
            assertEquals(2, provider.resolves); assertEquals(2, downloads.get());
        }
    }
    @Test void expiredUrlIsRefreshedBeforeDownload() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); ResourceProvider provider = new ResourceProvider(); provider.firstExpired = true;
        try (var player = new PlaybackController(provider, (uri, max) -> {
            assertEquals("/attempt-2.mp3", uri.getPath()); return Mp3DecoderTest.silentMp3();
        }, new Output(), main::add)) {
            player.add("42"); settle(player, main);
            assertEquals(PlaybackController.State.PLAYING, player.state()); assertEquals(2, provider.resolves);
        }
    }
    @Test void repeatedCdnRejectionStopsAfterOneRefreshAndOtherFailuresAreNotRetried() throws Exception {
        for (MusicError error : List.of(MusicError.RESOURCE_UNAVAILABLE, MusicError.RATE_LIMITED, MusicError.NETWORK_ERROR)) {
            Queue<Runnable> main = new ConcurrentLinkedQueue<>(); ResourceProvider provider = new ResourceProvider();
            try (var player = new PlaybackController(provider, (uri, max) -> { throw new MusicException(error); }, new Output(), main::add)) {
                player.add("42"); settle(player, main);
                assertEquals(PlaybackController.State.ERROR, player.state()); assertEquals(error.translationKey(), player.message());
                assertEquals(error == MusicError.RESOURCE_UNAVAILABLE ? 2 : 1, provider.resolves);
            }
        }
    }
    @Test void clearingQueueDiscardsPendingRefreshEvenIfProviderCannotCancel() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        var refresh = new CompletableFuture<PlaybackResource>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        ResourceProvider provider = new ResourceProvider() {
            @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
                if (resolves == 0) return super.resolvePlayback(id);
                resolves++; return refresh;
            }
        };
        var downloads = new java.util.concurrent.atomic.AtomicInteger();
        try (var player = new PlaybackController(provider, (uri, max) -> {
            downloads.incrementAndGet(); throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        }, new Output(), main::add)) {
            player.add("42");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (provider.resolves < 2 && System.nanoTime() < deadline) {
                Runnable task; while ((task = main.poll()) != null) task.run(); Thread.sleep(5);
            }
            assertEquals(2, provider.resolves); player.clear();
            refresh.complete(new PlaybackResource(new SongId(42), URI.create("https://m801.music.126.net/late.mp3"), Instant.now().plusSeconds(60)));
            Runnable task; while ((task = main.poll()) != null) task.run();
            assertEquals(PlaybackController.State.IDLE, player.state()); assertEquals(1, downloads.get());
        }
    }
}
