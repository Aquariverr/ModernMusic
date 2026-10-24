package dev.modernmusic.probe;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.qq.QqAccountProvider;
import java.nio.file.*;
import java.time.Clock;

/** Explicit user-assisted verification of production code. Never reads or saves an account. */
public final class QqLoginProbe {
    public static void main(String[] args) throws Exception {
        var method = args.length == 1 ? AccountProvider.QrMethod.valueOf(args[0]) : AccountProvider.QrMethod.QQ;
        if (method == AccountProvider.QrMethod.APP) throw new IllegalArgumentException("QQ or WECHAT required");
        Path imagePath = Path.of("build", "qr-live", "qq-login-" + method + "-" + System.currentTimeMillis() + ".png");
        Files.createDirectories(imagePath.getParent());
        try (var http = new BoundedHttpTransport()) {
            HttpTransport diagnostic = new HttpTransport() {
                public byte[] get(java.net.URI uri, int max) { throw new AssertionError("Unexpected transport"); }
                public CookieResponse qqLogin(java.net.URI uri, int max, String cookies, byte[] form) {
                    // Only fixed route names: no query strings, redirect URLs, cookies, or account identifiers.
                    if (!uri.getPath().contains("ptqrlogin") && !uri.getPath().contains("/connect/l/"))
                        System.out.println("LOGIN_STAGE " + (uri.getPath().startsWith("/connect/qrcode/") ? "qr_image" : uri.getPath()));
                    return http.qqLogin(uri, max, cookies, form);
                }
                public CookieResponse qqApi(java.net.URI uri, int max, SessionCookie cookie, byte[] json) {
                    System.out.println("LOGIN_STAGE " + (cookie == null ? "music_session" : "profile_validation"));
                    return http.qqApi(uri, max, cookie, json);
                }
            };
            var provider = new QqAccountProvider(diagnostic, Clock.systemUTC());
            var challenge = provider.beginQr(method);
            var bits = new QRCodeWriter().encode(challenge.content(), BarcodeFormat.QR_CODE, 360, 360);
            var image = new java.awt.image.BufferedImage(360, 360, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 360; y++) for (int x = 0; x < 360; x++) image.setRGB(x, y, bits.get(x, y) ? 0 : 0xffffff);
            javax.imageio.ImageIO.write(image, "png", imagePath.toFile());
            System.out.println("QR_READY " + method + " " + imagePath);
            AccountProvider.QrState previous = null;
            while (challenge.expiresAt().isAfter(java.time.Instant.now())) {
                var result = provider.pollQr(challenge);
                if (result.state() != previous) { previous = result.state(); System.out.println("QR_STATE " + method + " " + previous); }
                switch (result.state()) {
                    case AUTHORIZED -> {
                        provider.validate(result.cookie());
                        System.out.println("PASS: " + method + " authorization and account validation; no credentials saved"); return;
                    }
                    case EXPIRED, CANCELLED -> throw new AuthException("qr_expired");
                    default -> Thread.sleep(2000);
                }
            }
            throw new AuthException("qr_expired");
        } catch (AuthException | MusicException error) {
            System.out.println("FAIL: " + method + " " + (error instanceof AuthException auth ? auth.translationKey() : ((MusicException) error).error()));
            throw new IllegalStateException("QR verification did not complete; see redacted stage above", error);
        } finally { Files.deleteIfExists(imagePath); }
    }
}
