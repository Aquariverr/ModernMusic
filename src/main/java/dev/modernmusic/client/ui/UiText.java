package dev.modernmusic.client.ui;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Word wrapping with Unicode line boundaries, including Chinese text next to Latin words. */
final class UiText {
    private UiText() {}
    private record Run(int start, int end, Style style) {}

    static List<FormattedCharSequence> wrap(Font font, FormattedText text, int width) {
        var plain = new StringBuilder(); var runs = new ArrayList<Run>();
        text.visit((style, value) -> {
            int start = plain.length(); plain.append(value);
            runs.add(new Run(start, plain.length(), style)); return Optional.empty();
        }, Style.EMPTY);
        String value = plain.toString(); var lines = new ArrayList<FormattedCharSequence>();
        var breaks = BreakIterator.getLineInstance(Locale.ROOT); breaks.setText(value);
        int start = 0;
        do {
            int newline = value.indexOf('\n', start), end = newline < 0 ? value.length() : newline;
            if (start == end) lines.add(FormattedCharSequence.EMPTY);
            while (start < end) {
                var remaining = slice(value, runs, start, end);
                int fit = font.substrByWidth(remaining, Math.max(1, width)).getString().length();
                int limit = Math.min(end, start + Math.max(Character.charCount(value.codePointAt(start)), fit));
                int stop = limit == end ? end : breaks.preceding(limit + 1);
                if (stop <= start) stop = limit; // Long URLs/words still fit within the viewport.
                int trimmed = stop;
                while (trimmed > start && Character.isWhitespace(value.charAt(trimmed - 1))) trimmed--;
                lines.add(Language.getInstance().getVisualOrder(slice(value, runs, start, trimmed)));
                start = stop;
                while (start < end && Character.isWhitespace(value.charAt(start))) start++;
            }
            if (newline < 0) break;
            start = newline + 1;
        } while (start <= value.length());
        return List.copyOf(lines);
    }

    private static FormattedText slice(String value, List<Run> runs, int start, int end) {
        var parts = new ArrayList<FormattedText>();
        for (var run : runs) {
            int left = Math.max(start, run.start()), right = Math.min(end, run.end());
            if (left < right) parts.add(FormattedText.of(value.substring(left, right), run.style()));
        }
        return FormattedText.composite(parts);
    }
}
