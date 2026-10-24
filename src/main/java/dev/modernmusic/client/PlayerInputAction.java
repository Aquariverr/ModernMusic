package dev.modernmusic.client;

import dev.modernmusic.domain.SongReferenceParser;
import java.util.regex.Pattern;

/** Chooses the player's Enter action without making any provider requests. */
public enum PlayerInputAction {
    SEARCH, SONG, PLAYLIST;

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern LINK = Pattern.compile("[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);

    public static PlayerInputAction resolve(String input, boolean playlistSelected, dev.modernmusic.domain.MusicPlatform platform) {
        if (platform == dev.modernmusic.domain.MusicPlatform.NETEASE) return resolve(input, playlistSelected);
        if (input == null || input.isBlank()) throw new SongReferenceParser.InputException("empty");
        if (input.length() > SongReferenceParser.MAX_INPUT_LENGTH) throw new SongReferenceParser.InputException("too_long");
        String text = input.strip();
        // Local audio is resolved by its file name, including names that resemble IDs or links.
        if (platform == dev.modernmusic.domain.MusicPlatform.LOCAL) return SONG;
        if (platform == dev.modernmusic.domain.MusicPlatform.KUGOU) {
            if (!DIGITS.matcher(text).matches() && !text.matches("[A-Fa-f0-9]{32}") && !text.startsWith("collection_") && !LINK.matcher(text).find()) return SEARCH;
            try {
                if (playlistSelected) dev.modernmusic.domain.KugouReferenceParser.playlist(text); else dev.modernmusic.domain.KugouReferenceParser.song(text);
                return playlistSelected ? PLAYLIST : SONG;
            } catch (SongReferenceParser.InputException selected) {
                try {
                    if (playlistSelected) dev.modernmusic.domain.KugouReferenceParser.song(text); else dev.modernmusic.domain.KugouReferenceParser.playlist(text);
                    return playlistSelected ? SONG : PLAYLIST;
                } catch (SongReferenceParser.InputException ignored) { throw selected; }
            }
        }
        if (!DIGITS.matcher(text).matches() && !text.matches("[A-Za-z0-9]{14}") && !LINK.matcher(text).find()) return SEARCH;
        try {
            if (playlistSelected) dev.modernmusic.domain.QqReferenceParser.playlist(text); else dev.modernmusic.domain.QqReferenceParser.song(text);
            return playlistSelected ? PLAYLIST : SONG;
        } catch (SongReferenceParser.InputException selected) {
            try {
                if (playlistSelected) dev.modernmusic.domain.QqReferenceParser.song(text); else dev.modernmusic.domain.QqReferenceParser.playlist(text);
                return playlistSelected ? SONG : PLAYLIST;
            } catch (SongReferenceParser.InputException ignored) { throw selected; }
        }
    }
    public static PlayerInputAction resolve(String input, boolean playlistSelected) {
        if (input == null || input.isBlank()) throw new SongReferenceParser.InputException("empty");
        if (input.length() > SongReferenceParser.MAX_INPUT_LENGTH) throw new SongReferenceParser.InputException("too_long");
        String text = input.strip();
        // Bare IDs need the selected type; URLs carry their own song/playlist type.
        if (DIGITS.matcher(text).matches()) {
            if (playlistSelected) SongReferenceParser.parsePlaylist(text);
            else SongReferenceParser.parse(text);
            return playlistSelected ? PLAYLIST : SONG;
        }
        if (!LINK.matcher(text).find()) return SEARCH;
        try {
            if (playlistSelected) SongReferenceParser.parsePlaylist(text);
            else SongReferenceParser.parse(text);
            return playlistSelected ? PLAYLIST : SONG;
        } catch (SongReferenceParser.InputException selectedError) {
            try {
                if (playlistSelected) SongReferenceParser.parse(text);
                else SongReferenceParser.parsePlaylist(text);
                return playlistSelected ? SONG : PLAYLIST;
            } catch (SongReferenceParser.InputException ignored) {
                // Keep malformed, unsupported and ambiguous links out of keyword searches.
                throw selectedError;
            }
        }
    }
}
