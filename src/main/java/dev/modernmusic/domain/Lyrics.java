package dev.modernmusic.domain;

import java.util.*;
import java.util.regex.Pattern;

/** Immutable LRC timeline. Supports repeated timestamps, offset tags and translated lines. */
public record Lyrics(List<Line> lines) {
    public record Line(long timeMs, String text, String translation) {}
    public static final Lyrics EMPTY = new Lyrics(List.of());
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,8})]", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\p{Cntrl}§]");
    private static final int MAX_LINES = 5000, MAX_TEXT_LENGTH = 512;
    public Lyrics { lines = List.copyOf(lines); }

    public static Lyrics parse(String original, String translated) {
        var text = parseLines(original); var translation = parseLines(translated);
        return new Lyrics(text.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue(),
                translation.getOrDefault(e.getKey(), ""))).toList());
    }
    private static NavigableMap<Long, String> parseLines(String input) {
        var result = new TreeMap<Long, String>();
        if (input == null || input.length() > 256_000) return result;
        var offsetMatch = OFFSET.matcher(input);
        long offset = offsetMatch.find() ? Long.parseLong(offsetMatch.group(1)) : 0;
        for (String row : input.split("\\R")) {
            var match = TIME.matcher(row); var times = new ArrayList<Long>(); int end = 0;
            while (match.find() && times.size() < 32) {
                if (Integer.parseInt(match.group(2)) >= 60) continue;
                String fraction = match.group(3);
                long ms = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                times.add(Math.max(0, Long.parseLong(match.group(1)) * 60_000 + Integer.parseInt(match.group(2)) * 1000L + ms + offset));
                end = match.end();
            }
            if (times.isEmpty()) continue;
            String value = UNSAFE_TEXT.matcher(row.substring(end)).replaceAll("").strip();
            value = value.substring(0, Math.min(MAX_TEXT_LENGTH, value.length()));
            for (long time : times) {
                // Duplicate timestamps must not bypass the text bound or repeatedly copy ever-growing lines.
                if (result.size() == MAX_LINES && !result.containsKey(time)) continue;
                result.merge(time, value, Lyrics::mergeText);
            }
        }
        return result;
    }
    private static String mergeText(String first, String next) {
        if (first.isEmpty()) return next;
        if (next.isEmpty() || first.length() == MAX_TEXT_LENGTH) return first;
        String separator = " / ";
        int available = MAX_TEXT_LENGTH - first.length();
        if (available <= separator.length()) return first;
        return first + separator + next.substring(0, Math.min(next.length(), available - separator.length()));
    }
    public int indexAt(long positionMs) {
        int low = 0, high = lines.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs() <= positionMs) low = middle + 1; else high = middle - 1;
        }
        return high;
    }
    public Line at(long positionMs) { int index = indexAt(positionMs); return index < 0 ? null : lines.get(index); }
}
