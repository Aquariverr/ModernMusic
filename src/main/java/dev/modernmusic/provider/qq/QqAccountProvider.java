package dev.modernmusic.provider.qq;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.DisplayText;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.time.Clock;

/** QQ/WeChat QR and Cookie sign-in use first-party authorization and profile endpoints. */
public final class QqAccountProvider implements AccountProvider {
    private final HttpTransport http;
    private final Clock clock;
    private final QqQrLogin qr;
    public QqAccountProvider(HttpTransport http, Clock clock) { this.http = http; this.clock = clock; qr = new QqQrLogin(http, clock); }
    @Override public MusicPlatform platform() { return MusicPlatform.QQ; }
    @Override public AccountSession validate(SessionCookie cookie) {
        if (cookie == null || cookie.platform() != platform()) throw new AuthException("invalid_cookie");
        try {
            var root = QqApi.json(http.qqApi(URI.create("https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&cid=205360838&reqfrom=1&userid="
                    + cookie.qqUin() + "&loginUin=" + cookie.qqUin() + "&g_tk=" + QqApi.token(cookie)), 1024 * 1024, cookie, null).body());
            var creator = root.getAsJsonObject("data").getAsJsonObject("creator");
            if (creator == null) throw invalidProfile("missing_creator");
            String returned = QqApi.text(creator, "uin");
            // The authenticated profile endpoint masks creator.uin as 0, including for WeChat accounts.
            // Keep the exact ID from the music session; reject a conflicting disclosed identity.
            if (!returned.isEmpty() && !returned.equals("0") && !returned.equals(cookie.qqUin()))
                throw invalidProfile(returned.matches("[0-9]+") ? "numeric_identity_mismatch" : "opaque_identity");
            String name = DisplayText.clean(QqApi.text(creator, "nick"));
            if (name.isBlank()) throw invalidProfile("missing_nickname");
            return new AccountSession(cookie.qqUin(), name.substring(0, Math.min(64, name.length())), cookie, clock.instant(), QqUris.image(QqApi.text(creator, "headpic")));
        } catch (MusicException | AuthException failure) { throw failure; }
        catch (RuntimeException invalid) { throw invalidProfile("profile_shape"); }
    }
    private static AuthException invalidProfile(String stage) {
        org.slf4j.LoggerFactory.getLogger(QqAccountProvider.class).warn("QQ Music profile failed stage={}", stage);
        return new AuthException("invalid_response");
    }
    @Override public java.util.List<QrMethod> qrMethods() { return java.util.List.of(QrMethod.QQ, QrMethod.WECHAT); }
    @Override public QrChallenge beginQr() { return beginQr(QrMethod.QQ); }
    @Override public QrChallenge beginQr(QrMethod method) { return qr.begin(method); }
    @Override public QrPoll pollQr(QrChallenge challenge) { return qr.poll(challenge); }
}
