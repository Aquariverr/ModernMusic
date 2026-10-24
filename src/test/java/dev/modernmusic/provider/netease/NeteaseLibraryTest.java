package dev.modernmusic.provider.netease;

import dev.modernmusic.domain.*;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseLibraryTest {
    private NeteaseMusicProvider fixture(String json) {
        return new NeteaseMusicProvider((uri, max) -> json.getBytes(StandardCharsets.UTF_8), Clock.systemUTC());
    }
    @Test void playlistUsesOrderedIdsEvenWhenReturnedTrackMetadataIsPartial() {
        try (var provider = fixture("""
                {"code":200,"playlist":{"id":42,"name":"测试","trackCount":3,"trackIds":[{"id":3},{"id":1},{"id":2}],
                "tracks":[{"id":1,"name":"One","dt":1000,"ar":[{"name":"Artist"}],"al":{"picUrl":"http://p1.music.126.net/test.jpg"}}]}}
                """)) {
            var list = provider.getPlaylist(new SongId(42)).join();
            assertEquals(List.of(new SongId(3), new SongId(1), new SongId(2)), list.ids());
            assertEquals(1, list.tracks().size()); assertEquals("https", list.tracks().getFirst().artwork().getScheme());
        }
    }
    @Test void parsesLyricsAndAbsentLyrics() {
        try (var provider = fixture("{\"code\":200,\"lrc\":{\"lyric\":\"[00:01]Hello\"},\"tlyric\":{\"lyric\":\"[00:01]你好\"}}")) {
            assertEquals("你好", provider.getLyrics(new SongId(42)).join().at(1000).translation());
        }
        try (var provider = fixture("{\"code\":200,\"nolyric\":true}")) { assertTrue(provider.getLyrics(new SongId(42)).join().lines().isEmpty()); }
    }
    @Test void imageUrlsAreOptionalAndConfinedToNeteaseImageHosts() {
        for (String url : List.of("https://localhost/a", "https://p1.music.126.net.evil.test/a", "https://m801.music.126.net/a", "file:///image.jpg", "https://p1.music.126.net:443/a", "https://user@p1.music.126.net/a")) assertNull(NeteaseUris.image(url));
        assertEquals(URI.create("https://p2.music.126.net/a.jpg"), NeteaseUris.image("http://p2.music.126.net/a.jpg"));
    }
    @Test void libraryRequestsUseAccountTransportForPrivatePlaylists() {
        var cookie = SessionCookie.parse("MUSIC_U=fixture");
        HttpTransport transport = new HttpTransport() {
            public byte[] get(URI uri, int limit) { throw new AssertionError("Missing account transport"); }
            public CookieResponse getWithCookies(URI uri, int limit, SessionCookie actual) {
                assertEquals(cookie.header(), actual.header()); assertEquals("music.163.com", uri.getHost());
                return new CookieResponse("{\"code\":200,\"playlist\":{\"id\":42,\"name\":\"Private\",\"trackIds\":[],\"trackCount\":0}}".getBytes(StandardCharsets.UTF_8), List.of());
            }
        };
        try (var provider = new NeteaseMusicProvider(transport, Clock.systemUTC(), () -> cookie, ignored -> {})) {
            assertTrue(provider.getPlaylist(new SongId(42)).join().ids().isEmpty());
        }
    }
    @Test void thumbnailsUseHttpsAndReplaceSizeWithoutCorruptingEscapedPathsOrOtherParameters() {
        assertEquals(URI.create("https://p1.music.126.net/cover.jpg?param=512y512"),
                NeteaseUris.thumbnail("http://p1.music.126.net/cover.jpg", 512));
        assertEquals(URI.create("https://p2.music.126.net/a%20b.jpg?quality=90&token=a%2Fb&param=512y512"),
                NeteaseUris.thumbnail("https://p2.music.126.net/a%20b.jpg?param=3000y3000&quality=90&token=a%2Fb&param=1024y1024", 512));
        var thumbnail = NeteaseUris.thumbnail("https://p1.music.126.net/cover.jpg?param=512y512", 512);
        assertEquals(thumbnail, NeteaseUris.thumbnail(thumbnail.toString(), 512));
        assertNull(NeteaseUris.thumbnail("https://p1.music.126.net.evil.test/cover.jpg", 512));
        assertNull(NeteaseUris.thumbnail("https://m1.music.126.net/song.mp3", 512));
        assertNull(NeteaseUris.thumbnail(null, 512));
        assertThrows(IllegalArgumentException.class, () -> NeteaseUris.thumbnail("https://p1.music.126.net/cover.jpg", 3000));
    }
}
