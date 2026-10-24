package dev.modernmusic.client.ui;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import java.time.Clock;

/** Opt-in reproduction of the reported public cover, using the production image download and decoder. */
public final class ArtworkCoverProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport(); var provider = new NeteaseMusicProvider(http, Clock.systemUTC())) {
            var song = provider.getTrack(new SongId(1294899297)).join();
            if (song.artwork() == null) throw new AssertionError("Song has no artwork URL");
            int[] pixels = ArtworkCache.load((uri, limit) -> {
                if (!"param=512y512".equals(uri.getRawQuery())) throw new AssertionError("Unbounded cover requested");
                byte[] bytes = http.get(uri, limit);
                System.out.println("Cover download: " + bytes.length + " bytes, thumbnail=512x512");
                return bytes;
            }, song.artwork());
            if (pixels.length != ArtworkCache.SIZE * ArtworkCache.SIZE || java.util.Arrays.stream(pixels).allMatch(pixel -> pixel >>> 24 == 0))
                throw new AssertionError("Empty decoded cover");
            System.out.println("PASS: " + song.id() + " " + song.title() + ", decoded=256x256");
        }
    }
}
