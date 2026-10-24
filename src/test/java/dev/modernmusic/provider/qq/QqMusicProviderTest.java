package dev.modernmusic.provider.qq;

import com.google.gson.*;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QqMusicProviderTest {
    static final String MID = "0039MnYb0qxYhV";
    static final String SONG = "{\"id\":123,\"mid\":\"" + MID + "\",\"name\":\"Song\",\"interval\":123,\"singer\":[{\"name\":\"Singer\"}],\"album\":{\"mid\":\"0039MnYb0qxYhV\"},\"file\":{\"media_mid\":\"0039MnYb0qxYhV\"}}";
    static class Http implements HttpTransport {
        SessionCookie sent; String response; JsonObject request;
        public byte[] get(URI uri, int limit) { throw new AssertionError("QQ API must use confined transport"); }
        public CookieResponse qqApi(URI uri, int limit, SessionCookie cookie, byte[] json) {
            sent = cookie;
            if (json != null) request = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            String body = request != null && request.has("music.search.SearchCgiService") ? response.replace("req_0", "music.search.SearchCgiService") : response;
            return new CookieResponse(body.getBytes(StandardCharsets.UTF_8), List.of());
        }
        void rpc(String data) { response = "{\"code\":0,\"req_0\":{\"code\":0,\"data\":" + data + "}}"; }
    }
    @Test void detailsAndSearchCarryQqIdentityAndRespectPaging() {
        var http = new Http(); var cookie = SessionCookie.parse("uin=123; qqmusic_key=fixture", MusicPlatform.QQ);
        try (var provider = new QqMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail())) {
            http.rpc("{\"track_info\":" + SONG + "}");
            Track track = provider.getReference(MID).join();
            assertEquals(new SongId(123, MusicPlatform.QQ), track.id()); assertSame(cookie, http.sent);
            assertEquals(123000, track.durationMs()); assertEquals("y.gtimg.cn", track.artwork().getHost());
            http.rpc("{\"body\":{\"item_song\":[" + SONG + "]},\"meta\":{\"sum\":21}}");
            var page = provider.searchSongs("Singer", 10, 10).join(); assertTrue(page.hasMore()); assertEquals(21, page.total());
            assertEquals(2, http.request.getAsJsonObject("req_0").getAsJsonObject("param").get("page_num").getAsInt());
        }
    }
    @Test void reportedLinksRequestTheirNumericIdOrMid() {
        var http = new Http();
        try (var provider = new QqMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> fail())) {
            http.rpc("{\"track_info\":" + SONG.replace("\"id\":123", "\"id\":216494081").replace(MID, "001NKo0b2Z4uGu") + "}");
            var numeric = provider.getReference("https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect ").join();
            assertEquals(new SongId(216494081, MusicPlatform.QQ), numeric.id());
            assertEquals(216494081, http.request.getAsJsonObject("req_0").getAsJsonObject("param").get("song_id").getAsLong());
            var mid = provider.getReference("https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2").join();
            assertEquals(numeric.id(), mid.id());
            assertEquals("001NKo0b2Z4uGu", http.request.getAsJsonObject("req_0").getAsJsonObject("param").get("song_mid").getAsString());
        }
    }
    @Test void lyricsPlaylistsAndExpiredSessionsHaveBoundedResults() {
        var http = new Http(); var cookie = SessionCookie.parse("uin=123; qm_keyst=fixture", MusicPlatform.QQ); var expired = new ArrayList<SessionCookie>();
        try (var provider = new QqMusicProvider(http, Clock.systemUTC(), () -> cookie, expired::add)) {
            http.rpc("{\"lyric\":\"" + Base64.getEncoder().encodeToString("[00:01]line".getBytes(StandardCharsets.UTF_8)) + "\"}");
            assertEquals("line", provider.getLyrics(new SongId(123, MusicPlatform.QQ)).join().lines().get(0).text());
            http.response = "{\"code\":0,\"cdlist\":[{\"dissname\":\"List\",\"total_song_num\":1,\"songlist\":[" + SONG + "]}]}";
            assertEquals(List.of(new SongId(123, MusicPlatform.QQ)), provider.getPlaylist(new SongId(5, MusicPlatform.QQ)).join().ids());
            http.response = "{\"code\":1000}";
            assertThrows(CompletionException.class, () -> provider.getTrack(new SongId(123, MusicPlatform.QQ)).join()); assertEquals(List.of(cookie), expired);
        }
    }
    @Test void accountRequiresAValidatedProfileAndUsesItsNickname() {
        var http = new Http(); var cookie = SessionCookie.parse("uin=123; qqmusic_key=fixture", MusicPlatform.QQ);
        var account = new QqAccountProvider(http, Clock.systemUTC());
        http.response = "{\"code\":0,\"data\":{\"creator\":{\"uin\":123,\"nick\":\"QQ listener\",\"headpic\":\"https://q.qlogo.cn/g?b=qq&nk=123&s=100\"}}}";
        assertEquals("QQ listener", account.validate(cookie).nickname());
        http.response = http.response.replace("\"uin\":123", "\"uin\":0");
        assertEquals("123", account.validate(cookie).userId());
        var wechat = SessionCookie.parse("wxuin=9007199254740993;login_type=2;qqmusic_key=W_X_fixture", MusicPlatform.QQ);
        assertEquals("9007199254740993", account.validate(wechat).userId());
        assertEquals("QQ listener", account.validate(wechat).nickname());
        http.response = "{\"code\":1000}"; assertThrows(MusicException.class, () -> account.validate(cookie));
        http.response = "{\"code\":0,\"data\":{\"creator\":{\"uin\":456,\"nick\":\"Wrong user\"}}}"; assertThrows(AuthException.class, () -> account.validate(cookie));
        assertThrows(AuthException.class, () -> SessionCookie.parse("MUSIC_U=fixture", MusicPlatform.QQ));
        assertThrows(AuthException.class, () -> SessionCookie.parse("uin=123; qqmusic_key=fixture\r\nInjected: x", MusicPlatform.QQ));
        assertFalse(cookie.toString().contains("fixture"));
    }
    @Test void playbackRequestsMp3AndRejectsEmptyOrForeignResources() {
        var cookie = SessionCookie.parse("uin=123; qqmusic_key=fixture", MusicPlatform.QQ);
        var path = new java.util.concurrent.atomic.AtomicReference<>("M500" + MID + ".mp3?vkey=fixture");
        HttpTransport http = new Http() {
            @Override public CookieResponse qqApi(URI uri, int max, SessionCookie sent, byte[] json) {
                assertSame(cookie, sent);
                var root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
                var call = root.getAsJsonObject("req_0");
                if (call.get("module").getAsString().equals("music.pf_song_detail_svr")) rpc("{\"track_info\":" + SONG + "}");
                else {
                    assertEquals("M500" + MID + ".mp3", call.getAsJsonObject("param").getAsJsonArray("filename").get(0).getAsString());
                    rpc("{\"midurlinfo\":[{\"songmid\":\"" + MID + "\",\"purl\":\"" + path.get() + "\"}],\"sip\":[\"https://evil.test/\",\"http://dl.stream.qqmusic.qq.com/\"]}");
                }
                return super.qqApi(uri, max, sent, json);
            }
        };
        try (var provider = new QqMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail())) {
            var id = new SongId(123, MusicPlatform.QQ);
            assertEquals("https", provider.resolvePlayback(id).join().uri().getScheme());
            assertEquals("dl.stream.qqmusic.qq.com", provider.refreshPlayback(id).join().uri().getHost());
            path.set(""); assertThrows(CompletionException.class, () -> provider.resolvePlayback(id).join());
            path.set("https://evil.test/M500x.mp3"); assertThrows(CompletionException.class, () -> provider.resolvePlayback(id).join());
        }
    }
}
