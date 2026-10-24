package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAudioProviderTest {
    @TempDir Path folder;
    static class Remote implements LocalAudioProvider.Remote {
        boolean online;
        List<Track> catalog = List.of();
        CompletableFuture<byte[]> bytes = new CompletableFuture<>();
        public boolean available() { return online; }
        public boolean canUpload() { return online; }
        public CompletableFuture<List<Track>> refresh() { return CompletableFuture.completedFuture(catalog); }
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(catalog.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow()); }
        public CompletableFuture<byte[]> download(SongId id) { return bytes; }
        public CompletableFuture<Track> upload(String name, byte[] bytes) { throw new AssertionError(); }
    }
    static Track serverTrack() { return new Track(new SongId("server_" + "a".repeat(32), MusicPlatform.LOCAL), "测试.wav", List.of(), 100); }
    static byte[] wav() {
        var b = ByteBuffer.allocate(1644).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(1636).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(1600);
        return b.array();
    }
    static class Output implements AudioOutput {
        PcmAudio audio;
        public void load(PcmAudio audio) { this.audio = audio; }
        public void pause(boolean value) {}
        public void seek(long value) {}
        public void volume(float value) {}
        public void stop() { audio = null; }
        public Snapshot snapshot() { return new Snapshot(audio == null ? State.STOPPED : State.PLAYING, 0, audio == null ? 0 : audio.durationMs()); }
        public void close() {}
    }
    @Test void resolvesNamesWithoutNetworkAndRejectsAmbiguityAcrossSources() throws Exception {
        Files.write(folder.resolve("测试.wav"), wav());
        var remote = new Remote();
        try (var provider = new LocalAudioProvider(folder, Runnable::run, remote)) {
            var track = provider.getReference("测试").get(3, TimeUnit.SECONDS);
            assertTrue(track.id().isClientLocal());
            assertEquals(track.id(), provider.getReference("测试.wav").get(3, TimeUnit.SECONDS).id());
            remote.online = true; remote.catalog = List.of(serverTrack());
            var failure = assertThrows(ExecutionException.class, () -> provider.getReference("测试").get(3, TimeUnit.SECONDS));
            assertEquals(MusicError.AUDIO_AMBIGUOUS, ((MusicException) failure.getCause()).error());
            assertEquals(serverTrack().id(), provider.getServerReference("测试").get(3, TimeUnit.SECONDS).id());
        }
    }
    @Test void localFilePlaysThroughControllerWithoutHttpAndSurvivesPlatformSwitch() throws Exception {
        Files.write(folder.resolve("测试.wav"), wav());
        Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        var local = new LocalAudioProvider(folder, main::add, new Remote());
        var multi = new MultiPlatformProvider(local, local, local, local);
        var output = new Output();
        try (var player = new PlaybackController(multi, (uri, size) -> { throw new AssertionError("Local audio reached HTTP"); }, output, main::add)) {
            player.switchPlatform(); player.switchPlatform(); player.switchPlatform();
            assertEquals(MusicPlatform.LOCAL, player.platform());
            assertTrue(player.addPersonal("测试"));
            drainUntil(main, () -> !player.importing());
            assertEquals(1, player.personalQueue().entries().size());
            player.playPersonal(0);
            drainUntil(main, () -> output.audio != null);
            assertEquals(100, output.audio.durationMs());
            player.switchPlatform();
            assertNotNull(output.audio);
            player.seek(50); player.stop(); assertNull(output.audio);
        }
    }
    @Test void cancelledServerDownloadCannotStartPlaybackAfterStop() throws Exception {
        Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        var remote = new Remote(); remote.online = true; remote.catalog = List.of(serverTrack());
        var output = new Output();
        try (var local = new LocalAudioProvider(folder, main::add, remote);
             var player = new PlaybackController(new MultiPlatformProvider(local, local, local, local),
                     (uri, max) -> { throw new AssertionError(); }, output, main::add)) {
            player.addSearchTracks(player.searchDestinations().getFirst(), remote.catalog).join();
            player.playPersonal(0);
            while (!main.isEmpty()) main.remove().run();
            player.stop();
            remote.bytes.complete(wav());
            while (!main.isEmpty()) main.remove().run();
            assertNull(output.audio);
            assertTrue(remote.bytes.isCancelled());
        }
    }
    static void drainUntil(Queue<Runnable> main, java.util.function.BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Runnable action; while ((action = main.poll()) != null) action.run();
            Thread.sleep(5);
        }
        assertTrue(done.getAsBoolean());
    }
}
