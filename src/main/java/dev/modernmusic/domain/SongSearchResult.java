package dev.modernmusic.domain;

import java.util.List;

/** One bounded page of song metadata; never contains playback URLs or credentials. */
public record SongSearchResult(List<Track> tracks, int total, boolean hasMore) {
    public static final int MAX_QUERY_LENGTH = 128, MAX_PAGE_SIZE = 30, MAX_OFFSET = 990;
    public SongSearchResult {
        tracks = List.copyOf(tracks);
        if (tracks.size() > MAX_PAGE_SIZE || total < tracks.size()) throw new IllegalArgumentException("search_result");
    }
    public static String query(String input) {
        if (input == null) throw new IllegalArgumentException("search_query");
        String query = input.strip();
        if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH || query.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("search_query");
        return query;
    }
}
