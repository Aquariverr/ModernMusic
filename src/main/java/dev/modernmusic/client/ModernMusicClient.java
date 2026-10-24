package dev.modernmusic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.audio.JavaSoundOutput;
import dev.modernmusic.client.ui.PlayerScreen;
import dev.modernmusic.client.ui.PlayerTheme;
import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.MultiPlatformProvider;
import dev.modernmusic.provider.qq.*;
import dev.modernmusic.provider.kugou.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import dev.modernmusic.provider.netease.NeteaseAccountProvider;
import dev.modernmusic.auth.AccountController;
import dev.modernmusic.auth.RememberedSessionStore;
import dev.modernmusic.client.ui.ArtworkCache;
import dev.modernmusic.client.ui.MusicHud;
import dev.modernmusic.client.ui.ThemedTooltips;
import dev.modernmusic.client.ui.UiFont;
import java.time.Clock;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.*;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.glfw.GLFW;

/** Separate physical-client entry point: dedicated servers never construct audio/UI classes. */
public final class ModernMusicClient {
    private final KeyMapping openPlayer = new KeyMapping("key.modernmusic.open", KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.modernmusic");
    private final KeyMapping pause = unbound("pause"), next = unbound("next"), stop = unbound("stop"), toggleHud = unbound("hud");
    private static KeyMapping unbound(String action) {
        return new KeyMapping("key.modernmusic." + action, KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.modernmusic");
    }
    private PlaybackController player;
    private AccountController qqAccount, kugouAccount;
    private static final java.util.Map<MusicPlatform, AccountController> accounts = new java.util.EnumMap<>(MusicPlatform.class);
    public static AccountController account(MusicPlatform platform) { return accounts.get(platform); }
    private AccountController account;
    private HudSettings hud;
    private RoomClient rooms;
    private final GameAudioBridge audio = new GameAudioBridge(() -> player);

    public static void initialize() { new ModernMusicClient(); }

    private ModernMusicClient() {
        var bus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        var container = net.minecraftforge.fml.ModLoadingContext.get();
        bus.addListener(this::registerKeys);
        bus.addListener(this::soundReloaded);
        bus.addListener(this::registerHud);
        bus.addListener(this::registerReload);
        bus.addListener(this::registerShaders);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::login);
        MinecraftForge.EVENT_BUS.addListener(this::logout);
        MinecraftForge.EVENT_BUS.addListener(this::shutdown);
        MinecraftForge.EVENT_BUS.addListener(audio::playSound);
        MinecraftForge.EVENT_BUS.addListener(ThemedTooltips::render);
        container.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((game, parent) -> new PlayerScreen(parent, player(), account, hud, ModernMusicClient::account)));
    }

    private PlaybackController player() {
        if (player == null) {
            settings();
            BoundedHttpTransport http = new BoundedHttpTransport();
            Minecraft game = Minecraft.getInstance();
            account = new AccountController(new NeteaseAccountProvider(http, Clock.systemUTC()),
                    new RememberedSessionStore(game.gameDirectory.toPath().resolve("modernmusic-private")),
                    game::execute, () -> { if (player != null) player.accountChanged(MusicPlatform.NETEASE); }, Clock.systemUTC());
            qqAccount = new AccountController(new QqAccountProvider(http, Clock.systemUTC()),
                    new RememberedSessionStore(game.gameDirectory.toPath().resolve("modernmusic-private/qq")), game::execute,
                    () -> { if (player != null) player.accountChanged(MusicPlatform.QQ); }, Clock.systemUTC());
            kugouAccount = new AccountController(new KugouAccountProvider(http, Clock.systemUTC()),
                    new RememberedSessionStore(game.gameDirectory.toPath().resolve("modernmusic-private/kugou")), game::execute,
                    () -> { if (player != null) player.accountChanged(MusicPlatform.KUGOU); }, Clock.systemUTC());
            accounts.put(MusicPlatform.NETEASE, account); accounts.put(MusicPlatform.QQ, qqAccount); accounts.put(MusicPlatform.KUGOU, kugouAccount);
            var netease = SharedAccountClient.connect(new NeteaseMusicProvider(http, Clock.systemUTC(), account::cookie,
                    cookie -> game.execute(() -> account.invalidate(cookie))), () -> { if (player != null) player.accountChanged(MusicPlatform.NETEASE); });
            var qq = SharedAccountClient.connect(MusicPlatform.QQ, new QqMusicProvider(http, Clock.systemUTC(), qqAccount::cookie,
                    cookie -> game.execute(() -> qqAccount.invalidate(cookie))), () -> { if (player != null) player.accountChanged(MusicPlatform.QQ); });
            var kugou = SharedAccountClient.connect(MusicPlatform.KUGOU, new KugouMusicProvider(http, Clock.systemUTC(), kugouAccount::cookie,
                    cookie -> game.execute(() -> kugouAccount.invalidate(cookie))), () -> { if (player != null) player.accountChanged(MusicPlatform.KUGOU); });
            ServerAudioClient.initialize();
            var provider = new MultiPlatformProvider(netease, qq, kugou,
                    new LocalAudioProvider(game.gameDirectory.toPath().resolve("modernmusic/audio"), game::execute));
            player = new PlaybackController(provider, http,
                    new JavaSoundOutput(), Minecraft.getInstance()::execute,
                    new QueueStore(game.gameDirectory.toPath().resolve("config/modernmusic-queues.json")));
            rooms = new RoomClient(player);
        }
        return player;
    }

    private void settings() {
        if (hud != null) return;
        hud = new HudSettings(Minecraft.getInstance().gameDirectory.toPath().resolve("config/modernmusic-hud.json"));
        UiFont.smooth(hud.font() == HudSettings.FontStyle.SMOOTH);
        PlayerTheme.apply(hud.theme());
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        for (var key : new KeyMapping[]{openPlayer, pause, next, stop, toggleHud}) event.register(key);
    }
    private void registerHud(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("music_hud", (gui, graphics, delta, width, height) -> {
            if (hud != null) MusicHud.render(graphics, player, hud, graphics.guiWidth(), graphics.guiHeight(), false);
        });
    }
    private void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new net.minecraft.client.renderer.ShaderInstance(event.getResourceProvider(),
                    new net.minecraft.resources.ResourceLocation(ModernMusic.MOD_ID, "ui_shape"),
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR), dev.modernmusic.client.ui.Shapes::shader);
        } catch (java.io.IOException error) {
            // Shapes still draw with the vanilla shader; only the faintest fades are lost.
            dev.modernmusic.client.ui.Shapes.shader(null);
            ModernMusic.LOGGER.warn("UI shape shader unavailable; using the vanilla shader", error);
        }
    }
    private void registerReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                manager -> Minecraft.getInstance().execute(() -> {
                    ArtworkCache.clearIfCreated();
                    PlayerTheme.reloadSprites();
                    UiFont.clear();
                    if (player != null) player.audioReloaded();
                }));
    }
    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        if (hud == null && game.getOverlay() == null) settings();
        // Build the smooth font for the current GUI scale before a screen needs it (after loading or resizing).
        if (game.getOverlay() == null) { UiFont.get(); UiFont.prewarm(hud); }
        while (openPlayer.consumeClick()) {
            if (game.level != null && game.screen == null) game.setScreen(new PlayerScreen(null, player(), account, hud, ModernMusicClient::account));
        }
        consume(pause, game, () -> player().togglePause());
        consume(next, game, () -> player().next());
        consume(stop, game, () -> player().stop());
        consume(toggleHud, game, () -> { hud.enabled(!hud.enabled()); hud.save(); });
        if (account != null) account.tick();
        if (qqAccount != null) qqAccount.tick();
        if (kugouAccount != null) kugouAccount.tick();
        if (player != null) { SharedAccountClient.tick(); ServerAudioClient.tick(); audio.tick(game); }
        if (rooms != null) rooms.tick();
    }
    // Fired while handling the login packet, before subsequent play payloads are dispatched.
    private void login(ClientPlayerNetworkEvent.LoggingIn event) { player(); ServerAudioClient.disconnect(); SharedAccountClient.beginConnection(); }
    private void consume(KeyMapping key, Minecraft game, Runnable action) {
        // Always drain clicks: typing in chat/screens must never replay commands on closing.
        while (key.consumeClick()) if (game.level != null && game.screen == null && player != null) action.run();
    }
    private void logout(ClientPlayerNetworkEvent.LoggingOut event) { SharedAccountClient.disconnect(); ServerAudioClient.disconnect(); if (rooms != null) rooms.disconnect(); if (player != null) player.suspend(); ArtworkCache.clearIfCreated(); }
    private void soundReloaded(SoundEngineLoadEvent event) {
        if (player != null) Minecraft.getInstance().execute(() -> player.audioReloaded());
    }
    private void shutdown(net.minecraftforge.event.GameShuttingDownEvent event) {
        try { try { if (account != null) account.close(); } finally { try { if (qqAccount != null) qqAccount.close(); } finally { if (kugouAccount != null) kugouAccount.close(); accounts.clear(); } } }
        finally { try { if (player != null) player.close(); } finally { ArtworkCache.closeIfCreated(); } }
    }
}
