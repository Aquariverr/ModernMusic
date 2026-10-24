package dev.modernmusic.probe;

import dev.modernmusic.auth.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.qq.QqAccountProvider;
import dev.modernmusic.provider.kugou.KugouAccountProvider;
import java.time.Clock;
import java.util.List;

/** Anonymous challenges only: never reads stored accounts, prints or saves a usable QR. */
public final class PlatformQrProbe {
    public static void main(String[] args) {
        boolean failed = false;
        try (var http = new BoundedHttpTransport()) {
            for (AccountProvider provider : List.of(new KugouAccountProvider(http, Clock.systemUTC()), new QqAccountProvider(http, Clock.systemUTC()))) {
                for (var method : provider.qrMethods()) {
                    try {
                        var challenge = provider.beginQr(method);
                        if (challenge.content().isBlank()) throw new AuthException("invalid_response");
                        var poll = provider.pollQr(challenge);
                        if (poll.state() != AccountProvider.QrState.WAITING || poll.cookie() != null) throw new AuthException("invalid_response");
                        System.out.println("PASS: " + provider.platform() + "/" + method + " QR creation and WAITING; secrets withheld");
                    } catch (RuntimeException error) {
                        failed = true;
                        System.out.println("FAIL: " + provider.platform() + "/" + method + " " + (error instanceof AuthException auth ? auth.translationKey()
                                : error instanceof MusicException music ? music.error() : error.getClass().getSimpleName()));
                    }
                }
            }
        }
        if (failed) throw new IllegalStateException("Anonymous QR probe failed; see redacted results above");
    }
}
