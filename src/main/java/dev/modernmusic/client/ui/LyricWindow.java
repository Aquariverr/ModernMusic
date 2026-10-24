package dev.modernmusic.client.ui;

import dev.modernmusic.domain.Lyrics;
import java.util.ArrayList;
import java.util.List;

/** A bounded timeline window: current text first, then translation and nearby context. */
public final class LyricWindow {
    private LyricWindow() {}
    public record Row(String text, boolean current, boolean translation) {}

    public static List<Row> rows(Lyrics lyrics, long position, int capacity) {
        if (capacity <= 0 || lyrics.lines().isEmpty()) return List.of();
        int current = lyrics.indexAt(position);
        var candidates = new ArrayList<Row>();
        int focus = 0;
        for (int i = Math.max(0, current - capacity); i < Math.min(lyrics.lines().size(), current + capacity + 1); i++) {
            var line = lyrics.lines().get(i);
            if (i == current) focus = candidates.size();
            candidates.add(new Row(line.text(), i == current, false));
            if (!line.translation().isBlank()) candidates.add(new Row(line.translation(), i == current, true));
        }
        // Leave room for the current translation before allocating preceding context.
        int currentRows = current >= 0 && !lyrics.lines().get(current).translation().isBlank() ? 2 : 1;
        int before = Math.min(capacity / 3, Math.max(0, capacity - currentRows));
        int start = Math.max(0, focus - before);
        if (candidates.size() - start < capacity) start = Math.max(0, candidates.size() - capacity);
        return List.copyOf(candidates.subList(start, Math.min(candidates.size(), start + capacity)));
    }
}
