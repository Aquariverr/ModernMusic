package dev.modernmusic.client.ui;

import dev.modernmusic.domain.Lyrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricWindowTest {
    final Lyrics lyrics = Lyrics.parse("[00:01]one\n[00:02]two\n[00:03]three\n[00:04]four\n[00:05]five",
            "[00:03]translation");

    @Test void compactWindowKeepsCurrentTextAndTranslation() {
        assertEquals(List.of("three"), LyricWindow.rows(lyrics, 3_000, 1).stream().map(LyricWindow.Row::text).toList());
        var rows = LyricWindow.rows(lyrics, 3_000, 2);
        assertEquals(List.of("three", "translation"), rows.stream().map(LyricWindow.Row::text).toList());
        assertTrue(rows.stream().allMatch(LyricWindow.Row::current));
        assertTrue(rows.getLast().translation());
    }
    @Test void expandedWindowShowsBothSidesAndFollowsSeeking() {
        var rows = LyricWindow.rows(lyrics, 3_000, 5);
        assertEquals(List.of("two", "three", "translation", "four", "five"), rows.stream().map(LyricWindow.Row::text).toList());
        assertEquals("five", LyricWindow.rows(lyrics, 5_000, 3).getLast().text());
        assertTrue(LyricWindow.rows(lyrics, 1_000, 3).getFirst().current());
    }
    @Test void PreludeEmptyAndNoSpaceRemainBounded() {
        assertTrue(LyricWindow.rows(lyrics, 0, 3).stream().noneMatch(LyricWindow.Row::current));
        assertEquals(List.of("one", "two", "three"), LyricWindow.rows(lyrics, 0, 3).stream().map(LyricWindow.Row::text).toList());
        assertTrue(LyricWindow.rows(lyrics, 3_000, 0).isEmpty());
        assertTrue(LyricWindow.rows(Lyrics.EMPTY, 3_000, 5).isEmpty());
        assertEquals(6, LyricWindow.rows(lyrics, 5_000, 20).size());
    }
}
