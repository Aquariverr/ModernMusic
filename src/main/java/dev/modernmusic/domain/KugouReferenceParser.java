package dev.modernmusic.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses full Kugou links locally; share redirects never receive account credentials. */
public final class KugouReferenceParser {
    private KugouReferenceParser() {}
    public record SongReference(String value, boolean encoded) {}
    public static SongReference song(String input) {
        String value = parse(input, false);
        return new SongReference(value, encoded(value));
    }
    public static SongId playlist(String input) { return new SongId(parse(input, true), MusicPlatform.KUGOU); }
    private static String parse(String input, boolean playlist) {
        String text = ReferenceInput.normalize(input);
        if (valid(text, playlist)) return playlist ? text : text.toUpperCase(java.util.Locale.ROOT);
        String link = ReferenceInput.singleUrl(text, "kugou_unsupported");
        try {
            URI uri = URI.create(link);
            if (!Set.of("www.kugou.com", "m.kugou.com", "music.kugou.com").contains(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null) throw fail("kugou_unsupported");
            String path = uri.getPath(), id = null, query = uri.getRawQuery();
            if (uri.getRawFragment() != null) {
                if (playlist || !"/song/".equals(path) || query != null) throw fail("kugou_unsupported");
                query = uri.getRawFragment();
                if (encoded(query)) return query;
                if (encoded(query.split("&", 2)[0])) throw fail("ambiguous");
            }
            if (!playlist) {
                var encodedRoute = Pattern.compile("/(?:song|mixsong)/([a-z0-9]{6,14})\\.html").matcher(path);
                if (encodedRoute.matches() && encoded(encodedRoute.group(1)) && (query == null || "share=1".equals(query))) return encodedRoute.group(1);
            }
            var route = Pattern.compile(playlist ? "/(?:songlist/(collection_[0-9_]+)|yy/special/single/([0-9]+)\\.html|plist/list/([0-9]+))/?"
                    : "/song/([A-Fa-f0-9]{32})(?:\\.html)?/?").matcher(path);
            if (route.matches()) for (int i = 1; i <= route.groupCount(); i++) if (route.group(i) != null) id = route.group(i);
            boolean queryRoute = playlist ? Set.of("/songlist/", "/songlist", "/share/zlist.html", "/yy/special/single").contains(path)
                    : Set.of("/song/", "/song", "/share/song.html", "/yy/index.php").contains(path);
            if (queryRoute && query != null) for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if ((playlist ? Set.of("specialid", "global_collection_id", "id") : Set.of("hash", "Hash", "FileHash")).contains(key)) {
                    if (id != null) throw fail("ambiguous");
                    id = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                }
            }
            if (!valid(id, playlist)) throw fail("kugou_unsupported");
            return playlist ? id : id.toUpperCase(java.util.Locale.ROOT);
        } catch (SongReferenceParser.InputException error) { throw error; }
        catch (RuntimeException error) { throw fail("invalid_url"); }
    }
    private static boolean encoded(String value) { return value.matches("[a-z0-9]{6,14}") && !value.matches("[0-9]+"); }
    private static boolean valid(String id, boolean playlist) {
        if (id == null) return false;
        if (!playlist) return id.matches("[A-Fa-f0-9]{32}");
        if (id.matches("collection_[0-9]{1,2}_[0-9]{1,19}_[0-9]{1,19}_[0-9]")) return true;
        try { return id.matches("[1-9][0-9]{0,18}") && Long.parseLong(id) > 0; } catch (RuntimeException error) { return false; }
    }
    private static SongReferenceParser.InputException fail(String code) { return new SongReferenceParser.InputException(code); }
}
