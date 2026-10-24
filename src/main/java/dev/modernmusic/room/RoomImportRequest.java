package dev.modernmusic.room;

import java.util.*;

/** The client's provider resolves the playlist; only normalized song IDs cross the game connection. */
public record RoomImportRequest(UUID request, UUID room, UUID epoch, List<String> songs, int total) {
    public RoomImportRequest {
        Objects.requireNonNull(request); Objects.requireNonNull(room); Objects.requireNonNull(epoch);
        songs = List.copyOf(songs);
        if (songs.isEmpty() || songs.size() > 400 || total < songs.size() || songs.stream().anyMatch(id -> id == null || id.length() > 64))
            throw new IllegalArgumentException("Invalid room import");
        songs.forEach(dev.modernmusic.domain.SongId::stored);
    }
}
