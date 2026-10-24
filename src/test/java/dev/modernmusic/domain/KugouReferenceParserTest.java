package dev.modernmusic.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KugouReferenceParserTest {
    static final String HASH = "A331C7D3DC826D784124C8DC9284436F";
    @Test void hashesAndProviderIdsRoundTripWithoutNumericLoss() {
        var id = new SongId(HASH.toLowerCase(), MusicPlatform.KUGOU);
        assertEquals(HASH, id.key()); assertEquals(id, SongId.stored(id.toString()));
        assertEquals(new SongId(42), SongId.stored("42")); assertEquals(new SongId(42, MusicPlatform.QQ), SongId.stored("qq:42"));
        assertNotEquals(id, new SongId("B" + HASH.substring(1), MusicPlatform.KUGOU));
        assertThrows(IllegalStateException.class, id::wireValue);
    }
    @Test void parsesHashesShareTextAndWhitelistedFullLinks() {
        var expected = new KugouReferenceParser.SongReference(HASH, false);
        for (String value : new String[]{HASH, HASH.toLowerCase(), "https://www.kugou.com/song/#hash=" + HASH,
                "分享 https://www.kugou.com/song/" + HASH + ".html", "https://m.kugou.com/share/song.html?hash=" + HASH})
            assertEquals(expected, KugouReferenceParser.song(value));
        assertEquals(new SongId(125032, MusicPlatform.KUGOU), KugouReferenceParser.playlist("https://www.kugou.com/yy/special/single/125032.html"));
        assertEquals("collection_3_2098608661_4_0", KugouReferenceParser.playlist("collection_3_2098608661_4_0").key());
    }
    @Test void malformedAmbiguousAndForeignReferencesNeverBecomeRequests() {
        for (String value : new String[]{"123", "https://kugou.com.evil.test/song/#hash=" + HASH, "https://www.kugou.com:443/song/#hash=" + HASH,
                "https://user@www.kugou.com/song/#hash=" + HASH, "https://www.kugou.com/song/?hash=" + HASH + "&hash=" + HASH,
                "https://www.kugou.com/song/?hash=" + HASH + " https://www.kugou.com/song/?hash=" + HASH})
            assertThrows(SongReferenceParser.InputException.class, () -> KugouReferenceParser.song(value));
        assertThrows(SongReferenceParser.InputException.class, () -> KugouReferenceParser.playlist("https://www.kugou.com/songlist/gcid_unknown/"));
    }
    @Test void encodedSongLinksStaySeparateFromPersistentHashIds() {
        for (String input : new String[]{"https://www.kugou.com/song/#g2ln2xf9", "分享 https://www.kugou.com/song/#g2ln2xf9 ",
                "https://www.kugou.com/mixsong/g2ln2xf9.html?share=1", "https://m.kugou.com/mixsong/g2ln2xf9.html",
                "https://www.kugou.com/song/g2ln2xf9.html"}) {
            assertEquals(new KugouReferenceParser.SongReference("g2ln2xf9", true), KugouReferenceParser.song(input));
            assertThrows(SongReferenceParser.InputException.class, () -> KugouReferenceParser.playlist(input));
        }
        for (String input : new String[]{"https://www.kugou.com.evil.test/song/#g2ln2xf9", "https://user@www.kugou.com/song/#g2ln2xf9",
                "https://www.kugou.com/song/?hash=" + HASH + "#g2ln2xf9", "https://www.kugou.com/song/#g2ln2xf9&hash=" + HASH,
                "https://www.kugou.com/song/#12345678", "https://www.kugou.com/song/#../../evil", "https://www.kugou.com/song/#g2ln2xf9 https://www.kugou.com/song/#g2ln2xf9",
                "https://www.kugou.com/mixsong/g2ln2xf9.html?hash=" + HASH})
            assertThrows(SongReferenceParser.InputException.class, () -> KugouReferenceParser.song(input), input);
    }
}
