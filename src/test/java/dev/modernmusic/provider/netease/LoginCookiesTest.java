package dev.modernmusic.provider.netease;

import dev.modernmusic.auth.*;
import dev.modernmusic.provider.MusicException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LoginCookiesTest {
    @Test void snapshotsKeepMatchingCsrfAndHeaderAndLateResponsesOnlyMergeUnchangedCookies() {
        var jar = new LoginCookies(SessionCookie.parse("MUSIC_U=fixture; NMTID=before; __csrf=before"));
        var sent = jar.snapshot();
        jar.accept(List.of("NMTID=ready; Path=/", "__csrf=ready; Path=/"), sent);
        jar.accept(List.of("NMTID=stale; Path=/", "__csrf=; Max-Age=0; Path=/", "__remember_me=true; Path=/"), sent);
        assertEquals("ready", jar.playbackToken()); assertEquals("ready", jar.csrf());
        assertTrue(jar.header().contains("__remember_me=true"));
        assertEquals("before", sent.csrf()); assertTrue(sent.header().contains("__csrf=before"));
        assertFalse(sent.toString().contains("fixture"));
        assertThrows(UnsupportedOperationException.class, () -> sent.values().put("NMTID", "changed"));
    }

    @Test void invalidCookieResponseDoesNotPartiallyChangeTheSession() {
        var jar = new LoginCookies(SessionCookie.parse("MUSIC_U=fixture; NMTID=ready"));
        assertThrows(MusicException.class, () -> jar.accept(List.of("NMTID=changed; Path=/", "__csrf=bad\nvalue; Path=/")));
        assertEquals("ready", jar.playbackToken());
    }

    @Test void webLoginDoesNotSendDesktopClientIdentity() {
        assertEquals("", new LoginCookies().header());
        var jar = new LoginCookies(SessionCookie.parse("MUSIC_U=fixture"));
        jar.accept(List.of("os=pc; Path=/", "appver=3.1.17; Path=/"));
        assertEquals("MUSIC_U=fixture", jar.header());
    }
    @Test void preservesBootstrapThenRotatesCredentialsAndPersistsOnlyMinimum() {
        LoginCookies jar = new LoginCookies();
        jar.accept(List.of("NMTID=bootstrap; Domain=.music.163.com; Secure", "__csrf=first; Path=/", "analytics=ignore; Path=/"));
        assertTrue(jar.header().contains("NMTID=bootstrap")); assertFalse(jar.header().contains("analytics"));
        jar.accept(List.of("MUSIC_U=first-token; Path=/", "MUSIC_U=second-token; Path=/", "__csrf=updated; Path=/"));
        assertEquals("MUSIC_U=second-token; __csrf=updated; NMTID=bootstrap", jar.session().header());
        assertFalse(jar.toString().contains("second-token"));
    }
    @Test void honorsDeletionAndRejectsForeignOrMalformedResponseCookies() {
        LoginCookies jar = new LoginCookies(SessionCookie.parse("MUSIC_U=fixture"));
        jar.accept(List.of("MUSIC_U=foreign; Domain=example.com; Path=/"));
        assertEquals("MUSIC_U=fixture", jar.session().header());
        jar.accept(List.of("MUSIC_U=deleted; Max-Age=0; Path=/"));
        assertThrows(AuthException.class, jar::session);
        assertThrows(MusicException.class, () -> jar.accept(List.of("__csrf=bad\nvalue; Path=/")));
    }
}
