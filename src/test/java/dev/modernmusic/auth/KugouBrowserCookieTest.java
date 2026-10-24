package dev.modernmusic.auth;

import com.google.gson.*;
import dev.modernmusic.domain.MusicPlatform;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class KugouBrowserCookieTest {
    private static final String TOKEN = "a".repeat(64);
    private static final String PACKED = "KugooID=123456789&KugooPwd=ignored-password-digest&NickName=%u6D4B%u8BD5"
            + "&Pic=https%3A%2F%2Fimge.kugou.com%2Fignored.jpg&RegState=1&RegFrom=&t=" + TOKEN
            + "&a_id=1014&ct=1791608000&UserName=ignored-user&t1=";
    private static SessionCookie parse(String input) { return SessionCookie.parse(input, MusicPlatform.KUGOU); }

    @Test void matchesOfficialBrowserExportIncludingPackedAndSeparateAliases() {
        var cookies = new JsonArray();
        add(cookies, "KugooID", "123456789"); add(cookies, "t", TOKEN); add(cookies, "a_id", "1014");
        add(cookies, "mid", "device"); add(cookies, "kg_mid", "device");
        add(cookies, "dfid", "fingerprint"); add(cookies, "kg_dfid", "fingerprint");
        add(cookies, "KuGoo", PACKED); add(cookies, "UserName", "ignored-user");
        var cookie = parse(cookies.toString());
        assertEquals("123456789", cookie.value("userid")); assertEquals(TOKEN, cookie.value("token"));
        assertEquals("1014", cookie.value("appid")); assertEquals("device", cookie.value("mid"));
        assertEquals("fingerprint", cookie.value("dfid"));
        assertEquals("1791608000", cookie.value("ct"));
        assertFalse(cookie.header().contains("ignored")); assertFalse(cookie.header().contains("KuGoo"));
        assertFalse(cookie.toString().contains(TOKEN));
        assertEquals(cookie.header(), parse(cookie.header()).header());
    }
    @Test void packedCookieAloneWorksInJsonAndOriginalCookieHeader() {
        var cookies = new JsonArray(); add(cookies, "KuGoo", PACKED);
        var cookie = parse(cookies.toString());
        assertEquals("123456789", cookie.value("userid")); assertEquals(TOKEN, cookie.value("token"));
        assertEquals("1014", cookie.value("appid"));
        assertEquals(cookie.header(), parse("Cookie: KuGoo=" + PACKED + "; KugooID=123456789; t=" + TOKEN + "; a_id=1014").header());
        var object = new JsonObject(); object.addProperty("KuGoo", PACKED);
        assertEquals(cookie.header(), parse(object.toString()).header());
        object.addProperty("KuGoo", URLEncoder.encode(PACKED, StandardCharsets.UTF_8));
        assertEquals(cookie.header(), parse(object.toString()).header());
    }
    @Test void websiteTokenDefaultsToWebsiteAppButExplicitOriginWins() {
        assertEquals("1014", parse("KugooID=123; t=fixture").value("appid"));
        assertEquals("", parse("userid=123; token=fixture").value("appid"));
        assertEquals("", parse("userid=123; token=fixture; KuGoo=").value("appid"));
        assertEquals("", parse("{\"userid\":\"123\",\"token\":\"fixture\",\"KuGoo\":\"\"}").value("appid"));
        assertEquals("1010", parse("KugooID=123; t=fixture; a_id=1010").value("appid"));
        assertEquals("1010", parse("{\"t\":\"fixture\",\"KugooID\":\"123\",\"a_id\":\"1010\"}").value("appid"));
        assertEquals("1010", parse("{\"a_id\":\"1010\",\"KugooID\":\"123\",\"t\":\"fixture\"}").value("appid"));
        assertEquals("1014", parse("{\"KugooID\":\"123\",\"t\":\"fixture\"}").value("appid"));
    }
    @Test void unrelatedOrExpiredPackedCookieCannotInjectCredentials() {
        var cookies = new JsonArray(); add(cookies, "userid", "123"); add(cookies, "token", "fixture");
        var foreign = add(cookies, "KuGoo", PACKED); foreign.addProperty("domain", "example.com");
        var expired = add(cookies, "KuGoo", PACKED); expired.addProperty("expirationDate", 1);
        assertEquals("userid=123; token=fixture", parse(cookies.toString()).header());
    }
    @ParameterizedTest @ValueSource(strings = {
            "KuGoo=KugooID=123&t=one&t=two&a_id=1014", "userid=123; KugooID=456; t=fixture",
            "KuGoo=KugooID=123&t=fixture&a_id=1014; t=other", "KugooID=123; t=fixture; a_id=9999",
            "KuGoo=KugooID=123&t=bad%3Btoken", "KuGoo=KugooID=123&t=bad%0D%0Avalue", "KuGoo=KugooID=123&t=%zz",
            "KuGoo=NickName=unverified&KugooID=123&KugooPwd=not-a-token", "userid=123;token=fixture;ct=invalid",
            "KugooID=123;t=fixture;ct=1791608000999"
    })
    void rejectsConflictsAndUnsafeOrUnsupportedCredentials(String input) {
        var error = assertThrows(AuthException.class, () -> parse(input));
        assertEquals("modernmusic.auth.invalid_cookie", error.translationKey()); assertNull(error.getCause());
    }
    private static JsonObject add(JsonArray entries, String name, String value) {
        var entry = new JsonObject(); entry.addProperty("domain", ".kugou.com"); entry.addProperty("path", "/");
        entry.addProperty("expirationDate", 4102444800L); entry.addProperty("name", name); entry.addProperty("value", value);
        entries.add(entry); return entry;
    }
}
