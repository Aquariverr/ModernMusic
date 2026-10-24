package dev.modernmusic.provider.kugou;

import com.google.gson.*;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** First-party Kugou endpoints. Requests and responses may contain credentials or signed URLs. */
final class KugouApi {
    private static final String SALT = "OIlwieks28dk2k092lksi2UIkp";
    private final HttpTransport http;
    private final Clock clock;
    private final String guestMid = UUID.randomUUID().toString().replace("-", "");
    KugouApi(HttpTransport http, Clock clock) { this.http = http; this.clock = clock; }
    String appid(SessionCookie cookie) {
        return cookie == null || cookie.value("appid").isEmpty() ? "1005" : cookie.value("appid");
    }
    String mid(SessionCookie cookie) {
        if (cookie == null) return guestMid;
        String value = cookie.value("mid");
        return value.isEmpty() ? md5("modernmusic:" + cookie.value("userid")) : value;
    }
    HttpTransport.CookieResponse websiteSession(SessionCookie cookie) {
        var params = new LinkedHashMap<String, String>();
        params.put("a_id", appid(cookie)); params.put("userid", cookie.value("userid")); params.put("t", cookie.value("token"));
        params.put("ct", cookie.value("ct").isEmpty() ? Long.toString(clock.instant().getEpochSecond()) : cookie.value("ct"));
        params.put("callback", ""); params.put("domain", "kugou.com"); params.put("midname", "kg_mid"); params.put("days", "365");
        params.put("mid", mid(cookie));
        return http.kugouApi(uri("https://login-user.kugou.com/v1/cross/", params), 65536, cookie, null);
    }
    JsonObject gateway(String path, Map<String, String> parameters, JsonObject body, SessionCookie cookie) {
        if (cookie != null && cookie.platform() != MusicPlatform.KUGOU) throw new MusicException(MusicError.INVALID_RESPONSE);
        var params = new TreeMap<String, String>();
        boolean web = !appid(cookie).equals("1005");
        params.put("appid", appid(cookie)); params.put("clientver", web ? "1000" : "20489"); params.put("clienttime", Long.toString(clock.instant().getEpochSecond()));
        params.put("mid", mid(cookie)); params.put("dfid", cookie == null || cookie.value("dfid").isEmpty() ? "-" : cookie.value("dfid")); params.put("uuid", "-");
        if (web) { params.put("srcappid", "2919"); params.put("uuid", mid(cookie)); }
        if (cookie != null) { params.put("userid", cookie.value("userid")); params.put("token", cookie.value("token")); }
        params.putAll(parameters);
        String json = body == null ? "" : body.toString();
        String salt = web ? "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" : SALT;
        params.put("signature", md5(salt + params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining()) + json + salt));
        return json(http.kugouApi(uri("https://gateway.kugou.com" + path, params), 4 * 1024 * 1024, cookie,
                body == null ? null : json.getBytes(StandardCharsets.UTF_8)).body());
    }
    JsonObject get(String endpoint, Map<String, String> parameters) {
        return json(http.kugouApi(uri(endpoint, parameters), 4 * 1024 * 1024, null, null).body());
    }
    JsonObject qr(String path, Map<String, String> parameters) {
        var params = new TreeMap<String, String>();
        params.put("appid", "1005"); params.put("srcappid", "2919"); params.put("clientver", "20489");
        params.put("clienttime", Long.toString(clock.instant().getEpochSecond()));
        params.put("mid", guestMid); params.put("dfid", "-"); params.put("uuid", "-"); params.put("plat", "4");
        params.putAll(parameters);
        String salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
        params.put("signature", md5(salt + params.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .sorted().collect(java.util.stream.Collectors.joining()) + salt));
        return json(http.kugouApi(uri("https://login-user.kugou.com" + path, params), 65536, null, null).body());
    }
    JsonObject sharedSong(String encodedId) {
        // The official mobile song link points to this page. Read its JSON data, never execute scripts or follow share redirects.
        if (!encodedId.matches("[a-z0-9]{6,14}") || encodedId.matches("[0-9]+")) throw new MusicException(MusicError.INVALID_RESPONSE);
        byte[] body = http.kugouApi(URI.create("https://www.kugou.com/mixsong/" + encodedId + ".html?share=1"),
                1024 * 1024, null, null).body();
        String page = new String(body, StandardCharsets.UTF_8);
        var assignment = java.util.regex.Pattern.compile("\\bvar\\s+dataFromSmarty\\s*=\\s*").matcher(page);
        if (!assignment.find()) throw new MusicException(MusicError.INVALID_RESPONSE);
        try {
            // JsonReader consumes just the array; trailing page JavaScript is not JSON and is never evaluated.
            var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(page.substring(assignment.end())));
            var songs = JsonParser.parseReader(reader).getAsJsonArray();
            if (songs.size() != 1) throw new MusicException(MusicError.INVALID_RESPONSE);
            var song = songs.get(0).getAsJsonObject();
            if (!encodedId.equals(text(song, "encode_album_audio_id"))) throw new MusicException(MusicError.INVALID_RESPONSE);
            if (!text(song, "hash").matches("[A-Fa-f0-9]{32}")) throw new MusicException(MusicError.UNAVAILABLE);
            return song;
        } catch (MusicException error) { throw error; }
        catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    static URI uri(String endpoint, Map<String, String> parameters) {
        return URI.create(endpoint + "?" + parameters.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&")));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String md5(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static JsonObject json(byte[] body) {
        try {
            var root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            int code = root.has("error_code") ? root.get("error_code").getAsInt() : root.has("errcode") ? root.get("errcode").getAsInt() : 0;
            if (code == 20018) throw new MusicException(MusicError.AUTH_EXPIRED);
            if (code == 152) throw new MusicException(MusicError.AUTH_REQUIRED);
            if (code != 0 && code != 200 || root.has("status") && root.get("status").getAsInt() == 0) throw new MusicException(MusicError.UNAVAILABLE);
            return root;
        } catch (MusicException error) { throw error; }
        catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    static String text(JsonObject obj, String... fields) {
        if (obj != null) for (String field : fields) if (obj.has(field) && obj.get(field).isJsonPrimitive()) {
            String value = obj.get(field).getAsString(); if (!value.isBlank()) return value;
        }
        return "";
    }
    static String display(String value) {
        String clean = value.replaceAll("<[^>]{0,256}>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replaceAll("[\\p{Cntrl}§]", "").strip();
        return clean.substring(0, Math.min(256, clean.length()));
    }
}
