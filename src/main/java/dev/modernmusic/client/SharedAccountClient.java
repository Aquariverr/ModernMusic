package dev.modernmusic.client;

import dev.modernmusic.network.SharedAccountNetwork;
import dev.modernmusic.network.SharedAdminNetwork;
import dev.modernmusic.provider.MusicProvider;
import dev.modernmusic.domain.MusicPlatform;
import java.util.*;
import java.time.Clock;
import net.minecraft.client.Minecraft;
import dev.modernmusic.network.ForgeNetwork;

public final class SharedAccountClient {
    private static final Map<MusicPlatform, SharedPlaybackSource> sources = new EnumMap<>(MusicPlatform.class);
    private static final Map<MusicPlatform, SharedAdminController> admins = new EnumMap<>(MusicPlatform.class);
    private static MusicPlatform selected = MusicPlatform.NETEASE;
    private static boolean hadSourceChannel, hadAdminChannel;
    private static SharedLoginSettings loginSettings;
    private static String server;
    public static void beginConnection() {
        disconnect();
        var game = Minecraft.getInstance();
        if (loginSettings == null) loginSettings = new SharedLoginSettings(game.gameDirectory.toPath().resolve("config/modernmusic-shared-login.json"));
        var local = game.getSingleplayerServer();
        var remote = game.getCurrentServer();
        server = local != null ? "local:" + local.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
                : remote != null ? "server:" + remote.ip.trim().toLowerCase(Locale.ROOT) : null;
    }
    public static boolean canRememberLogin() { return loginSettings != null && server != null; }
    public static boolean keepLogin(MusicPlatform platform) { return canRememberLogin() && loginSettings.remembered(server, platform); }
    public static boolean keepLogin(MusicPlatform platform, boolean value) {
        return canRememberLogin() && loginSettings.remember(server, platform, value);
    }
    public static void select(MusicPlatform platform) { selected = platform; }
    public static SharedPlaybackSource source() { return sources.get(selected); }
    public static SharedAdminController admin() { return admins.get(selected); }
    public static void disconnect() {
        admins.values().forEach(SharedAdminController::disconnect); sources.values().forEach(SharedPlaybackSource::disconnect);
        hadSourceChannel = hadAdminChannel = false;
        server = null;
    }
    /** Support belongs to the current connection, independently of account readiness or permissions. */
    public static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeNetwork.available(connection.getConnection());
    }
    public static boolean adminAvailable() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeNetwork.available(connection.getConnection());
    }
    public static void tick() {
        boolean sourceChannel = available(), adminChannel = adminAvailable();
        if (sourceChannel) sources.values().forEach(SharedPlaybackSource::tick);
        else if (hadSourceChannel) sources.values().forEach(SharedPlaybackSource::disconnect);
        if (adminChannel) admins.values().forEach(SharedAdminController::tick);
        else if (hadAdminChannel) admins.values().forEach(SharedAdminController::disconnect);
        hadSourceChannel = sourceChannel; hadAdminChannel = adminChannel;
    }
    public static SharedPlaybackSource connect(MusicProvider personal, Runnable changed) { return connect(MusicPlatform.NETEASE, personal, changed); }
    public static SharedPlaybackSource connect(MusicPlatform platform, MusicProvider personal, Runnable changed) {
        var admin = new SharedAdminController(request -> {
            if (!adminAvailable()) throw new IllegalStateException("Server does not support shared account administration");
            ForgeNetwork.sendToServer(new SharedAdminNetwork.CommandPayload(platform, request.view(), request.sequence(), request.action(), request.value()));
        }, Clock.systemUTC(), platform);
        admins.put(platform, admin);
        SharedAdminNetwork.clientCapability = value -> { if (adminAvailable()) admins.values().forEach(item -> item.capability(value)); };
        SharedAdminNetwork.clientSnapshot = value -> { if (adminAvailable()) admins.values().forEach(item -> item.receive(value)); };
        var source = new SharedPlaybackSource(personal, request -> {
            if (!available()) throw new IllegalStateException("Server does not support shared account playback");
            ForgeNetwork.sendToServer(new SharedAccountNetwork.RequestPayload(request.request(), request.generation(), request.song(), request.refresh()));
        }, changed, Clock.systemUTC(), platform, () -> keepLogin(platform));
        sources.put(platform, source);
        SharedAccountNetwork.clientState = state -> { var target = sources.get(state.platform()); if (available() && target != null) target.state(state.generation(), state.error()); };
        SharedAccountNetwork.clientProfile = value -> { var target = sources.get(value.platform()); if (available() && target != null) target.profile(value.generation(), value.profile()); };
        SharedAccountNetwork.clientResult = result -> {
            MusicPlatform resultPlatform;
            try { resultPlatform = dev.modernmusic.domain.SongId.stored(result.song()).platform(); } catch (RuntimeException invalid) { return; }
            var target = sources.get(resultPlatform);
            if (available() && target != null) target.receive(result.request(), result.generation(), result.song(), result.url(), result.expiresAt(), result.error());
        };
        return source;
    }
    private SharedAccountClient() {}
}
