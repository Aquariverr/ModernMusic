package dev.modernmusic.probe;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.kugou.*;
import java.time.Clock;

/** Explicit public integration check. Fails on errors; never prints credentials or signed resources. */
public final class KugouProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport(); var provider = new KugouMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> {})) {
            var id = new SongId("A331C7D3DC826D784124C8DC9284436F", MusicPlatform.KUGOU);
            var song = provider.getTrack(id).join();
            if (!song.id().equals(id) || song.title().isBlank()) throw new AssertionError("Details");
            System.out.println("Kugou detail=OK");
            var first = provider.searchSongs("hello", 0, 10).join(); var second = provider.searchSongs("hello", 10, 10).join();
            if (first.tracks().isEmpty() || second.tracks().isEmpty() || first.tracks().get(0).id().equals(second.tracks().get(0).id())) throw new AssertionError("Search pagination");
            System.out.println("Kugou search=" + first.tracks().size() + ", page2=" + second.tracks().size());
            var list = provider.getPlaylist(new SongId(125032, MusicPlatform.KUGOU)).join();
            if (list.ids().isEmpty()) throw new AssertionError("Playlist");
            System.out.println("Kugou numeric playlist=" + list.ids().size());
            var collection = provider.getPlaylist(new SongId("collection_3_2098608661_4_0", MusicPlatform.KUGOU)).join();
            if (collection.ids().isEmpty()) throw new AssertionError("Collection");
            System.out.println("Kugou collection playlist=" + collection.ids().size());
            var lyrics = provider.getLyrics(id).join();
            if (lyrics.lines().isEmpty()) throw new AssertionError("Lyrics");
            System.out.println("Kugou lyrics=" + lyrics.lines().size());
            var resource = provider.resolvePlayback(id).join();
            var audio = new dev.modernmusic.client.audio.Mp3Decoder().decode(http.get(resource.uri(), 32 * 1024 * 1024));
            if (audio.durationMs() < 100000) throw new AssertionError("Full MP3 decode");
            System.out.println("Kugou MP3 decode=OK, duration=" + audio.durationMs());
        }
    }
}
