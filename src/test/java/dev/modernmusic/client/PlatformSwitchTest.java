package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSwitchTest {
    static class Provider implements MusicProvider {
        final List<SongId> requested = new ArrayList<>(); int searches;
        public CompletableFuture<Track> getTrack(SongId id) { requested.add(id); return new CompletableFuture<>(); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return new CompletableFuture<>(); }
        public CompletableFuture<SongSearchResult> searchSongs(String q, int offset, int limit) { searches++; return CompletableFuture.completedFuture(new SongSearchResult(List.of(), 0, false)); }
        public void close() {}
    }
    static class QqProvider extends Provider {
        @Override public CompletableFuture<Track> getReference(String reference) {
            return CompletableFuture.completedFuture(new Track(new SongId(Long.parseLong(reference), MusicPlatform.QQ), "Song", List.of(), 123000));
        }
    }
    @TempDir Path directory;
    @Test void switchingPreservesQueueSelectionLoadingAndRoutesEachTrackByOrigin() {
        var netease = new Provider(); var qq = new QqProvider(); var output = new SharedPlaybackTest.Output();
        try (var player = new PlaybackController(new MultiPlatformProvider(netease, qq), (uri, max) -> new byte[0], output, Runnable::run)) {
            player.add("123"); var queue = player.queue(); int stops = output.stops;
            player.switchPlatform(); assertSame(queue, player.queue()); assertEquals(0, queue.index()); assertEquals(stops, output.stops);
            assertEquals(PlaybackController.State.LOADING, player.state());
            player.add("123"); assertEquals(List.of(new SongId(123), new SongId(123, MusicPlatform.QQ)), queue.entries());
            player.search().search("song", 0, 10); assertEquals(1, qq.searches); assertEquals(0, netease.searches);
            player.play(0); assertEquals(new SongId(123), netease.requested.getLast()); assertTrue(qq.requested.isEmpty());
            player.play(1); assertEquals(new SongId(123, MusicPlatform.QQ), qq.requested.getLast());
            player.switchPlatform(); assertEquals(2, queue.entries().size()); assertNull(player.search().result());
        }
    }
    @Test void mixedQueuePersistsWithoutLosingOldIds() {
        Path file = directory.resolve("queues.json"); var library = new QueueLibrary();
        library.active().queue().restore(List.of(new SongId(123), new SongId(123, MusicPlatform.QQ), new SongId("A".repeat(32), MusicPlatform.KUGOU)), 1, PlaybackMode.REPEAT_ALL);
        try (var store = new QueueStore(file)) { store.save(library.snapshot(List.of())); }
        try (var store = new QueueStore(file)) {
            var saved = store.load().join().queues().getFirst();
            assertEquals(library.active().queue().entries(), saved.songs()); assertEquals(1, saved.selected());
        }
    }
    @Test void enterRecognizesQqSongsPlaylistsAndKeywords() {
        assertEquals(PlayerInputAction.SONG, PlayerInputAction.resolve("0039MnYb0qxYhV", false, MusicPlatform.QQ));
        assertEquals(PlayerInputAction.PLAYLIST, PlayerInputAction.resolve("https://y.qq.com/n/ryqq/playlist/123", false, MusicPlatform.QQ));
        assertEquals(PlayerInputAction.SEARCH, PlayerInputAction.resolve("周杰伦", false, MusicPlatform.QQ));
    }
    @Test void reportedLinksChooseSongImportEvenWithPlaylistSelected() {
        for (boolean playlist : new boolean[]{false, true}) {
            assertEquals(PlayerInputAction.SONG, PlayerInputAction.resolve("https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect ", playlist, MusicPlatform.QQ));
            assertEquals(PlayerInputAction.SONG, PlayerInputAction.resolve("https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2", playlist, MusicPlatform.QQ));
            assertEquals(PlayerInputAction.SONG, PlayerInputAction.resolve("https://www.kugou.com/song/#g2ln2xf9", playlist, MusicPlatform.KUGOU));
        }
    }
    @Test void encodedKugouLinkReachesProviderAndOnlyResolvedHashEntersQueue() {
        var references = new ArrayList<String>();
        var pending = new CompletableFuture<Track>();
        var kugou = new Provider() {
            @Override public CompletableFuture<Track> getReference(String reference) { references.add(reference); return pending; }
        };
        try (var player = new PlaybackController(new MultiPlatformProvider(new Provider(), new Provider(), kugou),
                (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.switchPlatform(); player.switchPlatform();
            String link = "https://www.kugou.com/song/#g2ln2xf9";
            assertTrue(player.add(link)); assertEquals(List.of(link), references); assertTrue(player.queue().entries().isEmpty());
            var id = new SongId("78A65512178A7F0ECB797F51BF9EC0B9", MusicPlatform.KUGOU);
            pending.complete(new Track(id, "Song", List.of("Singer"), 212000, null));
            assertEquals(List.of(id), player.queue().entries());
        }
    }
    @Test void qqSharedSourceHidesIdentityAndValidatesItsOwnCdn() {
        var sent = new ArrayList<SharedPlaybackSource.Request>();
        var netease = new SharedPlaybackSource(new Provider(), sent::add, () -> {}, java.time.Clock.systemUTC());
        var qq = new SharedPlaybackSource(new Provider(), sent::add, () -> {}, java.time.Clock.systemUTC(), MusicPlatform.QQ);
        try (var multi = new MultiPlatformProvider(netease, qq)) {
            qq.state(1, null); qq.profile(1, new dev.modernmusic.shared.SharedAccountProfile("QQ server", "https://q.qlogo.cn/fixture.png")); qq.select(true);
            var id = new SongId(123, MusicPlatform.QQ); var future = multi.resolvePlayback(id);
            var request = sent.getFirst(); assertEquals("qq:123", request.song());
            multi.select(MusicPlatform.NETEASE);
            qq.receive(request.request(), 1, "qq:123", "https://dl.stream.qqmusic.qq.com/M500fixture.mp3", System.currentTimeMillis() + 60000, null);
            assertEquals(id, future.join().id());
            var view = PlaybackAccountView.of(null, "modernmusic.auth.guest", qq);
            assertTrue(view.server()); assertEquals("", view.nickname()); assertNull(view.avatar()); assertFalse(netease.selected());
        }
    }
    @Test void threePlatformsCycleWithoutStoppingMusicOrReplacingTheQueue() {
        var netease = new Provider(); var qq = new QqProvider(); var kugou = new Provider(); var output = new SharedPlaybackTest.Output();
        try (var player = new PlaybackController(new MultiPlatformProvider(netease, qq, kugou), (uri, max) -> new byte[0], output, Runnable::run)) {
            player.add("123"); var queue = player.queue(); int stops = output.stops;
            player.switchPlatform(); player.add("123"); player.switchPlatform();
            assertEquals(MusicPlatform.KUGOU, player.platform()); assertSame(queue, player.queue()); assertEquals(stops, output.stops); assertEquals(0, queue.index());
            player.search().search("Song", 0, 10); assertEquals(1, kugou.searches); assertEquals(0, qq.searches);
            player.play(0); assertEquals(MusicPlatform.NETEASE, netease.requested.getLast().platform());
            player.play(1); assertEquals(MusicPlatform.QQ, qq.requested.getLast().platform());
            player.switchPlatform(); assertEquals(MusicPlatform.NETEASE, player.platform()); assertNull(player.search().result());
        }
        assertEquals(PlayerInputAction.SONG, PlayerInputAction.resolve("A".repeat(32), false, MusicPlatform.KUGOU));
        assertEquals(PlayerInputAction.PLAYLIST, PlayerInputAction.resolve("collection_3_123_4_0", false, MusicPlatform.KUGOU));
        assertEquals(PlayerInputAction.SEARCH, PlayerInputAction.resolve("晚风", false, MusicPlatform.KUGOU));
    }
    @Test void kugouSharedResolutionMatchesTheWholeHashAndHidesServerIdentity() {
        var sent = new ArrayList<SharedPlaybackSource.Request>();
        try (var source = new SharedPlaybackSource(new Provider(), sent::add, () -> {}, java.time.Clock.systemUTC(), MusicPlatform.KUGOU)) {
            source.state(7, null); source.profile(7, new dev.modernmusic.shared.SharedAccountProfile("酷狗共享账号", "https://imge.kugou.com/avatar.jpg")); source.select(true);
            var id = new SongId("A".repeat(32), MusicPlatform.KUGOU); var future = source.resolvePlayback(id); var request = sent.getFirst();
            assertEquals(id.toString(), request.song());
            source.receive(request.request(), 7, "kugou:" + "B".repeat(32), "https://fsandroid.tx.kugou.com/song.mp3", System.currentTimeMillis() + 60000, null);
            assertFalse(future.isDone());
            source.receive(request.request(), 7, id.toString(), "https://fsandroid.tx.kugou.com/song.mp3", System.currentTimeMillis() + 60000, null);
            assertEquals(id, future.join().id());
            var view = PlaybackAccountView.of(null, "modernmusic.auth.guest", source);
            assertEquals("", view.nickname()); assertNull(view.avatar()); assertTrue(view.server());
        }
    }
}
