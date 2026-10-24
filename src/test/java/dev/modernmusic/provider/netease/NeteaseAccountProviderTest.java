package dev.modernmusic.provider.netease;

import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseAccountProviderTest {
    @Test void securityChallengeOnlyExposesOfficialHttpsVerificationLinksAndRedactsThem() {
        for (String value : List.of("https://music.163.com/st/verify?token=fixture-secret",
                "https://evil.example/st/verify", "http://music.163.com/st/verify", "https://music.163.com.evil.example/st/verify",
                "https://user@music.163.com/st/verify", "https://music.163.com:443/st/verify", "javascript:alert(1)")) {
            var p = provider("{\"code\":8821,\"verifyUrl\":\"" + value + "\"}", "/api/login/qrcode/client/login", null, List.of());
            var result = p.pollQr(new AccountProvider.QrChallenge("test-key-123", CLOCK.instant().plusSeconds(120)));
            assertEquals(AccountProvider.QrState.VERIFY_REQUIRED, result.state());
            assertEquals(value.startsWith("https://music.163.com/st/"), result.verificationUri() != null);
            assertFalse(result.toString().contains("fixture-secret")); assertNull(result.cookie());
        }
    }
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);
    private static final SessionCookie COOKIE = SessionCookie.parse("MUSIC_U=fixture-secret");
    private NeteaseAccountProvider provider(String json, String path, SessionCookie cookie, List<String> returned) {
        return new NeteaseAccountProvider(new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Missing account transport"); }
            public CookieResponse getQrAccount(URI uri, int max, String actual) {
                assertEquals("https", uri.getScheme()); assertEquals("music.163.com", uri.getHost());
                assertEquals(path, uri.getPath());
                assertEquals(path.endsWith("/unikey") ? "type=3" : "key=test-key-123&type=3", uri.getRawQuery());
                assertFalse(actual.contains("MUSIC_U="));
                return new CookieResponse(json.getBytes(StandardCharsets.UTF_8), returned);
            }
            public CookieResponse postAccount(URI uri, int max, String actual, byte[] form) {
                assertEquals("https", uri.getScheme()); assertEquals("music.163.com", uri.getHost());
                assertEquals(path, uri.getPath());
                if (cookie != null) assertTrue(actual.contains(cookie.header()));
                else assertFalse(actual.contains("MUSIC_U="));
                assertNull(uri.getQuery());
                assertFalse(new String(form, StandardCharsets.UTF_8).contains("fixture-secret"));
                assertFalse(uri.toString().contains("fixture-secret"));
                return new CookieResponse(json.getBytes(StandardCharsets.UTF_8), returned);
            }
        }, CLOCK);
    }
    @Test void validatesMatchingAccountAndCleansDisplayName() {
        var p = provider("{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42,\"nickname\":\"Hi§\\n\"}}", "/weapi/w/nuser/account/get", COOKIE, List.of());
        var result = p.validate(COOKIE);
        assertEquals("42", result.userId()); assertEquals("Hi", result.nickname()); assertSame(COOKIE, result.cookie());
    }
    @Test void readsAvatarAndIgnoresUntrustedImageUrlWithoutRejectingLogin() {
        for (String url : List.of("http://p1.music.126.net/avatar.jpg", "https://example.com/avatar.jpg")) {
            var p = provider("{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42,\"nickname\":\"User\",\"avatarUrl\":\"" + url + "\"}}",
                    "/weapi/w/nuser/account/get", COOKIE, List.of());
            var session = p.validate(COOKIE);
            assertEquals("42", session.userId());
            assertEquals(url.contains("p1.music.126.net") ? URI.create("https://p1.music.126.net/avatar.jpg") : null, session.avatar());
        }
    }
    @Test void rejectsAnonymousMismatchMalformedAndUpstreamMessagesWithoutLeaking() {
        for (String json : List.of("{\"code\":200,\"account\":null,\"profile\":null}",
                "{\"code\":200,\"account\":{\"id\":1},\"profile\":{\"userId\":2,\"nickname\":\"secret\"}}", "<html>secret</html>", "{}")) {
            var p = provider(json, "/weapi/w/nuser/account/get", COOKIE, List.of());
            var error = assertThrows(MusicException.class, () -> p.validate(COOKIE));
            assertFalse(error.toString().contains("secret")); assertNull(error.getCause());
        }
    }
    @ParameterizedTest @CsvSource({"800,EXPIRED", "801,WAITING", "802,CONFIRM", "8821,VERIFY_REQUIRED", "803,AUTHORIZED"})
    void handlesQrStatuses(int code, AccountProvider.QrState state) {
        var p = provider("{\"code\":" + code + "}", "/api/login/qrcode/client/login", null,
                List.of("MUSIC_U=fixture-secret; HttpOnly; Secure", "__csrf=csrf; Path=/"));
        var result = p.pollQr(new AccountProvider.QrChallenge("test-key-123", CLOCK.instant().plusSeconds(120)));
        assertEquals(state, result.state());
        if (state == AccountProvider.QrState.AUTHORIZED) assertEquals("MUSIC_U=fixture-secret; __csrf=csrf", result.cookie().header());
        else assertNull(result.cookie());
    }
    @Test void beginsBoundedChallengeRejectsUnsafeKeyAndMissingAuthorizedCookie() {
        var p = provider("{\"code\":200,\"unikey\":\"test-key-123\"}", "/api/login/qrcode/unikey", null, List.of());
        assertEquals(CLOCK.instant().plusSeconds(120), p.beginQr().expiresAt());
        assertThrows(MusicException.class, () -> provider("{\"code\":200,\"unikey\":\"abc&attack=1\"}", "/api/login/qrcode/unikey", null, List.of()).beginQr());
        assertThrows(AuthException.class, () -> provider("{\"code\":803}", "/api/login/qrcode/client/login", null, List.of())
                .pollQr(new AccountProvider.QrChallenge("test-key-123", CLOCK.instant().plusSeconds(120))));
    }
    @ParameterizedTest @CsvSource({"301,AUTH_EXPIRED", "401,AUTH_EXPIRED", "405,RATE_LIMITED", "429,RATE_LIMITED", "-460,RATE_LIMITED"})
    void mapsAccountErrors(int code, MusicError expected) {
        var p = provider("{\"code\":" + code + ",\"message\":\"fixture-secret\"}", "/weapi/w/nuser/account/get", COOKIE, List.of());
        assertEquals(expected, assertThrows(MusicException.class, () -> p.validate(COOKIE)).error());
    }
    @Test void locallyExpiredChallengeMakesNoRequest() {
        var p = new NeteaseAccountProvider((uri, max) -> { throw new AssertionError("Unexpected request"); }, CLOCK);
        assertEquals(AccountProvider.QrState.EXPIRED, p.pollQr(new AccountProvider.QrChallenge("test-key-123", CLOCK.instant())).state());
    }
    @Test void desktopQrPreservesCookiesThroughConfirmationAuthorizationAndWebAccountValidation() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Generic GET must not be used"); }
            public CookieResponse getQrAccount(URI uri, int max, String header) {
                return switch (calls.incrementAndGet()) {
                    case 1 -> {
                        assertEquals("/api/login/qrcode/unikey", uri.getPath()); assertEquals("type=3", uri.getRawQuery());
                        assertEquals("", header);
                        yield response("{\"code\":200,\"unikey\":\"test-key-123\"}", "NMTID=bootstrap; Path=/", "__csrf=first; Path=/");
                    }
                    case 2 -> {
                        assertEquals("/api/login/qrcode/client/login", uri.getPath());
                        assertEquals("key=test-key-123&type=3", uri.getRawQuery());
                        assertTrue(header.contains("NMTID=bootstrap")); assertTrue(header.contains("__csrf=first"));
                        yield response("{\"code\":802}", "__csrf=confirmation; Path=/");
                    }
                    case 3 -> {
                        assertEquals("/api/login/qrcode/client/login", uri.getPath());
                        assertEquals("key=test-key-123&type=3", uri.getRawQuery());
                        assertTrue(header.contains("NMTID=bootstrap"));
                        assertTrue(header.contains("__csrf=confirmation"));
                        yield response("{\"code\":803}", "MUSIC_U=old; Path=/", "MUSIC_U=authorized; Path=/", "__csrf=second; Path=/");
                    }
                    default -> throw new AssertionError("Unexpected extra request");
                };
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertEquals(4, calls.incrementAndGet());
                var body = com.google.gson.JsonParser.parseString(new String(form, StandardCharsets.UTF_8)).getAsJsonObject();
                assertNull(uri.getQuery()); assertTrue(body.has("timestamp"));
                assertEquals("/weapi/w/nuser/account/get", uri.getPath()); assertTrue(header.contains("MUSIC_U=authorized"));
                assertTrue(header.contains("NMTID=bootstrap"));
                assertEquals("second", body.get("csrf_token").getAsString());
                return response("{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42,\"nickname\":\"Fixture\"}}", "MUSIC_U=rotated; Path=/");
            }
            private CookieResponse response(String body, String... cookies) { return new CookieResponse(body.getBytes(StandardCharsets.UTF_8), List.of(cookies)); }
        };
        var provider = new NeteaseAccountProvider(http, CLOCK, json -> json.getBytes(StandardCharsets.UTF_8));
        var challenge = provider.beginQr();
        var waiting = provider.pollQr(challenge);
        assertEquals(AccountProvider.QrState.CONFIRM, waiting.state()); assertNull(waiting.cookie());
        var polled = provider.pollQr(challenge);
        assertEquals(AccountProvider.QrState.AUTHORIZED, polled.state());
        assertEquals("MUSIC_U=authorized; __csrf=second; NMTID=bootstrap", polled.cookie().header());
        assertEquals("MUSIC_U=rotated; __csrf=second; NMTID=bootstrap", provider.validate(polled.cookie()).cookie().header());
        assertEquals(4, calls.get());
    }
}
