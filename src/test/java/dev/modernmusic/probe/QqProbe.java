package dev.modernmusic.probe;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.qq.*;
import java.time.Clock;

/** Opt-in public endpoints only. Never reads saved sessions or prints signed URLs. */
public final class QqProbe {
    public static void main(String[] args) {
        try (var http = new BoundedHttpTransport(); var qq = new QqMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> {})) {
            probe("detail", () -> { var song = qq.getReference("0039MnYb0qxYhV").join(); System.out.println("QQ song=" + song.id() + " title=" + song.title()); });
            probe("search", () -> {
                var page = qq.searchSongs("晴天", 0, 10).join(); var next = qq.searchSongs("晴天", 10, 10).join();
                if (page.tracks().isEmpty() || next.tracks().isEmpty() || page.tracks().getFirst().id().equals(next.tracks().getFirst().id())) throw new IllegalStateException("Search pagination failed");
                System.out.println("QQ search tracks=" + page.tracks().size() + " total=" + page.total() + " page2=" + next.tracks().size());
            });
            probe("lyrics", () -> { var song = qq.getReference("0039MnYb0qxYhV").join(); System.out.println("QQ lyric lines=" + qq.getLyrics(song.id()).join().lines().size()); });
            probe("playlist", () -> System.out.println("QQ playlist tracks=" + qq.getPlaylist(new SongId(8660752029L, MusicPlatform.QQ)).join().ids().size()));
            probe("playback", () -> { var song = qq.getReference("0039MnYb0qxYhV").join(); var resource = qq.resolvePlayback(song.id()).join(); System.out.println("QQ audio host=" + resource.uri().getHost()); });
        }
    }
    private static void probe(String stage, Runnable action) {
        try { action.run(); System.out.println(stage + "=OK"); }
        catch (RuntimeException failure) {
            Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
            System.out.println(stage + "=" + (cause instanceof MusicException music ? music.error() : cause.getClass().getSimpleName()));
        }
    }
}
