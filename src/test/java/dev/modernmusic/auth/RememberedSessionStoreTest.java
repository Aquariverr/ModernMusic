package dev.modernmusic.auth;

import com.sun.jna.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class RememberedSessionStoreTest {
    @TempDir Path directory;
    private final AccountSession session = new AccountSession("42", "Fixture", SessionCookie.parse("MUSIC_U=fixture-secret-cookie; NMTID=fixture-session"),
            Instant.parse("2026-10-07T00:00:00Z"), URI.create("https://p1.music.126.net/test.jpg"));
    @Test void roundTripsIdentityAvatarAndCookieWithoutPassword() throws Exception {
        var store = new RememberedSessionStore(directory); store.save(session);
        var restored = new RememberedSessionStore(directory).load();
        assertEquals(session.userId(), restored.userId()); assertEquals(session.nickname(), restored.nickname());
        assertEquals(session.avatar(), restored.avatar()); assertEquals(session.cookie().header(), restored.cookie().header());
        if (Platform.isWindows()) assertFalse(new String(Files.readAllBytes(directory.resolve("remembered-session.bin")), StandardCharsets.ISO_8859_1).contains("fixture-secret-cookie"));
        else assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("remembered-session.bin")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        store.clear(); assertFalse(store.exists());
    }
    @Test void rejectsOversizeTruncatedAndCorruptRecords() throws Exception {
        var store = new RememberedSessionStore(directory); Path file = directory.resolve("remembered-session.bin");
        store.save(session); byte[] bytes = Files.readAllBytes(file);
        bytes[0] ^= 1; Files.write(file, bytes); assertThrows(AuthException.class, store::load);
        Files.write(file, new byte[65537]); assertThrows(AuthException.class, store::load);
        Files.write(file, new byte[3]); assertThrows(AuthException.class, store::load);
        if (Platform.isWindows()) {
            store.save(session); bytes = Files.readAllBytes(file); bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
            assertThrows(AuthException.class, store::load);
        }
    }
    @Test void deletesLegacyVaultAndInterruptedSavePreservesExistingRecord() throws Exception {
        var store = new RememberedSessionStore(directory);
        Files.writeString(directory.resolve("client-session.bin"), "legacy"); assertTrue(store.hasLegacy());
        store.save(session); assertFalse(store.hasLegacy());
        Thread.currentThread().interrupt();
        try { assertThrows(AuthException.class, () -> store.save(session)); } finally { Thread.interrupted(); }
        assertEquals(session.cookie().header(), store.load().cookie().header());
        store.clear(); assertFalse(store.exists());
    }
}
