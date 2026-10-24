package dev.modernmusic.domain;

public enum MusicPlatform {
    NETEASE, QQ, KUGOU, LOCAL;
    public String translationKey() { return "modernmusic.platform." + name().toLowerCase(java.util.Locale.ROOT); }
    public MusicPlatform next() { return values()[(ordinal() + 1) % values().length]; }
    public static java.util.List<MusicPlatform> onlineValues() { return java.util.List.of(NETEASE, QQ, KUGOU); }
    public String key(String suffix) { return "modernmusic." + (this == NETEASE ? "" : name().toLowerCase(java.util.Locale.ROOT) + ".") + suffix; }
    public boolean sharedCookieOnly() { return this == QQ || this == KUGOU; }
}
