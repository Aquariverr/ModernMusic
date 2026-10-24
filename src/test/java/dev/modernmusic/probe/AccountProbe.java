package dev.modernmusic.probe;

import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.netease.NeteaseAccountProvider;
import java.time.Clock;

/** Anonymous endpoint probe only. Never accepts user credentials or emits a usable QR key. */
public final class AccountProbe {
    public static void main(String[] args) {
        if (args.length > 0) {
            if (args.length != 1 || !args[0].equals("--captcha-rejection")) throw new IllegalArgumentException("Unsupported probe mode");
            captchaRejection(); return;
        }
        try (var http = new BoundedHttpTransport()) {
            var provider = new NeteaseAccountProvider(http, Clock.systemUTC());
            var qr = provider.beginQr();
            var poll = provider.pollQr(qr);
            if (poll.state() != AccountProvider.QrState.WAITING) throw new IllegalStateException("Unexpected anonymous QR state");
            System.out.println("PASS: anonymous QR key creation and WAITING status; key and cookies withheld");
            try {
                provider.validate(SessionCookie.parse("MUSIC_U=modernmusic-invalid-probe"));
                throw new IllegalStateException("Invalid probe credential accepted");
            } catch (MusicException error) {
                if (error.error() != MusicError.AUTH_EXPIRED) throw new IllegalStateException("Unexpected anonymous validation state");
                System.out.println("PASS: invalid session classified AUTH_EXPIRED");
            }
        } catch (MusicException error) { throw new IllegalStateException("Anonymous account probe failed: " + error.error()); }
    }
    /** A single negative login request using a reserved fictional number; never calls the SMS sender. */
    private static void captchaRejection() {
        try (var http = new BoundedHttpTransport()) {
            HttpTransport guard = new HttpTransport() {
                private int calls;
                public byte[] get(java.net.URI uri, int max) { throw new IllegalStateException("Probe GET forbidden"); }
                public CookieResponse postAccount(java.net.URI uri, int max, String cookie, byte[] form) {
                    if (++calls != 1 || !uri.toString().equals("https://music.163.com/weapi/login/cellphone"))
                        throw new IllegalStateException("Unexpected probe request");
                    CookieResponse response = http.postAccount(uri, max, cookie, form);
                    var body = com.google.gson.JsonParser.parseString(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    int status = body.get("code").getAsInt();
                    System.out.println("Captcha endpoint negative probe: code=" + status
                            + " verificationRequired=" + (body.has("verifyUrl") || body.has("verifyId") || body.has("redirectUrl")));
                    if (status == 200) throw new IllegalStateException("Unexpected fictional login success");
                    return response;
                }
            };
            try {
                new NeteaseAccountProvider(guard, Clock.systemUTC()).loginSms(new PhoneLogin("1", "2025550123"), "000000");
                throw new IllegalStateException("Unexpected fictional login success");
            } catch (AuthException error) {
                System.out.println("PASS: fictional login rejected as " + error.translationKey() + "; no SMS was sent");
            }
        }
    }
}
