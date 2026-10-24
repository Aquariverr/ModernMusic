package dev.modernmusic.domain;

import java.util.regex.Pattern;

/** Share-text handling only; each platform still validates its own IDs and URL routes. */
final class ReferenceInput {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'，。；！？（）《》]+", Pattern.CASE_INSENSITIVE);

    private ReferenceInput() {}

    static String normalize(String input) {
        if (input == null || input.isBlank()) throw new SongReferenceParser.InputException("empty");
        if (input.length() > SongReferenceParser.MAX_INPUT_LENGTH) throw new SongReferenceParser.InputException("too_long");
        return input.strip();
    }

    static String singleUrl(String text, String unsupported) {
        var matcher = URL.matcher(text);
        if (!matcher.find()) throw new SongReferenceParser.InputException(unsupported);
        String link = matcher.group().replaceFirst("[)\\]}.;,!]+$", "");
        if (matcher.find()) throw new SongReferenceParser.InputException("ambiguous");
        return link;
    }
}
