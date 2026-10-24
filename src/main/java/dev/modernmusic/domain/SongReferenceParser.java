package dev.modernmusic.domain;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline parser. Short links are intentionally unsupported until their redirect policy is verified. */
public final class SongReferenceParser {
    public static final int MAX_INPUT_LENGTH = 2048;
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,19}");
    private static final Set<String> HOSTS = Set.of("music.163.com", "y.music.163.com");
    private static final Set<String> PATHS = Set.of("/song", "/song/", "/m/song", "/m/song/");

    private SongReferenceParser() {}

    public static SongId parse(String input) {
        return parse(input, false);
    }

    public static SongId parsePlaylist(String input) { return parse(input, true); }

    private static SongId parse(String input, boolean playlist) {
        String text = ReferenceInput.normalize(input);
        if (DECIMAL.matcher(text).matches()) return parseId(text);
        String candidate = ReferenceInput.singleUrl(text, "unsupported");
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null || !HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))
                    || uri.getRawUserInfo() != null || uri.getPort() != -1) {
                throw new InputException("unsupported");
            }
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            if (uri.getRawFragment() != null) {
                if (!(path.isEmpty() || path.equals("/")) || query != null) throw new InputException("unsupported");
                URI route = URI.create(uri.getRawFragment());
                if (route.isAbsolute() || route.getRawAuthority() != null || route.getRawFragment() != null) {
                    throw new InputException("unsupported");
                }
                path = route.getRawPath();
                query = route.getRawQuery();
            }
            boolean allowed = playlist ? Set.of("/playlist", "/playlist/", "/m/playlist", "/m/playlist/").contains(path) : PATHS.contains(path);
            if (!allowed || query == null) throw new InputException(playlist ? "playlist_unsupported" : "unsupported");
            String id = null;
            for (String pair : query.split("&", -1)) {
                String[] parts = pair.split("=", 2);
                String key = decode(parts[0]);
                String value = parts.length == 2 ? decode(parts[1]) : "";
                if (key.equals("id")) {
                    if (id != null) throw new InputException("ambiguous");
                    id = value;
                }
            }
            if (id == null) throw new InputException("invalid_id");
            return parseId(id);
        } catch (InputException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new InputException("invalid_url");
        }
    }

    private static String decode(String text) {
        String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8);
        if (decoded.indexOf('\uFFFD') >= 0) throw new InputException("invalid_url");
        return decoded;
    }

    private static SongId parseId(String value) {
        try {
            if (!DECIMAL.matcher(value).matches()) throw new NumberFormatException();
            return new SongId(Long.parseLong(value));
        } catch (IllegalArgumentException exception) {
            throw new InputException("invalid_id");
        }
    }

    public static final class InputException extends IllegalArgumentException {
        private final String code;

        public InputException(String code) {
            super(code); // Never put the original share text in exceptions or logs.
            this.code = code;
        }

        public String translationKey() {
            return "modernmusic.input." + code;
        }
    }
}
