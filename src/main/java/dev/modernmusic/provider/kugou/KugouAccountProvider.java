package dev.modernmusic.provider.kugou;

import com.google.gson.JsonObject;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.*;

public final class KugouAccountProvider implements AccountProvider {
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB";
    private final KugouApi api;
    private final Clock clock;
    public KugouAccountProvider(HttpTransport http, Clock clock) { api = new KugouApi(http, clock); this.clock = clock; }
    @Override public MusicPlatform platform() { return MusicPlatform.KUGOU; }
    @Override public AccountSession validate(SessionCookie cookie) {
        if (cookie == null || cookie.platform() != platform()) throw new AuthException("invalid_cookie");
        try {
            if (Set.of("1010", "1014").contains(cookie.value("appid")))
                return KugouWebsiteSession.validate(api.websiteSession(cookie), cookie, clock);
            long now = clock.instant().getEpochSecond();
            var secret = new JsonObject(); secret.addProperty("token", cookie.value("token")); secret.addProperty("clienttime", now);
            var body = new JsonObject(); body.addProperty("visit_time", now); body.addProperty("usertype", 1);
            body.addProperty("p", encrypt(secret.toString())); body.addProperty("userid", Long.parseLong(cookie.value("userid")));
            var root = api.gateway("/v3/get_my_info", Map.of("plat", "1"), body, cookie);
            var data = root.getAsJsonObject("data");
            if (data == null) throw new AuthException("invalid_response");
            String returned = KugouApi.text(data, "userid", "user_id");
            if (!returned.isEmpty() && !returned.equals(cookie.value("userid"))) throw new AuthException("invalid_response");
            String name = KugouApi.display(KugouApi.text(data, "nickname", "nick_name"));
            if (name.isBlank()) throw new AuthException("invalid_response");
            return new AccountSession(cookie.value("userid"), name.substring(0, Math.min(64, name.length())), cookie, clock.instant(),
                    KugouUris.image(KugouApi.text(data, "pic", "user_pic", "avatar")));
        } catch (AuthException | MusicException error) { throw error; }
        catch (RuntimeException error) { throw new AuthException("invalid_response"); }
    }
    /** Kugou's profile protocol uses a right-zero-padded RSA block. */
    private static String encrypt(String value) {
        try {
            var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            int length = (key.getModulus().bitLength() + 7) / 8;
            if (bytes.length > length) throw new AuthException("invalid_cookie");
            var message = new java.math.BigInteger(1, Arrays.copyOf(bytes, length));
            return String.format(Locale.ROOT, "%0" + length * 2 + "X", message.modPow(key.getPublicExponent(), key.getModulus()));
        } catch (java.security.GeneralSecurityException error) { throw new AuthException("invalid_response"); }
    }
    @Override public QrChallenge beginQr() {
        try {
            var data = api.qr("/v2/qrcode", Map.of("appid", "1001", "type", "1",
                    "qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&")).getAsJsonObject("data");
            String key = KugouApi.text(data, "qrcode");
            if (!key.matches("[A-Za-z0-9_-]{8,256}")) throw new AuthException("invalid_response");
            return new QrChallenge(key, clock.instant().plusSeconds(120),
                    "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + key, QrMethod.APP);
        } catch (AuthException | MusicException error) { throw error; }
        catch (RuntimeException invalid) { throw new AuthException("invalid_response"); }
    }
    @Override public QrPoll pollQr(QrChallenge challenge) {
        if (challenge.method() != QrMethod.APP || !challenge.key().matches("[A-Za-z0-9_-]{8,256}")) throw new AuthException("invalid_response");
        if (!challenge.expiresAt().isAfter(clock.instant())) return new QrPoll(QrState.EXPIRED, null);
        try {
            var data = api.qr("/v2/get_userinfo_qrcode", Map.of("qrcode", challenge.key())).getAsJsonObject("data");
            return switch (Integer.parseInt(KugouApi.text(data, "status"))) {
                case 0 -> new QrPoll(QrState.EXPIRED, null);
                case 1 -> new QrPoll(QrState.WAITING, null);
                case 2 -> new QrPoll(QrState.CONFIRM, null);
                case 4 -> new QrPoll(QrState.AUTHORIZED, SessionCookie.parse("userid=" + KugouApi.text(data, "userid")
                        + "; token=" + KugouApi.text(data, "token") + "; mid=" + api.mid(null), platform()));
                default -> throw new AuthException("invalid_response");
            };
        } catch (AuthException | MusicException error) { throw error; }
        catch (RuntimeException invalid) { throw new AuthException("invalid_response"); }
    }
}
