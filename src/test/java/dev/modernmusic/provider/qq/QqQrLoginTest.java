package dev.modernmusic.provider.qq;

import com.google.gson.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.modernmusic.auth.AccountProvider.*;

class QqQrLoginTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
    static final String CONTENT = "https://example.test/qr-fixture";
    static class Http implements HttpTransport {
        String qqStatus = "66", wxStatus = "408", wxCode = "", musicData = "{\"musicid\":123,\"musickey\":\"fixture_music_key\"}";
        String callback = "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=123&service=ptqrlogin&ptsigx=fixture_sig&s_url=fixture&pt_login_type=4&server_parameter=preserve%2Bme";
        String location = "https://y.qq.com/portal/wx_redirect.html?code=fixture_code&state=state";
        List<String> loginCookies = List.of("p_skey=fixture_skey; Domain=.graph.qq.com; Path=/; Secure",
                "p_uin=o000123; Domain=.graph.qq.com; Path=/; Secure", "pt4_token=\"fixture==\"; Domain=.graph.qq.com; Path=/; Secure",
                "pt_oauth_token=fixture_oauth@padded==; Domain=.graph.qq.com; Path=/; Secure", "pt_login_type=3; Domain=.qq.com; Path=/; Secure",
                "p_skey=; Domain=.qq.com; Path=/; Max-Age=0", "p_uin=; Domain=.qq.com; Path=/; Max-Age=0",
                "pt4_token=; Domain=.qq.com; Path=/; Max-Age=0",
                "unrelated=discard");
        boolean missingSkey, completedLogin; int requests, exchanges; JsonObject exchange;
        public byte[] get(URI uri, int max) { throw new AssertionError("Unconfined request"); }
        public CookieResponse qqLogin(URI uri, int max, String cookies, byte[] form) {
            requests++;
            return switch (uri.getPath()) {
                case "/cgi-bin/xlogin" -> {
                    assertEquals("", cookies); assertNull(form);
                    yield new CookieResponse("ptui_version:encodeURIComponent(\"26100915\")".getBytes(StandardCharsets.UTF_8),
                            List.of("pt_login_sig=fixture_login_sig; Domain=.ptlogin2.qq.com; Path=/; Secure"));
                }
                case "/ssl/ptqrshow" -> {
                    assertEquals("pt_login_sig=fixture_login_sig", cookies); assertNull(form);
                    assertTrue(uri.getQuery().contains("u1=https://graph.qq.com/oauth2.0/login_jump"));
                    yield new CookieResponse(qrImage(), List.of("qrsig=fixture_qrsig; Path=/; Secure; HttpOnly"));
                }
                case "/connect/qrconnect" -> {
                    assertTrue(uri.getQuery().contains("appid=wx48db31d50e334801")); assertEquals("", cookies);
                    yield response("<img src=\"/connect/qrcode/fixture_uuid\"/>");
                }
                case "/connect/qrcode/fixture_uuid" -> new CookieResponse(qrImage(), List.of());
                case "/ssl/ptqrlogin" -> {
                    assertEquals("qrsig=fixture_qrsig; pt_login_sig=fixture_login_sig", cookies);
                    assertTrue(uri.getQuery().contains("login_sig=fixture_login_sig")); assertTrue(uri.getQuery().contains("js_ver=26100915"));
                    assertTrue(uri.getQuery().contains("ptqrtoken=" + QqQrLogin.hash("fixture_qrsig", 0)));
                    var callbackBody = response("ptuiCB('" + qqStatus + "','0','" + callback + "','0','status','nickname');").body();
                    yield new CookieResponse(callbackBody, qqStatus.equals("0") ? List.of(
                            "uin=o000123; Domain=.qq.com; Path=/; Secure", "skey=@fixture+skey=; Domain=.qq.com; Path=/; Secure",
                            "ptcz=fixture_ptcz; Domain=.qq.com; Path=/; Secure", "RK=fixture_rk; Domain=.qq.com; Path=/; Secure",
                            "qrsig=never_forward; Domain=.qq.com; Path=/") : List.of());
                }
                case "/connect/l/qrconnect" -> {
                    assertEquals("", cookies); assertTrue(uri.getQuery().contains("uuid=fixture_uuid"));
                    yield response("window.wx_errcode=" + wxStatus + ";window.wx_code='" + wxCode + "';");
                }
                case "/check_sig" -> {
                    assertEquals("", cookies); assertTrue(uri.getQuery().contains("ptsigx=fixture_sig"));
                    assertEquals(callback, uri.toASCIIString());
                    yield new CookieResponse(new byte[0], missingSkey ? List.of() : loginCookies,
                            "https://graph.qq.com/oauth2.0/login_jump");
                }
                case "/oauth2.0/login_jump" -> {
                    assertTrue(cookies.contains("p_skey=fixture_skey")); assertFalse(cookies.contains("qrsig")); assertNull(form);
                    assertTrue(cookies.contains("pt_oauth_token=fixture_oauth@padded==")); assertTrue(cookies.contains("pt_login_type=3"));
                    completedLogin = true;
                    yield new CookieResponse(new byte[0], List.of("uin=o000123; Domain=.graph.qq.com; Path=/; Secure"));
                }
                case "/oauth2.0/authorize" -> {
                    assertTrue(completedLogin);
                    assertTrue(cookies.contains("p_skey=fixture_skey")); assertFalse(cookies.contains("qrsig")); assertFalse(cookies.contains("unrelated"));
                    assertTrue(cookies.contains("skey=@fixture+skey=")); assertTrue(cookies.contains("pt4_token=fixture=="));
                    assertTrue(cookies.contains("uin=o000123")); assertTrue(cookies.contains("ptcz=fixture_ptcz"));
                    assertTrue(cookies.contains("pt_oauth_token=fixture_oauth@padded==")); assertTrue(cookies.contains("pt_login_type=3"));
                    assertTrue(cookies.contains("RK=fixture_rk"));
                    assertNotNull(form); assertTrue(new String(form, StandardCharsets.UTF_8).contains("client_id=100497308"));
                    yield new CookieResponse(new byte[0], List.of(), location);
                }
                default -> throw new AssertionError("Unexpected route");
            };
        }
        public CookieResponse qqApi(URI uri, int max, SessionCookie cookie, byte[] json) {
            if (uri.getPath().endsWith("fcg_get_profile_homepage.fcg")) {
                assertNotNull(cookie); assertNull(json);
                assertTrue(uri.getQuery().contains("userid=" + cookie.qqUin()));
                return response("{\"code\":0,\"data\":{\"creator\":{\"uin\":0,\"nick\":\"QR listener\",\"headpic\":\"\"}}}");
            }
            assertNull(cookie); exchanges++;
            exchange = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            return response("{\"code\":0,\"req_0\":{\"code\":0,\"data\":" + musicData + "}}");
        }
    }
    static HttpTransport.CookieResponse response(String text) { return new HttpTransport.CookieResponse(text.getBytes(StandardCharsets.UTF_8), List.of()); }
    static byte[] qrImage() {
        try {
            var bits = new QRCodeWriter().encode(CONTENT, BarcodeFormat.QR_CODE, 220, 220);
            var image = new java.awt.image.BufferedImage(220, 220, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 220; y++) for (int x = 0; x < 220; x++) image.setRGB(x, y, bits.get(x, y) ? 0 : 0xffffff);
            var bytes = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (Exception error) { throw new AssertionError(error); }
    }
    @Test void qqQrPreservesImagePayloadAndMapsAllTerminalStates() {
        var http = new Http(); var provider = new QqAccountProvider(http, CLOCK); var challenge = provider.beginQr();
        assertEquals(CONTENT, challenge.content()); assertEquals(QrMethod.QQ, challenge.method());
        assertEquals(CLOCK.instant().plusSeconds(120), challenge.expiresAt()); assertFalse(challenge.toString().contains("fixture"));
        String[] codes = {"66", "67", "65", "68"}; QrState[] states = {QrState.WAITING, QrState.CONFIRM, QrState.EXPIRED, QrState.CANCELLED};
        for (int i = 0; i < codes.length; i++) { http.qqStatus = codes[i]; assertEquals(states[i], provider.pollQr(challenge).state()); }
        assertEquals(0, http.exchanges);
        http.qqStatus = "999"; assertThrows(AuthException.class, () -> provider.pollQr(challenge));
    }
    @Test void qqAuthorizationExchangesCodeForMusicCredentials() {
        var http = new Http(); http.qqStatus = "0"; var provider = new QqAccountProvider(http, CLOCK);
        http.location += "#";
        var result = provider.pollQr(provider.beginQr());
        assertEquals(QrState.AUTHORIZED, result.state()); assertEquals("123", result.cookie().qqUin()); assertEquals("1", result.cookie().value("login_type"));
        assertEquals("fixture_music_key", result.cookie().value("qqmusic_key"));
        assertEquals("QQConnectLogin.LoginServer", http.exchange.getAsJsonObject("req_0").get("module").getAsString());
        assertEquals(2, http.exchange.getAsJsonObject("comm").get("tmeLoginType").getAsInt());
        var session = provider.validate(result.cookie());
        assertEquals("QR listener", session.nickname()); assertEquals("123", session.userId());
    }
    @Test void wechatStatusAndAuthorizationUseIndependentLoginType() {
        var http = new Http(); var provider = new QqAccountProvider(http, CLOCK); var qr = provider.beginQr(QrMethod.WECHAT);
        assertEquals(CONTENT, qr.content()); assertEquals(QrMethod.WECHAT, qr.method());
        String[] codes = {"408", "404", "402", "403"}; QrState[] states = {QrState.WAITING, QrState.CONFIRM, QrState.EXPIRED, QrState.CANCELLED};
        for (int i = 0; i < codes.length; i++) { http.wxStatus = codes[i]; assertEquals(states[i], provider.pollQr(qr).state()); }
        http.wxStatus = "405"; assertThrows(AuthException.class, () -> provider.pollQr(qr));
        http.wxCode = "fixture_wx_code";
        var result = provider.pollQr(qr); assertEquals(QrState.AUTHORIZED, result.state()); assertEquals("123", result.cookie().qqUin());
        assertEquals("123", result.cookie().value("wxuin")); assertEquals("2", result.cookie().value("login_type"));
        assertEquals(1, http.exchange.getAsJsonObject("comm").get("tmeLoginType").getAsInt());
        assertEquals("music.login.LoginServer", http.exchange.getAsJsonObject("req_0").get("module").getAsString());
        assertEquals("wx48db31d50e334801", http.exchange.getAsJsonObject("req_0").getAsJsonObject("param").get("strAppid").getAsString());
        var session = provider.validate(result.cookie());
        assertEquals("QR listener", session.nickname()); assertEquals("123", session.userId());
    }
    @Test void redirectsCannotSendCredentialsToUnrelatedOrigins() {
        for (String callback : List.of("https://evil.test/check_sig?uin=123&ptsigx=fixture", "https://ssl.ptlogin2.graph.qq.com:443/check_sig?uin=123&ptsigx=fixture")) {
            var http = new Http(); http.qqStatus = "0"; http.callback = callback; var provider = new QqAccountProvider(http, CLOCK);
            var qr = provider.beginQr(); assertThrows(AuthException.class, () -> provider.pollQr(qr)); assertEquals(3, http.requests);
        }
        var http = new Http(); http.qqStatus = "0"; http.location = "https://evil.test/portal/wx_redirect.html?code=fixture";
        var provider = new QqAccountProvider(http, CLOCK); var qr = provider.beginQr();
        assertThrows(AuthException.class, () -> provider.pollQr(qr)); assertEquals(0, http.exchanges);
    }
    @Test void missingAndMalformedCredentialsNeverAuthorize() {
        var http = new Http(); http.qqStatus = "0"; http.missingSkey = true;
        var provider = new QqAccountProvider(http, CLOCK); var qr = provider.beginQr();
        assertThrows(AuthException.class, () -> provider.pollQr(qr)); assertEquals(0, http.exchanges);
        http.missingSkey = false;
        for (String data : List.of("{}", "{\"musicid\":123}", "{\"musicid\":123,\"musickey\":\"key;uin=456\"}")) {
            http.musicData = data; assertThrows(AuthException.class, () -> provider.pollQr(qr));
        }
    }
    @Test void expiredChallengeAndInvalidImageNeverStartAuthorization() {
        var http = new Http(); var provider = new QqAccountProvider(http, CLOCK);
        assertEquals(QrState.EXPIRED, provider.pollQr(new QrChallenge("fixture_qrsig", CLOCK.instant(), CONTENT, QrMethod.QQ)).state());
        assertEquals(0, http.requests);
        assertThrows(AuthException.class, () -> QrImage.content(new byte[]{1, 2, 3}));
        assertThrows(AuthException.class, () -> QrImage.content(new byte[1024 * 1024 + 1]));
        assertThrows(AuthException.class, () -> provider.beginQr(QrMethod.APP));
    }
    @Test void musicCredentialsPreserveOpaqueTokensAndExactStringIdentity() {
        var http = new Http(); http.wxStatus = "405"; http.wxCode = "fixture_code";
        http.musicData = "{\"musicid\":9007199254740992,\"str_musicid\":\"9007199254740993\",\"musickey\":\"W_X_fixture@padded==\"}";
        var provider = new QqAccountProvider(http, CLOCK); var result = provider.pollQr(provider.beginQr(QrMethod.WECHAT));
        assertEquals("9007199254740993", result.cookie().qqUin());
        assertEquals("W_X_fixture@padded==", result.cookie().value("qqmusic_key"));
    }
    @Test void deletedOrInapplicableLoginCookiesNeverAuthorize() {
        for (List<String> cookies : List.of(
                List.of("p_skey=fixture_skey; Domain=.graph.qq.com; Path=/", "p_skey=; Domain=graph.qq.com; Path=/; Max-Age=0"),
                List.of("p_skey=fixture_skey; Domain=.graph.qq.com; Path=/", "p_skey=deleted; Domain=.graph.qq.com; Path=/; Max-Age=0"),
                List.of("p_skey=fixture_skey; Path=/"),
                List.of("p_skey=fixture_skey; Domain=.qq.com; Path=/unrelated"),
                List.of("p_skey=fixture_skey; Domain=evil.test; Path=/"))) {
            var http = new Http(); http.qqStatus = "0"; http.loginCookies = cookies;
            var provider = new QqAccountProvider(http, CLOCK); var qr = provider.beginQr();
            var error = assertThrows(AuthException.class, () -> provider.pollQr(qr));
            assertEquals("modernmusic.auth.qr_missing_cookie", error.translationKey()); assertEquals(0, http.exchanges);
        }
    }
}
