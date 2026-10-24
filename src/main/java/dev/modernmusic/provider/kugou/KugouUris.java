package dev.modernmusic.provider.kugou;

import dev.modernmusic.provider.*;
import java.net.URI;
import java.util.Set;

public final class KugouUris {
    private KugouUris() {}
    public static boolean audioHost(String host) {
        return host != null && (host.equals("fsandroid.kugou.com") || host.equals("fsmobile.kugou.com")
                || host.matches("(?:fsandroid|fsmobile|sharefs|webfs)(?:\\.[a-z0-9-]+)?\\.kugou\\.com"));
    }
    private static URI https(String value) {
        URI uri = URI.create(value);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw new MusicException(MusicError.INVALID_RESPONSE);
        return "http".equals(uri.getScheme()) ? URI.create("https" + value.substring(4)) : uri;
    }
    public static URI audio(String value) {
        try { var uri = https(value); if (audioHost(uri.getHost())) return uri; }
        catch (IllegalArgumentException ignored) { }
        throw new MusicException(MusicError.INVALID_RESPONSE);
    }
    public static URI image(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) return null;
        try {
            var uri = https(value.replace("{size}", "400"));
            return Set.of("imge.kugou.com", "imgessl.kugou.com", "singerimg.kugou.com", "singerimgssl.kugou.com", "c1.kgimg.com")
                    .contains(uri.getHost()) ? uri : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
