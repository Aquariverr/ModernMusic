package dev.modernmusic.audio;

import dev.modernmusic.provider.MusicException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AudioLibraryTest {
    @TempDir Path directory;

    private static byte[] wave(int milliseconds) {
        return AudioFilesTest.wave(1, 1, 8000, 16, new byte[milliseconds * 16]);
    }

    @Test void knownPlaybackReadsNeverRediscoverTheDirectory() throws Exception {
        byte[] expected = wave(100);
        Files.write(directory.resolve("selected.wav"), expected);
        Files.write(directory.resolve("unrelated.wav"), wave(200));
        var scans = new AtomicInteger();
        var library = new AudioLibrary(directory, path -> {
            assertEquals(1, scans.incrementAndGet(), "known songs must not enumerate unrelated files");
            return Files.newDirectoryStream(path);
        });
        var id = AudioLibrary.idForFileName("selected.wav");
        assertEquals(100, library.getTrack(id).orElseThrow().durationMs());
        Files.write(directory.resolve("unrelated.wav"), new byte[]{1, 2, 3});
        for (int i = 0; i < 3; i++) {
            assertEquals(100, library.getTrack(id).orElseThrow().durationMs());
            var audio = library.readAudio(id);
            assertEquals("selected.wav", audio.fileName());
            assertArrayEquals(expected, audio.bytes());
        }
        assertEquals("selected.wav", library.fileName(id));
        assertArrayEquals(expected, library.read(id));
        assertEquals(1, scans.get());
    }

    @Test void targetedLookupRevalidatesChangesDeletionAndInvalidReplacement() throws Exception {
        Path target = directory.resolve("selected.wav");
        Files.write(target, wave(100));
        var library = new AudioLibrary(directory);
        var id = library.list().getFirst().id();
        Files.write(target, wave(200));
        assertEquals(200, library.getTrack(id).orElseThrow().durationMs());
        assertEquals(200, AudioFiles.decode(library.readAudio(id).bytes(), "selected.wav").durationMs());
        Files.write(target, new byte[]{1, 2, 3});
        assertTrue(library.getTrack(id).isEmpty());
        assertThrows(MusicException.class, () -> library.readAudio(id));
        Files.delete(target);
        assertTrue(library.getTrack(id).isEmpty());
        assertThrows(MusicException.class, () -> library.fileName(id));
        Files.write(target, wave(300));
        assertEquals(300, library.getTrack(id).orElseThrow().durationMs());
    }

    @Test void replacementIdentityInvalidatesCachedMetadataEvenWithSameSizeAndTime() throws Exception {
        Path target = directory.resolve("selected.wav"), replacement = directory.resolve("replacement.wav");
        Files.write(target, wave(100));
        var library = new AudioLibrary(directory);
        var id = library.list().getFirst().id();
        var before = Files.readAttributes(target, BasicFileAttributes.class);
        Files.write(replacement, AudioFilesTest.wave(1, 1, 16000, 16, new byte[1600]));
        Files.setLastModifiedTime(replacement, before.lastModifiedTime());
        Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        var after = Files.readAttributes(target, BasicFileAttributes.class);
        assumeTrue(before.fileKey() != null && !before.fileKey().equals(after.fileKey()), "filesystem must expose replacement identities");
        assertEquals(before.size(), after.size());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        assertEquals(50, library.getTrack(id).orElseThrow().durationMs());
    }

    @Test void replacedSymlinkCannotUsePreviouslyCachedSafeEntry() throws Exception {
        Path libraryPath = Files.createDirectory(directory.resolve("library"));
        Path target = libraryPath.resolve("selected.wav"), outside = directory.resolve("outside.wav");
        Files.write(target, wave(100)); Files.write(outside, wave(200));
        var library = new AudioLibrary(libraryPath);
        var id = library.list().getFirst().id();
        Files.delete(target);
        try { Files.createSymbolicLink(target, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        }
        assertTrue(library.getTrack(id).isEmpty());
        assertThrows(MusicException.class, () -> library.readAudio(id));
    }

    @Test void unknownIdsAndExplicitBrowsingStillDiscoverNewFiles() throws Exception {
        var scans = new AtomicInteger();
        var library = new AudioLibrary(directory, path -> {
            scans.incrementAndGet(); return Files.newDirectoryStream(path);
        });
        assertTrue(library.list().isEmpty());
        Files.write(directory.resolve("new.wav"), wave(100));
        assertTrue(library.getTrack(AudioLibrary.idForFileName("new.wav")).isPresent());
        assertEquals(2, scans.get());
        Files.write(directory.resolve("another.wav"), wave(200));
        assertEquals(2, library.list().size());
        assertEquals(3, scans.get());
    }
}
