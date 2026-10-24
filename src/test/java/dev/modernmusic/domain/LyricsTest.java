package dev.modernmusic.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricsTest {
    @Test void followsTimelineIncludingSeekAndRepeatedTimestamps() {
        Lyrics lyrics = Lyrics.parse("[ti:Title]\n[00:03.50]Third\n[00:01.2][00:05.000]Chorus\n[00:02.05]Second", "[00:01.200]翻译\n[00:05.000]再次");
        assertEquals(4, lyrics.lines().size()); assertNull(lyrics.at(1199));
        assertEquals("Chorus", lyrics.at(1200).text()); assertEquals("翻译", lyrics.at(1200).translation());
        assertEquals("Third", lyrics.at(4999).text()); assertEquals("Chorus", lyrics.at(5000).text());
        assertEquals("Second", lyrics.at(2050).text());
    }
    @Test void offsetsEmptyLinesAndMalformedInputAreHandled() {
        var lyrics = Lyrics.parse("[offset:-500]\n[00:00.1]§Start\n[00:01.50]\n[00:70]bad\nnot timed", "");
        assertEquals(2, lyrics.lines().size()); assertEquals("Start", lyrics.at(0).text());
        assertEquals("", lyrics.at(1000).text()); assertTrue(Lyrics.parse(null, "").lines().isEmpty());
        assertTrue(Lyrics.parse("x".repeat(256001), "").lines().isEmpty());
    }
    @Test void duplicateTimestampsCannotAmplifyOneLineBeyondItsLimit() {
        String repeated = "[00:01]" + "word ".repeat(60) + "\n";
        var lyrics = Lyrics.parse(repeated.repeat(700), repeated.repeat(700));
        assertEquals(1, lyrics.lines().size());
        assertEquals(512, lyrics.at(1000).text().length());
        assertEquals(512, lyrics.at(1000).translation().length());
    }
    @Test void timestampBatchesCannotOvershootTheTimelineLimit() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 5056; i++) {
            input.append(String.format(java.util.Locale.ROOT, "[%02d:%02d]", i / 60, i % 60));
            if (i % 32 == 31) input.append("line\n");
        }
        assertEquals(5000, Lyrics.parse(input.toString(), "").lines().size());
    }
}
