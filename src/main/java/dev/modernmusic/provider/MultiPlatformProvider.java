package dev.modernmusic.provider;

import dev.modernmusic.domain.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Browsing selection never changes the source of an existing queue entry. */
public final class MultiPlatformProvider implements MusicProvider {
    private final Map<MusicPlatform, MusicProvider> providers;
    private MusicPlatform selected = MusicPlatform.NETEASE;
    public MultiPlatformProvider(MusicProvider netease, MusicProvider qq) { providers = Map.of(MusicPlatform.NETEASE, netease, MusicPlatform.QQ, qq); }
    public MultiPlatformProvider(MusicProvider netease, MusicProvider qq, MusicProvider kugou) {
        providers = Map.of(MusicPlatform.NETEASE, netease, MusicPlatform.QQ, qq, MusicPlatform.KUGOU, kugou);
    }
    public MultiPlatformProvider(MusicProvider netease, MusicProvider qq, MusicProvider kugou, MusicProvider local) {
        providers = Map.of(MusicPlatform.NETEASE, netease, MusicPlatform.QQ, qq, MusicPlatform.KUGOU, kugou, MusicPlatform.LOCAL, local);
    }
    public MusicPlatform selected() { return selected; }
    public void select(MusicPlatform platform) {
        if (!providers.containsKey(platform)) throw new IllegalArgumentException("Platform unavailable");
        selected = platform;
    }
    public MusicPlatform next() { var next = selected.next(); while (!providers.containsKey(next)) next = next.next(); return next; }
    public MusicProvider provider(MusicPlatform platform) { return providers.get(platform); }
    @Override public CompletableFuture<Track> getReference(String reference) { return provider(selected).getReference(reference); }
    @Override public CompletableFuture<Track> getTrack(SongId id) { return provider(id.platform()).getTrack(id); }
    @Override public CompletableFuture<List<Track>> getTracks(List<SongId> ids) {
        if (ids.size() > 50) throw new IllegalArgumentException("metadata batch");
        var requested = List.copyOf(ids);
        var requests = new ArrayList<CompletableFuture<List<Track>>>();
        try {
            for (var platform : MusicPlatform.values()) {
                var songs = requested.stream().filter(id -> id.platform() == platform).toList();
                if (!songs.isEmpty()) requests.add(provider(platform).getTracks(songs));
            }
        } catch (RuntimeException failure) {
            requests.forEach(request -> request.cancel(true));
            return CompletableFuture.failedFuture(failure);
        }
        return MetadataRequests.combine(requests);
    }
    @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return provider(id.platform()).resolvePlayback(id); }
    @Override public CompletableFuture<PlaybackResource> refreshPlayback(SongId id) { return provider(id.platform()).refreshPlayback(id); }
    @Override public CompletableFuture<Lyrics> getLyrics(SongId id) { return provider(id.platform()).getLyrics(id); }
    @Override public CompletableFuture<Playlist> getPlaylist(SongId id) { return provider(id.platform()).getPlaylist(id); }
    @Override public CompletableFuture<SongSearchResult> searchSongs(String query, int offset, int limit) { return provider(selected).searchSongs(query, offset, limit); }
    @Override public void close() { providers.values().forEach(MusicProvider::close); }
}
