package dev.modernmusic.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QqReferenceParserTest {
    @Test void acceptsReportedWechatAndPlayerLinks() {
        assertEquals("216494081", QqReferenceParser.song("https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect "));
        assertEquals("001NKo0b2Z4uGu", QqReferenceParser.song("https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2"));
        assertEquals("001NKo0b2Z4uGu", QqReferenceParser.song("分享歌曲 https://y.qq.com/n/ryqq/player?mid=001NKo0b2Z4uGu#webchat_redirect"));
    }
    @Test void acceptsSongIdsMidsAndFullShareLinks() {
        for (String reference : List.of("0039MnYb0qxYhV", "https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV",
                "分享 晴天 https://y.qq.com/n/yqq/song/0039MnYb0qxYhV.html", "https://y.qq.com/n/ryqq_v2/songDetail/?songmid=0039MnYb0qxYhV"))
            assertEquals("0039MnYb0qxYhV", QqReferenceParser.song(reference));
        assertEquals("123", QqReferenceParser.song("https://i.y.qq.com/v8/playsong.html?songid=123"));
        assertEquals(new SongId(123, MusicPlatform.QQ), QqReferenceParser.playlist("https://y.qq.com/n/m/detail/taoge/index.html?id=123"));
        assertEquals(new SongId(123, MusicPlatform.QQ), QqReferenceParser.playlist("https://y.qq.com/n/ryqq/playlist/123"));
        assertEquals(new SongId(123, MusicPlatform.QQ), QqReferenceParser.playlist("https://y.qq.com/n/ryqq_v2/playlist/123"));
        assertEquals("0039MnYb0qxYhV", QqReferenceParser.song("https://y.qq.com/n/ryqq_v2/songDetail/0039MnYb0qxYhV"));
    }
    @Test void rejectsForeignLinksAmbiguityAndInvalidIds() {
        for (String input : List.of("0", "9223372036854775808", "https://music.163.com/song?id=123", "https://y.qq.com.evil.test/n/ryqq/songDetail/0039MnYb0qxYhV",
                "https://y.qq.com:443/v8/playsong.html?songid=123", "https://x@y.qq.com/v8/playsong.html?songid=123", "https://y.qq.com/v8/playsong.html?songid=123&songmid=0039MnYb0qxYhV",
                "https://y.qq.com.evil.test/?id=1", "https://y.qq.com/n/ryqq/playlist/123", "https://c6.y.qq.com/base/fcgi-bin/u?__=abc",
                "https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&songid=123",
                "https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&mid=001NKo0b2Z4uGu",
                "https://i.y.qq.com/v8/playsong.html?songid=216494081#songid=123"))
            assertThrows(SongReferenceParser.InputException.class, () -> QqReferenceParser.song(input), input);
    }
    @Test void platformIdentitySurvivesWireAndDiskAndSeparatesDuplicates() {
        var netease = new SongId(123); var qq = new SongId(123, MusicPlatform.QQ);
        assertNotEquals(netease, qq);
        for (var id : List.of(netease, qq, new SongId(Long.MAX_VALUE, MusicPlatform.QQ))) {
            assertEquals(id, SongId.fromWire(id.wireValue())); assertEquals(id, SongId.stored(id.toString()));
        }
        assertThrows(IllegalArgumentException.class, () -> SongId.fromWire(0));
        assertThrows(ArithmeticException.class, () -> SongId.fromWire(Long.MIN_VALUE));
        var queue = new PlaybackQueue(); assertTrue(queue.add(netease)); assertTrue(queue.add(qq)); assertFalse(queue.add(qq));
    }
}
