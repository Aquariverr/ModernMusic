package dev.modernmusic.provider.qq;

import com.google.gson.*;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class QqApi {
    private QqApi() {}
    static JsonObject json(byte[] bytes) {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            check(root); return root;
        } catch (MusicException failure) { throw failure; }
        catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    static void check(JsonObject root) {
        int code = root.has("code") ? root.get("code").getAsInt() : -1;
        if (code == 0) return;
        throw new MusicException(code == 1000 || code == 2000 ? MusicError.AUTH_EXPIRED : code == 429 ? MusicError.RATE_LIMITED : MusicError.UNAVAILABLE);
    }
    static JsonObject rpc(HttpTransport http, SessionCookie cookie, String module, String method, JsonObject param) {
        var root = new JsonObject(); var comm = new JsonObject();
        comm.addProperty("ct", 24); comm.addProperty("cv", 0); comm.addProperty("format", "json");
        comm.addProperty("uin", cookie == null ? "0" : cookie.qqUin());
        comm.addProperty("g_tk", token(cookie));
        if (cookie != null) {
            comm.addProperty("ct", 19);
            comm.addProperty("authst", cookie.value("qqmusic_key").isEmpty() ? cookie.value("qm_keyst") : cookie.value("qqmusic_key"));
            comm.addProperty("tmeLoginType", "2".equals(cookie.value("login_type")) ? 1 : 2);
        }
        // QQ's mobile search schema includes item_song and requires a matching client profile.
        boolean search = module.equals("music.search.SearchCgiService");
        String key = "req_0";
        if (search) {
            comm = new JsonObject(); comm.addProperty("ct", 11); comm.addProperty("cv", 1003006);
            comm.addProperty("v", 1003006); comm.addProperty("tmeAppID", "qqmusic"); comm.addProperty("format", "json");
        }
        root.add("comm", comm);
        var call = new JsonObject(); call.addProperty("module", module); call.addProperty("method", method); call.add("param", param); root.add(key, call);
        JsonObject response = json(http.qqApi(URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg"), 2 * 1024 * 1024, cookie,
                root.toString().getBytes(StandardCharsets.UTF_8)).body()).getAsJsonObject(key);
        if (response == null) throw new MusicException(MusicError.INVALID_RESPONSE);
        check(response);
        JsonObject data = response.getAsJsonObject("data");
        if (data == null) throw new MusicException(MusicError.INVALID_RESPONSE);
        return data;
    }
    static int token(SessionCookie cookie) {
        String key = cookie == null ? "" : cookie.value("qqmusic_key").isEmpty() ? cookie.value("qm_keyst") : cookie.value("qqmusic_key");
        int hash = 5381; for (int i = 0; i < key.length(); i++) hash += (hash << 5) + key.charAt(i);
        return hash & 0x7fffffff;
    }
    static String text(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
