package dev.modernmusic.provider.qq;

import dev.modernmusic.provider.*;
import java.net.URI;
import java.util.Set;

public final class QqUris {
    private QqUris() {}
    public static boolean audioHost(String host) {
        return host != null && (host.equals("stream.qqmusic.qq.com") || host.endsWith(".stream.qqmusic.qq.com"));
    }
    private static URI checked(String value, boolean image) {
        try {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            boolean allowed = image ? Set.of("y.gtimg.cn", "y.qq.com", "q.qlogo.cn", "thirdqq.qlogo.cn", "thirdwx.qlogo.cn").contains(host) : audioHost(host);
            if (!allowed || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getRawPath().isEmpty()) throw new IllegalArgumentException();
            return URI.create("https" + uri.toASCIIString().substring(uri.getScheme().length()));
        } catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    public static URI audio(String value) { return checked(value, false); }
    public static URI image(String value) { try { return checked(value, true); } catch (RuntimeException ignored) { return null; } }
}
