package dev.modernmusic.client;

import dev.modernmusic.auth.*;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.shared.SharedAccountProfile;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackAccountViewTest {
    final SharedPlaybackSourceTest fixture = new SharedPlaybackSourceTest();
    final AccountSession personal = new AccountSession("1", "Local user", SessionCookie.parse("MUSIC_U=fixture-local"),
            Instant.now(), URI.create("https://p1.music.126.net/personal.png"));
    final SharedAccountProfile server = new SharedAccountProfile("Server user", "https://p2.music.126.net/server.png");
    PlaybackAccountView view(AccountSession local) { return PlaybackAccountView.of(local, "modernmusic.error.auth_expired", fixture.source); }
    @Test void selectingSharedHidesIdentityIncludingProfilesFromOlderServers() {
        fixture.ready(); fixture.source.profile(1, server);
        var view = view(personal);
        assertTrue(view.server()); assertTrue(view.ready()); assertEquals("", view.nickname());
        assertNull(view.avatar()); assertEquals("modernmusic.shared.ready", view.statusKey());
        assertEquals("modernmusic.shared.account_mode", view.modeKey(false, false));
        assertEquals("modernmusic.shared.room_mode", view.modeKey(true, false));
        assertEquals("modernmusic.shared.team_mode", view.modeKey(true, true));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(dev.modernmusic.domain.MusicPlatform.class)
    void everyPlatformHidesOldServerIdentityAndRestoresPersonalIdentity(dev.modernmusic.domain.MusicPlatform platform) {
        try (var source = new SharedPlaybackSource(fixture.personal, fixture.sent::add, () -> {}, fixture.clock, platform)) {
            source.state(1, null); source.select(true); source.profile(1, server);
            var view = PlaybackAccountView.of(personal, "modernmusic.auth.guest", source);
            assertTrue(view.server()); assertEquals("", view.nickname()); assertNull(view.avatar());
            source.select(false);
            view = PlaybackAccountView.of(personal, "modernmusic.auth.guest", source);
            assertFalse(view.server()); assertEquals(personal.nickname(), view.nickname()); assertEquals(personal.avatar(), view.avatar());
        }
    }
    @Test void unavailableSharedSourceDoesNotFallBackToPersonalIdentityAndOldServersGetGenericDisplay() {
        fixture.ready(); assertTrue(view(personal).server()); assertEquals("", view(personal).nickname());
        fixture.source.profile(1, server); fixture.source.state(1, MusicError.SHARED_DISABLED);
        var view = view(personal); assertTrue(view.server()); assertFalse(view.ready());
        assertEquals("", view.nickname()); assertNull(view.avatar()); assertEquals(MusicError.SHARED_DISABLED.translationKey(), view.statusKey());
    }
    @Test void switchingBackAndDisconnectRestoreLocalOrGuestDisplay() {
        fixture.ready(); fixture.source.profile(1, server); fixture.source.select(false);
        var view = view(personal); assertFalse(view.server()); assertTrue(view.ready()); assertEquals(personal.avatar(), view.avatar());
        assertEquals("modernmusic.auth.account_mode", view.modeKey(false, false));
        fixture.source.select(true); fixture.source.disconnect();
        view = view(null); assertFalse(view.server()); assertFalse(view.ready()); assertNull(view.avatar());
        assertEquals("modernmusic.ui.mode", view.modeKey(false, false));
    }
}
