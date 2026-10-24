package dev.modernmusic.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.modernmusic.domain.MusicPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class BrowserCookieImportTest {
    private static final String EXPORT = """
            [
              {"domain":".music.163.com","path":"/","name":"MUSIC_U","value":"netease-fixture==","session":true,"httpOnly":true},
              {"domain":".music.163.com","path":"/","name":"__csrf","value":"csrf-fixture","expirationDate":4102444800.5},
              {"domain":".music.163.com","path":"/","name":"NMTID","value":"playback-fixture"},
              {"domain":".y.qq.com","path":"/","name":"uin","value":"o00123","expires":-1},
              {"domain":".y.qq.com","path":"/","name":"qqmusic_key","value":"@qq-fixture=="},
              {"domain":".y.qq.com","path":"/","name":"psrf_qqopenid","value":"openid-fixture"},
              {"domain":".kugou.com","path":"/","name":"userid","value":"456"},
              {"domain":".kugou.com","path":"/","name":"token","value":"kugou-fixture"},
              {"domain":".kugou.com","path":"/","name":"KUGOU_API_MID","value":"device-fixture"},
              {"domain":".kugou.com","path":"/","name":"dfid","value":"dfid-fixture"},
              {"domain":".example.com","name":"MUSIC_U","value":"other-site-secret"},
              {"domain":".example.com","name":"token","value":"other-site-secret"},
              {"domain":".example.com","name":"uin","value":"999"},
              {"domain":".y.qq.com","name":"analytics","value":"中文; metadata, ignored"}
            ]
            """;

    @ParameterizedTest @EnumSource(value = MusicPlatform.class, names = "LOCAL", mode = EnumSource.Mode.EXCLUDE)
    void importsOnlySelectedPlatformFromBrowserArrayAndRoundTrips(MusicPlatform platform) {
        var cookie = SessionCookie.parse(EXPORT, platform);
        assertEquals(platform, cookie.platform());
        assertFalse(cookie.header().contains("other-site-secret"));
        assertFalse(cookie.header().contains("analytics"));
        switch (platform) {
            case NETEASE -> assertEquals("MUSIC_U=netease-fixture==; __csrf=csrf-fixture; NMTID=playback-fixture", cookie.header());
            case QQ -> assertEquals("uin=123; qqmusic_key=@qq-fixture==; psrf_qqopenid=openid-fixture", cookie.header());
            case KUGOU -> assertEquals("userid=456; token=kugou-fixture; mid=device-fixture; dfid=dfid-fixture", cookie.header());
        }
        assertEquals(cookie.header(), SessionCookie.parse(cookie.header(), platform).header());
        assertFalse(cookie.toString().contains("fixture"));
    }

    @ParameterizedTest @EnumSource(value = MusicPlatform.class, names = "LOCAL", mode = EnumSource.Mode.EXCLUDE)
    void acceptsStorageStateWrapperAndBom(MusicPlatform platform) {
        assertEquals(SessionCookie.parse(EXPORT, platform).header(), SessionCookie.parse(" \n\uFEFF {\"cookies\":" + EXPORT
                + ",\"origins\":[{\"origin\":\"https://example.com\",\"localStorage\":[{\"name\":\"token\",\"value\":\"ignored\"}]}]}", platform).header());
    }

    @Test void acceptsMapsAndWechatLongIdWithoutNumericConversion() {
        var cookie = SessionCookie.parse("""
                {"cookies":{"uin":"123","wxuin":"1152921504606846999","login_type":"2",
                "qm_keyst":"@wechat-fixture==","wxopenid":"wx-fixture","unrelated":"中文"}}
                """, MusicPlatform.QQ);
        assertEquals("1152921504606846999", cookie.qqUin());
        assertEquals("@wechat-fixture==", cookie.value("qm_keyst"));
        assertEquals("2", cookie.value("login_type"));
        assertEquals("wx-fixture", cookie.value("wxopenid"));
        assertFalse(cookie.header().contains("unrelated"));
        assertEquals("MUSIC_U=fixture", SessionCookie.parse("{\"MUSIC_U\":\"fixture\"}").header());
        assertEquals("userid=42; token=fixture; mid=device", SessionCookie.parse(
                "{\"userid\":\"42\",\"token\":\"fixture\",\"KUGOU_API_MID\":\"device\"}", MusicPlatform.KUGOU).header());
    }

    @Test void moreSpecificMusicSiteWinsIndependentlyOfExportOrder() {
        var entries = new JsonArray();
        entries.add(entry(".qq.com", "uin", "999"));
        entries.add(entry(".qq.com", "uin", "888")); // A lower-priority conflict must not mask a clear music cookie.
        entries.add(entry(".Y.QQ.COM", "uin", "123"));
        entries.add(entry("qzone.qq.com", "uin", "777"));
        entries.add(entry(".y.qq.com", "qqmusic_key", "fixture"));
        assertEquals("123", SessionCookie.parse(entries.toString(), MusicPlatform.QQ).qqUin());
        var reversed = new JsonArray();
        for (int i = entries.size() - 1; i >= 0; i--) reversed.add(entries.get(i));
        assertEquals("123", SessionCookie.parse(reversed.toString(), MusicPlatform.QQ).qqUin());
    }
    @Test void infersWechatOnlyWhenNoQqIdentityOrExplicitLoginTypeExists() {
        var wechat = SessionCookie.parse("{\"wxuin\":\"1152921504606846999\",\"qm_keyst\":\"fixture\"}", MusicPlatform.QQ);
        assertEquals("1152921504606846999", wechat.qqUin()); assertEquals("2", wechat.value("login_type"));
        var qq = SessionCookie.parse("{\"qqmusic_uin\":\"123\",\"wxuin\":\"456\",\"qqmusic_key\":\"fixture\"}", MusicPlatform.QQ);
        assertEquals("123", qq.qqUin()); assertEquals("", qq.value("login_type"));
        assertInvalid("{\"wxuin\":\"456\",\"qqmusic_key\":\"fixture\",\"login_type\":\"1\"}", MusicPlatform.QQ);
    }

    @Test void deduplicatesIdenticalValuesAndCanonicalDeviceAliases() {
        assertEquals("MUSIC_U=fixture", SessionCookie.parse("""
                [{"name":"MUSIC_U","value":"fixture","domain":".music.163.com"},
                 {"name":"MUSIC_U","value":"fixture","domain":"music.163.com"}]
                """).header());
        assertEquals("device", SessionCookie.parse("""
                {"userid":"42","token":"fixture","KUGOU_API_MID":"device","mid":"device"}
                """, MusicPlatform.KUGOU).value("mid"));
    }

    @Test void skipsExpiredDeletedAndNarrowPathCookies() {
        var entries = new JsonArray();
        entries.add(entry(".music.163.com", "MUSIC_U", "fixture"));
        var expired = entry(".music.163.com", "MUSIC_U", "old");
        expired.addProperty("expirationDate", 1); entries.add(expired);
        var expiredAlternative = entry(".music.163.com", "MUSIC_U", "old-again");
        expiredAlternative.addProperty("expires", 0); entries.add(expiredAlternative);
        var narrow = entry(".music.163.com", "MUSIC_U", "other-path");
        narrow.addProperty("path", "/other"); entries.add(narrow);
        entries.add(entry(".music.163.com", "__csrf", ""));
        assertEquals("MUSIC_U=fixture", SessionCookie.parse(entries.toString()).header());
        assertEquals("MUSIC_U=fixture", SessionCookie.parse("""
                [{"name":"MUSIC_U","value":"fixture","session":true,"expirationDate":0}]
                """).header());
    }

    @ParameterizedTest @ValueSource(strings = {"music.163.com.evil.test", "evil-music.163.com", "mail.163.com", "example.com", "https://music.163.com", "..music.163.com"})
    void ignoresCookiesFromUnrelatedOrLookalikeDomains(String domain) {
        var entries = new JsonArray(); entries.add(entry(domain, "MUSIC_U", "wrong-site"));
        assertInvalid(entries.toString(), MusicPlatform.NETEASE);
    }

    @ParameterizedTest @ValueSource(strings = {
            "[]", "{}", "{\"cookies\":null}", "{\"cookies\":\"MUSIC_U=secret\"}",
            "[{\"name\":\"MUSIC_U\",\"value\":\"secret\",\"expirationDate\":1}]",
            "[{\"name\":\"MUSIC_U\",\"value\":\"secret\"},{\"name\":\"MUSIC_U\",\"value\":\"conflict\"}]",
            "{\"MUSIC_U\":\"first-secret\",\"MUSIC_U\":\"last-secret\"}",
            "{\"MUSIC_U\":123}", "{\"MUSIC_U\":null}", "{\"MUSIC_U\":true}", "{\"MUSIC_U\":{\"value\":\"secret\"}}",
            "{\"MUSIC_U\":\"secret; __csrf=injected\"}", "{\"MUSIC_U\":\"secret\\r\\nInjected: value\"}",
            "{\"MUSIC_U\":\" secret\"}", "{\"MUSIC_U\":\"中文\"}",
            "{MUSIC_U:'secret'}", "{\"MUSIC_U\":\"secret\",}", "{\"MUSIC_U\":\"secret\"} trailing",
            "{\"MUSIC_U\":\"secret\"}{}", "[{\"name\":\"MUSIC_U\",\"value\":\"secret\"},]",
            "{\"MUSIC_U\":\"secret\",/*comment*/\"x\":1}", "[{\"name\":\"MUSIC_U\",\"value\":\"secret\""
    })
    void malformedConflictingOrUnsafeJsonHasOnlySanitizedErrors(String input) {
        assertInvalid(input, MusicPlatform.NETEASE);
    }

    @Test void rejectsAmbiguousQqAndKugouCredentials() {
        assertInvalid("[{\"name\":\"uin\",\"value\":\"123\"},{\"name\":\"uin\",\"value\":\"456\"},"
                + "{\"name\":\"qqmusic_key\",\"value\":\"fixture\"}]", MusicPlatform.QQ);
        assertInvalid("{\"userid\":\"42\",\"token\":\"fixture\",\"mid\":\"one\",\"KUGOU_API_MID\":\"two\"}", MusicPlatform.KUGOU);
        assertInvalid("{\"uin\":1234567890123456789,\"qqmusic_key\":\"fixture\"}", MusicPlatform.QQ);
    }

    @Test void acceptsLargeExportsButKeepsSeparateInputAndSessionBounds() {
        String large = "{\"metadata\":\"" + "无关".repeat(30_000) + "\",\"cookies\":[{\"name\":\"MUSIC_U\",\"value\":\"fixture\"}]}";
        assertTrue(large.length() > SessionCookie.MAX_LENGTH);
        assertEquals("MUSIC_U=fixture", SessionCookie.parse(large).header());
        assertInvalid("{\"MUSIC_U\":\"" + "x".repeat(SessionCookie.MAX_IMPORT_LENGTH) + "\"}", MusicPlatform.NETEASE);
        assertInvalid("{\"MUSIC_U\":\"" + "x".repeat(SessionCookie.MAX_LENGTH) + "\"}", MusicPlatform.NETEASE);
        assertInvalid("{\"MUSIC_U\":\"fixture\",\"metadata\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}", MusicPlatform.NETEASE);
        var invalidDomain = new JsonArray();
        invalidDomain.add(entry("a.".repeat(10_000) + "music.163.com", "MUSIC_U", "unrelated"));
        invalidDomain.add(entry("music.163.com", "MUSIC_U", "fixture"));
        assertEquals("MUSIC_U=fixture", SessionCookie.parse(invalidDomain.toString()).header());
    }

    private static JsonObject entry(String domain, String name, String value) {
        var cookie = new JsonObject();
        cookie.addProperty("domain", domain); cookie.addProperty("path", "/");
        cookie.addProperty("name", name); cookie.addProperty("value", value);
        return cookie;
    }
    private static void assertInvalid(String input, MusicPlatform platform) {
        var error = assertThrows(AuthException.class, () -> SessionCookie.parse(input, platform));
        assertEquals("modernmusic.auth.invalid_cookie", error.translationKey());
        assertEquals("Account operation failed", error.getMessage());
        assertNull(error.getCause());
    }
}
