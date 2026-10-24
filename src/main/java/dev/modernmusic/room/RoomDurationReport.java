package dev.modernmusic.room;

import dev.modernmusic.domain.SongId;
import java.util.Objects;
import java.util.UUID;

/** Decoded audio metadata tied to one playback, never a playback-control command. */
public record RoomDurationReport(UUID room, UUID epoch, long playbackRevision, String song, long durationMs) {
    public static final long MAX_DURATION_MS = 3_600_000;

    public RoomDurationReport {
        Objects.requireNonNull(room); Objects.requireNonNull(epoch); Objects.requireNonNull(song);
        if (playbackRevision < 0 || song.length() > 64 || SongId.stored(song).isClientLocal()
                || durationMs <= 0 || durationMs > MAX_DURATION_MS)
            throw new IllegalArgumentException("Invalid room duration report");
    }
}
