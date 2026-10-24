package dev.modernmusic.provider;

import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import dev.modernmusic.provider.qq.QqMusicProvider;
import dev.modernmusic.provider.kugou.KugouMusicProvider;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCloseTest {
    @ParameterizedTest
    @EnumSource(value = MusicPlatform.class, names = {"NETEASE", "QQ", "KUGOU"})
    void closeTerminatesRunningAndQueuedRequestsForEveryPlatform(MusicPlatform platform) throws Exception {
        var started = new CountDownLatch(2); var release = new CountDownLatch(1);
        var returned = new CountDownLatch(2);
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int maxBytes) { return block(); }
            public CookieResponse qqApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
                return new CookieResponse(block(), List.of());
            }
            public CookieResponse kugouApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
                return new CookieResponse(block(), List.of());
            }
            private byte[] block() {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                returned.countDown();
                return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        };
        MusicProvider provider = switch (platform) {
            case NETEASE -> new NeteaseMusicProvider(http, Clock.systemUTC());
            case QQ -> new QqMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> fail());
            case KUGOU -> new KugouMusicProvider(http, Clock.systemUTC(), () -> null, ignored -> fail());
            default -> throw new AssertionError(platform);
        };
        var song = platform == MusicPlatform.KUGOU
                ? new SongId("A331C7D3DC826D784124C8DC9284436F", platform) : new SongId(42, platform);
        try {
            var first = provider.getTrack(song); var second = provider.getTrack(song);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = provider.getTrack(song);
            provider.close();
            assertTrue(first.isCancelled()); assertTrue(second.isCancelled()); assertTrue(queued.isCancelled());
            provider.close();
            var rejected = provider.getTrack(song);
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertEquals(MusicError.RATE_LIMITED, assertInstanceOf(MusicException.class, failure.getCause()).error());
        } finally {
            release.countDown(); provider.close();
            assertTrue(returned.await(2, TimeUnit.SECONDS));
        }
    }
}
