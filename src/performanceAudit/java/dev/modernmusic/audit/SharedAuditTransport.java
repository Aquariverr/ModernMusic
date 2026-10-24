package dev.modernmusic.audit;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.auth.*;
import dev.modernmusic.network.SharedAccountNetwork;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.netease.*;
import dev.modernmusic.server.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import net.minecraft.server.MinecraftServer;

/** Transparent real HTTP measurement. Never records credentials, URLs, forms or response bodies. */
final class SharedAuditTransport implements HttpTransport {
    private final BoundedHttpTransport delegate = new BoundedHttpTransport();
    private final AtomicLong requests = new AtomicLong(), sent = new AtomicLong(), received = new AtomicLong();
    private final Path events;
    private ServerSharedAccount account;
    private volatile boolean authenticatedProvider;
    private SharedAuditTransport(Path directory) throws Exception {
        events = directory.resolve("shared-http.jsonl"); Files.writeString(events, "");
    }
    static SharedAuditTransport install(MinecraftServer server, Path directory) throws Exception {
        var transport = new SharedAuditTransport(directory);
        var networkField = ModernMusic.class.getDeclaredField("sharedAccount"); networkField.setAccessible(true);
        var network = (SharedAccountNetwork) networkField.get(null);
        var accountField = SharedAccountNetwork.class.getDeclaredField("accounts"); accountField.setAccessible(true);
        var accounts = accounts(accountField.get(network));
        var previous = accounts.get(dev.modernmusic.domain.MusicPlatform.NETEASE); if (previous != null) previous.close();
        var constructor = ServerSharedAccount.class.getDeclaredConstructor(Path.class, Executor.class, Clock.class,
                AccountProvider.class, BiFunction.class, Runnable.class); constructor.setAccessible(true);
        Clock clock = Clock.systemUTC();
        BiFunction<SessionCookie, Consumer<SessionCookie>, MusicProvider> music = (cookie, expired) -> {
            transport.authenticatedProvider = cookie != null;
            return new NeteaseMusicProvider(transport, clock, () -> cookie, expired);
        };
        transport.account = constructor.newInstance(directory.resolve("audit-shared-private"), (Executor) server, clock,
                new NeteaseAccountProvider(transport, clock), music, (Runnable) transport.delegate::close);
        SharedAccountConfig.ENABLED.set(true); SharedAccountConfig.OPERATORS_ONLY.set(false);
        transport.account.enabled(true); accounts.put(dev.modernmusic.domain.MusicPlatform.NETEASE, transport.account);
        return transport;
    }
    /** Validate the reflected map before retaining the instance used by the admin network. */
    @SuppressWarnings("unchecked")
    private static Map<dev.modernmusic.domain.MusicPlatform, ServerSharedAccount> accounts(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.entrySet().stream().anyMatch(entry ->
                !(entry.getKey() instanceof dev.modernmusic.domain.MusicPlatform) || !(entry.getValue() instanceof ServerSharedAccount)))
            throw new IllegalStateException("Unexpected shared account registry");
        return (Map<dev.modernmusic.domain.MusicPlatform, ServerSharedAccount>) map;
    }
    Map<String, Object> snapshot() {
        return Map.of("sharedReady", account.resolver().ready(), "sharedAuthenticatedProvider", authenticatedProvider,
                "sharedHttpRequestsTotal", requests.get(), "sharedHttpRequestBodyBytesTotal", sent.get(),
                "sharedHttpResponseBodyBytesTotal", received.get());
    }
    @Override public byte[] get(URI uri, int max) {
        return measured(uri, 0, () -> new CookieResponse(delegate.get(uri, max), List.of())).body();
    }
    @Override public CookieResponse getWithCookies(URI uri, int max, SessionCookie cookie) {
        return measured(uri, 0, () -> delegate.getWithCookies(uri, max, cookie));
    }
    @Override public CookieResponse getQrAccount(URI uri, int max, String cookie) {
        return measured(uri, 0, () -> delegate.getQrAccount(uri, max, cookie));
    }
    @Override public CookieResponse postAccount(URI uri, int max, String cookie, byte[] form) {
        return measured(uri, form.length, () -> delegate.postAccount(uri, max, cookie, form));
    }
    private CookieResponse measured(URI uri, int requestBytes, Supplier<CookieResponse> operation) {
        long start = System.nanoTime(); long epoch = System.currentTimeMillis();
        String path = uri.getPath();
        String category = path.contains("account/get") ? "account-validation"
                : path.contains("player/url") ? "playback-resolution"
                : uri.getHost().endsWith("music.126.net") ? "audio-cdn" : "other-api";
        requests.incrementAndGet(); sent.addAndGet(requestBytes);
        CookieResponse result;
        try {
            result = operation.get();
        } catch (RuntimeException | Error failure) {
            try { record(epoch, category, requestBytes, 0, start, false); }
            catch (RuntimeException loggingFailure) { failure.addSuppressed(loggingFailure); }
            throw failure;
        }
        int responseBytes = result.body().length;
        received.addAndGet(responseBytes);
        record(epoch, category, requestBytes, responseBytes, start, true);
        return result;
    }
    private synchronized void record(long epoch, String category, int requestBytes, int responseBytes, long start, boolean success) {
        var row = new LinkedHashMap<String, Object>(); row.put("epochMs", epoch); row.put("operation", category);
        row.put("requestBodyBytes", requestBytes); row.put("responseBodyBytes", responseBytes);
        row.put("elapsedMs", (System.nanoTime() - start) / 1e6); row.put("success", success);
        try { Files.writeString(events, AuditMetrics.JSON.toJson(row) + "\n", StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot write shared HTTP counters", failure); }
    }
}
