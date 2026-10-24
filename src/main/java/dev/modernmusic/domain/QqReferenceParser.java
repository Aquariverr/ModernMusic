package dev.modernmusic.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Only full first-party links are accepted. No untrusted share URL is fetched. */
public final class QqReferenceParser {
    private QqReferenceParser() {}
    public static String song(String input) { return parse(input, false); }
    public static SongId playlist(String input) { return new SongId(Long.parseLong(parse(input, true)), MusicPlatform.QQ); }
    private static String parse(String input, boolean playlist) {
        String text = ReferenceInput.normalize(input);
        if (valid(text, playlist)) return text;
        String link = ReferenceInput.singleUrl(text, "qq_unsupported");
        try {
            URI uri = URI.create(link);
            if (!Set.of("y.qq.com", "i.y.qq.com", "m.y.qq.com").contains(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getRawFragment() != null && !"webchat_redirect".equals(uri.getRawFragment()))
                throw fail("qq_unsupported");
            String path = uri.getPath(), id = null;
            var route = Pattern.compile(playlist ? "/(?:n/ryqq(?:_v2)?/playlist|n/yqq/playlist)/([0-9]+)(?:\\.html)?" : "/(?:n/ryqq(?:_v2)?/songDetail|n/yqq/song)/([A-Za-z0-9]+)(?:\\.html)?").matcher(path);
            if (route.matches()) id = route.group(1);
            boolean queryRoute = playlist ? Set.of("/n/ryqq/playlist", "/n/ryqq_v2/playlist", "/n/yqq/playlist", "/n/m/detail/taoge/index.html").contains(path)
                    : Set.of("/n/ryqq/songDetail", "/n/yqq/song", "/n/ryqq_v2/songDetail", "/n/ryqq_v2/songDetail/",
                            "/n/ryqq/player", "/n/ryqq_v2/player", "/n/m/detail/song/index.html", "/v8/playsong.html").contains(path);
            if (queryRoute && uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if ((playlist ? Set.of("id", "disstid") : Set.of("id", "songid", "songmid", "mid")).contains(key)) {
                    if (id != null) throw fail("ambiguous");
                    id = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                }
            }
            if (!valid(id, playlist)) throw fail("qq_unsupported");
            return id;
        } catch (SongReferenceParser.InputException failure) { throw failure; }
        catch (RuntimeException invalid) { throw fail("invalid_url"); }
    }
    private static boolean valid(String id, boolean playlist) {
        if (id == null) return false;
        if (!playlist && id.matches("[A-Za-z0-9]{14}") && !id.matches("[0-9]+")) return true;
        try { return id.matches("[0-9]{1,19}") && Long.parseLong(id) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static SongReferenceParser.InputException fail(String code) { return new SongReferenceParser.InputException(code); }
}
