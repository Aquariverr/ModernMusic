package dev.modernmusic.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PlaylistReferenceTest {
    @ParameterizedTest @ValueSource(strings = {"123", "https://music.163.com/playlist?id=123", "https://music.163.com/#/playlist?id=123", "分享歌单 https://y.music.163.com/m/playlist?id=123&userid=1"})
    void parsesStandardPlaylistReferences(String input) { assertEquals(new SongId(123), SongReferenceParser.parsePlaylist(input)); }
    @ParameterizedTest @ValueSource(strings = {"0", "9223372036854775808", "https://music.163.com/song?id=123", "https://music.163.com.evil.test/playlist?id=123", "https://music.163.com/playlist?id=1&id=2", "https://music.163.com/playlist?id=1 https://music.163.com/playlist?id=2", "https://localhost/playlist?id=1", "https://music.163.com:443/playlist?id=1"})
    void rejectsWrongKindAmbiguityAndUntrustedUrls(String input) { assertThrows(SongReferenceParser.InputException.class, () -> SongReferenceParser.parsePlaylist(input)); }
}
