package dev.modernmusic.domain;

import javax.annotation.Nonnull;
/** Provider-scoped identifiers, never a user-supplied download URL. */
public record SongId(String key, MusicPlatform platform) {
    public SongId(long value) { this(value, MusicPlatform.NETEASE); }
    public SongId(long value, MusicPlatform platform) { this(Long.toString(value), platform); }
    public SongId {
        java.util.Objects.requireNonNull(platform);
        java.util.Objects.requireNonNull(key);
        if (platform == MusicPlatform.LOCAL) {
            if (!key.matches("(?:local|server)_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid audio ID");
        } else if (platform == MusicPlatform.KUGOU && key.matches("[A-Fa-f0-9]{32}")) key = key.toUpperCase(java.util.Locale.ROOT);
        // Public Kugou playlist IDs are already canonical; other IDs must be positive numbers.
        else if (platform != MusicPlatform.KUGOU || !key.matches("collection_[0-9]{1,2}_[0-9]{1,19}_[0-9]{1,19}_[0-9]")) {
            if (!key.matches("[0-9]{1,19}") || Long.parseLong(key) <= 0) throw new IllegalArgumentException("Invalid music ID");
            key = Long.toString(Long.parseLong(key));
        }
    }
    public long value() { return Long.parseLong(key); }
    public boolean isClientLocal() { return platform == MusicPlatform.LOCAL && key.startsWith("local_"); }
    public boolean isServerAudio() { return platform == MusicPlatform.LOCAL && key.startsWith("server_"); }
    /** Legacy numeric ADD commands; all queue/resource packets use the full string identifier. */
    public long wireValue() {
        if (platform == MusicPlatform.KUGOU || platform == MusicPlatform.LOCAL) throw new IllegalStateException("Hash IDs require string transport");
        return platform == MusicPlatform.QQ ? -value() : value();
    }
    public static SongId fromWire(long value) {
        return new SongId(value < 0 ? Math.negateExact(value) : value, value < 0 ? MusicPlatform.QQ : MusicPlatform.NETEASE);
    }
    public static SongId stored(String value) {
        if (value.startsWith("qq:")) return new SongId(value.substring(3), MusicPlatform.QQ);
        if (value.startsWith("kugou:")) return new SongId(value.substring(6), MusicPlatform.KUGOU);
        if (value.startsWith("local:")) return new SongId(value.substring(6), MusicPlatform.LOCAL);
        return new SongId(value, MusicPlatform.NETEASE);
    }
    public static SongId fromWire(String value) { return stored(value); }

    @Override
    @Nonnull public String toString() {
        return (platform == MusicPlatform.NETEASE ? "" : platform.name().toLowerCase(java.util.Locale.ROOT) + ":") + key;
    }
}
