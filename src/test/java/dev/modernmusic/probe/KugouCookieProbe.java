package dev.modernmusic.probe;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.kugou.KugouAccountProvider;
import java.nio.file.*;
import java.time.Clock;
import java.util.Set;

/** Explicit production-path validation. No credential output, persistence, or automatic execution. */
public final class KugouCookieProbe {
    public static void main(String[] args) {
        String stage = "read";
        try {
            var options = Set.of(args);
            SessionCookie cookie;
            if (options.contains("--fixture")) cookie = SessionCookie.parse("KugooID=123;t=fixture-not-a-real-token;a_id=1014;mid=fixture-device", MusicPlatform.KUGOU);
            else {
                String inputFile = System.getenv("MODERNMUSIC_COOKIE_FILE");
                if (inputFile == null) throw new IllegalArgumentException();
                Path input = Path.of(inputFile);
                if (Files.size(input) > SessionCookie.MAX_IMPORT_LENGTH * 4L) throw new IllegalArgumentException();
                stage = "parse";
                cookie = SessionCookie.parse(Files.readString(input), MusicPlatform.KUGOU);
            }
            System.out.println("Kugou cookie parse=OK");
            if (options.contains("--parse-only")) return;
            stage = "profile";
            try (var http = new BoundedHttpTransport()) {
                var session = new KugouAccountProvider(http, Clock.systemUTC()).validate(cookie);
                if (session.nickname().isBlank() || !session.userId().equals(cookie.value("userid"))) throw new IllegalStateException();
                System.out.println("Kugou profile validation=OK; credentials not saved");
            }
        } catch (Exception error) {
            String category = error instanceof AuthException auth ? auth.translationKey()
                    : error instanceof MusicException music ? music.error().name() : "LOCAL_OR_PROTOCOL_ERROR";
            System.err.println("Kugou stage=" + stage + "; result=" + category);
            System.exit(1);
        }
    }
}
