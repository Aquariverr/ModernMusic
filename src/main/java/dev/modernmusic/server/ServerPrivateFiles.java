package dev.modernmusic.server;

import dev.modernmusic.auth.AuthException;
import java.nio.file.*;
import java.util.List;

/** Removes obsolete 0.8.0 QR files. New administrator views exist only in memory. */
final class ServerPrivateFiles {
    private final Path directory;
    ServerPrivateFiles(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    void clearLoginArtifacts() {
        try {
            for (String name : List.of("login-qr.png", "verification.html")) {
                Path file = directory.resolve(name);
                for (Path item = file; item != null; item = item.getParent())
                    if (Files.isSymbolicLink(item)) throw new AuthException("storage_error");
                Files.deleteIfExists(file);
            }
        } catch (Exception failure) { throw new AuthException("storage_error"); }
    }
}
