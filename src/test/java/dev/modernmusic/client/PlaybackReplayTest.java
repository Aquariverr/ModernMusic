package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackReplayTest {
    private static final Lyrics LYRICS = Lyrics.parse("[00:00.00]Fixture", "");

    private static final class Provider implements MusicProvider {
        int details, resolves, lyricRequests;
        boolean delayedLyrics;
        final Map<SongId, CompletableFuture<Lyrics>> lyrics = new HashMap<>();
        public CompletableFuture<Track> getTrack(SongId id) {
            details++;
            return CompletableFuture.completedFuture(new Track(id, "Track " + id.value(), List.of(), 1000));
        }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            resolves++;
            return CompletableFuture.completedFuture(new PlaybackResource(id,
                    URI.create("https://m801.music.126.net/fixture.mp3"), Instant.now().plusSeconds(60)));
        }
        public CompletableFuture<Lyrics> getLyrics(SongId id) {
            lyricRequests++;
            var request = new CompletableFuture<Lyrics>() {
                @Override public boolean cancel(boolean interrupt) { return false; }
            };
            lyrics.put(id, request);
            if (!delayedLyrics) request.complete(LYRICS);
            return request;
        }
        public void close() {}
    }

    private static final class Output implements AudioOutput {
        final List<PcmAudio> loaded = new ArrayList<>();
        Snapshot value = new Snapshot(State.STOPPED, 0, 0);
        public void load(PcmAudio audio) {
            loaded.add(audio); value = new Snapshot(State.PLAYING, 0, audio.durationMs());
        }
        public void pause(boolean pause) { value = new Snapshot(pause ? State.PAUSED : State.PLAYING, value.positionMs(), value.durationMs()); }
        public void seek(long position) { value = new Snapshot(value.state(), position, value.durationMs()); }
        public void volume(float volume) {}
        public void stop() { value = new Snapshot(State.STOPPED, 0, 0); }
        public Snapshot snapshot() { return value; }
        public void close() {}
    }

    private static final class Fixture implements AutoCloseable {
        final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        final Provider provider = new Provider();
        final Output output = new Output();
        final AtomicInteger downloads = new AtomicInteger();
        final PlaybackController player = new PlaybackController(provider, (uri, max) -> {
            downloads.incrementAndGet(); return Mp3DecoderTest.silentMp3();
        }, output, main::add);
        void awaitLoad(int count) throws Exception {
            SharedPlaybackTest.pump(main, () -> output.loaded.size() == count);
            drain(); player.tick(1);
        }
        void drain() { Runnable callback; while ((callback = main.poll()) != null) callback.run(); }
        public void close() { player.close(); }
    }

    @Test void tenManualReplaysReuseTheDecodedPcmAndLyricsFromPlayingAndPausedStates() throws Exception {
        try (var fixture = new Fixture()) {
            var player = fixture.player;
            player.add("42"); fixture.awaitLoad(1);
            PcmAudio original = fixture.output.loaded.getFirst();
            assertSame(LYRICS, player.lyrics());
            for (int replay = 0; replay < 10; replay++) {
                player.seek(500);
                if (replay % 2 == 0) player.togglePause();
                player.play(0);
                assertEquals(PlaybackController.State.LOADING, player.state());
                assertEquals("modernmusic.status.audio_starting", player.message());
                assertEquals(0, player.audio().positionMs());
                assertEquals(AudioOutput.State.PLAYING, player.audio().state());
                assertSame(original, fixture.output.loaded.getLast());
                assertSame(LYRICS, player.lyrics());
                fixture.drain(); player.tick(1);
                assertEquals(PlaybackController.State.PLAYING, player.state());
            }
            assertEquals(11, fixture.output.loaded.size());
            assertEquals(1, fixture.provider.details);
            assertEquals(1, fixture.provider.resolves);
            assertEquals(1, fixture.provider.lyricRequests);
            assertEquals(1, fixture.downloads.get());
        }
    }

    @Test void replayPreservesPendingLyricsAndChangingSongsStillRejectsLateLyrics() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.provider.delayedLyrics = true;
            var player = fixture.player;
            player.add("42"); fixture.awaitLoad(1);
            var pending = fixture.provider.lyrics.get(new SongId(42));
            player.play(0);
            assertEquals(1, fixture.provider.lyricRequests);
            pending.complete(LYRICS); fixture.drain();
            assertSame(LYRICS, player.lyrics());

            player.add("43"); player.play(1); fixture.awaitLoad(3);
            var stale = fixture.provider.lyrics.get(new SongId(43));
            player.play(1); player.playbackSourceChanged(); player.play(1); fixture.awaitLoad(5);
            stale.complete(LYRICS); fixture.drain();
            assertSame(Lyrics.EMPTY, player.lyrics());
            var current = Lyrics.parse("[00:00.00]Current source", "");
            fixture.provider.lyrics.get(new SongId(43)).complete(current); fixture.drain();
            assertSame(current, player.lyrics());
            assertEquals(3, fixture.provider.lyricRequests);
            assertEquals(3, fixture.downloads.get());
        }
    }

    @Test void lifecycleInvalidationForcesAFreshDownloadForTheSameSong() throws Exception {
        List<Consumer<PlaybackController>> invalidations = List.of(
                PlaybackController::stop, PlaybackController::suspend, PlaybackController::playbackSourceChanged,
                PlaybackController::clearTemporaryCache, PlaybackController::audioReloaded);
        for (var invalidate : invalidations) {
            try (var fixture = new Fixture()) {
                var player = fixture.player;
                player.add("42"); fixture.awaitLoad(1);
                invalidate.accept(player);
                assertEquals(0, player.bufferedBytes());
                player.play(0); fixture.awaitLoad(2);
                assertNotSame(fixture.output.loaded.getFirst(), fixture.output.loaded.getLast());
                assertEquals(2, fixture.provider.resolves);
                assertEquals(2, fixture.provider.lyricRequests);
                assertEquals(2, fixture.downloads.get());
            }
        }
    }

    @Test void changingSongsAndQueuesNeverReusesAnotherSelectionsAudio() throws Exception {
        try (var fixture = new Fixture()) {
            var player = fixture.player;
            player.add("42"); fixture.awaitLoad(1);
            player.add("43"); player.play(1); fixture.awaitLoad(2);
            player.play(0); fixture.awaitLoad(3);
            var originalQueue = player.library().active().id();
            assertTrue(player.createQueue("Other"));
            player.add("42"); fixture.awaitLoad(4);
            player.switchQueue(originalQueue); player.play(0); fixture.awaitLoad(5);
            assertEquals(5, fixture.provider.resolves);
            assertEquals(5, fixture.downloads.get());
            for (int i = 1; i < fixture.output.loaded.size(); i++)
                assertNotSame(fixture.output.loaded.get(i - 1), fixture.output.loaded.get(i));
        }
    }

    @Test void roomControlsStillRouteAndLocalAudioIsNotReusedAcrossRoomBoundaries() throws Exception {
        try (var fixture = new Fixture()) {
            var player = fixture.player;
            player.add("42"); fixture.awaitLoad(1);
            player.applyRoom(new SharedPlaybackTest().room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            fixture.awaitLoad(2);
            var commands = new ArrayList<RoomCommand.Action>();
            player.roomCommands((action, value) -> commands.add(action));
            player.play(0);
            assertEquals(List.of(RoomCommand.Action.PLAY), commands);
            assertEquals(2, fixture.output.loaded.size());
            player.applyRoom(null); player.play(0); fixture.awaitLoad(3);
            assertEquals(3, fixture.provider.resolves);
            assertEquals(3, fixture.downloads.get());
        }
    }
}
