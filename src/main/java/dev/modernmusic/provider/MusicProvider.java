package dev.modernmusic.provider;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import java.util.concurrent.CompletableFuture;

/** Asynchronous music metadata, library and playback capabilities; providers own credential handling. */
public interface MusicProvider extends AutoCloseable {
    default CompletableFuture<Track> getReference(String reference) { return getTrack(dev.modernmusic.domain.SongReferenceParser.parse(reference)); }
    CompletableFuture<Track> getTrack(SongId id);
    /** Bounded metadata-only lookup; never resolves audio or starts playback. */
    default CompletableFuture<java.util.List<Track>> getTracks(java.util.List<SongId> ids) {
        var songs = java.util.List.copyOf(ids);
        if (songs.size() > 50) throw new IllegalArgumentException("metadata batch");
        return MetadataRequests.sequential(songs, this::getTrack);
    }
    CompletableFuture<PlaybackResource> resolvePlayback(SongId id);
    default CompletableFuture<PlaybackResource> refreshPlayback(SongId id) { return resolvePlayback(id); }
    default CompletableFuture<dev.modernmusic.domain.Lyrics> getLyrics(SongId id) {
        return CompletableFuture.completedFuture(dev.modernmusic.domain.Lyrics.EMPTY);
    }
    default CompletableFuture<dev.modernmusic.domain.Playlist> getPlaylist(SongId id) {
        return CompletableFuture.failedFuture(new MusicException(MusicError.UNAVAILABLE));
    }
    default CompletableFuture<dev.modernmusic.domain.SongSearchResult> searchSongs(String query, int offset, int limit) {
        return CompletableFuture.failedFuture(new MusicException(MusicError.UNAVAILABLE));
    }
    @Override void close();
}
