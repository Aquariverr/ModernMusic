package dev.modernmusic.provider.kugou;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KugouWebsiteSessionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
    private static final String PROFILE = "KuGoo=KugooID=123&KugooPwd=discarded-digest&NickName=%u6D4B%u8BD5%2BListener"
            + "&Pic=https%3A%2F%2Fimge.kugou.com%2Favatar.jpg&t=renewed-token&a_id=1014&ct=1791590400; Domain=.kugou.com; Path=/; Secure";
    private static SessionCookie cookie(String text) { return SessionCookie.parse(text, MusicPlatform.KUGOU); }
    private static SessionCookie input() { return cookie("KugooID=123;t=fixture;a_id=1014;kg_mid=device;kg_dfid=fingerprint;ct=1791500000"); }
    private static HttpTransport.CookieResponse response(String body, String... cookies) {
        return new HttpTransport.CookieResponse(body.getBytes(StandardCharsets.UTF_8), List.of(cookies));
    }
    private static AccountSession validate(HttpTransport.CookieResponse reply) { return KugouWebsiteSession.validate(reply, input(), CLOCK); }
    private static void invalid(HttpTransport.CookieResponse reply) {
        var error = assertThrows(AuthException.class, () -> validate(reply));
        assertEquals("modernmusic.auth.invalid_response", error.translationKey()); assertNull(error.getCause());
    }

    @Test void websiteLoginUsesItsOwnEndpointAndConfirmedServerProfile() {
        var seen = new ArrayList<URI>();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse kugouApi(URI uri, int max, SessionCookie session, byte[] body) {
                seen.add(uri); assertNull(body); assertEquals(65536, max); assertEquals("fixture", session.value("token"));
                return response("", PROFILE);
            }
        };
        var session = new KugouAccountProvider(http, CLOCK).validate(input());
        assertEquals("123", session.userId()); assertEquals("测试+Listener", session.nickname());
        assertEquals(URI.create("https://imge.kugou.com/avatar.jpg"), session.avatar());
        assertEquals("renewed-token", session.cookie().value("token")); assertEquals("1791590400", session.cookie().value("ct"));
        assertEquals("device", session.cookie().value("mid")); assertEquals("fingerprint", session.cookie().value("dfid"));
        assertFalse(session.cookie().header().contains("discarded")); assertFalse(session.cookie().header().contains("NickName"));
        assertEquals(1, seen.size());
        URI uri = seen.get(0); assertEquals("https", uri.getScheme()); assertEquals("login-user.kugou.com", uri.getHost());
        assertEquals("/v1/cross/", uri.getPath()); assertTrue(uri.getQuery().contains("a_id=1014&userid=123&t=fixture&ct=1791500000&"));
        assertTrue(uri.getQuery().contains("callback=&domain=kugou.com&")); assertFalse(uri.toString().contains("discarded"));
    }
    @Test void missingTimestampUsesCurrentTimeAndOlderWebAppIsPreserved() {
        var seen = new ArrayList<URI>();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse kugouApi(URI uri, int max, SessionCookie session, byte[] body) {
                seen.add(uri); return response("", PROFILE.replace("a_id=1014", "a_id=1010"));
            }
        };
        var session = new KugouAccountProvider(http, CLOCK).validate(cookie("KugooID=123;t=fixture;a_id=1010"));
        assertEquals("1010", session.cookie().value("appid"));
        assertTrue(seen.get(0).getQuery().contains("a_id=1010")); assertTrue(seen.get(0).getQuery().contains("ct=1791590400"));
    }
    @Test void invalidTokenCannotBeAcceptedEvenWithApparentlyValidResponseCookies() {
        for (int code : List.of(20017, 20018)) {
            var error = assertThrows(MusicException.class, () -> validate(response("var error_code =" + code + ";\n", PROFILE)));
            assertEquals(MusicError.AUTH_EXPIRED, error.error());
        }
        assertEquals(MusicError.UNAVAILABLE, assertThrows(MusicException.class, () -> validate(response("var error_code=99999;", PROFILE))).error());
    }
    @Test void rejectsUnconfirmedIdentityMissingCredentialsAndConflictingProfiles() {
        for (String bad : List.of(PROFILE.replace("KugooID=123", "KugooID=456"), PROFILE.replace("&t=renewed-token", ""),
                PROFILE.replace("NickName=%u6D4B%u8BD5%2BListener", "NickName="), PROFILE.replace("a_id=1014", "a_id=1005")))
            invalid(response("", bad));
        invalid(response("")); invalid(response("", "KuGoo=; Max-Age=0"));
        invalid(response("", PROFILE, PROFILE.replace("renewed-token", "another-token")));
        assertEquals("123", validate(response("", PROFILE, PROFILE)).userId());
    }
    @Test void neverEvaluatesScriptOrTreatsHtmlAsSuccessfulLogin() {
        for (String body : List.of("<html>Sign in</html>", "document.cookie='" + PROFILE + "';", "var error_code=0;alert('fixture');", "{}"))
            invalid(response(body, PROFILE));
        invalid(response(" ".repeat(65537), PROFILE));
        invalid(new HttpTransport.CookieResponse(new byte[0], List.of(PROFILE), "https://evil.test/"));
        assertEquals("123", validate(response("var error_code=0;", PROFILE)).userId());
    }
    @Test void decodesDisplayTextAsDataAndFiltersAvatarHosts() {
        String profile = PROFILE.replace("%u6D4B%u8BD5%2BListener", "%E6%B5%8B%E8%AF%95%u0025+%27%29%3Bfixture%28%27");
        assertEquals("测试%+');fixture('", validate(response("", profile)).nickname());
        assertNull(validate(response("", PROFILE.replace("imge.kugou.com", "evil.test"))).avatar());
        assertEquals(URI.create("https://imge.kugou.com/kugouicon/165/20261010/20261010fixture.jpg"),
                validate(response("", PROFILE.replace("https%3A%2F%2Fimge.kugou.com%2Favatar.jpg", "20261010fixture.jpg"))).avatar());
        invalid(response("", PROFILE.replace("%u6D4B%u8BD5%2BListener", "%zz")));
    }
}
