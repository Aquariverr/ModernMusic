package dev.modernmusic.provider.netease;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.*;
import dev.modernmusic.auth.SessionCookie;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseMusicProviderTest {
    @Test void batchMetadataUsesOneRequestAndFiltersUnrequestedSongs() {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        try (var provider = new NeteaseMusicProvider((uri, max) -> {
            requests.incrementAndGet(); assertEquals("ids=[42,43]", uri.getQuery());
            return """
                    {"code":200,"songs":[
                      {"id":43,"name":"Second","duration":1000,"artists":[{"name":"Artist"}]},
                      {"id":42,"name":"First","duration":1000,"artists":[]},
                      {"id":99,"name":"Unrequested","duration":1000,"artists":[]}]}
                    """.getBytes(StandardCharsets.UTF_8);
        }, CLOCK)) {
            var tracks = provider.getTracks(List.of(new SongId(42), new SongId(43))).join();
            assertEquals(List.of(new SongId(43), new SongId(42)), tracks.stream().map(dev.modernmusic.domain.Track::id).toList());
            assertEquals("Second", tracks.get(0).title()); assertEquals(1, requests.get());
        }
    }
    @Test void authenticatedResolutionUsesOnlyAccountTransportAndReportsExpiredSnapshot() {
        var cookie = SessionCookie.parse("MUSIC_U=fixture-secret");
        var expired = new AtomicReference<SessionCookie>();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Credentials were dropped"); }
            public CookieResponse postAccount(URI uri, int max, String actual, byte[] form) {
                assertEquals(cookie.header(), actual); assertEquals("music.163.com", uri.getHost());
                assertFalse(uri.toString().contains("fixture-secret"));
                return new CookieResponse("{\"code\":200,\"data\":[{\"id\":42,\"code\":301}]}".getBytes(StandardCharsets.UTF_8), List.of());
            }
        };
        try (var provider = new NeteaseMusicProvider(http, CLOCK, () -> cookie, expired::set)) {
            assertThrows(CompletionException.class, () -> provider.resolvePlayback(new SongId(42)).join());
            assertSame(cookie, expired.get());
        }
    }
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
    private static final String VALID = "{\"id\":42,\"code\":200,\"url\":\"http://m801.music.126.net/private/test.mp3?token=secret\",\"type\":\"mp3\",\"expi\":1200,\"freeTrialInfo\":null}";

    private NeteaseMusicProvider provider(String json) {
        return new NeteaseMusicProvider(new HttpTransport() {
            public byte[] get(URI uri, int max) {
                assertEquals("/api/song/detail/", uri.getPath());
                assertTrue(uri.getRawQuery().contains("42"));
                return json.getBytes(StandardCharsets.UTF_8);
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertEquals("https://music.163.com/weapi/song/enhance/player/url/v1", uri.toString());
                assertEquals("", header);
                assertTrue(new String(form, StandardCharsets.UTF_8).startsWith("params="));
                return new CookieResponse(json.getBytes(StandardCharsets.UTF_8), List.of());
            }
        }, CLOCK);
    }
    private void failure(String json, MusicError expected) {
        try (var provider = provider(json)) {
            var exception = assertThrows(CompletionException.class, () -> provider.resolvePlayback(new SongId(42)).join());
            assertInstanceOf(MusicException.class, exception.getCause());
            assertEquals(expected, ((MusicException) exception.getCause()).error());
            assertNull(exception.getCause().getCause());
            assertFalse(exception.getCause().toString().contains("secret"));
        }
    }
    private String envelope(String row) { return "{\"code\":200,\"data\":[" + row + "]}"; }

    @Test void webPlaybackCarriesCurrentSessionCsrfAndExplicitMp3QualityWithoutCachingOrGuestFallback() {
        var cookie = SessionCookie.parse("MUSIC_U=fixture-secret; __csrf=fixture-csrf");
        var current = new AtomicReference<>(cookie);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Legacy playback GET must not be used"); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertEquals("https://music.163.com/weapi/song/enhance/player/url/v1", uri.toString());
                assertEquals(current.get().header(), header);
                var body = com.google.gson.JsonParser.parseString(new String(form, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("[42]", body.get("ids").getAsString());
                assertEquals("standard", body.get("level").getAsString());
                assertEquals("mp3", body.get("encodeType").getAsString());
                assertEquals(calls.get() == 0 ? "fixture-csrf" : "rotated-csrf", body.get("csrf_token").getAsString());
                assertEquals(CLOCK.millis(), body.get("timestamp").getAsLong());
                assertEquals(5, body.size()); calls.incrementAndGet();
                return new CookieResponse(envelope(VALID).getBytes(StandardCharsets.UTF_8), List.of());
            }
        };
        try (var provider = new NeteaseMusicProvider(http, CLOCK, current::get, ignored -> fail("Valid account expired"), json -> json.getBytes(StandardCharsets.UTF_8))) {
            provider.resolvePlayback(new SongId(42)).join();
            current.set(SessionCookie.parse("MUSIC_U=rotated; __csrf=rotated-csrf"));
            provider.resolvePlayback(new SongId(42)).join();
            assertEquals(2, calls.get());
        }
    }

    @Test void preservesSignedCdnPathAndQueryExactlyWhenUpgradingTls() {
        String suffix = "://m801.music.126.net/a%2Fb/song.mp3?auth=a%2Bb%2Fc%3D&token=A+B%252F";
        assertEquals("https" + suffix, NeteaseUris.audio("http" + suffix).toASCIIString());
    }

    @Test void resolvesGuestMp3AndRedactsResource() {
        try (var provider = provider(envelope(VALID))) {
            var resource = provider.resolvePlayback(new SongId(42)).join();
            assertEquals("https", resource.uri().getScheme());
            assertEquals(CLOCK.instant().plusSeconds(1200), resource.expiresAt());
            assertFalse(resource.toString().contains("secret"));
        }
    }
    @Test void readsLegacyDetailsAndSanitizesDisplayText() {
        try (var provider = provider("{\"code\":200,\"songs\":[{\"id\":42,\"name\":\"A§B\\n\",\"duration\":201656,\"artists\":[{\"name\":\"Artist\"}]}]}")) {
            var track = provider.getTrack(new SongId(42)).join();
            assertEquals("AB", track.title()); assertEquals("Artist", track.artistText());
            assertEquals(201656, track.durationMs());
        }
    }
    @ParameterizedTest @CsvSource({"301,AUTH_EXPIRED", "429,RATE_LIMITED", "405,RATE_LIMITED", "-460,RATE_LIMITED", "404,UNAVAILABLE"})
    void mapsTopLevelErrors(int code, MusicError error) { failure("{\"code\":" + code + "}", error); }
    @Test void rejectsNullTrialWrongFormatAndMismatchedId() {
        failure(envelope(VALID.replace("\"code\":200", "\"code\":-110")), MusicError.AUTH_REQUIRED);
        failure(envelope(VALID.replace("\"freeTrialInfo\":null", "\"freeTrialInfo\":{\"start\":0,\"end\":30}")), MusicError.TRIAL_ONLY);
        failure(envelope(VALID.replace("\"mp3\"", "\"flac\"")), MusicError.UNSUPPORTED_FORMAT);
        failure(envelope(VALID.replace("\"id\":42", "\"id\":43")), MusicError.INVALID_RESPONSE);
        failure(envelope(VALID.replace("1200", "0")), MusicError.INVALID_RESPONSE);
        failure(envelope("{\"id\":42,\"code\":200,\"url\":null}"), MusicError.UNAVAILABLE);
        failure("{\"code\":200,\"data\":[]}", MusicError.UNAVAILABLE);
        failure("{\"code\":200,\"data\":[{}]}", MusicError.INVALID_RESPONSE);
        failure("<html>service error</html>", MusicError.INVALID_RESPONSE);
    }
    @ParameterizedTest @ValueSource(strings = {"https://music.126.net.evil.example/a.mp3", "https://127.0.0.1/a.mp3",
            "https://m801.music.126.net:443/a.mp3", "https://secret@m801.music.126.net/a.mp3",
            "file:///a.mp3", "https://m801.music.126.net/a.mp3#fragment", "https://music.163.com/a.mp3"})
    void rejectsNonCdnResources(String url) { assertThrows(MusicException.class, () -> NeteaseUris.audio(url)); }
}
