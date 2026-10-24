package dev.modernmusic.auth;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.net.URI;

/** Blocking operations; AccountController owns the bounded worker and cancellation. */
public interface AccountProvider {
    default dev.modernmusic.domain.MusicPlatform platform() { return dev.modernmusic.domain.MusicPlatform.NETEASE; }
    AccountSession validate(SessionCookie cookie);
    QrChallenge beginQr();
    default QrChallenge beginQr(QrMethod method) {
        if (method != QrMethod.APP) throw new AuthException("qr_unavailable");
        return beginQr();
    }
    default java.util.List<QrMethod> qrMethods() { return java.util.List.of(QrMethod.APP); }
    QrPoll pollQr(QrChallenge challenge);
    default void sendSms(PhoneLogin phone) { throw new AuthException("sms_unavailable"); }
    default AccountSession loginSms(PhoneLogin phone, String code) { throw new AuthException("sms_unavailable"); }
    enum QrMethod { APP, QQ, WECHAT }
    record QrChallenge(String key, Instant expiresAt, String content, QrMethod method) {
        public QrChallenge(String key, Instant expiresAt) {
            this(key, expiresAt, "https://music.163.com/login?codekey=" + key, QrMethod.APP);
        }
        @Nonnull @Override public String toString() { return "QrChallenge[redacted]"; }
    }
    enum QrState { WAITING, CONFIRM, VERIFY_REQUIRED, EXPIRED, CANCELLED, AUTHORIZED }
    record QrPoll(QrState state, SessionCookie cookie, URI verificationUri) {
        public QrPoll(QrState state, SessionCookie cookie) { this(state, cookie, null); }
        @Nonnull @Override public String toString() { return "QrPoll[" + state + "]"; }
    }
}
