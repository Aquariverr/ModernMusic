package dev.modernmusic.provider.netease;

import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import java.net.URI;
import java.util.Locale;

public final class NeteaseUris {
    private NeteaseUris() {}

    /** Optional artwork must never make an otherwise playable track fail. */
    public static URI image(String value) {
        if (value == null || value.length() > 4096) return null;
        try {
            URI uri = audio(value);
            if (!uri.getHost().matches("p[0-9]+[a-z]?\\.music\\.126\\.net")) return null;
            return uri;
        } catch (RuntimeException ignored) { return null; }
    }

    /** Request a CDN thumbnail instead of downloading full-resolution album art that may exceed decoder limits. */
    public static URI thumbnail(String value, int size) {
        if (size < 1 || size > 1024) throw new IllegalArgumentException("thumbnail size");
        URI uri = image(value);
        if (uri == null) return null;
        String raw = uri.toASCIIString();
        int queryStart = raw.indexOf('?');
        String base = queryStart < 0 ? raw : raw.substring(0, queryStart);
        var query = new java.util.ArrayList<String>();
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            if (!part.isEmpty() && !part.equals("param") && !part.startsWith("param=")) query.add(part);
        }
        query.add("param=" + size + "y" + size);
        return URI.create(base + "?" + String.join("&", query));
    }

    /** Only provider-returned NetEase music CDN hosts. Never accept arbitrary redirect destinations. */
    public static URI audio(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".music.126.net")
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawPath() == null || uri.getRawPath().isEmpty()) {
                throw new IllegalArgumentException();
            }
            // The tested API returns HTTP CDN URLs; always upgrade the same resource to TLS.
            return URI.create("https" + uri.toASCIIString().substring(uri.getScheme().length()));
        } catch (IllegalArgumentException exception) {
            throw new MusicException(MusicError.INVALID_RESPONSE);
        }
    }
}
