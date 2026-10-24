package dev.modernmusic.provider;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import dev.modernmusic.auth.SessionCookie;

/** Finite bodies and total request deadlines; redirects are never automatically followed. */
public final class BoundedHttpTransport implements HttpTransport, AutoCloseable {
    private static final String WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    // QR client profile used by NetMusic-BetterLogin; keep playback on its existing web profile.
    private static final String QR_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.6";
    private final java.util.function.Supplier<HttpClient> clientFactory;
    private HttpClient client;
    private final java.util.Set<CompletableFuture<?>> requests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean closed;

    public BoundedHttpTransport() {
        this(() -> HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    BoundedHttpTransport(java.util.function.Supplier<HttpClient> clientFactory) { this.clientFactory = clientFactory; }

    /** First use is on a provider worker; construction and dispatch cannot race shutdown. */
    private synchronized CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, int maxBytes) {
        if (closed) throw new java.util.concurrent.CancellationException();
        if (client == null) client = clientFactory.get();
        var pending = client.sendAsync(request, info -> new LimitedBody(maxBytes));
        requests.add(pending);
        pending.whenComplete((result, failure) -> requests.remove(pending));
        return pending;
    }

    @Override
    public byte[] get(URI uri, int maxBytes) {
        return exchange(request(uri, "", false, null), maxBytes, false).body();
    }

    @Override public CookieResponse getWithCookies(URI uri, int maxBytes, SessionCookie cookie) {
        requireAccountOrigin(uri);
        if (cookie != null && cookie.platform() != dev.modernmusic.domain.MusicPlatform.NETEASE) throw new MusicException(MusicError.INVALID_RESPONSE);
        return exchange(request(uri, cookie == null ? "" : cookie.header(), true, null), maxBytes, true);
    }

    @Override public CookieResponse qqApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
        return exchange(qqRequest(uri, cookie, json), maxBytes, true);
    }
    @Override public CookieResponse kugouApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
        return exchange(kugouRequest(uri, cookie, json), maxBytes, true);
    }
    @Override public CookieResponse qqLogin(URI uri, int maxBytes, String cookies, byte[] form) {
        return exchange(qqLoginRequest(uri, cookies, form), Math.min(maxBytes, 1024 * 1024), true, true);
    }
    static HttpRequest qqLoginRequest(URI uri, String cookies, byte[] form) {
        String host = uri.getHost(), path = uri.getRawPath();
        boolean qqUi = "xui.ptlogin2.qq.com".equals(host);
        boolean poll = qqUi && "/ssl/ptqrlogin".equals(path);
        boolean qrImage = qqUi && "/ssl/ptqrshow".equals(path);
        boolean authorize = "graph.qq.com".equals(host) && "/oauth2.0/authorize".equals(path);
        boolean loginJump = "graph.qq.com".equals(host) && "/oauth2.0/login_jump".equals(path);
        boolean wx = "open.weixin.qq.com".equals(host) && ("/connect/qrconnect".equals(path)
                || path.matches("/connect/qrcode/[A-Za-z0-9_-]{8,128}"));
        boolean wxPoll = "lp.open.weixin.qq.com".equals(host) && "/connect/l/qrconnect".equals(path);
        boolean allowed = poll || qrImage || authorize || loginJump || wx || wxPoll
                || qqUi && "/cgi-bin/xlogin".equals(path)
                || "ssl.ptlogin2.graph.qq.com".equals(host) && "/check_sig".equals(path);
        if (!allowed || !"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || cookies == null || cookies.length() > 8192 || cookies.chars().anyMatch(c -> c < 32 || c > 126)
                || !cookies.isEmpty() && !poll && !qrImage && !authorize && !loginJump || authorize != (form != null)
                || form != null && form.length > 16384)
            throw new MusicException(MusicError.INVALID_RESPONSE);
        if (!cookies.isEmpty()) for (String pair : cookies.split(";")) {
            String[] item = pair.strip().split("=", 2);
            if (item.length != 2 || !(poll ? java.util.Set.of("qrsig", "pt_login_sig") : qrImage ? java.util.Set.of("pt_login_sig")
                    : java.util.Set.of("p_skey", "p_uin", "pt4_token", "ptcz", "uin", "skey", "pt_oauth_token", "pt_login_type", "RK")).contains(item[0])
                    || !dev.modernmusic.auth.CookieValue.valid(item[1], 2048)) throw new MusicException(MusicError.INVALID_RESPONSE);
        }
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(wxPoll ? 40 : 20))
                .header("User-Agent", WEB_USER_AGENT).header("Accept-Encoding", "identity").header("Cache-Control", "no-store");
        if (!authorize) builder.header("Referer", wx || wxPoll ? "https://open.weixin.qq.com/" : "https://xui.ptlogin2.qq.com/");
        if (!cookies.isEmpty()) builder.header("Cookie", cookies);
        if (form != null) builder.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofByteArray(form));
        return builder.build();
    }
    static HttpRequest kugouRequest(URI uri, SessionCookie cookie, byte[] json) {
        String host = uri.getHost(), path = uri.getRawPath();
        boolean gateway = "gateway.kugou.com".equals(host);
        boolean websiteSession = "login-user.kugou.com".equals(host) && "/v1/cross/".equals(path);
        boolean allowed = websiteSession || gateway && java.util.Set.of("/v5/url", "/v3/get_my_info", "/pubsongs/v2/get_other_list_file_nofilt", "/api/v3/special/song").contains(path)
                || "login-user.kugou.com".equals(host) && java.util.Set.of("/v2/qrcode", "/v2/get_userinfo_qrcode").contains(path)
                || "songsearch.kugou.com".equals(host) && "/song_search_v2".equals(path)
                || "m.kugou.com".equals(host) && "/app/i/getSongInfo.php".equals(path)
                || "www.kugou.com".equals(host) && path.matches("/mixsong/[a-z0-9]{6,14}\\.html") && "share=1".equals(uri.getRawQuery())
                || "krcs.kugou.com".equals(host) && java.util.Set.of("/search", "/download").contains(path);
        if (!allowed || !"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || cookie != null && ((!gateway && !websiteSession) || cookie.platform() != dev.modernmusic.domain.MusicPlatform.KUGOU)
                || websiteSession && (cookie == null || !java.util.Set.of("1010", "1014").contains(cookie.value("appid")))
                || json != null && (!gateway || !"/v3/get_my_info".equals(path) || json.length > 65536))
            throw new MusicException(MusicError.INVALID_RESPONSE);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", WEB_USER_AGENT)
                .header("Referer", "https://www.kugou.com/").header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("Cache-Control", "no-store");
        if (gateway) {
            if ("/v5/url".equals(path)) builder.header("x-router", "trackercdn.kugou.com");
            if ("/v3/get_my_info".equals(path)) builder.header("x-router", "usercenter.kugou.com");
            if ("/api/v3/special/song".equals(path)) builder.header("x-router", "mobilecdn.kugou.com");
        }
        if (json != null) builder.header("Content-Type", "application/json;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofByteArray(json));
        return builder.build();
    }
    static HttpRequest qqRequest(URI uri, SessionCookie cookie, byte[] json) {
        boolean endpoint = "u.y.qq.com".equals(uri.getHost()) && "/cgi-bin/musicu.fcg".equals(uri.getRawPath())
                || "c.y.qq.com".equals(uri.getHost()) && java.util.Set.of("/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                "/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg", "/lyric/fcgi-bin/fcg_query_lyric_new.fcg").contains(uri.getRawPath());
        if (!endpoint || !"https".equals(uri.getScheme()) || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || cookie != null && cookie.platform() != dev.modernmusic.domain.MusicPlatform.QQ
                || json != null && (json.length > 65536 || !"u.y.qq.com".equals(uri.getHost()))) throw new MusicException(MusicError.INVALID_RESPONSE);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", WEB_USER_AGENT)
                .header("Referer", "https://y.qq.com/").header("Origin", "https://y.qq.com")
                .header("Accept", "application/json").header("Accept-Encoding", "identity").header("Cache-Control", "no-store");
        if (cookie != null) builder.header("Cookie", cookie.header());
        if (json != null) builder.header("Content-Type", "application/json;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofByteArray(json));
        return builder.build();
    }

    @Override public CookieResponse getQrAccount(URI uri, int maxBytes, String cookieHeader) {
        return exchange(qrRequest(uri, cookieHeader), maxBytes, true);
    }

    @Override public CookieResponse postAccount(URI uri, int maxBytes, String cookieHeader, byte[] form) {
        requireAccountOrigin(uri);
        if (!uri.getPath().startsWith("/weapi/") || uri.getQuery() != null || form.length > 65536
                || cookieHeader.length() > 32768 || cookieHeader.chars().anyMatch(c -> c < 32 || c > 126))
            throw new MusicException(MusicError.INVALID_RESPONSE);
        return exchange(request(uri, cookieHeader, true, form), maxBytes, true);
    }

    static void requireAccountOrigin(URI uri) {
        if (!"https".equals(uri.getScheme()) || !"music.163.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null
                || !(uri.getPath().startsWith("/api/") || uri.getPath().startsWith("/weapi/"))) throw new MusicException(MusicError.INVALID_RESPONSE);
    }

    private CookieResponse exchange(HttpRequest request, int maxBytes, boolean account) {
        return exchange(request, maxBytes, account, false);
    }
    private CookieResponse exchange(HttpRequest request, int maxBytes, boolean account, boolean login) {
        URI uri = request.uri();
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            int timeout = login ? 45 : account ? 20 : 45;
            exchange = send(request, maxBytes);
            // Cover the whole body too, including a CDN which sends headers then stalls.
            HttpResponse<byte[]> response = exchange.get(timeout, java.util.concurrent.TimeUnit.SECONDS);
            if (login && (response.statusCode() == 302 || response.statusCode() == 303))
                return new CookieResponse(response.body(), response.headers().allValues("Set-Cookie"), response.headers().firstValue("Location").orElse(""));
            if (response.statusCode() != 200 && response.statusCode() != 206) org.slf4j.LoggerFactory.getLogger(BoundedHttpTransport.class).warn(
                    "HTTP request failed service={} host={} status={}", isAudioCdn(uri) ? "audio" : "api", uri.getHost(), response.statusCode());
            return switch (response.statusCode()) {
                case 200 -> new CookieResponse(response.body(), account ? response.headers().allValues("Set-Cookie") : List.of());
                case 206 -> {
                    requireCompleteAudioRange(uri, response.headers().firstValue("Content-Range").orElse(""), response.body().length);
                    yield new CookieResponse(response.body(), List.of());
                }
                case 401, 403, 404 -> throw new MusicException(rejection(uri, response.statusCode()));
                case 429 -> throw new MusicException(MusicError.RATE_LIMITED);
                default -> throw new MusicException(MusicError.NETWORK_ERROR);
            };
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
        } catch (java.util.concurrent.TimeoutException exception) {
            exchange.cancel(true);
            throw new MusicException(MusicError.NETWORK_ERROR);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof MusicException failure) throw failure;
                cause = cause.getCause();
            }
            throw new MusicException(MusicError.NETWORK_ERROR);
        }
    }

    static HttpRequest request(URI uri, String cookies, boolean account, byte[] form) {
        if (!account && form != null) throw new MusicException(MusicError.INVALID_RESPONSE);
        HttpRequest.Builder builder = requestBuilder(uri, cookies, account, WEB_USER_AGENT);
        if (form != null) builder.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(form));
        return builder.build();
    }

    static HttpRequest qrRequest(URI uri, String cookies) {
        requireAccountOrigin(uri);
        boolean allowed = switch (uri.getRawPath()) {
            case "/api/login/qrcode/unikey" -> "type=3".equals(uri.getRawQuery());
            case "/api/login/qrcode/client/login" -> uri.getRawQuery() != null
                    && uri.getRawQuery().matches("key=[A-Za-z0-9_-]{8,256}&type=3");
            default -> false;
        };
        if (!allowed) throw new MusicException(MusicError.INVALID_RESPONSE);
        return requestBuilder(uri, cookies, true, QR_USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded").build();
    }

    private static HttpRequest.Builder requestBuilder(URI uri, String cookies, boolean account, String userAgent) {
        if (account) requireAccountOrigin(uri);
        else if (!cookies.isEmpty()) throw new MusicException(MusicError.INVALID_RESPONSE);
        if (cookies.length() > 32768 || cookies.chars().anyMatch(c -> c < 32 || c > 126))
            throw new MusicException(MusicError.INVALID_RESPONSE);
        // Keep the web client identity consistent between resource resolution and media download.
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(account ? 20 : 45))
                .header("User-Agent", userAgent).header("Referer", dev.modernmusic.provider.kugou.KugouUris.audioHost(uri.getHost()) ? "https://www.kugou.com/" : dev.modernmusic.provider.qq.QqUris.audioHost(uri.getHost()) ? "https://y.qq.com/" : "https://music.163.com/")
                .header("Accept", "*/*").header("Accept-Encoding", "identity").GET();
        if (isAudioCdn(uri)) builder.header("Range", "bytes=0-");
        if (account) builder.header("Cache-Control", "no-store").header("Origin", "https://music.163.com");
        if (!cookies.isEmpty()) builder.header("Cookie", cookies);
        return builder;
    }

    static void requireCompleteAudioRange(URI uri, String range, int length) {
        // Our decoder needs the whole MP3, not an arbitrary partial response or an HTML error page.
        if (!isAudioCdn(uri) || length <= 0 || !range.equals("bytes 0-" + (length - 1) + "/" + length))
            throw new MusicException(MusicError.INVALID_RESPONSE);
    }

    static MusicError rejection(URI uri, int status) {
        // CDN authorization belongs to its signed URL; rejection does not invalidate the account session.
        if (isAudioCdn(uri)) return MusicError.RESOURCE_UNAVAILABLE;
        return status == 401 ? MusicError.AUTH_EXPIRED : MusicError.UNAVAILABLE;
    }
    private static boolean isAudioCdn(URI uri) {
        return uri.getHost() != null && (uri.getHost().endsWith(".music.126.net") || dev.modernmusic.provider.qq.QqUris.audioHost(uri.getHost()) || dev.modernmusic.provider.kugou.KugouUris.audioHost(uri.getHost()));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        // Java 17 HttpClient has no shutdown API. Cancel every in-flight exchange and release it.
        var pending = List.copyOf(requests);
        requests.clear();
        client = null;
        pending.forEach(request -> request.cancel(true));
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - output.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new MusicException(MusicError.RESOURCE_TOO_LARGE));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
