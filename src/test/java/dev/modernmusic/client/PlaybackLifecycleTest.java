package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackLifecycleTest {
    @Test void waveformFollowsOutputPositionAndSettlesAfterPauseStopAndFailure() throws Exception {
        var main = new ConcurrentLinkedQueue<Runnable>(); var output = new SharedPlaybackTest.Output();
        try (var player = new PlaybackController(new Provider(), (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            float[] levels = new float[32];
            player.add("1"); SharedPlaybackTest.pump(main, () -> player.bufferedBytes() > 0);
            byte[] pcm = new byte[8000 * 2];
            for (int i = 8000; i < pcm.length; i += 2) pcm[i + 1] = 64;
            var buffered = PlaybackController.class.getDeclaredField("bufferedAudio"); buffered.setAccessible(true);
            buffered.set(player, new PcmAudio(pcm, 8000, 1));
            output.value = new AudioOutput.Snapshot(AudioOutput.State.PLAYING, 900, 1000); player.tick(1);
            player.sampleWaveform(levels);
            assertTrue(levels[16] > .5);
            player.seek(200); player.sampleWaveform(levels); assertArrayEquals(new float[32], levels);
            player.seek(900); player.togglePause(); player.sampleWaveform(levels); assertArrayEquals(new float[32], levels);
            player.togglePause(); player.sampleWaveform(levels); assertTrue(levels[16] > .5);
            output.value = new AudioOutput.Snapshot(AudioOutput.State.ERROR, 900, 1000);
            player.sampleWaveform(levels); assertArrayEquals(new float[32], levels);
            player.stop(); player.sampleWaveform(levels); assertArrayEquals(new float[32], levels);
        }
    }

    @Test void shutdownStillClosesAudioWhenProviderCloseFails() {
        var audioClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var output = new AudioOutput() {
            public void load(PcmAudio value) {}
            public void pause(boolean value) {}
            public void seek(long value) {}
            public void volume(float value) {}
            public void stop() {}
            public Snapshot snapshot() { return new Snapshot(State.STOPPED, 0, 0); }
            public void close() { audioClosed.set(true); }
        };
        var player = new PlaybackController(new Provider() { public void close() { throw new IllegalStateException("fixture"); } },
                (uri, max) -> new byte[0], output, Runnable::run);
        assertThrows(IllegalStateException.class, player::close); assertTrue(audioClosed.get());
        assertDoesNotThrow(player::close);
    }
    static class Provider implements MusicProvider {
        CompletableFuture<Lyrics> lyrics = new CompletableFuture<>();
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Fixture", List.of(), 1000)); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/fixture.mp3"), Instant.now().plusSeconds(60)));
        }
        public CompletableFuture<Lyrics> getLyrics(SongId id) { return lyrics; }
        public void close() {}
    }
    @Test void deviceFailureReleasesDecodedBufferAndCancelsLyrics() throws Exception {
        var main = new ConcurrentLinkedQueue<Runnable>(); var output = new SharedPlaybackTest.Output(); var provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            player.add("1"); SharedPlaybackTest.pump(main, () -> player.bufferedBytes() > 0);
            output.value = new AudioOutput.Snapshot(AudioOutput.State.ERROR, 0, 0); player.tick(1);
            assertEquals(PlaybackController.State.ERROR, player.state()); assertEquals(0, player.bufferedBytes());
            assertTrue(provider.lyrics.isCancelled()); assertFalse(player.suppressesGameMusic());
        }
    }
    @Test void thousandsOfSharedQueueChangesDiscardOldMetadataAndPreserveLocalLibrary() throws Exception {
        Provider provider = new Provider() {
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return new CompletableFuture<>(); }
            public CompletableFuture<Lyrics> getLyrics(SongId id) { return CompletableFuture.completedFuture(Lyrics.EMPTY); }
        };
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.add("1"); var local = player.queue();
            var room = UUID.randomUUID(); var epoch = UUID.randomUUID(); var owner = UUID.randomUUID();
            for (long i = 10; i < 2010; i++) {
                player.applyRoom(new RoomSnapshot(room, epoch, "Fixture", i, i, i, owner, List.of(), List.of(Long.toString(i)), 0,
                        PlaybackMode.SEQUENTIAL, RoomSnapshot.State.PLAYING, 0, 0, 1000));
                assertNotNull(player.track(new SongId(i))); assertNull(player.track(new SongId(i - 1)));
                assertNotNull(player.track(new SongId(1)));
            }
            player.applyRoom(null); assertSame(local, player.queue()); assertNull(player.track(new SongId(2009)));
            var field = PlaybackController.class.getDeclaredField("tracks"); field.setAccessible(true);
            assertEquals(1, ((Map<?, ?>) field.get(player)).size());
        }
    }
    @Test void clearAndReloadRejectLateImportAndTrackWithoutErasingQueue() {
        for (boolean reload : List.of(false, true)) {
            var details = new CompletableFuture<Track>() { public boolean cancel(boolean mayInterrupt) { return false; } };
            var playlist = new CompletableFuture<Playlist>() { public boolean cancel(boolean mayInterrupt) { return false; } };
            Provider provider = new Provider() {
                public CompletableFuture<Track> getTrack(SongId id) { return details; }
                public CompletableFuture<Playlist> getPlaylist(SongId id) { return playlist; }
            };
            try (var player = new PlaybackController(provider, (uri, max) -> { fail("Late request downloaded"); return null; }, new SharedPlaybackTest.Output(), Runnable::run)) {
                player.add("1"); player.importPlaylist("2");
                if (reload) player.audioReloaded(); else player.clearTemporaryCache();
                playlist.complete(new Playlist("Fixture", List.of(new SongId(2)), List.of(), 1));
                details.complete(new Track(new SongId(1), "Late", List.of(), 1000));
                assertEquals(List.of(new SongId(1)), player.queue().entries()); assertFalse(player.importing());
                assertEquals(PlaybackController.State.STOPPED, player.state()); assertEquals(0, player.bufferedBytes());
            }
        }
    }
    @Test void clearCacheDoesNotBroadcastRoomControls() {
        try (var player = new PlaybackController(new Provider(), (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.applyRoom(new SharedPlaybackTest().room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            player.roomCommands((action, value) -> fail("Cache cleanup must not affect other listeners"));
            player.clearTemporaryCache(); assertTrue(player.shared()); assertEquals(2, player.queue().entries().size());
        }
    }
    @Test void synchronousResolutionFailureDoesNotStartOrRetainLyricRequest() {
        Provider provider = new Provider() {
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return CompletableFuture.failedFuture(new MusicException(MusicError.UNAVAILABLE)); }
            public CompletableFuture<Lyrics> getLyrics(SongId id) { fail("Lyrics started after playback failed"); return null; }
        };
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.add("1"); assertEquals(PlaybackController.State.ERROR, player.state());
        }
    }
}
