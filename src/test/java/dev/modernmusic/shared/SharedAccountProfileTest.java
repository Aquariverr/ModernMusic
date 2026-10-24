package dev.modernmusic.shared;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedAccountProfileTest {
    @Test void displayFieldsAreBoundedAndOnlyNeteaseImageHostsAreAccepted() {
        var profile = new SharedAccountProfile("\nServer§\t", "http://p1.music.126.net/avatar.png");
        assertEquals("Server", profile.nickname()); assertEquals("https", profile.avatarUri().getScheme());
        for (String url : new String[] {"file:///secret", "https://127.0.0.1/avatar", "https://evil.example/image", "https://m1.music.126.net/audio.mp3", "https://p1.music.126.net@evil/avatar"})
            assertNull(new SharedAccountProfile("Server", url).avatarUri());
        assertThrows(IllegalArgumentException.class, () -> new SharedAccountProfile("x".repeat(65), ""));
        assertThrows(IllegalArgumentException.class, () -> new SharedAccountProfile("Server", "x".repeat(4097)));
    }
}
