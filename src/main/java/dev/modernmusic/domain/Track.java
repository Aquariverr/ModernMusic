package dev.modernmusic.domain;

import java.util.List;
import java.util.Objects;

public record Track(SongId id, String title, List<String> artists, long durationMs, java.net.URI artwork) {
    public Track(SongId id, String title, List<String> artists, long durationMs) {
        this(id, title, artists, durationMs, null);
    }
    public Track {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        artists = List.copyOf(artists);
        if (durationMs <= 0) throw new IllegalArgumentException("Invalid duration");
    }

    public String artistText() {
        return String.join(" / ", artists);
    }
}
