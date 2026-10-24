package dev.modernmusic.probe;

import dev.modernmusic.client.PlayerInputAction;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.qq.QqMusicProvider;
import dev.modernmusic.provider.kugou.KugouMusicProvider;
import java.time.Clock;
import java.util.List;

/** Opt-in public metadata regression for the three reported links. No saved accounts or audio output. */
public final class SongLinkProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport();
             var qq = new QqMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> {});
             var kugou = new KugouMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> {})) {
            for (String link : List.of("https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect ",
                    "https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2")) {
                verifyAction(link, MusicPlatform.QQ);
                Track song = qq.getReference(link).join();
                if (song.id().platform() != MusicPlatform.QQ || song.title().isBlank() || song.durationMs() <= 0)
                    throw new AssertionError("QQ link metadata");
                if (link.contains("songid=") && song.id().value() != 216494081) throw new AssertionError("QQ numeric identity");
                System.out.println("QQ link=OK, id=" + song.id() + ", title=" + song.title());
            }
            String link = "https://www.kugou.com/song/#g2ln2xf9";
            verifyAction(link, MusicPlatform.KUGOU);
            Track song = kugou.getReference(link).join();
            if (!song.id().equals(new SongId("78A65512178A7F0ECB797F51BF9EC0B9", MusicPlatform.KUGOU))
                    || song.title().isBlank() || song.durationMs() != 212000) throw new AssertionError("Kugou link metadata");
            System.out.println("Kugou link=OK, id=" + song.id() + ", title=" + song.title());
        }
    }
    private static void verifyAction(String link, MusicPlatform platform) {
        for (boolean playlist : new boolean[]{false, true})
            if (PlayerInputAction.resolve(link, playlist, platform) != PlayerInputAction.SONG)
                throw new AssertionError("Song link action");
    }
}
