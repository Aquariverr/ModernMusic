package dev.modernmusic.provider.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local request-contract fixture only: no network and no real SMS recipient. */
class NeteaseSmsProviderTest {
    private static final PhoneLogin PHONE = new PhoneLogin("1", "2025550123");
    private static final class Transport implements HttpTransport {
        final List<String> paths = new ArrayList<>();
        final List<JsonObject> payloads = new ArrayList<>();
        String sendResponse = "{\"code\":200,\"data\":true}";
        String loginResponse = "{\"code\":200}";
        boolean includeCookie = true;
        public byte[] get(URI uri, int max) { throw new AssertionError("Account GET must not be used"); }
        public CookieResponse postAccount(URI uri, int max, String cookieHeader, byte[] form) {
            assertEquals("https", uri.getScheme()); assertEquals("music.163.com", uri.getHost()); assertNull(uri.getQuery());
            paths.add(uri.getPath()); payloads.add(JsonParser.parseString(new String(form, StandardCharsets.UTF_8)).getAsJsonObject());
            if (uri.getPath().endsWith("/captcha/sent")) return response(sendResponse, List.of("__csrf=sms-csrf; Path=/", "NMTID=sms-session; Domain=.music.163.com; Path=/"));
            if (uri.getPath().endsWith("/login/cellphone")) {
                assertEquals("/weapi/login/cellphone", uri.getPath());
                assertTrue(cookieHeader.contains("__csrf=sms-csrf"));
                assertTrue(cookieHeader.contains("NMTID=sms-session"));
                return response(loginResponse, includeCookie ? List.of("MUSIC_U=fixture; Path=/") : List.of());
            }
            assertEquals("/weapi/w/nuser/account/get", uri.getPath());
            assertTrue(cookieHeader.contains("MUSIC_U=fixture"));
            assertTrue(cookieHeader.contains("NMTID=sms-session"));
            return response("{\"code\":200,\"account\":{\"id\":42},\"profile\":{\"userId\":42,\"nickname\":\"Test\"}}", List.of());
        }
        private CookieResponse response(String body, List<String> cookies) { return new CookieResponse(body.getBytes(StandardCharsets.UTF_8), cookies); }
    }
    private NeteaseAccountProvider provider(Transport http) {
        // Capture the pre-envelope JSON here; WeapiCodec has separate encryption tests and an anonymous network probe.
        return new NeteaseAccountProvider(http, Clock.systemUTC(), json -> json.getBytes(StandardCharsets.UTF_8));
    }
    @Test void mapsPhoneFieldsUsesCaptchaNotPasswordAndValidatesReturnedSession() {
        Transport http = new Transport(); var provider = provider(http);
        provider.sendSms(PHONE); var session = provider.loginSms(PHONE, "123456");
        assertEquals(List.of("/weapi/sms/captcha/sent", "/weapi/login/cellphone", "/weapi/w/nuser/account/get"), http.paths);
        JsonObject send = http.payloads.get(0), login = http.payloads.get(1);
        assertEquals("1", send.get("ctcode").getAsString()); assertEquals("2025550123", send.get("cellphone").getAsString());
        assertFalse(send.has("secrete")); // The web sender must not request a PC middle-user code.
        assertEquals("2025550123", login.get("phone").getAsString()); assertEquals("1", login.get("countrycode").getAsString());
        assertEquals("123456", login.get("captcha").getAsString()); assertFalse(login.has("password"));
        assertTrue(login.get("rememberLogin").getAsBoolean());
        assertFalse(login.has("secureCaptcha")); assertFalse(login.has("remember")); assertFalse(login.has("checkToken"));
        assertEquals("sms-csrf", login.get("csrf_token").getAsString()); assertEquals("42", session.userId());
        assertTrue(session.cookie().header().contains("NMTID=sms-session"));
    }
    @Test void failedSendFalseSuccessAndRiskResponseAreNotReportedAsSent() {
        for (String response : List.of("{\"code\":200,\"data\":false}", "{\"code\":400}", "{\"code\":415,\"verifyUrl\":\"https://untrusted.invalid\"}")) {
            Transport http = new Transport(); http.sendResponse = response;
            assertThrows(AuthException.class, () -> provider(http).sendSms(PHONE)); assertEquals(1, http.paths.size());
        }
    }
    @Test void wrongCodeAndMissingCookieCannotInstallSession() {
        Transport http = new Transport(); var provider = provider(http); provider.sendSms(PHONE);
        http.loginResponse = "{\"code\":503,\"message\":\"private-phone-code\"}";
        var error = assertThrows(AuthException.class, () -> provider.loginSms(PHONE, "123456"));
        assertEquals("modernmusic.auth.sms_login_failed", error.translationKey()); assertFalse(error.toString().contains("private-phone-code"));
        http.loginResponse = "{\"code\":200}"; http.includeCookie = false;
        assertEquals("modernmusic.auth.sms_missing_cookie", assertThrows(AuthException.class, () -> provider.loginSms(PHONE, "123456")).translationKey());
        assertFalse(http.paths.contains("/weapi/w/nuser/account/get"));
    }
    @Test void smsRateLimitingIsExplicitAndDoesNotRetry() {
        Transport http = new Transport(); http.sendResponse = "{\"code\":429}";
        assertEquals(MusicError.RATE_LIMITED, assertThrows(MusicException.class, () -> provider(http).sendSms(PHONE)).error());
        assertEquals(1, http.paths.size());
    }
    @Test void actual10004FailureIsExplicitAndNeverRetriedOrTreatedAsBadCode() {
        Transport http = new Transport(); var provider = provider(http); provider.sendSms(PHONE);
        http.loginResponse = "{\"code\":10004,\"hitType\":4,\"message\":\"private details\",\"redirectUrl\":\"https://untrusted.invalid/private\"}";
        var error = assertThrows(AuthException.class, () -> provider.loginSms(PHONE, "123456"));
        assertEquals("modernmusic.auth.login_rejected_10004", error.translationKey());
        assertFalse(error.toString().contains("private")); assertEquals(2, http.paths.size());
        assertFalse(http.paths.contains("/weapi/w/nuser/account/get"));
    }
    @Test void verificationRedirectCannotBeIgnoredEvenWhenCodeIs200() {
        Transport http = new Transport(); var provider = provider(http); provider.sendSms(PHONE);
        http.loginResponse = "{\"code\":200,\"redirectUrl\":\"https://untrusted.invalid\"}";
        assertEquals("modernmusic.auth.verification_required", assertThrows(AuthException.class,
                () -> provider.loginSms(PHONE, "123456")).translationKey());
        assertEquals(2, http.paths.size());
    }
}
