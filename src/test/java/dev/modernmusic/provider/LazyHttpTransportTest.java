package dev.modernmusic.provider;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LazyHttpTransportTest {
    private static final URI URI = java.net.URI.create("https://music.163.com/api/fixture");

    @Test void unusedAndClosedTransportsNeverCreateAClient() {
        var calls = new AtomicInteger();
        var transport = new BoundedHttpTransport(() -> { calls.incrementAndGet(); return new Client(); });
        assertEquals(0, calls.get());
        transport.close(); transport.close();
        assertThrows(CancellationException.class, () -> transport.get(URI, 16));
        assertEquals(0, calls.get());
    }

    @Test void concurrentFirstRequestsShareOneClientAndCloseRejectsLaterDispatch() throws Exception {
        var calls = new AtomicInteger();
        var client = new Client();
        var transport = new BoundedHttpTransport(() -> { calls.incrementAndGet(); return client; });
        try (var workers = Executors.newFixedThreadPool(8)) {
            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                ready.countDown();
                try { start.await(); } catch (InterruptedException error) { throw new AssertionError(error); }
                assertThrows(MusicException.class, () -> transport.get(URI, 16));
            }));
            assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown();
            for (var result : results) result.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertEquals(8, client.sends.get());
        } finally { transport.close(); }
        transport.close();
        assertEquals(1, client.shutdowns.get());
        assertThrows(CancellationException.class, () -> transport.get(URI, 16));
        assertEquals(8, client.sends.get());
    }

    private static final class Client extends HttpClient {
        final AtomicInteger sends = new AtomicInteger(), shutdowns = new AtomicInteger();
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            assertEquals(0, shutdowns.get()); sends.incrementAndGet();
            return CompletableFuture.failedFuture(new MusicException(MusicError.NETWORK_ERROR));
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { return sendAsync(r, h); }
        public <T> HttpResponse<T> send(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new AssertionError("Blocking dispatch"); }
        public void shutdownNow() { shutdowns.incrementAndGet(); }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_2; }
        public Optional<Executor> executor() { return Optional.empty(); }
    }
}
