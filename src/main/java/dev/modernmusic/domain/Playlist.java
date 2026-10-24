package dev.modernmusic.domain;

import java.util.List;

public record Playlist(String name, List<SongId> ids, List<Track> tracks, int total) {
    public Playlist { ids = List.copyOf(ids); tracks = List.copyOf(tracks); }
}
