package dev.modernmusic.domain;

public enum PlaybackMode {
    SEQUENTIAL, REPEAT_ONE, REPEAT_ALL, SHUFFLE;
    public PlaybackMode next() { return values()[(ordinal() + 1) % values().length]; }
    public String translationKey() { return "modernmusic.mode." + name().toLowerCase(java.util.Locale.ROOT); }
}
