package dev.modernmusic.probe;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import java.time.Clock;

/** Public guest endpoints only. No login, cookies, SMS, audio device or personal data. */
public final class LibraryProbe {
    public static void main(String[] args) throws Exception {
        try (var http = new BoundedHttpTransport(); var provider = new NeteaseMusicProvider(http, Clock.systemUTC())) {
            var playlist = provider.getPlaylist(new SongId(3778678)).join();
            if (playlist.ids().isEmpty()) throw new IllegalStateException("Empty public playlist");
            System.out.printf("playlist=3778678 total=%d importedIds=%d metadata=%d%n", playlist.total(), playlist.ids().size(), playlist.tracks().size());
            var id = new SongId(1973665667); var track = provider.getTrack(id).join(); var lyrics = provider.getLyrics(id).join();
            if (track.artwork() == null || lyrics.lines().isEmpty()) throw new IllegalStateException("Missing public artwork or lyrics");
            byte[] art = http.get(track.artwork(), 4 * 1024 * 1024);
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(art));
            if (image == null) throw new IllegalStateException("Invalid public artwork");
            System.out.printf("track=%s lyricLines=%d artwork=%dx%d bytes=%d%n", id, lyrics.lines().size(), image.getWidth(), image.getHeight(), art.length);
            image.flush();
        }
    }
}
