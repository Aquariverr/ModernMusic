package dev.modernmusic.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionCookieTest {
    @Test void playbackSessionCookieSurvivesLoginAndStorageRepresentation() {
        var cookie = SessionCookie.parse("MUSIC_U=fixture; __csrf=csrf; NMTID=playback-secret; analytics=discard");
        assertEquals("MUSIC_U=fixture; __csrf=csrf; NMTID=playback-secret", cookie.header());
        assertEquals(cookie.header(), SessionCookie.parse(cookie.header()).header());
        assertFalse(cookie.toString().contains("playback-secret"));
        assertThrows(AuthException.class, () -> SessionCookie.parse("NMTID=not-a-login"));
    }
    @Test void retainsOnlyMinimumCookiesAndRedactsAllDiagnostics() {
        var cookie = SessionCookie.parse("Cookie: analytics=discard; MUSIC_U=test-secret; __csrf=test-csrf; os=pc");
        assertEquals("MUSIC_U=test-secret; __csrf=test-csrf", cookie.header());
        assertFalse(cookie.toString().contains("test-secret"));
        assertFalse(new AccountSession("42", "Test", cookie, Instant.EPOCH).toString().contains("test-secret"));
        assertFalse(new AccountProvider.QrChallenge("qr-secret", Instant.EPOCH).toString().contains("qr-secret"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "not-a-cookie", "MUSIC_A=anonymous", "__csrf=token", "MUSIC_U=",
            "MUSIC_U=a; MUSIC_U=b", "MUSIC_U=a\r\nInjected: b", "MUSIC_U=hello world", "MUSIC_U=\"secret\"", "MUSIC_U=中文"})
    void rejectsMalformedOrAmbiguousCredentials(String value) {
        var error = assertThrows(AuthException.class, () -> SessionCookie.parse(value));
        assertEquals("modernmusic.auth.invalid_cookie", error.translationKey());
        assertNull(error.getCause());
    }
    @Test void capsInputAndHandlesSeparateSetCookieHeaders() {
        assertThrows(AuthException.class, () -> SessionCookie.parse("MUSIC_U=" + "x".repeat(SessionCookie.MAX_LENGTH)));
        var cookie = SessionCookie.fromResponse(List.of("MUSIC_U=abc==; Domain=.music.163.com; Secure; HttpOnly", "__csrf=xyz; Path=/", "NMTID=session; Path=/", "ignored=data"));
        assertEquals("MUSIC_U=abc==; __csrf=xyz; NMTID=session", cookie.header());
    }
}
