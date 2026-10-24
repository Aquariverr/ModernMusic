package dev.modernmusic.provider.netease;

import com.google.gson.JsonParser;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseSearchTest {
    private static final String RESPONSE = """
            {"code":200,"result":{"songCount":12,"songs":[
            {"id":42,"name":"Song","duration":123000,"artists":[{"name":"Artist"}],"album":{"picUrl":"http://p1.music.126.net/cover.jpg"}},
            {"id":43,"name":"Other","dt":456000,"ar":[{"name":"歌手"}],"al":{}}]}}
            """;
    private NeteaseMusicProvider provider(String json, SessionCookie cookie, java.util.function.Consumer<byte[]> body,
                                           java.util.function.Consumer<SessionCookie> expired) {
        return new NeteaseMusicProvider(new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Search must POST"); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertEquals(URI.create("https://music.163.com/weapi/search/get"), uri);
                assertEquals(cookie == null ? "" : cookie.header(), header);
                assertTrue(max <= 1024 * 1024); body.accept(form);
                return new CookieResponse(json.getBytes(StandardCharsets.UTF_8), List.of());
            }
        }, Clock.systemUTC(), () -> cookie, expired, value -> value.getBytes(StandardCharsets.UTF_8));
    }
    @Test void searchSendsUnicodeInPostBodyAndParsesBothMetadataFormats() {
        for (var cookie : new SessionCookie[]{null, SessionCookie.parse("MUSIC_U=fixture")}) {
            try (var provider = provider(RESPONSE, cookie, body -> {
                var payload = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("晴天 & artist", payload.get("s").getAsString());
                assertEquals(1, payload.get("type").getAsInt());
                assertEquals(4, payload.get("offset").getAsInt()); assertEquals(2, payload.get("limit").getAsInt());
            }, ignored -> fail())) {
                var result = provider.searchSongs("  晴天 & artist  ", 4, 2).join();
                assertEquals(12, result.total()); assertTrue(result.hasMore()); assertEquals(2, result.tracks().size());
                assertEquals("https", result.tracks().getFirst().artwork().getScheme());
                assertEquals("歌手", result.tracks().getLast().artistText()); assertNull(result.tracks().getLast().artwork());
            }
        }
    }
    @Test void emptyAndFinalPagesDoNotOfferMoreResults() {
        try (var provider = provider("{\"code\":200,\"result\":{\"songCount\":0}}", null, body -> {}, ignored -> {})) {
            var result = provider.searchSongs("no matches", 0, 10).join(); assertTrue(result.tracks().isEmpty()); assertFalse(result.hasMore());
        }
        try (var provider = provider(RESPONSE, null, body -> {}, ignored -> {})) {
            assertFalse(provider.searchSongs("song", 10, 2).join().hasMore());
        }
    }
    @Test void malformedResponsesAndServiceErrorsAreReported() {
        for (String json : List.of("{}", "{\"code\":200,\"result\":{\"songCount\":2}}", "{\"code\":200,\"result\":{\"songCount\":-1}}", "not json")) {
            try (var provider = provider(json, null, body -> {}, ignored -> {})) {
                var error = assertThrows(CompletionException.class, () -> provider.searchSongs("song", 0, 10).join());
                assertEquals(MusicError.INVALID_RESPONSE, ((MusicException) error.getCause()).error());
            }
        }
        var cookie = SessionCookie.parse("MUSIC_U=fixture"); var expired = new AtomicReference<SessionCookie>();
        try (var provider = provider("{\"code\":301}", cookie, body -> {}, expired::set)) {
            assertThrows(CompletionException.class, () -> provider.searchSongs("song", 0, 10).join()); assertSame(cookie, expired.get());
        }
    }
    @Test void invalidQueriesAndPagesNeverReachNetwork() {
        try (var provider = provider(RESPONSE, null, body -> fail("Invalid request sent"), ignored -> {})) {
            for (String query : List.of(" ", "x".repeat(129), "bad\nquery"))
                assertThrows(CompletionException.class, () -> provider.searchSongs(query, 0, 10).join());
            assertThrows(CompletionException.class, () -> provider.searchSongs("song", -1, 10).join());
            assertThrows(CompletionException.class, () -> provider.searchSongs("song", 1000, 10).join());
            assertThrows(CompletionException.class, () -> provider.searchSongs("song", 0, 31).join());
        }
    }
}
