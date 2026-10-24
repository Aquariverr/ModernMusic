package dev.modernmusic.server;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Non-secret, unsynchronized COMMON settings. Never put a credential in a config spec. */
public final class SharedAccountConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED, OPERATORS_ONLY, QQ_ENABLED, QQ_OPERATORS_ONLY, KUGOU_ENABLED, KUGOU_OPERATORS_ONLY;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("sharedAccount");
        ENABLED = builder.comment("Allow clients to request playback using the server's dedicated account. Enabled by default; bind an account to use it.",
                "Use a dedicated account. Host administrators can access saved credentials; unencrypted game connections can expose login data.").define("enabled", true);
        OPERATORS_ONLY = builder.comment("Restrict shared playback to players with permission level 2 or higher.").define("operatorsOnly", false);
        builder.pop(); builder.push("qqSharedAccount");
        QQ_ENABLED = builder.comment("Enable the independent QQ Music shared account.").define("enabled", true);
        QQ_OPERATORS_ONLY = builder.define("operatorsOnly", false);
        builder.pop(); builder.push("kugouSharedAccount");
        KUGOU_ENABLED = builder.comment("Enable the independent Kugou Music shared account.").define("enabled", true);
        KUGOU_OPERATORS_ONLY = builder.define("operatorsOnly", false);
        builder.pop(); SPEC = builder.build();
    }
    public static ModConfigSpec.BooleanValue enabled(dev.modernmusic.domain.MusicPlatform platform) { return switch (platform) { case NETEASE -> ENABLED; case QQ -> QQ_ENABLED; case KUGOU -> KUGOU_ENABLED; case LOCAL -> throw new IllegalArgumentException("Local audio has no account"); }; }
    public static ModConfigSpec.BooleanValue operatorsOnly(dev.modernmusic.domain.MusicPlatform platform) { return switch (platform) { case NETEASE -> OPERATORS_ONLY; case QQ -> QQ_OPERATORS_ONLY; case KUGOU -> KUGOU_OPERATORS_ONLY; case LOCAL -> throw new IllegalArgumentException("Local audio has no account"); }; }
    private SharedAccountConfig() {}
}
