package dev.modernmusic.smoke;
import dev.modernmusic.network.ForgeNetwork;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.RoomClient;
import dev.modernmusic.client.SharedAccountClient;
import dev.modernmusic.client.SharedPlaybackSource;
import dev.modernmusic.client.ui.PlayerScreen;
import dev.modernmusic.client.ui.RoomScreen;
import dev.modernmusic.client.ui.SharedSourceScreen;
import dev.modernmusic.domain.Lyrics;
import dev.modernmusic.provider.PlaybackResource;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import dev.modernmusic.network.RoomNetwork;
import dev.modernmusic.network.SharedAccountNetwork;
import dev.modernmusic.network.SharedAdminNetwork;
import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.HttpTransport;
import dev.modernmusic.provider.MusicProvider;
import dev.modernmusic.room.RoomCommand;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

/** Real negotiation with a server lacking the mod, followed by connection changes in one client. */
public final class OptionalClientSmoke {
    private int step, loggedStep = -1, frames;
    private long stepDeadline;
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(6);
    private PlaybackController player;
    private RoomClient rooms;
    private Path directory;
    public OptionalClientSmoke() {
        if (Boolean.getBoolean("modernmusic.optionalClientSmoke")) MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (step < 0) return;
        var game = Minecraft.getInstance();
        try {
            if (directory == null) directory = game.gameDirectory.toPath().toAbsolutePath().normalize();
            Path serverResult = directory.resolveSibling("optional-server-smoke").resolve("optional-result.txt");
            if (Files.isRegularFile(serverResult) && Files.readString(serverResult).startsWith("FAIL:"))
                throw new IllegalStateException("Server fixture failed; see optional-server-smoke/optional-result.txt");
            if (step != loggedStep) {
                loggedStep = step; stepDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                ModernMusic.LOGGER.info("Optional-server compatibility smoke step {}", step);
            }
            if (System.nanoTime() > deadline || System.nanoTime() > stepDeadline)
                throw new IllegalStateException("Timeout at step " + step);
            if (game.screen instanceof DisconnectedScreen) throw new IllegalStateException("Server rejected or disconnected modded client at step " + step);
            if (game.getOverlay() != null) return;
            switch (step) {
                case 0 -> {
                    if (game.screen instanceof AccessibilityOnboardingScreen) {  game.setScreen(new TitleScreen()); }
                    if (!(game.screen instanceof TitleScreen)) return;
                    if (!Files.exists(directory.resolveSibling("optional-server-smoke").resolve("optional-ready.txt"))) return;
                    game.options.pauseOnLostFocus = false; game.options.renderDistance().set(2); game.options.simulationDistance().set(5);
                    game.options.framerateLimit().set(30); game.options.guiScale().set(2); game.getWindow().setWindowed(1000, 680);
                    connect(game); step++;
                }
                case 1 -> {
                    if (!inWorld(game)) return;
                    rooms = RoomClient.instance();
                    player = (PlaybackController) field(RoomClient.class, "player").get(rooms);
                    if (!player.queuesReady()) return;
                    installFixture(); checkUnavailable(); player.clear(); player.add("42"); step++;
                }
                case 2 -> {
                    if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException("Personal playback failed: " + player.message());
                    if (player.audio().positionMs() < 40) return;
                    checkUnavailable();
                    rooms.command(RoomCommand.Action.CREATE, "Must stay local", 0); rooms.joinTeam();
                    SharedAccountClient.source().select(true); SharedAccountClient.admin().open();
                    check(!SharedAccountClient.source().selected(), "Unavailable shared source was selectable");
                    check(!SharedAccountClient.admin().waiting(), "Unavailable admin sent a request");
                    check(!player.shared(), "Blocked group action changed personal queue");
                    game.setScreen(new RoomScreen(new PlayerScreen(null, player), rooms)); frames = 5; step++;
                }
                case 3 -> {
                    if (frames-- > 0) return;
                    check(!button(game, "modernmusic.room.create").active, "Group creation UI remains enabled");
                    check(game.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .noneMatch(b -> b.visible && b.active && b.getMessage().getString().equals(Component.translatable("modernmusic.team.join").getString())),
                            "FTB listening UI remains enabled");
                    capture(game, "optional-groups.png");
                    game.setScreen(new SharedSourceScreen(null, SharedAccountClient.source())); frames = 5; step++;
                }
                case 4 -> {
                    if (frames-- > 0) return;
                    check(!button(game, "modernmusic.shared.use_server").active, "Shared source UI remains enabled");
                    var manage = button(game, "modernmusic.admin.manage");
                    check(!manage.active && !manage.visible, "Shared administration UI remains enabled");
                    capture(game, "optional-shared-source.png");
                    check(player.queue().entries().size() == 1, "Personal queue lost on unavailable actions");
                    disconnect(game); frames = 10; step++;
                }
                case 5 -> {
                    if (frames-- > 0) return;
                    checkUnavailable();
                    check(player.state() == PlaybackController.State.STOPPED, "Disconnect retained personal playback");
                    game.createWorldOpenFlows().createFreshLevel("optional-restore-" + System.currentTimeMillis(),
                            new net.minecraft.world.level.LevelSettings("Compatibility restore", net.minecraft.world.level.GameType.CREATIVE, false,
                                    net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                            new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false),
                            registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions());
                    step++;
                }
                case 6 -> {
                    if (!inWorld(game) || !rooms.available() || !SharedAccountClient.available() || !SharedAccountClient.adminAvailable()
                            || !SharedAccountClient.admin().allowed()) return;
                    rooms.command(RoomCommand.Action.CREATE, "Capability restored", 0); step++;
                }
                case 7 -> {
                    if (rooms.update().room() == null) return;
                    check(player.shared(), "Integrated server group did not become active");
                    // Seed only availability, without binding an account, to exercise real logout cleanup of a selected source.
                    SharedAccountClient.source().state(Long.MAX_VALUE, null); SharedAccountClient.source().select(true);
                    check(SharedAccountClient.source().selected(), "Integrated shared source did not select");
                    disconnect(game); frames = 10; step++;
                }
                case 8 -> {
                    if (frames-- > 0) return;
                    checkUnavailable(); check(!player.shared(), "Group survived disconnect");
                    check(player.queue().entries().size() == 1, "Disconnect did not restore personal queue");
                    connect(game); step++;
                }
                case 9 -> {
                    if (!inWorld(game)) return;
                    checkUnavailable(); check(!player.shared(), "Previous group leaked onto server without mod");
                    check(rooms.update().room() == null && rooms.update().invitations().isEmpty(), "Stale group state retained");
                    player.play(0); step++;
                }
                case 10 -> {
                    if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException("Personal playback failed after reconnect: " + player.message());
                    if (player.audio().positionMs() < 40) return;
                    checkUnavailable();
                    finish(game, "PASS: joined dedicated Forge without Modern Music twice; personal silent MP3 playback and queue preserved; group/FTB/shared-source/admin actions disabled; integrated-server capabilities and group creation restored; disconnect cleared group state");
                }
                default -> throw new IllegalStateException("Unknown step " + step);
            }
        } catch (Throwable failure) {
            ModernMusic.LOGGER.error("Optional-server compatibility smoke failed at step {}", step, failure);
            finish(game, "FAIL: step=" + step + " type=" + failure.getClass().getSimpleName());
        }
    }
    private static boolean inWorld(Minecraft game) { return game.level != null && game.player != null && game.screen == null && RoomClient.instance() != null; }
    private static void connect(Minecraft game) {
        ConnectScreen.startConnecting(new TitleScreen(), game, new ServerAddress("127.0.0.1", 25577),
                new ServerData("No Modern Music", "127.0.0.1:25577", false), false);
    }
    private static void disconnect(Minecraft game) { game.level.disconnect(); game.clearLevel(new TitleScreen()); game.setScreen(new TitleScreen()); }
    private void checkUnavailable() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            for (var type : List.of(RoomNetwork.CommandPayload.TYPE, RoomNetwork.UpdatePayload.TYPE, RoomNetwork.ClockPayload.TYPE,
                    RoomNetwork.ImportPayload.TYPE, RoomNetwork.ImportResultPayload.TYPE, SharedAccountNetwork.StatePayload.TYPE,
                    SharedAccountNetwork.RequestPayload.TYPE, SharedAccountNetwork.ResultPayload.TYPE, SharedAccountNetwork.ProfilePayload.TYPE,
                    SharedAdminNetwork.CommandPayload.TYPE, SharedAdminNetwork.CapabilityPayload.TYPE, SharedAdminNetwork.SnapshotPayload.TYPE))
                check(!ForgeNetwork.available(connection.getConnection()), "Missing-mod server advertised channel " + type.id());
        }
        check(!rooms.available(), "Group channels advertised without server mod");
        check(!SharedAccountClient.available() && !SharedAccountClient.adminAvailable(), "Shared channels advertised without server mod");
        check(!SharedAccountClient.source().available() && !SharedAccountClient.source().selected(), "Shared account remained available/selected");
        check(!SharedAccountClient.admin().allowed(), "Shared administration remained allowed");
    }
    private void installFixture() throws Exception {
        var personal = field(SharedPlaybackSource.class, "personal");
        ((MusicProvider) personal.get(SharedAccountClient.source())).close();
        personal.set(SharedAccountClient.source(), new MusicProvider() {
            public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Personal playback", List.of("Silent fixture"), 10_000)); }
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/fixture.mp3"), Instant.now().plusSeconds(600))); }
            public CompletableFuture<Lyrics> getLyrics(SongId id) { return CompletableFuture.completedFuture(Lyrics.EMPTY); }
            public void close() {}
        });
        var transport = field(PlaybackController.class, "transport"); ((BoundedHttpTransport) transport.get(player)).close();
        byte[] mp3 = silentMp3(); transport.set(player, (HttpTransport) (uri, max) -> mp3); player.volume(0);
    }
    private static byte[] silentMp3() {
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 400; i++) { byte[] frame = new byte[417]; frame[0] = (byte) 0xff; frame[1] = (byte) 0xfb; frame[2] = (byte) 0x90; out.writeBytes(frame); }
        return out.toByteArray();
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception { var value = type.getDeclaredField(name); value.setAccessible(true); return value; }
    private static Button button(Minecraft game, String key) {
        String label = Component.translatable(key).getString();
        return game.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key));
    }
    private static void capture(Minecraft game, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(game.gameDirectory.toPath().resolve(name)); }
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private void finish(Minecraft game, String result) {
        step = -1;
        try { Files.writeString(directory.resolve("optional-result.txt"), result + "\n"); }
        catch (Exception failure) { ModernMusic.LOGGER.error("Cannot write compatibility result", failure); }
        ModernMusic.LOGGER.info("{}", result); game.stop();
    }
}
