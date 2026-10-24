package dev.modernmusic.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SongReferenceParserTest {
    @ParameterizedTest
    @ValueSource(strings = {"2058263032", "  02058263032\n", "https://music.163.com/song?id=2058263032",
            "http://music.163.com/#/song?id=2058263032", "https://y.music.163.com/m/song?id=2058263032&userid=12",
            "https://music.163.com/song/?id=%32%30%35%38%32%36%33%30%33%32",
            "分享一首歌 https://music.163.com/song?id=2058263032 (来自网易云音乐)",
            "分享：https://music.163.com/#/song?id=2058263032。", "HTTPS://MUSIC.163.COM/song?id=2058263032"})
    void acceptsSupportedReferences(String input) { assertEquals(new SongId(2058263032L), SongReferenceParser.parse(input)); }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "-1", "+1", "1.5", "1e5", "１２３", "9223372036854775808",
            "https://music.163.com/song?id=0", "https://music.163.com/song?id=-1",
            "https://music.163.com/song?id=1&id=1", "https://music.163.com/song?id=1&%69d=2",
            "https://music.163.com/song?id=1 https://music.163.com/song?id=2",
            "https://music.163.com.evil.example/song?id=1", "https://evil.example/music.163.com/song?id=1",
            "https://music.163.com@evil.example/song?id=1", "https://evil@music.163.com/song?id=1",
            "https://music.163.com:443/song?id=1", "https://127.0.0.1/song?id=1",
            "file:///song?id=1", "ftp://music.163.com/song?id=1", "https://music.163.com/playlist?id=1",
            "https://music.163.com/#/album?id=1", "https://music.163.com/artist?id=1",
            "https://music.163.com/#/song?id=1&id=2", "https://music.163.com/?id=1#/song?id=2",
            "https://music.163.com/song?id=%zz", "https://music.163.com/song?id=%C3%28",
            "https://music.163.com/song?id=1&x=%FF", "https://music.163.com/song?other=1",
            "https://music.163.com/song?id=", "https://music.163.com/%73ong?id=1",
            "https://163cn.tv/example", "https://music.163.com/song?id=1#bad"})
    void rejectsUnsafeOrAmbiguousInput(String input) { assertThrows(SongReferenceParser.InputException.class, () -> SongReferenceParser.parse(input)); }

    @Test void lengthAndPrecisionLimits() {
        assertThrows(SongReferenceParser.InputException.class, () -> SongReferenceParser.parse(null));
        assertThrows(SongReferenceParser.InputException.class, () -> SongReferenceParser.parse("1".repeat(2049)));
        assertEquals(Long.MAX_VALUE, SongReferenceParser.parse("9223372036854775807").value());
    }
}
