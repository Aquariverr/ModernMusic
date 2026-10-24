package dev.modernmusic.provider.kugou;

import dev.modernmusic.auth.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static dev.modernmusic.auth.AccountProvider.*;
import static org.junit.jupiter.api.Assertions.*;

class KugouQrLoginTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
    @Test void appQrUsesSignedRequestsAndAuthorizesOnlyWithCredentials() {
        var http = new KugouMusicProviderTest.Http(); var provider = new KugouAccountProvider(http, CLOCK);
        http.response = "{\"status\":1,\"data\":{\"qrcode\":\"fixture_qrcode\"}}";
        var qr = provider.beginQr();
        assertEquals("https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=fixture_qrcode", qr.content());
        assertEquals("login-user.kugou.com", http.uri.getHost()); assertEquals("/v2/qrcode", http.uri.getPath());
        assertTrue(http.uri.getQuery().contains("appid=1001")); assertTrue(http.uri.getQuery().contains("signature="));
        assertNull(http.cookie); assertNull(http.body);
        QrState[] states = {QrState.EXPIRED, QrState.WAITING, QrState.CONFIRM};
        for (int i = 0; i < 3; i++) {
            http.response = "{\"status\":1,\"data\":{\"status\":" + i + "}}";
            assertEquals(states[i], provider.pollQr(qr).state());
        }
        http.response = "{\"status\":1,\"data\":{\"status\":4,\"userid\":123,\"token\":\"fixture_token\"}}";
        var result = provider.pollQr(qr); assertEquals(QrState.AUTHORIZED, result.state());
        assertEquals("fixture_token", result.cookie().value("token")); assertFalse(result.cookie().value("mid").isBlank());
        assertEquals("/v2/get_userinfo_qrcode", http.uri.getPath()); assertTrue(http.uri.getQuery().contains("appid=1005"));
        assertFalse(result.toString().contains("fixture")); assertFalse(qr.toString().contains("fixture"));
        http.response = "{\"status\":1,\"data\":{\"status\":4,\"userid\":123}}";
        assertThrows(AuthException.class, () -> provider.pollQr(qr));
        http.response = "{\"status\":1,\"data\":{\"status\":99}}";
        assertThrows(AuthException.class, () -> provider.pollQr(qr));
    }
    @Test void invalidKeysAndExpiredCodesCannotBePolled() {
        var http = new KugouMusicProviderTest.Http(); var provider = new KugouAccountProvider(http, CLOCK);
        http.response = "{\"status\":1,\"data\":{\"qrcode\":\"invalid&token=bad\"}}";
        assertThrows(AuthException.class, provider::beginQr);
        var uri = http.uri;
        assertEquals(QrState.EXPIRED, provider.pollQr(new QrChallenge("fixture_key", CLOCK.instant())).state());
        assertSame(uri, http.uri);
        assertThrows(AuthException.class, () -> provider.pollQr(new QrChallenge("invalid&token=bad", CLOCK.instant().plusSeconds(120))));
    }
}
