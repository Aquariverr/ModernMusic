package dev.modernmusic.provider;

import javax.annotation.Nonnull;
import java.net.URI;
import java.util.List;
import dev.modernmusic.auth.SessionCookie;

@FunctionalInterface
public interface HttpTransport {
    byte[] get(URI uri, int maxBytes);
    /** Fixed first-party Kugou API routes; account credentials never accompany media requests. */
    default CookieResponse kugouApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
        throw new MusicException(MusicError.INTERNAL_ERROR);
    }
    /** QQ first-party API only; credentials never accompany CDN downloads. */
    default CookieResponse qqApi(URI uri, int maxBytes, SessionCookie cookie, byte[] json) {
        throw new MusicException(MusicError.INTERNAL_ERROR);
    }
    /** QQ/WeChat login routes only. Redirects are returned, never followed. */
    default CookieResponse qqLogin(URI uri, int maxBytes, String cookies, byte[] form) {
        throw new MusicException(MusicError.INTERNAL_ERROR);
    }

    default CookieResponse getWithCookies(URI uri, int maxBytes, SessionCookie cookie) {
        if (cookie != null) throw new MusicException(MusicError.INTERNAL_ERROR);
        return new CookieResponse(get(uri, maxBytes), List.of());
    }

    /** Desktop QR endpoints only. The challenge URL and temporary cookies must never be logged. */
    default CookieResponse getQrAccount(URI uri, int maxBytes, String cookieHeader) {
        throw new MusicException(MusicError.INTERNAL_ERROR);
    }

    /** First-party account POST only. The form and cookies are sensitive and must never be logged. */
    default CookieResponse postAccount(URI uri, int maxBytes, String cookieHeader, byte[] form) {
        throw new MusicException(MusicError.INTERNAL_ERROR);
    }

    record CookieResponse(byte[] body, List<String> setCookies, String location) {
        public CookieResponse(byte[] body, List<String> setCookies) { this(body, setCookies, ""); }
        @Nonnull @Override public String toString() { return "CookieResponse[redacted]"; }
    }
}
