package dev.modernmusic.provider.netease;

import com.google.gson.*;
import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/** Direct first-party HTTPS endpoints; no account credentials are sent to an API proxy. */
public final class NeteaseAccountProvider implements AccountProvider {
    private final HttpTransport http;
    private final Clock clock;
    private final java.util.function.Function<String, byte[]> encoder;
    private String qrKey;
    private LoginCookies qrCookies;
    private LoginCookies smsCookies = new LoginCookies();
    private PhoneLogin smsPhone;
    private int lastQrCode = -1;
    public NeteaseAccountProvider(HttpTransport http, Clock clock) { this(http, clock, WeapiCodec::encode); }
    NeteaseAccountProvider(HttpTransport http, Clock clock, java.util.function.Function<String, byte[]> encoder) {
        this.http = http; this.clock = clock; this.encoder = encoder;
    }

    @Override public AccountSession validate(SessionCookie cookie) {
        LoginCookies cookies = new LoginCookies(cookie);
        JsonObject body = json(request("/weapi/w/nuser/account/get", new JsonObject(), cookies));
        org.slf4j.LoggerFactory.getLogger(NeteaseAccountProvider.class).info("Account stage=validate code={} hasAccount={} hasProfile={}",
                code(body), body.has("account") && !body.get("account").isJsonNull(), body.has("profile") && !body.get("profile").isJsonNull());
        check(code(body));
        try {
            if (!body.has("profile") || body.get("profile").isJsonNull() || !body.has("account") || body.get("account").isJsonNull())
                throw new MusicException(MusicError.AUTH_EXPIRED);
            JsonObject profile = body.getAsJsonObject("profile");
            String id = profile.get("userId").getAsString();
            if (!id.matches("[1-9][0-9]{0,18}") || !id.equals(body.getAsJsonObject("account").get("id").getAsString()))
                throw new MusicException(MusicError.INVALID_RESPONSE);
            String nickname = profile.get("nickname").getAsString().replaceAll("[\\p{Cntrl}§]", "");
            SessionCookie refreshed = cookies.session();
            return new AccountSession(id, nickname.substring(0, Math.min(nickname.length(), 64)),
                    refreshed.header().equals(cookie.header()) ? cookie : refreshed, clock.instant(),
                    profile.has("avatarUrl") && !profile.get("avatarUrl").isJsonNull() ? NeteaseUris.image(profile.get("avatarUrl").getAsString()) : null);
        } catch (MusicException error) { throw error; }
        catch (RuntimeException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }

    @Override public QrChallenge beginQr() {
        LoginCookies cookies = new LoginCookies();
        // Match NetMusic-BetterLogin's desktop API GET flow, including its type and transport identity.
        JsonObject body = json(qrRequest("/api/login/qrcode/unikey?type=3", cookies));
        check(code(body));
        try {
            String key = body.get("unikey").getAsString();
            if (!key.matches("[A-Za-z0-9_-]{8,256}")) throw new MusicException(MusicError.INVALID_RESPONSE);
            qrKey = key; qrCookies = cookies; lastQrCode = -1;
            // Upstream 800 expires the challenge immediately; use a conservative local limit too.
            return new QrChallenge(key, clock.instant().plusSeconds(120));
        } catch (RuntimeException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }

    @Override public QrPoll pollQr(QrChallenge challenge) {
        if (!challenge.expiresAt().isAfter(clock.instant())) return new QrPoll(QrState.EXPIRED, null);
        if (!challenge.key().matches("[A-Za-z0-9_-]{8,256}")) throw new MusicException(MusicError.INVALID_RESPONSE);
        LoginCookies cookies = challenge.key().equals(qrKey) && qrCookies != null ? qrCookies : new LoginCookies();
        var response = qrRequest("/api/login/qrcode/client/login?key=" + challenge.key() + "&type=3", cookies);
        JsonObject body = json(response);
        int code = code(body);
        if (lastQrCode != code) {
            org.slf4j.LoggerFactory.getLogger(NeteaseAccountProvider.class).info("Account QR stage=poll code={}", code);
            lastQrCode = code;
        }
        return switch (code) {
            case 800 -> new QrPoll(QrState.EXPIRED, null);
            case 801 -> new QrPoll(QrState.WAITING, null);
            case 802 -> new QrPoll(QrState.CONFIRM, null);
            // 8821 is an interactive browser challenge. Repeating this request cannot solve it.
            case 8821 -> new QrPoll(QrState.VERIFY_REQUIRED, null, verificationUri(body));
            case 803 -> {
                qrCookies = null; qrKey = null;
                try { yield new QrPoll(QrState.AUTHORIZED, cookies.session()); }
                catch (AuthException error) { throw new AuthException("qr_missing_cookie"); }
            }
            default -> { check(code); throw new MusicException(MusicError.INVALID_RESPONSE); }
        };
    }

    private static URI verificationUri(JsonObject body) {
        for (String field : new String[]{"verifyUrl", "redirectUrl"}) {
            try {
                if (!body.has(field) || !body.get(field).isJsonPrimitive()) continue;
                String value = body.get(field).getAsString();
                if (value.length() > 4096) continue;
                URI uri = URI.create(value);
                if ("https".equals(uri.getScheme()) && "music.163.com".equals(uri.getHost())
                        && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                        && (uri.getPath().startsWith("/st/") || uri.getPath().equals("/login"))) return uri;
            } catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    @Override public void sendSms(PhoneLogin phone) {
        // Never retry sending automatically: one button press corresponds to one send request.
        smsPhone = phone; smsCookies = new LoginCookies();
        JsonObject data = new JsonObject();
        data.addProperty("ctcode", phone.countryCode()); data.addProperty("cellphone", phone.number());
        JsonObject body = json(request("/weapi/sms/captcha/sent", data, smsCookies));
        smsCheck(body, "sms_send");
    }

    @Override public AccountSession loginSms(PhoneLogin phone, String code) {
        String captcha = PhoneLogin.code(code);
        LoginCookies cookies = phone.equals(smsPhone) ? smsCookies : new LoginCookies();
        cookies.clearSession(); // Only credentials issued by this successful submission may authorize it.
        JsonObject data = new JsonObject();
        data.addProperty("phone", phone.number()); data.addProperty("countrycode", phone.countryCode());
        data.addProperty("captcha", captcha); data.addProperty("rememberLogin", true);
        // The official web client separates captcha sign-in from /w/login/cellphone (password).
        // Do not fabricate browser security tokens or retry another endpoint after a rejection.
        JsonObject body = json(request("/weapi/login/cellphone", data, cookies));
        try { smsCheck(body, "sms_login"); }
        catch (RuntimeException error) { cookies.clearSession(); throw error; }
        SessionCookie sessionCookie;
        try { sessionCookie = cookies.session(); }
        catch (AuthException error) { throw new AuthException("sms_missing_cookie"); }
        smsCookies = new LoginCookies(); smsPhone = null;
        return validate(sessionCookie);
    }

    private static void smsCheck(JsonObject body, String stage) {
        int status = code(body);
        boolean verification = body.has("verifyUrl") || body.has("verifyId") || body.has("redirectUrl") || status == 415;
        org.slf4j.LoggerFactory.getLogger(NeteaseAccountProvider.class).info("Account stage={} code={} verificationRequired={}", stage, status, verification);
        if (status == 405 || status == 429 || status == -460) throw new MusicException(MusicError.RATE_LIMITED);
        if (status == 10004) throw new AuthException("login_rejected_10004");
        // Some deployments return extra verification/risk objects; never open response-provided URLs automatically.
        if (verification) throw new AuthException("verification_required");
        if (status == 200 && (!body.has("data") || !body.get("data").isJsonPrimitive() || !body.get("data").getAsJsonPrimitive().isBoolean() || body.get("data").getAsBoolean())) return;
        throw new AuthException(stage + "_failed");
    }

    private HttpTransport.CookieResponse request(String path, JsonObject data, LoginCookies cookies) {
        data.addProperty("csrf_token", cookies.csrf());
        data.addProperty("timestamp", clock.millis());
        HttpTransport.CookieResponse response = http.postAccount(URI.create("https://music.163.com" + path),
                1024 * 1024, cookies.header(), encoder.apply(data.toString()));
        cookies.accept(response.setCookies());
        return response;
    }
    private HttpTransport.CookieResponse qrRequest(String pathAndQuery, LoginCookies cookies) {
        HttpTransport.CookieResponse response = http.getQrAccount(URI.create("https://music.163.com" + pathAndQuery),
                1024 * 1024, cookies.header());
        cookies.accept(response.setCookies());
        return response;
    }
    private static JsonObject json(HttpTransport.CookieResponse response) {
        try { return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    private static int code(JsonObject body) {
        try { return body.get("code").getAsInt(); }
        catch (RuntimeException error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    private static void check(int code) {
        if (code == 200) return;
        throw new MusicException(switch (code) {
            case 301, 401 -> MusicError.AUTH_EXPIRED;
            case 405, 429, -460 -> MusicError.RATE_LIMITED;
            default -> MusicError.UNAVAILABLE;
        });
    }
}
