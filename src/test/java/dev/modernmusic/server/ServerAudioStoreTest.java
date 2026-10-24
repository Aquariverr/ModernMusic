package dev.modernmusic.server;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerAudioStoreTest {
    @TempDir Path directory;
    @Test void storesValidatedBytesAndRestoresOriginalNamesAndIds() throws Exception {
        var store = new ServerAudioStore(directory); byte[] bytes = wav();
        var first = store.add("演示.wav", bytes); var second = store.add("演示.wav", bytes);
        assertTrue(first.id().isServerAudio()); assertNotEquals(first.id(), second.id()); assertEquals("演示.wav", first.title());
        assertArrayEquals(bytes, store.read(first.id())); assertEquals(2, store.catalog().size());
        var restored = new ServerAudioStore(directory); assertFalse(restored.failed());
        assertEquals(store.catalog(), restored.catalog()); assertArrayEquals(bytes, restored.read(first.id()));
        try (var files = Files.list(directory)) { assertEquals(3, files.count()); }
    }
    @Test void rejectsInvalidContentPathsAndPrivateIdentifiers() {
        var store = new ServerAudioStore(directory);
        assertThrows(MusicException.class, () -> store.add("../escape.wav", wav()));
        assertThrows(MusicException.class, () -> store.add("fake.mp3", wav()));
        assertThrows(MusicException.class, () -> store.add("bad.wav", new byte[100]));
        var local = new SongId("local_" + "a".repeat(32), MusicPlatform.LOCAL);
        assertFalse(store.contains(local)); assertThrows(MusicException.class, () -> store.read(local));
        assertTrue(store.catalog().isEmpty());
    }
    @Test void authorizationRevocationCannotPublishOrLeaveFiles() throws Exception {
        var store = new ServerAudioStore(directory);
        assertEquals(MusicError.SHARED_FORBIDDEN, assertThrows(MusicException.class, () -> store.add("a.wav", wav(), () -> false)).error());
        var checks = new AtomicInteger();
        assertThrows(MusicException.class, () -> store.add("a.wav", wav(), () -> checks.incrementAndGet() < 4));
        assertTrue(store.catalog().isEmpty());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        checks.set(0);
        assertThrows(MusicException.class, () -> store.add("a.wav", wav(), () -> checks.incrementAndGet() < 5));
        assertTrue(new ServerAudioStore(directory).catalog().isEmpty());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void corruptManifestIsPreservedAndCannotBeOverwritten() throws Exception {
        Files.writeString(directory.resolve("catalog.json"), "broken");
        var store = new ServerAudioStore(directory); assertTrue(store.failed());
        assertThrows(MusicException.class, () -> store.add("a.wav", wav()));
        assertEquals("broken", Files.readString(directory.resolve("catalog.json")));
    }
    @Test void changedOrMissingStoredFileCannotReturnUnexpectedBytes() throws Exception {
        var store = new ServerAudioStore(directory); var track = store.add("a.wav", wav());
        Path file = directory.resolve(track.id().key() + ".wav"); Files.write(file, new byte[] {1});
        assertThrows(MusicException.class, () -> store.read(track.id()));
        assertTrue(new ServerAudioStore(directory).catalog().isEmpty());
        Files.delete(file); assertThrows(MusicException.class, () -> store.read(track.id()));
    }
    private static byte[] wav() {
        int samples = 800;
        var b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R','I','F','F'}).putInt(b.capacity() - 8).put(new byte[] {'W','A','V','E'});
        b.put(new byte[] {'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        b.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        b.put(new byte[] {'d','a','t','a'}).putInt(samples * 2);
        return b.array();
    }
}
