package dev.modernmusic.server;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerPrivateFilesTest {
    @TempDir Path directory;
    @Test void obsoleteQrArtifactsAreRemoved() throws Exception {
        Files.writeString(directory.resolve("login-qr.png"), "fixture");
        Files.writeString(directory.resolve("verification.html"), "fixture");
        new ServerPrivateFiles(directory).clearLoginArtifacts();
        assertFalse(Files.exists(directory.resolve("login-qr.png")));
        assertFalse(Files.exists(directory.resolve("verification.html")));
    }
    @Test void migrationPreservesCredentialsAndUnconsumedUserInput() throws Exception {
        Files.writeString(directory.resolve("remembered-session.bin"), "fixture");
        Files.writeString(directory.resolve("import-cookie.txt"), "fixture");
        new ServerPrivateFiles(directory).clearLoginArtifacts();
        assertTrue(Files.exists(directory.resolve("remembered-session.bin")));
        assertTrue(Files.exists(directory.resolve("import-cookie.txt")));
    }
    @Test void missingDirectoryDoesNotCreateLoginFiles() {
        var absent = directory.resolve("absent");
        new ServerPrivateFiles(absent).clearLoginArtifacts(); assertFalse(Files.exists(absent));
    }
}
