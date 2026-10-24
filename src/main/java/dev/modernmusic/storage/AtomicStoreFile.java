package dev.modernmusic.storage;

import java.io.IOException;
import java.nio.file.*;

/** File replacement shared by queue stores; data formats and size limits stay with each store. */
public final class AtomicStoreFile {
    private final Path file;
    private final String prefix, pathError;
    private boolean needsBackup;

    public AtomicStoreFile(Path file, String prefix, String pathError) {
        this.file = file.toAbsolutePath().normalize(); this.prefix = prefix; this.pathError = pathError;
    }

    public void checkPath() {
        for (Path path = file; path != null; path = path.getParent())
            if (Files.isSymbolicLink(path)) throw new IllegalArgumentException(pathError);
    }

    public void backupOnNextWrite() { needsBackup = true; }

    public void write(byte[] bytes) throws IOException {
        checkPath(); Files.createDirectories(file.getParent());
        if (needsBackup && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            // Preserve an unreadable original before an explicit queue edit replaces it.
            Path backup = Files.createTempFile(file.getParent(), prefix + "recovery-", ".json");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING); needsBackup = false;
        }
        Path temporary = Files.createTempFile(file.getParent(), prefix, ".tmp");
        try {
            Files.write(temporary, bytes); checkPath();
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
