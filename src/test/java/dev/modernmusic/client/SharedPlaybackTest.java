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

class SharedPlaybackTest {
    final UUID id = UUID.randomUUID(), epoch = UUID.randomUUID(), owner = UUID.randomUUID();
    RoomSnapshot room(long revision, long playback, int index, RoomSnapshot.State state, long position, long anchor) {
        return new RoomSnapshot(id, epoch, "Shared", revision, 1, playback, owner, List.of(), List.of("42", "43"), index,
                PlaybackMode.SEQUENTIAL, state, position, anchor, 1_000);
    }
    static final class Output implements AudioOutput {
        Snapshot value = new Snapshot(State.STOPPED, 0, 0);
        int loads, seeks, stops;
        public void load(PcmAudio pcm) { load(pcm, 0, false); }
        public void load(PcmAudio pcm, long position, boolean paused) { loads++; value = new Snapshot(paused ? State.PAUSED : State.PLAYING, position, pcm.durationMs()); }
        public void pause(boolean paused) { value = new Snapshot(paused ? State.PAUSED : State.PLAYING, value.positionMs(), value.durationMs()); }
        public void seek(long position) { seeks++; value = new Snapshot(value.state(), position, value.durationMs()); }
        public void volume(float gain) {}
        public void stop() { stops++; value = new Snapshot(State.STOPPED, 0, 0); }
        public Snapshot snapshot() { return value; }
        public void close() {}
    }
    static final class Provider implements MusicProvider {
        final Map<SongId, CompletableFuture<Track>> requests = new HashMap<>();
        boolean delayed;
        int resolves;
        public CompletableFuture<Track> getTrack(SongId song) {
            if (!delayed) return CompletableFuture.completedFuture(new Track(song, "Track", List.of(), 1_000));
            var request = new CompletableFuture<Track>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            requests.put(song, request); return request;
        }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId song) {
            resolves++; return CompletableFuture.completedFuture(new PlaybackResource(song, URI.create("https://m801.music.126.net/test.mp3"), Instant.now().plusSeconds(60)));
        }
        void complete(long song) { requests.get(new SongId(song)).complete(new Track(new SongId(song), "Track " + song, List.of(), 1_000)); }
        public void close() {}
    }
    static void pump(Queue<Runnable> main, java.util.function.BooleanSupplier until) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!until.getAsBoolean() && System.nanoTime() < end) {
            Runnable task; while ((task = main.poll()) != null) task.run(); Thread.sleep(5);
        }
        assertTrue(until.getAsBoolean());
    }
    @Test void mutedSnapshotsDoNotResolveAudioAndCanResumeTheirLatestTimeline() {
        Provider provider = new Provider(); provider.delayed = true; var output = new Output();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, Runnable::run)) {
            var first = room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000);
            player.applyRoom(first, false);
            var latest = room(2, 2, 1, RoomSnapshot.State.PLAYING, 200, 11_000);
            player.applyRoom(latest, false);
            assertTrue(player.shared()); assertEquals(1, player.queue().index());
            assertEquals(PlaybackController.State.STOPPED, player.state()); assertEquals(0, provider.resolves); assertEquals(0, output.loads);
            player.applyRoom(latest);
            assertEquals(PlaybackController.State.LOADING, player.state()); assertTrue(provider.requests.containsKey(new SongId(43)));
        }
    }
    @Test void pausedRoomCanResumeWhileLocalAudioIsStillLoading() {
        Provider provider = new Provider(); provider.delayed = true;
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), Runnable::run)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            player.applyRoom(room(2, 1, 0, RoomSnapshot.State.PAUSED, 500, 11_000));
            assertEquals(PlaybackController.State.LOADING, player.state());
            assertTrue(player.canTogglePlayback()); assertFalse(player.playbackPlaying());
            var commands = new ArrayList<RoomCommand.Action>(); player.roomCommands((action, value) -> commands.add(action));
            player.togglePause(); assertEquals(List.of(RoomCommand.Action.PAUSE), commands);
            player.applyRoom(room(3, 1, 0, RoomSnapshot.State.PLAYING, 500, 12_000));
            assertTrue(player.playbackPlaying()); assertEquals(0, provider.resolves);
        }
    }
    @Test void sharedQueueLoadsNamesBeforePlaybackAndDoesNotRefetchOnHeartbeats() {
        Provider provider = new Provider(); provider.delayed = true;
        try (var player = new PlaybackController(provider, (uri, max) -> { throw new AssertionError("No audio for metadata"); }, new Output(), Runnable::run)) {
            var snapshot = room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0);
            player.applyRoom(snapshot); provider.complete(42); provider.complete(43);
            assertEquals("Track 43", player.track(new SongId(43)).title());
            assertEquals("Track 42", player.track().title()); assertEquals(0, provider.resolves);
            var original = provider.requests.get(new SongId(43));
            player.applyRoom(snapshot); player.tick(1);
            assertSame(original, provider.requests.get(new SongId(43)));
            player.applyRoom(null); assertNull(player.track(new SongId(43)));
        }
    }
    @Test void metadataCompletionAfterLeavingCannotRepopulateTheQueue() {
        Provider provider = new Provider(); provider.delayed = true;
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), Runnable::run)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            player.applyRoom(null); provider.complete(42);
            if (provider.requests.containsKey(new SongId(43))) provider.complete(43);
            assertNull(player.track(new SongId(42))); assertTrue(player.queue().entries().isEmpty());
        }
    }
    @Test void stoppedHeartbeatsDoNotRepeatedlyResetAudioButPlaybackChangesStillApply() {
        Provider provider = new Provider(); provider.delayed = true; Output output = new Output();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, Runnable::run)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            int stopped = output.stops;
            for (int heartbeat = 0; heartbeat < 60; heartbeat++)
                player.applyRoom(room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            // A membership-only revision also leaves the audio device alone.
            player.applyRoom(room(2, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            assertEquals(stopped, output.stops);
            player.applyRoom(room(3, 2, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            assertEquals(PlaybackController.State.LOADING, player.state());
            player.applyRoom(room(4, 3, 0, RoomSnapshot.State.STOPPED, 0, 0));
            assertEquals(PlaybackController.State.STOPPED, player.state());
            assertTrue(output.stops > stopped);
        }
    }
    @Test void sharedControlsOnlySendCommandsAndLocalLibrarySurvives() {
        Provider provider = new Provider(); provider.delayed = true; Output output = new Output();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, Runnable::run)) {
            player.add("1"); var local = player.queue();
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.STOPPED, 0, 0));
            List<RoomCommand.Action> commands = new ArrayList<>(); player.roomCommands((action, value) -> commands.add(action));
            player.add("44"); player.play(1); player.next(); player.seek(100); player.stop(); player.clear(); player.removeTrack(0); player.cycleMode();
            assertEquals(List.of(RoomCommand.Action.ADD, RoomCommand.Action.PLAY, RoomCommand.Action.NEXT, RoomCommand.Action.SEEK,
                    RoomCommand.Action.STOP, RoomCommand.Action.CLEAR, RoomCommand.Action.REMOVE, RoomCommand.Action.MODE), commands);
            assertEquals(List.of(new SongId(42), new SongId(43)), player.queue().entries());
            player.applyRoom(null);
            assertSame(local, player.queue()); assertEquals(List.of(new SongId(1)), player.queue().entries()); assertEquals(PlaybackController.State.STOPPED, player.state());
        }
    }
    @Test void staleDownloadCannotReplaceSharedTrackOrRestartAfterLeave() {
        Provider provider = new Provider(); provider.delayed = true;
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new Output(), Runnable::run)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            player.applyRoom(room(2, 2, 1, RoomSnapshot.State.PLAYING, 0, 12_000));
            provider.complete(42); assertEquals(0, provider.resolves);
            player.applyRoom(null); provider.complete(43); assertEquals(0, provider.resolves); assertNull(player.track()); assertFalse(player.shared());
        }
    }
    @Test void lateBufferStartsPausedThenAlignsToLatestSeekAndScheduledStart() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); Provider provider = new Provider(); Output output = new Output();
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            player.applyRoom(room(2, 1, 0, RoomSnapshot.State.PLAYING, 300, 12_000));
            pump(main, () -> output.loads == 1); assertEquals(AudioOutput.State.PAUSED, output.snapshot().state());
            player.synchronizeRoom(11_000, 1_000); assertEquals(300, output.snapshot().positionMs()); assertEquals(AudioOutput.State.PAUSED, output.snapshot().state());
            player.synchronizeRoom(12_200, 2_000); assertEquals(500, output.snapshot().positionMs()); assertEquals(AudioOutput.State.PLAYING, output.snapshot().state());
            assertEquals(1, output.loads);
        }
    }
    @Test void pauseAndQueueEditsKeepBufferAndServerOwnsNaturalAdvance() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); Output output = new Output(); Provider provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000)); pump(main, () -> output.loads == 1);
            player.applyRoom(room(2, 1, 0, RoomSnapshot.State.PAUSED, 500, 11_000));
            player.synchronizeRoom(20_000, 1_000); assertEquals(500, output.snapshot().positionMs()); assertEquals(AudioOutput.State.PAUSED, output.snapshot().state());
            output.value = new AudioOutput.Snapshot(AudioOutput.State.FINISHED, 1_000, 1_000); player.tick(1);
            assertEquals(0, player.queue().index()); assertEquals(1, provider.resolves);
            player.applyRoom(room(3, 2, 1, RoomSnapshot.State.PLAYING, 0, 22_000)); pump(main, () -> output.loads == 2);
            assertEquals(1, player.queue().index()); assertEquals(2, provider.resolves);
        }
    }
    @Test void repeatingSharedSongReusesBufferButStillWaitsForTheNewTimeline() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); Output output = new Output(); Provider provider = new Provider();
        var downloads = new java.util.concurrent.atomic.AtomicInteger();
        try (var player = new PlaybackController(provider, (uri, max) -> {
            downloads.incrementAndGet(); return Mp3DecoderTest.silentMp3();
        }, output, main::add)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000));
            pump(main, () -> output.loads == 1);
            player.synchronizeRoom(10_500, 1_000);
            player.applyRoom(room(2, 2, 0, RoomSnapshot.State.PLAYING, 0, 12_000));
            assertEquals(2, output.loads); assertEquals(1, provider.resolves); assertEquals(1, downloads.get());
            assertEquals(AudioOutput.State.PAUSED, output.snapshot().state());
            player.synchronizeRoom(11_500, 2_000);
            assertEquals(0, output.snapshot().positionMs()); assertEquals(AudioOutput.State.PAUSED, output.snapshot().state());
            player.synchronizeRoom(12_100, 3_000);
            assertEquals(100, output.snapshot().positionMs()); assertEquals(AudioOutput.State.PLAYING, output.snapshot().state());
            // Credential changes invalidate the retained PCM and must resolve/download again.
            player.playbackSourceChanged();
            player.applyRoom(room(2, 2, 0, RoomSnapshot.State.PLAYING, 0, 12_000));
            pump(main, () -> output.loads == 3);
            assertEquals(2, provider.resolves); assertEquals(2, downloads.get());
        }
    }
    @Test void credentialSourceChangeReloadsRoomWithoutIssuingControlCommands() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); Output output = new Output(); Provider provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            var snapshot = room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000);
            player.roomCommands((action, value) -> fail("Source change must not control the room"));
            player.applyRoom(snapshot); pump(main, () -> output.loads == 1);
            player.playbackSourceChanged(); assertEquals(AudioOutput.State.STOPPED, output.snapshot().state());
            player.applyRoom(snapshot); pump(main, () -> output.loads == 2);
            player.synchronizeRoom(10_500, 1_000); assertEquals(500, output.snapshot().positionMs());
            assertEquals(2, provider.resolves);
        }
    }
    @Test void driftCorrectionIgnoresSmallErrorsAndLimitsMediumSeeks() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>(); Output output = new Output();
        try (var player = new PlaybackController(new Provider(), (uri, max) -> Mp3DecoderTest.silentMp3(), output, main::add)) {
            player.applyRoom(room(1, 1, 0, RoomSnapshot.State.PLAYING, 0, 10_000)); pump(main, () -> output.loads == 1);
            player.synchronizeRoom(10_000, 1_000); int aligned = output.seeks;
            player.tick(1); assertEquals("modernmusic.room.playing", player.message());
            player.synchronizeRoom(10_100, 1_100); assertEquals(aligned, output.seeks);
            player.synchronizeRoom(10_250, 1_200); assertEquals(aligned, output.seeks);
            player.synchronizeRoom(10_250, 3_100); assertEquals(aligned + 1, output.seeks);
            player.applyRoom(null); assertEquals("modernmusic.status.idle", player.message());
            player.add("42"); pump(main, () -> output.loads == 2);
            player.tick(1); assertEquals("modernmusic.status.playing", player.message());
        }
    }
}
