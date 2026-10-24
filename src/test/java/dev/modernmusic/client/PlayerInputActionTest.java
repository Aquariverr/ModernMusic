package dev.modernmusic.client;

import dev.modernmusic.domain.SongReferenceParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.modernmusic.client.PlayerInputAction.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerInputActionTest {
    @ParameterizedTest @ValueSource(strings = {"晴天", "ambient.wav", "123", "  My Song.mp3  ", "playlist"})
    void localAudioNamesAlwaysAddSongs(String input) {
        assertEquals(SONG, resolve(input, false, dev.modernmusic.domain.MusicPlatform.LOCAL));
        assertEquals(SONG, resolve(input, true, dev.modernmusic.domain.MusicPlatform.LOCAL));
    }

    @Test void localAudioKeepsInputValidation() {
        assertEquals("modernmusic.input.empty", assertThrows(SongReferenceParser.InputException.class,
                () -> resolve(" ", false, dev.modernmusic.domain.MusicPlatform.LOCAL)).translationKey());
        assertEquals("modernmusic.input.too_long", assertThrows(SongReferenceParser.InputException.class,
                () -> resolve("a".repeat(2049), false, dev.modernmusic.domain.MusicPlatform.LOCAL)).translationKey());
    }

    @ParameterizedTest @ValueSource(strings = {"晴天", "周杰伦", "  Taylor Swift  ", "七里香 2004", "Love / Story", "歌曲 playlist"})
    void searchesKeywordsInEitherTab(String input) {
        assertEquals(SEARCH, resolve(input, false));
        assertEquals(SEARCH, resolve(input, true));
    }

    @ParameterizedTest @ValueSource(strings = {"123", " 00123 ", "9223372036854775807"})
    void usesSelectedTabForBareIds(String input) {
        assertEquals(SONG, resolve(input, false));
        assertEquals(PLAYLIST, resolve(input, true));
    }

    @ParameterizedTest @ValueSource(strings = {"https://music.163.com/song?id=123", "http://music.163.com/#/song?id=123",
            "分享歌曲：https://y.music.163.com/m/song?id=123&userid=1。", "HTTPS://MUSIC.163.COM/song/?id=%31%32%33"})
    void songLinksOverridePlaylistTab(String input) {
        assertEquals(SONG, resolve(input, false));
        assertEquals(SONG, resolve(input, true));
    }

    @ParameterizedTest @ValueSource(strings = {"https://music.163.com/playlist?id=123", "http://music.163.com/#/playlist?id=123",
            "分享歌单：https://y.music.163.com/m/playlist?id=123&userid=1。", "HTTPS://MUSIC.163.COM/playlist/?id=%31%32%33"})
    void playlistLinksOverrideSongTab(String input) {
        assertEquals(PLAYLIST, resolve(input, false));
        assertEquals(PLAYLIST, resolve(input, true));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "0", "9223372036854775808", "99999999999999999999",
            "https://music.163.com/song?id=0", "https://music.163.com/playlist?id=abc",
            "https://music.163.com/song?id=1&id=2", "https://music.163.com/song?id=1 https://music.163.com/playlist?id=2",
            "https://music.163.com.evil.example/song?id=123", "https://music.163.com:443/playlist?id=123",
            "https://music.163.com/song?id=%zz", "https://163cn.tv/example", "https://music.163.com/album?id=123",
            "ftp://music.163.com/song?id=123", "file:///song?id=123"})
    void rejectsInvalidReferencesInsteadOfSearching(String input) {
        assertThrows(SongReferenceParser.InputException.class, () -> resolve(input, false));
        assertThrows(SongReferenceParser.InputException.class, () -> resolve(input, true));
    }

    @Test void preservesValidationMessages() {
        assertEquals("modernmusic.input.empty", assertThrows(SongReferenceParser.InputException.class, () -> resolve(" ", false)).translationKey());
        assertEquals("modernmusic.input.too_long", assertThrows(SongReferenceParser.InputException.class, () -> resolve("a".repeat(2049), false)).translationKey());
        assertEquals("modernmusic.input.invalid_id", assertThrows(SongReferenceParser.InputException.class, () -> resolve("0", true)).translationKey());
    }
}
