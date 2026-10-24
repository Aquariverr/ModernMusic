package dev.modernmusic.provider.netease;

import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class NeteaseAccountSnapshotTest {
    enum Operation { TRACK, TRACKS, LYRICS, PLAYLIST, SEARCH, PLAYBACK }

    @ParameterizedTest
    @CsvSource({"TRACK,false", "TRACKS,false", "LYRICS,false", "PLAYLIST,false", "SEARCH,false", "PLAYBACK,false",
            "TRACK,true", "TRACKS,true", "LYRICS,true", "PLAYLIST,true", "SEARCH,true", "PLAYBACK,true"})
    void queuedRequestKeepsSubmittingAccountAfterSwitchOrLogout(Operation operation, boolean logout) throws Exception {
        runQueued(operation, SessionCookie.parse("MUSIC_U=original"),
                logout ? null : SessionCookie.parse("MUSIC_U=replacement"));
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void queuedGuestRequestCannotAcquireLaterLogin(Operation operation) throws Exception {
        runQueued(operation, null, SessionCookie.parse("MUSIC_U=replacement"));
    }

    private void runQueued(Operation operation, SessionCookie original, SessionCookie replacement) throws Exception {
        var current = new AtomicReference<>(original);
        var expired = new CopyOnWriteArrayList<SessionCookie>();
        var started = new CountDownLatch(2); var release = new CountDownLatch(1);
        String expectedHeader = original == null ? "" : original.header();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int maxBytes) { return reply(uri, ""); }
            public CookieResponse getWithCookies(URI uri, int maxBytes, SessionCookie cookie) {
                return new CookieResponse(reply(uri, cookie.header()), List.of());
            }
            public CookieResponse postAccount(URI uri, int maxBytes, String header, byte[] form) {
                return new CookieResponse(reply(uri, header), List.of());
            }
            private byte[] reply(URI uri, String header) {
                if (uri.getPath().equals("/api/song/lyric")
                        && (uri.getQuery().startsWith("id=1&") || uri.getQuery().startsWith("id=2&"))) {
                    started.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    return "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
                }
                assertEquals(expectedHeader, header, "Queued operation changed account");
                return "{\"code\":301}".getBytes(StandardCharsets.UTF_8);
            }
        };
        try (var provider = new NeteaseMusicProvider(http, Clock.systemUTC(), current::get, expired::add,
                text -> text.getBytes(StandardCharsets.UTF_8))) {
            var first = provider.getLyrics(new SongId(1)); var second = provider.getLyrics(new SongId(2));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var id = new SongId(42);
            CompletableFuture<?> request = switch (operation) {
                case TRACK -> provider.getTrack(id);
                case TRACKS -> provider.getTracks(List.of(id));
                case LYRICS -> provider.getLyrics(id);
                case PLAYLIST -> provider.getPlaylist(id);
                case SEARCH -> provider.searchSongs("fixture", 0, 5);
                case PLAYBACK -> provider.resolvePlayback(id);
            };
            current.set(replacement); release.countDown();
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
            assertEquals(MusicError.AUTH_EXPIRED, assertInstanceOf(MusicException.class, failure.getCause()).error());
            if (original == null) assertTrue(expired.isEmpty(), "Guest cannot expire a later account");
            else { assertEquals(1, expired.size()); assertSame(original, expired.get(0)); }
            assertSame(replacement, current.get());
        } finally { release.countDown(); }
    }
}
