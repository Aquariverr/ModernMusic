package dev.modernmusic.provider.kugou;

import com.google.gson.*;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KugouMusicProviderTest {
    static final String HASH = "A331C7D3DC826D784124C8DC9284436F";
    static final SongId ID = new SongId(HASH, MusicPlatform.KUGOU);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
    static final String SONG = "{\"hash\":\"" + HASH + "\",\"filename\":\"Singer - Song\",\"duration\":120,\"trans_param\":{\"union_cover\":\"http://imge.kugou.com/cover/{size}/fixture.jpg\"}}";
    static class Http implements HttpTransport {
        String response; URI uri; SessionCookie cookie; JsonObject body;
        public byte[] get(URI uri, int max) { throw new AssertionError("API requires confined transport"); }
        public CookieResponse kugouApi(URI uri, int max, SessionCookie cookie, byte[] json) {
            this.uri = uri; this.cookie = cookie; this.body = json == null ? null : JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            return new CookieResponse(response.getBytes(StandardCharsets.UTF_8), List.of());
        }
    }
    @Test void encodedLinkImportsCanonicalHashAndMillisecondsWithoutSendingCredentials() {
        var http = new Http();
        String page = "<script>var dataFromSmarty = [{\"hash\":\"" + HASH + "\",\"encode_album_audio_id\":\"g2ln2xf9\","
                + "\"song_name\":\"Song ] with \\\"quotes\\\"\",\"author_name\":\"Singer\",\"timelength\":212000}],playType=\"search_single\";</script>";
        http.response = page;
        var cookie = SessionCookie.parse("userid=123;token=fixture", MusicPlatform.KUGOU);
        try (var provider = new KugouMusicProvider(http, CLOCK, () -> cookie, ignored -> fail())) {
            var song = provider.getReference("https://www.kugou.com/song/#g2ln2xf9").join();
            assertEquals(ID, song.id()); assertEquals(212000, song.durationMs()); assertEquals("Song ] with \"quotes\"", song.title());
            assertEquals(List.of("Singer"), song.artists());
            assertEquals(URI.create("https://www.kugou.com/mixsong/g2ln2xf9.html?share=1"), http.uri);
            assertNull(http.cookie); assertNull(http.body);
            for (String bad : List.of(page.replace("g2ln2xf9", "g2ln2xf8"), "<html>error</html>",
                    "var dataFromSmarty = [];", "var dataFromSmarty = [{},{ }];", page.replace("212000", "-1"))) {
                http.response = bad;
                assertError(MusicError.INVALID_RESPONSE, () -> provider.getReference("https://www.kugou.com/song/#g2ln2xf9").join());
            }
            http.response = page.replace(HASH, "");
            assertError(MusicError.UNAVAILABLE, () -> provider.getReference("https://www.kugou.com/song/#g2ln2xf9").join());
        }
    }
    @Test void detailAndSearchNormalizeMetadataAndKeepPublicQueriesCredentialFree() {
        var http = new Http(); var cookie = SessionCookie.parse("userid=123; token=fixture", MusicPlatform.KUGOU);
        try (var provider = new KugouMusicProvider(http, CLOCK, () -> cookie, ignored -> fail())) {
            http.response = SONG;
            var song = provider.getReference(HASH.toLowerCase()).join();
            assertEquals(ID, song.id()); assertEquals("Song", song.title()); assertEquals(List.of("Singer"), song.artists());
            assertEquals(120000, song.durationMs()); assertEquals("https://imge.kugou.com/cover/400/fixture.jpg", song.artwork().toString());
            assertNull(http.cookie);
            http.response = "{\"status\":1,\"error_code\":0,\"data\":{\"total\":50,\"lists\":[" + SONG + "]}}";
            var result = provider.searchSongs("Singer & Song", 10, 10).join();
            assertEquals(List.of(ID), result.tracks().stream().map(Track::id).toList()); assertTrue(result.hasMore());
            assertTrue(http.uri.getRawQuery().contains("page=2")); assertNull(http.cookie); assertFalse(http.uri.toString().contains("fixture"));
            http.response = SONG.replace(HASH, "F".repeat(32));
            assertError(MusicError.INVALID_RESPONSE, () -> provider.getTrack(ID).join());
        }
    }
    @Test void playlistPaginationIsBoundedAndPreservesHashes() {
        var pages = new ArrayList<URI>();
        var http = new Http() {
            @Override public CookieResponse kugouApi(URI uri, int max, SessionCookie cookie, byte[] json) {
                pages.add(uri); response = "{\"status\":1,\"data\":{\"total\":501,\"info\":[" + String.join(",", Collections.nCopies(100, SONG)) + "]}}";
                return super.kugouApi(uri, max, cookie, json);
            }
        };
        try (var provider = new KugouMusicProvider(http, CLOCK, () -> null, ignored -> fail())) {
            var list = provider.getPlaylist(new SongId(123, MusicPlatform.KUGOU)).join();
            assertEquals(400, list.ids().size()); assertEquals(501, list.total()); assertEquals(4, pages.size()); assertTrue(list.ids().stream().allMatch(ID::equals));
            assertTrue(pages.getLast().getQuery().contains("page=4"));
        }
    }
    @Test void collectionMetadataUsesMillisecondsAndItsListName() {
        var http = new Http();
        http.response = "{\"status\":1,\"data\":{\"count\":1,\"list_info\":{\"name\":\"List\"},\"songs\":[" +
                SONG.replace("\"duration\":120", "\"timelen\":120123").replace("\"filename\"", "\"name\"") + "]}}";
        try (var provider = new KugouMusicProvider(http, CLOCK, () -> null, ignored -> fail())) {
            var list = provider.getPlaylist(new SongId("collection_3_123_4_0", MusicPlatform.KUGOU)).join();
            assertEquals("List", list.name()); assertEquals(120123, list.tracks().getFirst().durationMs());
            assertTrue(http.uri.getQuery().contains("global_collection_id=collection_3_123_4_0"));
        }
    }
    @Test void authorizedPlaybackUsesOwnCredentialsAndRejectsTrialsOrForeignCdn() {
        var http = new Http(); var cookie = SessionCookie.parse("userid=123; token=fixture; mid=device", MusicPlatform.KUGOU);
        var expired = new ArrayList<SessionCookie>();
        try (var provider = new KugouMusicProvider(http, CLOCK, () -> cookie, expired::add)) {
            http.response = "{\"status\":1,\"extName\":\"mp3\",\"url\":[\"http://fsandroid.tx.kugou.com/yp/full/song.mp3\"]}";
            var resource = provider.resolvePlayback(ID).join(); assertEquals(ID, resource.id()); assertEquals("https", resource.uri().getScheme());
            assertSame(cookie, http.cookie); assertTrue(http.uri.getQuery().contains("userid=123")); assertTrue(http.uri.getQuery().contains("IsFreePart=0"));
            http.response = http.response.replace("/full/", "/clip/"); assertError(MusicError.TRIAL_ONLY, () -> provider.resolvePlayback(ID).join());
            http.response = "{\"status\":1,\"extName\":\"mp3\",\"url\":[\"https://evil.test/song.mp3\"]}";
            assertError(MusicError.INVALID_RESPONSE, () -> provider.resolvePlayback(ID).join());
            http.response = "{\"status\":0,\"error_code\":20018}";
            assertError(MusicError.AUTH_EXPIRED, () -> provider.resolvePlayback(ID).join()); assertEquals(List.of(cookie), expired);
        }
    }
    @Test void cookieValidationRequiresConfirmedMatchingProfileAndRedactsSecrets() {
        var cookie = SessionCookie.parse("unrelated=private; userid=123; token=fixture; KUGOU_API_MID=device", MusicPlatform.KUGOU);
        assertEquals("device", cookie.value("mid")); assertFalse(cookie.header().contains("unrelated")); assertFalse(cookie.toString().contains("fixture"));
        for (String invalid : List.of("userid=123", "MUSIC_U=fixture", "userid=123;token=x\r\nInjected:y", "userid=123;token=a;token=b", "userid=0;token=x"))
            assertThrows(AuthException.class, () -> SessionCookie.parse(invalid, MusicPlatform.KUGOU));
        var http = new Http(); var auth = new KugouAccountProvider(http, CLOCK);
        http.response = "{\"status\":1,\"data\":{\"userid\":123,\"nickname\":\"Listener\",\"pic\":\"https://imge.kugou.com/avatar.jpg\"}}";
        var session = auth.validate(cookie); assertEquals("Listener", session.nickname()); assertSame(cookie, session.cookie()); assertNotNull(session.avatar());
        assertFalse(http.body.toString().contains("fixture")); assertEquals(256, http.body.get("p").getAsString().length());
        http.response = http.response.replace("123", "456"); assertThrows(AuthException.class, () -> auth.validate(cookie));
        http.response = "{\"status\":0,\"error_code\":20018}";
        assertEquals(MusicError.AUTH_EXPIRED, assertThrows(MusicException.class, () -> auth.validate(cookie)).error());
    }
    @Test void websiteCredentialsPreserveAppAndUseWebsiteSignature() {
        var http = new Http(); http.response = "{\"status\":1,\"data\":{}}";
        var cookie = SessionCookie.parse("KugooID=123;t=fixture;a_id=1014;kg_mid=device;kg_dfid=fingerprint", MusicPlatform.KUGOU);
        var api = new KugouApi(http, CLOCK);
        api.gateway("/v5/url", Map.of(), null, cookie);
        String query = http.uri.getRawQuery();
        assertTrue(query.contains("appid=1014&")); assertTrue(query.contains("srcappid=2919&"));
        assertTrue(query.contains("clientver=1000&")); assertTrue(query.contains("uuid=device"));
        assertFalse(query.contains("appid=1005"));
        assertTrue(query.contains("signature=8aad15e1d2859a8e8cdd704eb305a286&"));
        assertEquals("1014", api.appid(cookie)); assertEquals("1005", api.appid(null));
    }
    private static void assertError(MusicError error, Runnable action) {
        assertEquals(error, ((MusicException) assertThrows(CompletionException.class, action::run).getCause()).error());
    }
}
