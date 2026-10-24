package dev.modernmusic.client;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.client.audio.Mp3DecoderTest;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class LocalAudioDropTest {
    @TempDir Path folder;
    private Path source(String name, byte[] bytes) throws Exception {
        Path source = Files.createDirectories(folder.resolve("source")).resolve(name);
        return Files.write(source, bytes);
    }
    private Path library() { return folder.resolve("library"); }

    @Test void mixedDropCopiesAudioWithoutOverwritingOrMovingTheSourceAndContinuesAfterFailures() throws Exception {
        byte[] wav = LocalAudioProviderTest.wav();
        Path original = source("演示.wav", wav), mp3 = source("第二首.mp3", Mp3DecoderTest.silentMp3());
        Path invalid = source("invalid.wav", new byte[]{1, 2, 3});
        Path text = source("notes.txt", new byte[]{1});
        Path directory = Files.createDirectory(folder.resolve("folder.wav"));
        Files.createDirectory(library());
        byte[] previous = wav.clone(); previous[44] = 42;
        Files.write(library().resolve("演示.wav"), previous);
        try (var provider = new LocalAudioProvider(library(), Runnable::run, new DropRemote())) {
            var result = provider.importDropped(List.of(original, invalid, mp3, directory, text), false).get(4, TimeUnit.SECONDS);
            assertEquals(5, result.total()); assertEquals(2, result.tracks().size()); assertEquals(3, result.failures().size());
            assertEquals(List.of("演示 (1).wav", "第二首.mp3"), result.tracks().stream().map(Track::title).toList());
            assertArrayEquals(previous, Files.readAllBytes(library().resolve("演示.wav")));
            assertArrayEquals(wav, Files.readAllBytes(library().resolve("演示 (1).wav")));
            assertArrayEquals(wav, Files.readAllBytes(original)); assertTrue(Files.exists(mp3));
            assertEquals(List.of("invalid.wav", "folder.wav", "notes.txt"), result.failures().stream().map(LocalAudioProvider.DropFailure::fileName).toList());
            assertEquals(3, provider.browseLocal().get(4, TimeUnit.SECONDS).size());
            try (var entries = Files.list(library())) { assertEquals(3, entries.count()); }
        }
    }

    @Test void droppingAnExistingLibraryFileReturnsItsTrackWithoutDuplicatingIt() throws Exception {
        Files.createDirectory(library());
        Path existing = Files.write(library().resolve("existing.wav"), LocalAudioProviderTest.wav());
        try (var provider = new LocalAudioProvider(library(), Runnable::run, new DropRemote())) {
            var before = provider.browseLocal().get(4, TimeUnit.SECONDS).getFirst();
            var result = provider.importDropped(List.of(existing, existing), false).get(4, TimeUnit.SECONDS);
            assertEquals(List.of(before, before), result.tracks()); assertTrue(result.failures().isEmpty());
            try (var entries = Files.list(library())) { assertEquals(1, entries.count()); }
        }
    }

    @Test void validatesBatchAndLibraryLimitsBeforeAddingFiles() throws Exception {
        Path valid = source("new.wav", LocalAudioProviderTest.wav());
        try (var provider = new LocalAudioProvider(library(), Runnable::run, new DropRemote())) {
            var tooMany = java.util.Collections.nCopies(LocalAudioProvider.MAX_DROPPED_FILES + 1, valid);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> provider.importDropped(tooMany, false).get(4, TimeUnit.SECONDS));
            assertEquals(MusicError.RESOURCE_TOO_LARGE, ((MusicException) failure.getCause()).error());
            assertFalse(Files.exists(library()));
            assertThrows(ExecutionException.class, () -> provider.importDropped(List.of(), false).get(4, TimeUnit.SECONDS));
            Files.createDirectory(library());
            for (int i = 0; i < AudioFiles.MAX_LIBRARY_FILES; i++) Files.createFile(library().resolve("existing-" + i + ".txt"));
            var full = provider.importDropped(List.of(valid), false).get(4, TimeUnit.SECONDS);
            assertTrue(full.tracks().isEmpty()); assertEquals(MusicError.RESOURCE_TOO_LARGE, full.failures().getFirst().error());
            assertFalse(Files.exists(library().resolve("new.wav")));
        }
    }

    @Test void oversizedFileAndSymlinkAreRejectedWithoutCopying() throws Exception {
        Path oversized = source("large.wav", new byte[0]);
        try (var sparse = new java.io.RandomAccessFile(oversized.toFile(), "rw")) { sparse.setLength((long) AudioFiles.MAX_AUDIO_BYTES + 1); }
        try (var provider = new LocalAudioProvider(library(), Runnable::run, new DropRemote())) {
            var result = provider.importDropped(List.of(oversized), false).get(4, TimeUnit.SECONDS);
            assertEquals(MusicError.RESOURCE_TOO_LARGE, result.failures().getFirst().error());
            assertFalse(Files.exists(library()));
            Path link = folder.resolve("linked.wav"), target = source("valid.wav", LocalAudioProviderTest.wav());
            try { Files.createSymbolicLink(link, target); }
            catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) { return; }
            var linked = provider.importDropped(List.of(link), false).get(4, TimeUnit.SECONDS);
            assertTrue(linked.tracks().isEmpty()); assertEquals(MusicError.RESOURCE_UNAVAILABLE, linked.failures().getFirst().error());
            assertFalse(Files.exists(library()));
        }
    }

    @Test void serverDropUploadsSequentiallyAndNeverCopiesIntoTheLocalLibrary() throws Exception {
        Path first = source("first.wav", LocalAudioProviderTest.wav()), second = source("second.wav", new byte[]{1});
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            var result = provider.importDropped(List.of(first, second), true);
            drainUntil(main, () -> remote.uploads.size() == 1);
            assertFalse(result.isDone()); assertFalse(Files.exists(library()));
            Files.write(second, LocalAudioProviderTest.wav());
            remote.complete(0);
            drainUntil(main, () -> remote.uploads.size() == 2);
            remote.complete(1);
            drainUntil(main, result::isDone);
            assertEquals(2, result.join().tracks().size()); assertTrue(result.join().failures().isEmpty());
            assertEquals(List.of("first.wav", "second.wav"), remote.names);
            assertFalse(Files.exists(library()));
        }
    }

    @Test void deniedPermissionPreventsReadingOrUploadingAnyFile() throws Exception {
        var remote = new DropRemote(); remote.allowed = false;
        Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            var result = provider.importDropped(List.of(folder.resolve("nonexistent.wav")), true);
            drainUntil(main, result::isDone);
            assertEquals(MusicError.SHARED_FORBIDDEN, result.join().failures().getFirst().error());
            assertTrue(remote.uploads.isEmpty()); assertFalse(Files.exists(library()));
        }
    }

    @Test void connectionAtDropIsCapturedBeforeTheFirstMainCallback() throws Exception {
        Path first = source("first.wav", LocalAudioProviderTest.wav());
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            var result = provider.importDropped(List.of(first), true);
            remote.connection = UUID.randomUUID();
            drainUntil(main, result::isDone);
            assertEquals(MusicError.RESOURCE_UNAVAILABLE, result.join().failures().getFirst().error());
            assertTrue(remote.uploads.isEmpty());
        }
    }

    @Test void connectionChangesAfterReadingCannotUploadToTheReplacementServer() throws Exception {
        Path first = source("first.wav", LocalAudioProviderTest.wav()), second = source("second.wav", LocalAudioProviderTest.wav());
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            var result = provider.importDropped(List.of(first, second), true);
            main.remove().run(); // Permission was checked; the worker now validates the source.
            waitUntil(() -> !main.isEmpty()); // The upload callback is queued but has not run.
            remote.connection = UUID.randomUUID();
            drainUntil(main, result::isDone);
            assertEquals(2, result.join().failures().size());
            assertTrue(result.join().failures().stream().allMatch(failure -> failure.error() == MusicError.RESOURCE_UNAVAILABLE));
            assertTrue(remote.uploads.isEmpty()); assertFalse(Files.exists(library()));
        }
    }

    @Test void cancellingAnUploadBatchCancelsItsTransferAndNeverStartsTheNextFile() throws Exception {
        Path first = source("first.wav", LocalAudioProviderTest.wav()), second = source("second.wav", LocalAudioProviderTest.wav());
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            var result = provider.importDropped(List.of(first, second), true);
            drainUntil(main, () -> remote.uploads.size() == 1);
            result.cancel(true);
            assertTrue(remote.uploads.getFirst().isCancelled());
            while (!main.isEmpty()) main.remove().run();
            assertEquals(1, remote.uploads.size()); assertTrue(result.isCancelled());
        }
    }

    @Test void existingLibraryUploadKeepsItsOriginalConnectionUntilTheReadCompletes() throws Exception {
        Files.createDirectory(library());
        Files.write(library().resolve("selected.wav"), LocalAudioProviderTest.wav());
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            Track selected = provider.browseLocal().get(4, TimeUnit.SECONDS).getFirst();
            var result = provider.upload(selected);
            waitUntil(() -> !main.isEmpty());
            remote.connection = UUID.randomUUID();
            drainUntil(main, result::isDone);
            var failure = assertThrows(ExecutionException.class, () -> result.get(4, TimeUnit.SECONDS));
            assertEquals(MusicError.RESOURCE_UNAVAILABLE, ((MusicException) failure.getCause()).error());
            assertTrue(remote.uploads.isEmpty());
        }
    }

    @Test void existingLibraryUploadRechecksPermissionAfterReading() throws Exception {
        Files.createDirectory(library());
        Files.write(library().resolve("selected.wav"), LocalAudioProviderTest.wav());
        var remote = new DropRemote(); Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        try (var provider = new LocalAudioProvider(library(), main::add, remote)) {
            Track selected = provider.browseLocal().get(4, TimeUnit.SECONDS).getFirst();
            var result = provider.upload(selected);
            waitUntil(() -> !main.isEmpty());
            remote.allowed = false;
            drainUntil(main, result::isDone);
            var failure = assertThrows(ExecutionException.class, () -> result.get(4, TimeUnit.SECONDS));
            assertEquals(MusicError.SHARED_FORBIDDEN, ((MusicException) failure.getCause()).error());
            assertTrue(remote.uploads.isEmpty());
        }
    }

    private static void drainUntil(Queue<Runnable> main, BooleanSupplier done) throws Exception {
        waitUntil(() -> { Runnable callback; while ((callback = main.poll()) != null) callback.run(); return done.getAsBoolean(); });
    }
    private static void waitUntil(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(done.getAsBoolean());
    }
    private static final class DropRemote implements LocalAudioProvider.Remote {
        boolean allowed = true;
        Object connection = UUID.randomUUID();
        final List<String> names = new ArrayList<>();
        final List<CompletableFuture<Track>> uploads = new ArrayList<>();
        public boolean available() { return true; }
        public boolean canUpload() { return allowed; }
        public Object connectionToken() { return connection; }
        public CompletableFuture<List<Track>> refresh() { return CompletableFuture.completedFuture(List.of()); }
        public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError(); }
        public CompletableFuture<byte[]> download(SongId id) { throw new AssertionError(); }
        public CompletableFuture<Track> upload(String filename, byte[] bytes) {
            assertEquals(100, AudioFiles.inspect(bytes, filename).durationMs());
            names.add(filename); var future = new CompletableFuture<Track>(); uploads.add(future); return future;
        }
        void complete(int index) {
            uploads.get(index).complete(new Track(new SongId("server_" + UUID.randomUUID().toString().replace("-", ""), MusicPlatform.LOCAL), names.get(index), List.of(), 100));
        }
    }
}
