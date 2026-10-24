package dev.modernmusic.provider.netease;

import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NeteasePlaybackSessionTest {
    private static final SongId SONG = new SongId(42);
    private static HttpTransport.CookieResponse resource(String name, String... cookies) {
        String body = "{\"code\":200,\"data\":[{\"id\":42,\"code\":200,\"url\":\"https://m804.music.126.net/"
                + name + ".mp3\",\"type\":\"mp3\",\"expi\":1200}]}";
        return new HttpTransport.CookieResponse(body.getBytes(StandardCharsets.UTF_8), List.of(cookies));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS), "fixture was not released"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }

    @Test void slowLyricsDoNotDelayPlaybackHttp() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cookie = SessionCookie.parse("MUSIC_U=fixture; NMTID=ready");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse getWithCookies(URI uri, int max, SessionCookie supplied) {
                started.countDown(); await(release);
                return new CookieResponse("{\"code\":200}".getBytes(StandardCharsets.UTF_8), List.of());
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) { return resource("ready"); }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail(), s -> s.getBytes(StandardCharsets.UTF_8))) {
            var lyrics = p.getLyrics(SONG);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try { assertEquals("/ready.mp3", p.resolvePlayback(SONG).get(2, TimeUnit.SECONDS).uri().getPath()); }
            finally { release.countDown(); }
            lyrics.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void slowSearchDoesNotDelayPlaybackHttp() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cookie = SessionCookie.parse("MUSIC_U=fixture; NMTID=ready; __csrf=csrf");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                if (uri.getPath().equals("/weapi/search/get")) {
                    started.countDown(); await(release);
                    return new CookieResponse("{\"code\":200,\"result\":{\"songCount\":0}}".getBytes(StandardCharsets.UTF_8), List.of());
                }
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail(), s -> s.getBytes(StandardCharsets.UTF_8))) {
            var search = p.searchSongs("fixture", 0, 20);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try { assertEquals("/ready.mp3", p.resolvePlayback(SONG).get(2, TimeUnit.SECONDS).uri().getPath()); }
            finally { release.countDown(); }
            search.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void cancellingAPlaybackLockWaitReleasesWorkerWhileFirstResolutionStillRuns() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var cookie = SessionCookie.parse("MUSIC_U=fixture; NMTID=ready");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse getWithCookies(URI uri, int max, SessionCookie supplied) {
                return new CookieResponse("{\"code\":200}".getBytes(StandardCharsets.UTF_8), List.of());
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                calls.incrementAndGet(); started.countDown(); await(release); return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail(), s -> s.getBytes(StandardCharsets.UTF_8))) {
            var first = p.resolvePlayback(SONG);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var waiting = p.resolvePlayback(SONG);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean lockWait = false;
            while (System.nanoTime() < deadline && !lockWait) {
                lockWait = Thread.getAllStackTraces().entrySet().stream()
                        .filter(entry -> entry.getKey().getName().startsWith("modernmusic-provider"))
                        .anyMatch(entry -> java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                                frame.getClassName().equals(LoginCookies.class.getName()) && frame.getMethodName().equals("lockPlayback")));
                if (!lockWait) Thread.sleep(5);
            }
            assertTrue(lockWait, "second resolution did not wait for the playback lock");
            assertTrue(waiting.cancel(true));
            try {
                p.getLyrics(SONG).get(2, TimeUnit.SECONDS);
                assertFalse(first.isDone()); assertEquals(1, calls.get());
            } finally { release.countDown(); }
            first.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void lateMetadataResponseCannotRollBackRotatedPlaybackCookies() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var cookie = SessionCookie.parse("MUSIC_U=fixture; NMTID=before; __csrf=before");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse getWithCookies(URI uri, int max, SessionCookie supplied) {
                started.countDown(); await(release);
                return new CookieResponse("{\"code\":200,\"songs\":[{\"id\":42,\"name\":\"Fixture\",\"artists\":[],\"duration\":1000}]}"
                        .getBytes(StandardCharsets.UTF_8), List.of("NMTID=stale; Path=/", "__csrf=stale; Path=/"));
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                if (calls.incrementAndGet() == 1) return resource("cold", "NMTID=ready; Path=/", "__csrf=ready; Path=/");
                assertTrue(header.contains("NMTID=ready")); assertTrue(header.contains("__csrf=ready"));
                var payload = com.google.gson.JsonParser.parseString(new String(form, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("ready", payload.get("csrf_token").getAsString());
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail(), s -> s.getBytes(StandardCharsets.UTF_8))) {
            var metadata = p.getTrack(SONG);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            try { assertEquals("/ready.mp3", p.resolvePlayback(SONG).get(2, TimeUnit.SECONDS).uri().getPath()); }
            finally { release.countDown(); }
            metadata.get(2, TimeUnit.SECONDS);
            assertEquals("/ready.mp3", p.resolvePlayback(SONG).get(2, TimeUnit.SECONDS).uri().getPath());
            assertEquals(3, calls.get());
        } finally { release.countDown(); }
    }

    @Test void coldResourceIsDiscardedAndNewSessionSurvivesFollowingRequests() {
        var calls = new AtomicInteger();
        var cookie = SessionCookie.parse("MUSIC_U=fixture; __csrf=before");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Unexpected GET"); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertTrue(header.contains("MUSIC_U=fixture"));
                int call = calls.incrementAndGet();
                if (call == 1) {
                    assertFalse(header.contains("NMTID="));
                    return resource("cold-rejected", "NMTID=playback-session; Domain=.music.163.com; Path=/", "__csrf=after; Path=/");
                }
                assertTrue(header.contains("NMTID=playback-session"));
                assertTrue(header.contains("__csrf=after"));
                var json = com.google.gson.JsonParser.parseString(new String(form, StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals("after", json.get("csrf_token").getAsString());
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail(), s -> s.getBytes(StandardCharsets.UTF_8))) {
            assertEquals("/ready.mp3", p.resolvePlayback(SONG).join().uri().getPath());
            assertEquals(2, calls.get());
            assertEquals("/ready.mp3", p.resolvePlayback(SONG).join().uri().getPath());
            assertEquals(3, calls.get());
        }
    }

    @Test void metadataResponseInitializesTheSameAuthenticatedPlaybackSession() {
        var calls = new AtomicInteger();
        var cookie = SessionCookie.parse("MUSIC_U=fixture");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Lost authentication"); }
            public CookieResponse getWithCookies(URI uri, int max, SessionCookie supplied) {
                assertEquals(cookie.header(), supplied.header());
                return new CookieResponse("{\"code\":200,\"songs\":[{\"id\":42,\"name\":\"Fixture\",\"artists\":[],\"duration\":1000}]}".getBytes(StandardCharsets.UTF_8),
                        List.of("NMTID=metadata-session; Domain=.music.163.com; Path=/"));
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                calls.incrementAndGet(); assertTrue(header.contains("NMTID=metadata-session"));
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail())) {
            p.getTrack(SONG).join(); p.resolvePlayback(SONG).join(); assertEquals(1, calls.get());
        }
    }

    @Test void accountSwitchAndLogoutCannotReusePreviousPlaybackCookies() {
        var current = new AtomicReference<>(SessionCookie.parse("MUSIC_U=first; NMTID=first-session"));
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Unexpected GET"); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                assertEquals(current.get() == null ? "" : current.get().header(), header);
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), current::get, ignored -> fail())) {
            p.resolvePlayback(SONG).join();
            current.set(SessionCookie.parse("MUSIC_U=second; NMTID=second-session")); p.resolvePlayback(SONG).join();
            current.set(null); p.resolvePlayback(SONG).join();
        }
    }

    @Test void simultaneousResolutionsShareOneBootstrap() {
        var calls = new AtomicInteger();
        var cold = new AtomicInteger();
        var cookie = SessionCookie.parse("MUSIC_U=fixture");
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                calls.incrementAndGet();
                if (!header.contains("NMTID=ready")) {
                    cold.incrementAndGet(); return resource("cold-rejected", "NMTID=ready; Path=/");
                }
                return resource("ready");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, ignored -> fail())) {
            var first = p.resolvePlayback(SONG); var second = p.resolvePlayback(SONG);
            assertEquals("/ready.mp3", first.join().uri().getPath());
            assertEquals("/ready.mp3", second.join().uri().getPath());
            assertEquals(1, cold.get()); assertEquals(3, calls.get());
        }
    }

    @Test void sessionInitializationRetryIsBounded() {
        var calls = new AtomicInteger();
        HttpTransport http = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError(); }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                return resource("ready", "NMTID=rotating-" + calls.incrementAndGet() + "; Path=/");
            }
        };
        try (var p = new NeteaseMusicProvider(http, Clock.systemUTC())) {
            p.resolvePlayback(SONG).join(); assertEquals(2, calls.get());
        }
    }

    @Test void deniedResourcesDoNotTriggerBootstrapRetriesOrGuestFallback() {
        for (int status : new int[]{301, 429, -110}) {
            var calls = new AtomicInteger();
            var cookie = SessionCookie.parse("MUSIC_U=fixture");
            var expired = new AtomicReference<SessionCookie>();
            HttpTransport http = new HttpTransport() {
                public byte[] get(URI uri, int max) { throw new AssertionError(); }
                public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                    assertTrue(header.contains("MUSIC_U=fixture")); calls.incrementAndGet();
                    String body = "{\"code\":200,\"data\":[{\"id\":42,\"code\":" + status + "}]}";
                    return new CookieResponse(body.getBytes(StandardCharsets.UTF_8), List.of("NMTID=initialized; Path=/"));
                }
            };
            try (var p = new NeteaseMusicProvider(http, Clock.systemUTC(), () -> cookie, expired::set)) {
                var error = assertThrows(java.util.concurrent.CompletionException.class, () -> p.resolvePlayback(SONG).join());
                MusicError expected = status == 301 ? MusicError.AUTH_EXPIRED : status == 429 ? MusicError.RATE_LIMITED : MusicError.AUTH_REQUIRED;
                assertEquals(expected, ((MusicException) error.getCause()).error()); assertEquals(1, calls.get());
                assertSame(status == 301 ? cookie : null, expired.get());
            }
        }
    }
}
