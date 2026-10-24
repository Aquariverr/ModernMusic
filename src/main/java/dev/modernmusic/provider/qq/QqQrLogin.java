package dev.modernmusic.provider.qq;

import com.google.gson.JsonObject;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import static dev.modernmusic.auth.AccountProvider.*;

/** Direct QQ Connect / WeChat authorization, followed by a QQ Music session exchange. */
final class QqQrLogin {
    private static final String QQ_APP = "716027609", CONNECT_APP = "100497308", WX_APP = "wx48db31d50e334801";
    private static final String JUMP = "https://graph.qq.com/oauth2.0/login_jump";
    private static final String QQ_UI = "https://xui.ptlogin2.qq.com", QQ_QR = QQ_UI + "/ssl";
    private static final Set<String> CONNECT_COOKIES = Set.of("p_skey", "p_uin", "pt4_token", "ptcz", "uin", "skey", "pt_oauth_token", "pt_login_type", "RK");
    private static final Pattern QQ_CALLBACK = Pattern.compile("ptuiCB\\((.*)\\);?", Pattern.DOTALL);
    private static final Pattern ARGUMENT = Pattern.compile("'((?:\\\\.|[^'\\\\])*)'");
    private final HttpTransport http;
    private final Clock clock;
    private String stage = "create";
    QqQrLogin(HttpTransport http, Clock clock) { this.http = http; this.clock = clock; }

    QrChallenge begin(QrMethod method) {
        if (method == QrMethod.QQ) {
            var bootstrap = get(QQ_UI + "/cgi-bin/xlogin", params("appid", QQ_APP, "daid", "383", "style", "40",
                    "target", "self", "s_url", JUMP, "pt_3rd_aid", CONNECT_APP), "");
            String loginSig = cookies(Set.of("pt_login_sig"), URI.create(QQ_QR + "/ptqrlogin"),
                    new CookieBatch(bootstrap, URI.create(QQ_UI + "/cgi-bin/xlogin"))).getOrDefault("pt_login_sig", "");
            var version = Pattern.compile("ptui_version\\s*:\\s*encodeURIComponent\\(\"([0-9]{1,16})\"\\)").matcher(body(bootstrap));
            if (!CookieValue.valid(loginSig, 2048) || !version.find()) throw invalid();
            var response = get(QQ_QR + "/ptqrshow", params("appid", QQ_APP, "e", "2", "l", "M", "s", "3",
                    "d", "72", "v", "4", "t", "0." + clock.millis(), "daid", "383", "pt_3rd_aid", CONNECT_APP, "u1", JUMP), "pt_login_sig=" + loginSig);
            String key = cookies(Set.of("qrsig"), URI.create(QQ_QR + "/ptqrlogin"),
                    new CookieBatch(response, URI.create(QQ_QR + "/ptqrshow"))).getOrDefault("qrsig", "");
            if (key.length() < 8 || !CookieValue.valid(key, 2048)) throw invalid();
            var context = new JsonObject(); context.addProperty("qrsig", key); context.addProperty("login_sig", loginSig); context.addProperty("version", version.group(1));
            return new QrChallenge(context.toString(), clock.instant().plusSeconds(120), QrImage.content(response.body()), method);
        }
        if (method != QrMethod.WECHAT) throw new AuthException("qr_unavailable");
        String page = body(get("https://open.weixin.qq.com/connect/qrconnect", params("appid", WX_APP,
                "redirect_uri", redirect("2"), "response_type", "code", "scope", "snsapi_login", "state", "STATE",
                "href", "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect"), ""));
        var match = Pattern.compile("(?:uuid=|/connect/qrcode/)([A-Za-z0-9_-]{8,128})").matcher(page);
        if (!match.find()) throw invalid();
        String key = match.group(1);
        var image = get("https://open.weixin.qq.com/connect/qrcode/" + key, Map.of(), "");
        return new QrChallenge(key, clock.instant().plusSeconds(120), QrImage.content(image.body()), method);
    }

    QrPoll poll(QrChallenge challenge) {
        if (!challenge.expiresAt().isAfter(clock.instant())) return new QrPoll(QrState.EXPIRED, null);
        stage = "poll";
        try {
            return switch (challenge.method()) {
                case QQ -> pollQq(challenge.key());
                case WECHAT -> pollWechat(challenge.key());
                default -> throw invalid();
            };
        } catch (AuthException | MusicException error) { logFailure(challenge.method()); throw error; }
        catch (RuntimeException invalid) { logFailure(challenge.method()); throw invalid(); }
    }
    private QrPoll pollQq(String context) {
        if (context == null || context.length() > 8192) throw invalid();
        var saved = com.google.gson.JsonParser.parseString(context).getAsJsonObject();
        String key = QqApi.text(saved, "qrsig"), loginSig = QqApi.text(saved, "login_sig"), version = QqApi.text(saved, "version");
        if (!CookieValue.valid(loginSig, 2048) || !version.matches("[0-9]{1,16}")) throw invalid();
        if (key.length() < 8 || !CookieValue.valid(key, 2048)) throw invalid();
        var polled = get(QQ_QR + "/ptqrlogin", params("u1", JUMP, "ptqrtoken", hash(key, 0),
                "ptredirect", "0", "h", "1", "t", "1", "g", "1", "from_ui", "1", "ptlang", "2052",
                "action", "0-0-" + clock.millis(), "js_ver", version, "js_type", "1", "pt_uistyle", "40", "login_sig", loginSig,
                "aid", QQ_APP, "daid", "383", "pt_3rd_aid", CONNECT_APP), "qrsig=" + key + "; pt_login_sig=" + loginSig);
        String text = body(polled);
        var callback = QQ_CALLBACK.matcher(text.strip());
        if (!callback.matches()) throw invalid();
        var matcher = ARGUMENT.matcher(callback.group(1)); var args = new ArrayList<String>();
        while (matcher.find()) args.add(matcher.group(1));
        if (args.isEmpty()) throw invalid();
        return switch (args.getFirst()) {
            case "66" -> new QrPoll(QrState.WAITING, null);
            case "67" -> new QrPoll(QrState.CONFIRM, null);
            case "65" -> new QrPoll(QrState.EXPIRED, null);
            case "68" -> new QrPoll(QrState.CANCELLED, null);
            case "0" -> {
                if (args.size() < 3) throw invalid();
                yield new QrPoll(QrState.AUTHORIZED, authorizeQq(args.get(2), polled));
            }
            default -> throw invalid();
        };
    }
    private QrPoll pollWechat(String key) {
        if (!key.matches("[A-Za-z0-9_-]{8,128}")) throw invalid();
        String text = body(get("https://lp.open.weixin.qq.com/connect/l/qrconnect", params("uuid", key, "_", Long.toString(clock.millis())), ""));
        var status = Pattern.compile("window\\.wx_errcode\\s*=\\s*(\\d+)").matcher(text);
        if (!status.find()) throw invalid();
        return switch (status.group(1)) {
            case "408" -> new QrPoll(QrState.WAITING, null);
            case "404" -> new QrPoll(QrState.CONFIRM, null);
            case "402" -> new QrPoll(QrState.EXPIRED, null);
            case "403" -> new QrPoll(QrState.CANCELLED, null);
            case "405" -> {
                var code = Pattern.compile("window\\.wx_code\\s*=\\s*'([A-Za-z0-9_-]{1,512})'").matcher(text);
                if (!code.find()) throw invalid();
                yield new QrPoll(QrState.AUTHORIZED, musicSession(code.group(1), true));
            }
            default -> throw invalid();
        };
    }
    private SessionCookie authorizeQq(String callback, HttpTransport.CookieResponse polled) {
        stage = "callback";
        URI uri = URI.create(callback);
        requireOrigin(uri, "ssl.ptlogin2.graph.qq.com", "/check_sig");
        var query = query(uri);
        String uin = query.getOrDefault("uin", ""), sig = query.getOrDefault("ptsigx", "");
        if (!uin.matches("[1-9][0-9]{0,18}") || !sig.matches("[A-Za-z0-9_-]{1,512}")) throw invalid();
        stage = "check_sig";
        // The server chooses ticket-specific parameters. Rebuilding this URL can invalidate the login.
        var response = http.qqLogin(uri, 1024 * 1024, "", null);
        stage = "temporary_cookies";
        // ptqrlogin supplies the QQ session; check_sig supplies the QQ Connect session.
        var cookies = cookies(CONNECT_COOKIES, URI.create("https://graph.qq.com/oauth2.0/authorize"),
                new CookieBatch(polled, URI.create(QQ_QR + "/ptqrlogin")),
                new CookieBatch(response, URI.create("https://ssl.ptlogin2.graph.qq.com/check_sig")));
        String skey = cookies.getOrDefault("p_skey", "");
        if (skey.isBlank()) throw new AuthException("qr_missing_cookie");
        stage = "login_jump";
        var completed = http.qqLogin(URI.create(JUMP), 65536, cookieHeader(cookies), null);
        cookies = cookies(CONNECT_COOKIES, URI.create("https://graph.qq.com/oauth2.0/authorize"),
                new CookieBatch(polled, URI.create(QQ_QR + "/ptqrlogin")),
                new CookieBatch(response, uri), new CookieBatch(completed, URI.create(JUMP)));
        skey = cookies.getOrDefault("p_skey", "");
        if (skey.isBlank()) throw new AuthException("qr_missing_cookie");
        var form = params("response_type", "code", "client_id", CONNECT_APP, "redirect_uri", redirect("1"),
                "scope", "get_user_info,get_app_friends", "state", "state", "switch", "", "from_ptlogin", "1", "src", "1",
                "update_auth", "1", "openapi", "1010_1030", "g_tk", hash(skey, 5381),
                "auth_time", Long.toString(clock.millis()), "ui", UUID.randomUUID().toString());
        stage = "authorize";
        var authorized = http.qqLogin(URI.create("https://graph.qq.com/oauth2.0/authorize"), 65536,
                cookieHeader(cookies),
                encode(form).getBytes(StandardCharsets.UTF_8));
        stage = "redirect";
        URI location = URI.create(authorized.location());
        // An empty fragment marker does not change the callback origin or authorization query.
        if ("".equals(location.getRawFragment())) location = URI.create(authorized.location().substring(0, authorized.location().length() - 1));
        requireOrigin(location, "y.qq.com", "/portal/wx_redirect.html");
        String code = query(location).getOrDefault("code", "");
        if (!code.matches("[A-Za-z0-9_-]{1,512}")) throw invalid();
        return musicSession(code, false);
    }
    private SessionCookie musicSession(String code, boolean wechat) {
        stage = "music_session";
        var root = new JsonObject(); var comm = new JsonObject();
        comm.addProperty("ct", 24); comm.addProperty("cv", 0); comm.addProperty("format", "json");
        comm.addProperty("tmeLoginType", wechat ? 1 : 2); root.add("comm", comm);
        var param = new JsonObject(); param.addProperty("code", code);
        if (wechat) param.addProperty("strAppid", WX_APP);
        var request = new JsonObject(); request.addProperty("module", wechat ? "music.login.LoginServer" : "QQConnectLogin.LoginServer");
        request.addProperty("method", wechat ? "Login" : "QQLogin"); request.add("param", param); root.add("req_0", request);
        var response = QqApi.json(http.qqApi(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"), 65536, null,
                root.toString().getBytes(StandardCharsets.UTF_8)).body()).getAsJsonObject("req_0");
        if (response == null) throw invalid();
        QqApi.check(response);
        var data = response.getAsJsonObject("data");
        String musicid = QqApi.text(data, "str_musicid"), musickey = QqApi.text(data, "musickey");
        if (musicid.isEmpty()) musicid = QqApi.text(data, "musicid");
        if (!musicid.matches("[1-9][0-9]{0,18}") || !CookieValue.valid(musickey, 4096))
            throw new AuthException("qr_missing_cookie");
        return SessionCookie.parse((wechat ? "wxuin=" : "uin=") + musicid + "; qqmusic_key=" + musickey
                + "; qm_keyst=" + musickey + "; login_type=" + (wechat ? "2" : "1"), MusicPlatform.QQ);
    }
    private HttpTransport.CookieResponse get(String endpoint, Map<String, String> params, String cookies) {
        return http.qqLogin(URI.create(endpoint + (params.isEmpty() ? "" : "?" + encode(params))), 1024 * 1024, cookies, null);
    }
    private static String redirect(String type) { return "https://y.qq.com/portal/wx_redirect.html?login_type=" + type + "&surl=https://y.qq.com/"; }
    private static Map<String, String> params(String... pairs) {
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    private static String encode(Map<String, String> params) {
        return params.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private static String body(HttpTransport.CookieResponse response) { return new String(response.body(), StandardCharsets.UTF_8); }
    static String hash(String key, int seed) {
        int result = seed; for (int i = 0; i < key.length(); i++) result += (result << 5) + key.charAt(i);
        return Integer.toString(result & 0x7fffffff);
    }
    private record CookieScope(String name, String domain, String path) {}
    private record ScopedCookie(HttpCookie cookie, boolean hostOnly) {}
    private record CookieBatch(HttpTransport.CookieResponse response, URI origin) {}
    private static Map<String, String> cookies(Set<String> allowed, URI target, CookieBatch... batches) {
        var scoped = new LinkedHashMap<CookieScope, ScopedCookie>();
        for (var batch : batches) for (String header : batch.response().setCookies()) {
            URI origin = batch.origin();
            String[] pair = header.split(";", 2)[0].strip().split("=", 2);
            if (pair.length == 2 && allowed.contains(pair[0])) {
                final List<HttpCookie> parsed;
                try { parsed = HttpCookie.parse(header); } catch (IllegalArgumentException malformed) { throw invalid(); }
                for (var cookie : parsed) {
                    if (!allowed.contains(cookie.getName())) continue;
                    boolean hostOnly = cookie.getDomain() == null;
                    String domain = hostOnly ? origin.getHost() : cookie.getDomain().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                    if (!(domain.equals("qq.com") || domain.endsWith(".qq.com")) || !domainMatches(origin.getHost(), domain)) continue;
                    String path = cookie.getPath();
                    if (path == null || !path.startsWith("/")) {
                        int slash = origin.getPath().lastIndexOf('/');
                        path = slash <= 0 ? "/" : origin.getPath().substring(0, slash);
                    }
                    var scope = new CookieScope(cookie.getName(), domain, path);
                    // Deleting .qq.com cookies must not erase a new .graph.qq.com login cookie of the same name.
                    if (cookie.hasExpired() || cookie.getValue().isEmpty()) { scoped.remove(scope); continue; }
                    if (!CookieValue.valid(cookie.getValue(), 2048)) throw invalid();
                    scoped.put(scope, new ScopedCookie(cookie, hostOnly));
                }
            }
        }
        var result = new LinkedHashMap<String, String>();
        scoped.entrySet().stream().filter(entry -> {
            var scope = entry.getKey();
            return (entry.getValue().hostOnly() ? target.getHost().equals(scope.domain()) : domainMatches(target.getHost(), scope.domain()))
                    && (target.getPath().equals(scope.path()) || target.getPath().startsWith(scope.path().endsWith("/") ? scope.path() : scope.path() + "/"));
        }).sorted(Comparator.<Map.Entry<CookieScope, ScopedCookie>>comparingInt(e -> e.getKey().path().length()).reversed()
                .thenComparing(Comparator.comparingInt((Map.Entry<CookieScope, ScopedCookie> e) -> e.getKey().domain().length()).reversed()))
                .forEach(entry -> result.putIfAbsent(entry.getKey().name(), entry.getValue().cookie().getValue()));
        return result;
    }
    private static boolean domainMatches(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    private static String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("; "));
    }
    private static Map<String, String> query(URI uri) {
        var result = new HashMap<String, String>();
        if (uri.getRawQuery() == null) throw invalid();
        for (String item : uri.getRawQuery().split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2 && result.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) throw invalid();
        }
        return result;
    }
    private static void requireOrigin(URI uri, String host, String path) {
        if (!"https".equals(uri.getScheme()) || !host.equals(uri.getHost()) || !path.equals(uri.getRawPath())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getFragment() != null) throw invalid();
    }
    private static AuthException invalid() { return new AuthException("invalid_response"); }
    private void logFailure(QrMethod method) {
        org.slf4j.LoggerFactory.getLogger(QqQrLogin.class).warn("QQ Music QR failed method={} stage={}", method, stage);
    }
}
