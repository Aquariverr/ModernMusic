package dev.modernmusic.client;

import dev.modernmusic.auth.AccountSession;
import java.net.URI;

/** The selected playback identity, independent of personal login and room membership. */
public record PlaybackAccountView(boolean server, boolean ready, String nickname, URI avatar, String statusKey) {
    public static PlaybackAccountView of(AccountSession personal, String personalStatus, SharedPlaybackSource source) {
        if (source != null && source.selected()) {
            // Also hide identity fields sent by older servers.
            return new PlaybackAccountView(true, source.available(), "", null, source.statusKey());
        }
        return new PlaybackAccountView(false, personal != null, personal == null ? "" : personal.nickname(),
                personal == null ? null : personal.avatar(), personal == null ? personalStatus : "modernmusic.auth.signed_in");
    }
    public String modeKey(boolean group, boolean team) {
        if (server) return team ? "modernmusic.shared.team_mode" : group ? "modernmusic.shared.room_mode" : "modernmusic.shared.account_mode";
        return group ? team ? "modernmusic.team.mode" : "modernmusic.room.mode" : ready ? "modernmusic.auth.account_mode" : "modernmusic.ui.mode";
    }
}
