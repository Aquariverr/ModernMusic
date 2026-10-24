package dev.modernmusic.room;

import java.util.*;

public record RoomImportResult(UUID request, int imported, int total, String message, Integer processed) {
    /** Older peers report only the appended prefix; the optional field adds duplicate-aware receipts. */
    public RoomImportResult(UUID request, int imported, int total, String message) {
        this(request, imported, total, message, null);
    }
    public RoomImportResult {
        Objects.requireNonNull(request);
        if (imported < 0 || imported > 400 || total < imported || message == null || message.length() > 80)
            throw new IllegalArgumentException("Invalid room import result");
        if (processed != null && (processed < imported || processed > 400 || processed > total))
            throw new IllegalArgumentException("Invalid processed prefix");
    }
    public int processedCount() { return processed == null ? imported : processed; }
    public int skipped() { return processedCount() - imported; }
}
