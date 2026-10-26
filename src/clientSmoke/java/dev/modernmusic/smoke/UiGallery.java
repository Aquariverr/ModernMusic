package dev.modernmusic.smoke;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.auth.*;
import dev.modernmusic.client.*;
import dev.modernmusic.client.audio.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.netease.NeteaseUris;
import dev.modernmusic.room.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in visual gallery (runUiGallery): every screen at several sizes and GUI scales, fixture data only. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class UiGallery {
    private static final URI ART = URI.create("https://p1.music.126.net/gallery/cover.jpg");
    private static final URI AVATAR = URI.create("https://p1.music.126.net/gallery/avatar.jpg");
    private record Shot(String name, int width, int height, int scale, Runnable setup, double mouseX, double mouseY) {}
    private final List<Shot> shots = new ArrayList<>();
    private PlaybackController player;
    private final Fixture fixture = new Fixture();
    private HudSettings hud;
    private int step, frames, shot = -1;
    private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(8);
    private Path output;
    private final boolean localAudioGallery = "audio".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private final boolean audioDropGallery = "audio-drop".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private Runnable shotTick = () -> {};
    private java.util.function.BooleanSupplier shotReady = () -> true;
    private final boolean ae2MenuGallery = "room-ae2".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private final boolean roomManagementGallery = "room-v2".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private final boolean roomAvatarGallery = "room-avatars".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private final boolean roomGallery = ae2MenuGallery || "room".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"));
    private final boolean hidden = Boolean.parseBoolean(System.getenv("MODERN_MUSIC_GALLERY_HIDDEN"));
    private int menuHover = -1;
    private int menuKeyboardSteps, menuLateHover = -1, menuOriginOffset;

    public UiGallery() {
        if (Boolean.getBoolean("modernmusic.uiGallery")) NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        if (step < 0) return;
        var game = Minecraft.getInstance();
        try {
            if (hidden) org.lwjgl.glfw.GLFW.glfwHideWindow(game.getWindow().getWindow());
            if (System.nanoTime() > deadline) throw new IllegalStateException("Gallery timeout at step " + step + " shot " + shot);
            if (step == 4) shotTick.run();
            if (ae2MenuGallery && step == 4 && game.screen instanceof RoomScreen) {
                if (frames == 8) for (int i = 0; i < menuKeyboardSteps; i++) game.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, 0);
                if (frames == 4 && menuLateHover >= 0) moveMenuPointer(game, menuLateHover);
            }
            if (game.getOverlay() != null || frames-- > 0) return;
            switch (step) {
                case 0 -> {
                    if (game.screen instanceof AccessibilityOnboardingScreen) { game.options.onboardingAccessibilityFinished(); game.setScreen(new TitleScreen()); }
                    if (!(game.screen instanceof TitleScreen)) return;
                    if (!game.options.languageCode.equals("zh_cn")) {
                        game.options.languageCode = "zh_cn"; game.getLanguageManager().setSelected("zh_cn"); game.reloadResourcePacks(); return;
                    }
                    output = game.gameDirectory.toPath().resolve("gallery"); Files.createDirectories(output);
                    game.options.pauseOnLostFocus = false; game.options.renderDistance().set(4); game.options.simulationDistance().set(5);
                    game.options.framerateLimit().set(60); game.options.guiScale().set(2); game.getWindow().setWindowed(1280, 800);
                    game.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE; game.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    game.createWorldOpenFlows().createFreshLevel("ui-gallery-" + System.currentTimeMillis(),
                            new net.minecraft.world.level.LevelSettings("UI gallery", net.minecraft.world.level.GameType.CREATIVE, false,
                                    net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                            new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false),
                            registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                    frames = 40; step = 1;
                }
                case 1 -> {
                    if (game.level == null || game.player == null || RoomClient.instance() == null || game.screen != null) return;
                    installFixture(game); seedArtwork(game); step = 2; frames = 10;
                }
                case 2 -> {
                    if (!player.queuesReady()) return;
                    player.clear();
                    var target = player.searchDestinations().stream().filter(entry -> entry.id().equals(player.library().active().id())).findFirst().orElseThrow();
                    var tracks = new ArrayList<Track>(); for (long id = 42; id < 54; id++) tracks.add(track(id));
                    player.addSearchTracks(target, tracks); step = 20; frames = 10;
                }
                case 20 -> {
                    if (player.queue().entries().size() < 12) return;
                    player.play(0); step = 3; frames = 20;
                }
                case 3 -> {
                    if (player.state() != PlaybackController.State.PLAYING || player.lyrics().lines().isEmpty()) return;
                    // Synthetic PCM for the waveform only. GalleryOutput never opens an audio device.
                    field(PlaybackController.class, "bufferedAudio").set(player, waveformAudio());
                    planShots(game); step = 4;
                }
                case 4 -> {
                    if (shot >= 0 && !shotReady.getAsBoolean()) return;
                    if (shot >= 0) capture(game, shots.get(shot).name());
                    if (++shot >= shots.size()) { finish(game, "PASS: " + shots.size() + " gallery screenshots"); return; }
                    var next = shots.get(shot);
                    game.getWindow().setWindowed(next.width(), next.height());
                    game.options.guiScale().set(next.scale()); game.resizeDisplay();
                    UiFont.smooth(true); theme(game, HudSettings.Theme.ADAPTIVE);
                    menuHover = menuLateHover = -1; menuKeyboardSteps = menuOriginOffset = 0;
                    shotTick = () -> {}; shotReady = () -> true; next.setup().run(); frames = 12;
                    // Window pixels; negative keeps the cursor off every widget so no hover or tooltip leaks in.
                    double mouseX = next.mouseX(), mouseY = next.mouseY();
                    if (menuHover >= 0) {
                        var menu = (List<?>) field(RoomScreen.class, "menuButtons").get(game.screen);
                        var row = (net.minecraft.client.gui.components.AbstractWidget) menu.get(menuHover);
                        mouseX = row.getX() + row.getWidth() / 2.0; mouseY = row.getY() + row.getHeight() / 2.0;
                    }
                    if (game.screen instanceof MusicScreen music) {
                        mouseX = music.toGuiCoordinate(mouseX); mouseY = music.toGuiCoordinate(mouseY);
                    }
                    field(MouseHandler.class, "xpos").set(game.mouseHandler, mouseX * game.getWindow().getGuiScale());
                    field(MouseHandler.class, "ypos").set(game.mouseHandler, mouseY * game.getWindow().getGuiScale());
                }
                default -> throw new IllegalStateException("Unknown step " + step);
            }
        } catch (Throwable error) {
            ModernMusic.LOGGER.error("UI gallery failed at step {} shot {}", step, shot, error);
            finish(game, "FAIL: step=" + step + " shot=" + shot + " " + error);
        }
    }

    private void planShots(Minecraft game) {
        if ("hud".equalsIgnoreCase(System.getenv("MODERN_MUSIC_GALLERY_ONLY"))) { planHudShots(game); return; }
        if (roomAvatarGallery) { planRoomAvatarShots(game); return; }
        if (audioDropGallery) { planAudioDropShots(game); return; }
        if (localAudioGallery) { planAudioShots(game); return; }
        if (roomManagementGallery) { planRoomManagementShots(game); return; }
        if (ae2MenuGallery) { planAe2MenuShots(game); return; }
        if (roomGallery) { planRoomShots(game); return; }
        for (var platform : MusicPlatform.onlineValues()) {
            String name = "shared-login-" + platform.name().toLowerCase(Locale.ROOT);
            add(name + "-unchecked", 1280, 800, 2, () -> sharedLogin(game, platform, false));
            add(name + "-checked", 680, 480, 2, () -> sharedLogin(game, platform, true));
        }
        for (var selectedTheme : HudSettings.Theme.values()) {
            add("shared-login-theme-" + selectedTheme.name().toLowerCase(Locale.ROOT), 680, 480, 2, () -> {
                theme(game, selectedTheme); sharedLogin(game, MusicPlatform.NETEASE, true);
            });
        }
        add("shared-login-small", 420, 360, 2, () -> sharedLogin(game, MusicPlatform.KUGOU, true));
        for (var platform : MusicPlatform.onlineValues()) {
            String name = "cookie-json-" + platform.name().toLowerCase(Locale.ROOT);
            add(name + "-wide", 1280, 800, 2, () -> cookieImport(game, platform, false));
            add(name + "-narrow", 680, 480, 2, () -> cookieImport(game, platform, false));
        }
        add("cookie-json-invalid", 680, 480, 2, () -> cookieImport(game, MusicPlatform.NETEASE, true));
        add("qq-player-wide", 1280, 800, 2, () -> qqPlayer(game, false));
        add("qq-player-narrow", 680, 480, 2, () -> qqPlayer(game, false));
        add("qq-account", 1280, 800, 2, () -> { qqPlayer(game, false); game.setScreen(new AccountScreen(null, qqAccount)); });
        add("qr-login-qq-tabs-narrow", 680, 480, 2, () -> { qqPlayer(game, false); game.setScreen(new AccountScreen(null, qqAccount)); });
        add("qr-login-qq-wide", 1280, 800, 2, () -> platformQr(game, MusicPlatform.QQ, AccountProvider.QrMethod.QQ));
        add("qr-login-qq-narrow", 680, 480, 2, () -> platformQr(game, MusicPlatform.QQ, AccountProvider.QrMethod.QQ));
        add("qr-login-wechat-wide", 1280, 800, 2, () -> platformQr(game, MusicPlatform.QQ, AccountProvider.QrMethod.WECHAT));
        add("qr-login-wechat-narrow", 680, 480, 2, () -> platformQr(game, MusicPlatform.QQ, AccountProvider.QrMethod.WECHAT));
        add("qq-shared", 1280, 800, 2, () -> { qqPlayer(game, true); game.setScreen(new SharedSourceScreen(null, player.sharedSource())); });
        add("qq-search", 1280, 800, 2, () -> { qqPlayer(game, false); game.setScreen(new SongSearchScreen(null, player, "晚风")); });
        add("kugou-player-wide", 1280, 800, 2, () -> kugouPlayer(game, false));
        add("kugou-player-narrow", 680, 480, 2, () -> kugouPlayer(game, false));
        add("kugou-account", 1280, 800, 2, () -> { kugouPlayer(game, false); game.setScreen(new AccountScreen(null, kugouAccount)); });
        add("qr-login-kugou-tabs-narrow", 680, 480, 2, () -> { kugouPlayer(game, false); game.setScreen(new AccountScreen(null, kugouAccount)); });
        add("qr-login-kugou-wide", 1280, 800, 2, () -> platformQr(game, MusicPlatform.KUGOU, AccountProvider.QrMethod.APP));
        add("qr-login-kugou-narrow", 680, 480, 2, () -> platformQr(game, MusicPlatform.KUGOU, AccountProvider.QrMethod.APP));
        add("kugou-shared", 1280, 800, 2, () -> { kugouPlayer(game, true); game.setScreen(new SharedSourceScreen(null, player.sharedSource())); });
        add("kugou-search", 1280, 800, 2, () -> { kugouPlayer(game, false); game.setScreen(new SongSearchScreen(null, player, "晚风")); });
        for (var selectedTheme : new HudSettings.Theme[]{HudSettings.Theme.CREATE, HudSettings.Theme.AE2, HudSettings.Theme.ARS_NOUVEAU, HudSettings.Theme.ASTRAL_SORCERY, HudSettings.Theme.OREUI}) {
            add("kugou-theme-" + selectedTheme.name().toLowerCase(Locale.ROOT), 1280, 800, 2, () -> {
                theme(game, selectedTheme); kugouPlayer(game, false);
            });
        }
        Runnable playerScreen = () -> game.setScreen(new PlayerScreen(null, player, null, hud));
        add("player-wide", 1280, 800, 2, playerScreen);
        add("player-854", 854, 480, 2, playerScreen);
        add("player-narrow", 680, 480, 2, playerScreen);
        add("player-gs3", 1280, 800, 3, playerScreen);
        add("player-gs4", 1600, 960, 4, playerScreen);
        // The same physical window must retain the full composition at every vanilla GUI setting.
        for (int scale : new int[]{1, 2, 3, 4, 0}) {
            add("scaling-720-gs" + scale, 1280, 720, scale, () -> {
                theme(game, HudSettings.Theme.CREATE); playerScreen.run();
            });
        }
        for (int[] size : new int[][]{{854, 480}, {1366, 768}, {1474, 904}, {1920, 1080}, {2560, 1440}, {960, 960}, {3440, 1440}}) {
            add("scaling-" + size[0] + "x" + size[1], size[0], size[1], 0, () -> {
                theme(game, HudSettings.Theme.CREATE); playerScreen.run();
            });
        }
        shots.add(new Shot("player-hover.png", 1280, 800, 2, playerScreen, 520, 268));
        add("player-pixel", 1280, 800, 2, () -> { playerScreen.run(); UiFont.smooth(false); game.screen.init(game, game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight()); });
        add("hud-world", 1280, 800, 2, () -> game.setScreen(null));
        add("hud-world-gs3", 1280, 800, 3, () -> game.setScreen(null));
        add("hud-editor", 1280, 800, 2, () -> game.setScreen(new HudEditorScreen(null, player, hud)));
        add("hud-editor-compact", 680, 480, 2, () -> game.setScreen(new HudEditorScreen(null, player, hud)));
        add("hud-lyrics-bold", 1280, 800, 2, () -> {
            var styled = new HudSettings(output.resolve("hud-bold.json")); styled.ensureLayout(640, 400);
            styled.lyricStyle(HudSettings.LyricStyle.BOLD); styled.component(HudSettings.Part.LYRICS).scale(1.5);
            styled.component(HudSettings.Part.PROGRESS).position(8, 120, 640, 400);
            game.setScreen(hudOnly(styled));
        });
        // Laid out at GUI scale 2, shown at 2 and 3: corner and centered groups keep their GUI-pixel margins.
        Runnable anchored = () -> {
            var layout = new HudSettings(output.resolve("hud-anchored.json")); layout.ensureLayout(640, 400);
            layout.component(HudSettings.Part.COVER).position(352, 12, 640, 400); layout.component(HudSettings.Part.INFO).position(410, 12, 640, 400);
            layout.component(HudSettings.Part.LYRICS).position(180, 312, 640, 400); layout.component(HudSettings.Part.PROGRESS).position(180, 344, 640, 400);
            game.setScreen(hudOnly(layout));
        };
        add("hud-anchored", 1280, 800, 2, anchored);
        add("hud-anchored-gs3", 1280, 800, 3, anchored);
        add("search", 1280, 800, 2, () -> {
            var screen = new SongSearchScreen(null, player); game.setScreen(screen); player.search().search("晚风", 0, 8);
        });
        add("queues", 1280, 800, 2, () -> game.setScreen(new QueueManagerScreen(null, player)));
        add("cache", 1280, 800, 2, () -> game.setScreen(new CacheScreen(null, player)));
        add("room", 1280, 800, 2, () -> game.setScreen(new RoomScreen(null, RoomClient.instance())));
        add("account", 1280, 800, 2, () -> game.setScreen(new AccountScreen(null, account(game, false))));
        add("account-qr", 1280, 800, 2, () -> {
            var account = account(game, false); account.beginQr(); game.setScreen(new AccountScreen(null, account));
        });
        add("account-signed-in", 1280, 800, 2, () -> {
            var account = account(game, true); account.loginCookie("MUSIC_U=gallery-fixture-not-a-real-cookie");
            game.setScreen(new AccountScreen(null, account));
        });
        // Every theme on the player, plus the theme picker and OreUI across the other surfaces.
        Runnable themes = () -> game.setScreen(new ThemeScreen(null, new HudSettings(output.resolve("theme-gallery.json")), () -> player.track().artwork()));
        add("theme-picker", 1280, 800, 2, themes);
        add("theme-picker-compact", 854, 480, 2, themes);
        for (var theme : HudSettings.Theme.values()) {
            String name = theme.name().toLowerCase(Locale.ROOT);
            add("theme-" + name + "-player", 1280, 800, 2, () -> { theme(game, theme); playerScreen.run(); });
            add("theme-" + name + "-player-narrow", 680, 480, 2, () -> { theme(game, theme); playerScreen.run(); });
            add("theme-" + name + "-hud", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(null); });
        }
        add("theme-oreui-picker", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.OREUI); themes.run(); });
        add("theme-oreui-search", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.OREUI); game.setScreen(new SongSearchScreen(null, player)); player.search().search("晚风", 0, 8);
        });
        add("theme-oreui-hud-editor", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.OREUI); game.setScreen(new HudEditorScreen(null, player, hud)); });
        add("theme-oreui-account", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.OREUI); game.setScreen(new AccountScreen(null, account(game, false))); });
        add("theme-oreui-pixel", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.OREUI); playerScreen.run(); UiFont.smooth(false);
            game.screen.init(game, game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight());
        });
        add("theme-daylight-search", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.DAYLIGHT); game.setScreen(new SongSearchScreen(null, player)); player.search().search("晚风", 0, 8);
        });
        add("theme-daylight-queues", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.DAYLIGHT); game.setScreen(new QueueManagerScreen(null, player)); });
        add("theme-midnight-hud-editor", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.MIDNIGHT); game.setScreen(new HudEditorScreen(null, player, hud)); });
        add("theme-mint-search", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.MINT); game.setScreen(new SongSearchScreen(null, player)); player.search().search("晚风", 0, 8);
        });
        add("theme-mint-hud-editor", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.MINT); game.setScreen(new HudEditorScreen(null, player, hud)); });
        add("theme-mint-account", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.MINT); var account = account(game, true); account.loginCookie("MUSIC_U=gallery-fixture-not-a-real-cookie");
            game.setScreen(new AccountScreen(null, account));
        });
        // The mod-inspired skins on every other surface, plus a hovered row and button, to compare states.
        for (var theme : List.of(HudSettings.Theme.CREATE, HudSettings.Theme.AE2, HudSettings.Theme.ARS_NOUVEAU, HudSettings.Theme.ASTRAL_SORCERY)) {
            String name = "theme-" + theme.name().toLowerCase(Locale.ROOT);
            add(name + "-search", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(new SongSearchScreen(null, player)); player.search().search("晚风", 0, 8); });
            add(name + "-queues", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(new QueueManagerScreen(null, player)); });
            add(name + "-hud-editor", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(new HudEditorScreen(null, player, hud)); });
            add(name + "-account", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(new AccountScreen(null, account(game, false))); });
            add(name + "-picker", 1280, 800, 2, () -> { theme(game, theme); themes.run(); });
            add(name + "-cache", 1280, 800, 2, () -> { theme(game, theme); game.setScreen(new CacheScreen(null, player)); });
            shots.add(new Shot(name + "-hover-row.png", 1280, 800, 2, () -> { theme(game, theme); playerScreen.run(); }, 520, 268));
            shots.add(new Shot(name + "-hover-button.png", 1280, 800, 2, () -> { theme(game, theme); playerScreen.run(); }, 538, 59));
        }
        var sharedSource = new SharedPlaybackSource(fixture, request -> {}, () -> {}, Clock.systemUTC());
        sharedSource.state(1, null);
        sharedSource.profile(1, new dev.modernmusic.shared.SharedAccountProfile("Private shared nickname", AVATAR.toString()));
        Runnable sharedPlayer = () -> {
            sharedSource.select(true); setProvider(sharedSource);
            game.setScreen(new PlayerScreen(null, player, account(game, false), hud)); SharedDisplayScenario.focusAccount(game);
            var view = PlaybackAccountView.of(null, "modernmusic.auth.guest", sharedSource);
            if (!view.server() || !view.ready() || !view.nickname().isEmpty() || view.avatar() != null) throw new AssertionError("Shared identity leaked");
            SharedDisplayScenario.checkSharedTooltip(game, player.platform());
        };
        add("privacy-shared-player", 1280, 800, 2, sharedPlayer);
        add("privacy-shared-player-compact", 680, 480, 4, sharedPlayer);
        Runnable sharedScreen = () -> {
            sharedSource.select(true); game.setScreen(new SharedSourceScreen(null, sharedSource));
            var toggle = game.screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                    .map(net.minecraft.client.gui.components.Button.class::cast)
                    .filter(button -> button.getMessage().getString().contains("使用本机账号")).findFirst().orElseThrow();
            game.setLastInputType(InputType.KEYBOARD_TAB); game.screen.setFocused(toggle);
        };
        add("privacy-source", 1280, 800, 2, sharedScreen);
        add("privacy-source-compact", 680, 480, 4, sharedScreen);
        add("privacy-source-narrow", 420, 360, 2, sharedScreen);
        add("privacy-local-player", 1280, 800, 2, () -> {
            setProvider(fixture);
            var local = account(game, true); local.loginCookie("MUSIC_U=gallery-fixture-not-a-real-cookie");
            game.setScreen(new PlayerScreen(null, player, local, hud)); SharedDisplayScenario.focusAccount(game);
        });
        add("privacy-source-unavailable", 680, 480, 2, () -> {
            sharedSource.state(2, MusicError.SHARED_FORBIDDEN); game.setScreen(new SharedSourceScreen(null, sharedSource));
        });
        add("waveform-paused", 1280, 800, 2, () -> { setProvider(fixture); player.togglePause(); playerScreen.run(); });
        add("waveform-seek", 1280, 800, 2, () -> { player.togglePause(); player.seek(42_000); playerScreen.run(); });
        add("waveform-stopped", 680, 480, 2, () -> { player.stop(); playerScreen.run(); });
        add("privacy-hud-stopped", 1280, 800, 2, () -> {
            setProvider(fixture); player.stop(); game.setScreen(hudOnly(hud));
            String status = MusicHud.status(player);
            if (!status.endsWith("已停止") || status.contains("重新开始")) throw new AssertionError("Stopped HUD contains instructions");
        });
        add("waveform-empty", 1280, 800, 2, () -> { player.clear(); playerScreen.run(); });
        add("ide-admin-state-reset", 680, 480, 2, () -> IdeInspectionSmoke.verify(game));
        // MODERNMUSIC_GALLERY_ONLY=theme-create,theme-ae2 keeps just the shots whose names contain one of the parts.
        String only = System.getenv("MODERNMUSIC_GALLERY_ONLY");
        if (only != null && !only.isBlank()) {
            var parts = List.of(only.split(","));
            shots.removeIf(shot -> parts.stream().noneMatch(part -> shot.name().contains(part.strip())));
        }
    }
    private void sharedLogin(Minecraft game, MusicPlatform platform, boolean checked) {
        try {
            if (!SharedAccountClient.keepLogin(platform, false)) throw new AssertionError("Shared login settings unavailable");
            var source = new SharedPlaybackSource(fixture, request -> {}, () -> {}, Clock.systemUTC(), platform,
                    () -> SharedAccountClient.keepLogin(platform));
            source.state(1, null); source.select(true);
            game.setScreen(new SharedSourceScreen(null, source));
            if (checked) {
                clickKey(game, "modernmusic.shared.keep_login");
                if (!SharedAccountClient.keepLogin(platform)) throw new AssertionError("Shared login checkbox did not save");
                source.disconnect(); source.state(1, null);
                if (!source.selected()) throw new AssertionError("Shared login did not restore after reconnect");
                clickKey(game, "modernmusic.shared.keep_login");
                if (SharedAccountClient.keepLogin(platform) || !source.selected()) {
                    capture(game, "shared-login-click-failure.png");
                    throw new AssertionError("Unchecking failed: remembered=" + SharedAccountClient.keepLogin(platform) + ", selected=" + source.selected());
                }
                source.disconnect(); source.state(1, null);
                if (source.selected()) throw new AssertionError("Unchecked shared login restored");
                clickKey(game, "modernmusic.shared.keep_login");
                source.disconnect(); source.state(1, null);
            }
            game.setScreen(new SharedSourceScreen(null, source));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void setProvider(MusicProvider provider) {
        try { field(PlaybackController.class, "provider").set(player, provider); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private LocalAudioProvider audioProvider;
    private List<Track> audioLocalTracks;
    private final List<Track> audioServerTracks = new ArrayList<>();
    private boolean audioAdmin = true, audioOnline = true, audioUploadPending, audioUploadFailure;
    private CompletableFuture<Track> audioUpload;
    private final Map<String, byte[]> audioUploadedFiles = new LinkedHashMap<>();
    private Path audioDropExternal;
    private byte[] audioExistingBytes;
    private CompletableFuture<?> audioDropOperation;
    private boolean audioDropInvoked;

    /** Real MP3/WAV files with a deterministic in-process server catalogue; no external account or device. */
    private void installAudioFixture(Minecraft game) throws Exception {
        Path folder = output.resolve(audioDropGallery ? "audio-drop-fixture-" + System.currentTimeMillis() : "audio-fixture"); Files.createDirectories(folder);
        for (int i = 0; i < SONGS.length; i++) {
            String extension = i % 2 == 0 ? ".wav" : ".mp3";
            String name = SONGS[i][0] + extension;
            Files.write(folder.resolve(name), extension.equals(".wav") ? silentWav() : silentMp3());
            audioServerTracks.add(new Track(new SongId("server_" + String.format(Locale.ROOT, "%032x", i + 1), MusicPlatform.LOCAL), name, List.of(), 252_000));
        }
        audioProvider = new LocalAudioProvider(folder, game::execute, new LocalAudioProvider.Remote() {
            public boolean available() { return audioOnline; }
            public boolean canUpload() { return audioAdmin; }
            public CompletableFuture<List<Track>> refresh() {
                return audioOnline ? CompletableFuture.completedFuture(List.copyOf(audioServerTracks))
                        : CompletableFuture.failedFuture(new MusicException(MusicError.RESOURCE_UNAVAILABLE));
            }
            public CompletableFuture<Track> getTrack(SongId id) {
                return CompletableFuture.completedFuture(audioServerTracks.stream().filter(track -> track.id().equals(id)).findFirst().orElseThrow());
            }
            public CompletableFuture<byte[]> download(SongId id) { return CompletableFuture.completedFuture(silentWav()); }
            public CompletableFuture<Track> upload(String name, byte[] bytes) {
                if (!audioAdmin || audioUploadFailure) return CompletableFuture.failedFuture(new MusicException(MusicError.SHARED_FORBIDDEN));
                var track = new Track(new SongId("server_" + (audioDropGallery ? String.format(Locale.ROOT, "%032x", 10000 + audioUploadedFiles.size()) : "e".repeat(32)), MusicPlatform.LOCAL), name, List.of(), 1000);
                if (audioDropGallery) { audioUploadedFiles.put(name, bytes.clone()); audioServerTracks.add(track); }
                audioUpload = audioUploadPending ? new CompletableFuture<>() : CompletableFuture.completedFuture(track);
                return audioUpload;
            }
        });
        audioLocalTracks = audioProvider.browseLocal().join();
        if (audioLocalTracks.size() != SONGS.length) throw new AssertionError("Audio gallery files failed validation");
        var multi = new MultiPlatformProvider(fixture, fixture, fixture, audioProvider); multi.select(MusicPlatform.LOCAL); setProvider(multi);
        player.stop(); player.applyRoom(null); player.clearPersonalQueue();
        var destination = player.searchDestinations().stream().filter(entry -> !entry.shared()).findFirst().orElseThrow();
        player.addSearchTracks(destination, audioLocalTracks).join();
    }
    /** A focused drag/drop gallery, with tick-driven completion so upload continuations can use the main executor. */
    private void planAudioDropShots(Minecraft game) {
        try {
            installAudioFixture(game);
            audioDropExternal = Files.createTempDirectory(output, "audio-drop-external-");
            audioExistingBytes = silentWav(); audioExistingBytes[44] = 42;
            Files.write(audioProvider.directory().resolve("同名保留.wav"), audioExistingBytes);
            Files.write(audioDropExternal.resolve("同名保留.wav"), silentWav());
            Files.write(audioDropExternal.resolve("拖入的雨声.wav"), silentWav());
            Files.write(audioDropExternal.resolve("拖入的旋律.mp3"), silentMp3());
            Files.writeString(audioDropExternal.resolve("不支持的文件.txt"), "Not an audio file");
            Files.write(audioDropExternal.resolve("直接上传.wav"), silentWav());
            Files.write(audioDropExternal.resolve("直接上传.mp3"), silentMp3());
            Files.write(audioDropExternal.resolve("禁止上传.wav"), silentWav());
            Files.write(audioDropExternal.resolve("播放器拖入.wav"), silentWav());
        } catch (Exception failure) { throw new IllegalStateException("Audio drop fixture setup", failure); }
        for (var style : new HudSettings.Theme[] {HudSettings.Theme.ADAPTIVE, HudSettings.Theme.CREATE}) {
            String name = style == HudSettings.Theme.ADAPTIVE ? "adaptive" : "create";
            add("audio-drop-song-" + name, 960, 720, 2, () -> audioDropPlayer(game, style, false));
            add("audio-drop-selected-" + name, 960, 720, 2, () -> audioDropPlayer(game, style, true));
        }
        add("audio-drop-local-mixed", 960, 720, 2, () -> {
            audioDropPlayer(game, HudSettings.Theme.ADAPTIVE, true);
            game.setScreen(new AudioBrowserScreen(null, player, "", false));
            stageAudioDrop(game, List.of("拖入的雨声.wav", "拖入的旋律.mp3", "同名保留.wav", "不支持的文件.txt"));
        });
        add("audio-drop-server-direct", 960, 720, 2, () -> {
            audioAdmin = true; audioDropPlayer(game, HudSettings.Theme.ADAPTIVE, true);
            roomFixture(game, "owner");
            stageAudioDrop(game, List.of("直接上传.wav", "直接上传.mp3", "不支持的文件.txt"));
        });
        add("audio-drop-server-nonadmin", 960, 720, 2, () -> {
            audioDropPlayer(game, HudSettings.Theme.CREATE, true); audioAdmin = false;
            game.setScreen(new AudioBrowserScreen(null, player, "", true));
            stageAudioDrop(game, List.of("禁止上传.wav"));
        });
        add("audio-drop-player-local", 960, 720, 2, () -> {
            audioAdmin = true; audioDropPlayer(game, HudSettings.Theme.CREATE, true);
            stageAudioDrop(game, List.of("播放器拖入.wav"));
        });
    }
    private void audioDropPlayer(Minecraft game, HudSettings.Theme style, boolean local) {
        try {
            theme(game, style); player.stop(); player.applyRoom(null);
            var provider = (MultiPlatformProvider) field(PlaybackController.class, "provider").get(player);
            provider.select(local ? MusicPlatform.LOCAL : MusicPlatform.NETEASE);
            game.setScreen(new PlayerScreen(null, player, null, hud));
        } catch (Exception failure) { throw new IllegalStateException("Audio selected-label setup", failure); }
    }
    private void stageAudioDrop(Minecraft game, List<String> filenames) {
        audioDropInvoked = false; audioDropOperation = null;
        List<Path> paths = filenames.stream().map(audioDropExternal::resolve).toList();
        shotReady = () -> audioDropInvoked && audioDropOperation == null;
        shotTick = () -> {
            try {
                if (!audioDropInvoked) {
                    if (game.screen instanceof AudioBrowserScreen browser && field(AudioBrowserScreen.class, "pendingLoad").get(browser) != null) return;
                    if (game.screen instanceof RoomScreen room) {
                        var tab = (net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, "inputType").get(room);
                        Class<?> button = Class.forName("dev.modernmusic.client.ui.ThemedButton");
                        if (tab.active || !field(button, "selected").getBoolean(tab) || !field(button, "selectedOnly").getBoolean(tab))
                            throw new AssertionError("Room audio label lost selected inactive appearance");
                    }
                    game.screen.onFilesDrop(paths); audioDropInvoked = true;
                    if (!(game.screen instanceof AudioBrowserScreen browser)) throw new AssertionError("Drop did not open audio browser");
                    audioDropOperation = (CompletableFuture<?>) field(AudioBrowserScreen.class, "pendingDrop").get(browser);
                    // Permission rejection is synchronous and intentionally never starts an upload.
                    if (audioDropOperation == null && audioAdmin) throw new AssertionError("Drop never started import");
                    frames = Math.max(frames, 12);
                }
                if (audioDropOperation != null && audioDropOperation.isDone()) {
                    var browser = (AudioBrowserScreen) game.screen;
                    if (field(AudioBrowserScreen.class, "pendingDrop").get(browser) == null
                            && field(AudioBrowserScreen.class, "pendingLoad").get(browser) == null) {
                        audioDropOperation = null; frames = Math.max(frames, 8);
                    }
                }
            } catch (Exception failure) { throw new IllegalStateException("Audio screen drag/drop failed", failure); }
        };
    }
    private void verifyAudioDropScreen(Minecraft game, String name) throws Exception {
        if (game.screen instanceof PlayerScreen screen) {
            boolean local = name.contains("selected"); String key = local ? "modernmusic.audio.tab" : "modernmusic.song.tab";
            var tab = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().equals(key))
                    .findFirst().orElseThrow();
            Class<?> button = Class.forName("dev.modernmusic.client.ui.ThemedButton");
            if (!field(button, "selected").getBoolean(tab) || tab.active == local || field(button, "selectedOnly").getBoolean(tab) != local)
                throw new AssertionError("Audio label lost its normal selected appearance or became interactive");
            if (local) {
                screen.mouseClicked(screen.toGuiCoordinate(tab.getX() + 10), screen.toGuiCoordinate(tab.getY() + 10), 0);
                if (field(PlayerScreen.class, "playlistInput").getBoolean(screen)) throw new AssertionError("Inactive audio label changed input mode");
            }
            return;
        }
        if (!(game.screen instanceof AudioBrowserScreen screen)) throw new AssertionError("Audio drop screenshot not in browser");
        var notice = (net.minecraft.network.chat.Component) field(AudioBrowserScreen.class, "notice").get(screen);
        if (notice == null) throw new AssertionError("Drop summary missing");
        @SuppressWarnings("unchecked") var tracks = (List<Track>) field(AudioBrowserScreen.class, "tracks").get(screen);
        var titles = tracks.stream().map(Track::title).toList();
        if (name.equals("audio-drop-local-mixed.png")) {
            for (String title : List.of("拖入的雨声.wav", "拖入的旋律.mp3", "同名保留.wav", "同名保留 (1).wav"))
                if (!titles.contains(title)) throw new AssertionError("Dropped local file absent: " + title);
            if (!Arrays.equals(audioExistingBytes, Files.readAllBytes(audioProvider.directory().resolve("同名保留.wav"))))
                throw new AssertionError("Drop replaced an existing local file");
            if (!Arrays.equals(silentWav(), Files.readAllBytes(audioProvider.directory().resolve("同名保留 (1).wav"))))
                throw new AssertionError("Conflicting dropped file was not copied separately");
            if (Files.exists(audioProvider.directory().resolve("不支持的文件.txt"))) throw new AssertionError("Invalid drop was copied");
            if (!notice.getString().contains("3") || !notice.getString().contains("1")) throw new AssertionError("Mixed drop result lacks success/failure counts");
        } else if (name.equals("audio-drop-server-direct.png")) {
            if (!field(AudioBrowserScreen.class, "server").getBoolean(screen)) throw new AssertionError("Room drop did not target server");
            for (String title : List.of("直接上传.wav", "直接上传.mp3")) {
                if (!titles.contains(title) || !audioUploadedFiles.containsKey(title)) throw new AssertionError("Direct upload missing: " + title);
                if (Files.exists(audioProvider.directory().resolve(title))) throw new AssertionError("Server drop copied into local library");
            }
            if (!Arrays.equals(silentWav(), audioUploadedFiles.get("直接上传.wav")) || !Arrays.equals(silentMp3(), audioUploadedFiles.get("直接上传.mp3")))
                throw new AssertionError("Direct upload bytes changed");
            if (!notice.getString().contains("2") || !notice.getString().contains("1")) throw new AssertionError("Mixed server drop result lacks counts");
        } else if (name.equals("audio-drop-server-nonadmin.png")) {
            if (audioUploadedFiles.containsKey("禁止上传.wav") || Files.exists(audioProvider.directory().resolve("禁止上传.wav")))
                throw new AssertionError("Non-admin drop uploaded or copied a file");
            if (!notice.getString().contains("管理员")) throw new AssertionError("Non-admin drop permission feedback missing");
        } else if (name.equals("audio-drop-player-local.png")) {
            if (field(AudioBrowserScreen.class, "server").getBoolean(screen) || !titles.contains("播放器拖入.wav"))
                throw new AssertionError("Player drop did not import into local browser");
            if (!Files.exists(audioProvider.directory().resolve("播放器拖入.wav"))) throw new AssertionError("Player drop did not copy local file");
        }
        ModernMusic.LOGGER.info("PASS: drag/drop {}: {}", name, notice.getString());
    }
    private void planAudioShots(Minecraft game) {
        try { installAudioFixture(game); }
        catch (Exception failure) { throw new IllegalStateException("Audio gallery setup", failure); }
        for (int[] size : new int[][]{{1280, 800}, {420, 360}, {320, 240}}) {
            String label = size[0] == 1280 ? "wide" : size[0] == 420 ? "narrow" : "minimum";
            add("audio-player-" + label, size[0], size[1], 2, () -> game.setScreen(new PlayerScreen(null, player, null, hud)));
            add("audio-local-" + label, size[0], size[1], 2, () -> audioBrowser(game, "local"));
            add("audio-server-" + label, size[0], size[1], 2, () -> audioBrowser(game, "server"));
        }
        add("audio-local-destinations", 680, 480, 2, () -> audioBrowser(game, "destinations"));
        add("audio-server-destinations", 680, 480, 2, () -> audioBrowser(game, "server-destinations"));
        add("audio-local-filter-empty", 680, 480, 2, () -> audioBrowser(game, "empty"));
        add("audio-local-uploading", 680, 480, 2, () -> audioBrowser(game, "uploading"));
        add("audio-local-uploaded", 680, 480, 2, () -> audioBrowser(game, "uploaded"));
        add("audio-local-upload-error", 680, 480, 2, () -> audioBrowser(game, "upload-error"));
        add("audio-local-nonadmin", 680, 480, 2, () -> audioBrowser(game, "nonadmin"));
        add("audio-server-offline", 680, 480, 2, () -> audioBrowser(game, "offline"));
        add("audio-room-browse", 680, 480, 2, () -> {
            audioOnline = true; audioAdmin = true;
            roomFixture(game, "owner");
            try {
                if (((net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, "inputType").get(game.screen)).active)
                    throw new AssertionError("Local room playlist toggle enabled");
                clickKey(game, "modernmusic.audio.browse");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private void audioBrowser(Minecraft game, String state) {
        try {
            if (audioUpload != null && !audioUpload.isDone()) audioUpload.completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
            audioAdmin = !state.equals("nonadmin"); audioOnline = !state.equals("offline");
            audioUploadPending = state.equals("uploading"); audioUploadFailure = state.equals("upload-error");
            roomFixture(game, "owner", false);
            boolean server = state.startsWith("server") || state.equals("offline");
            var screen = new AudioBrowserScreen(null, player, state.equals("empty") ? "unmatched-file" : "", server);
            game.setScreen(screen);
            var pending = (CompletableFuture<?>) field(AudioBrowserScreen.class, "pendingLoad").get(screen);
            if (pending != null) { try { pending.join(); } catch (CompletionException expected) { } }
            screen.tick();
            if (state.endsWith("destinations")) { clickKey(game, "modernmusic.search.select_page"); clickField(game, "destinationButton"); }
            else if (!state.equals("empty") && !state.equals("offline")) {
                var rows = (List<?>) field(AudioBrowserScreen.class, "rows").get(screen);
                clickWidget(game, (net.minecraft.client.gui.components.AbstractWidget) rows.getFirst());
                if (state.startsWith("upload")) clickKey(game, "modernmusic.audio.upload");
            }
        } catch (Exception failure) { throw new IllegalStateException("Audio browser " + state, failure); }
    }
    private void verifyAudioScreen(Minecraft game, String name) throws Exception {
        if (game.screen instanceof PlayerScreen screen) {
            var audioTab = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                            && text.getKey().equals("modernmusic.audio.tab")).findFirst().orElseThrow();
            if (audioTab.active || field(PlayerScreen.class, "playlistInput").getBoolean(screen)) throw new AssertionError("Local playlist mode enabled");
        }
        if (!(game.screen instanceof AudioBrowserScreen screen)) return;
        if (name.contains("destinations")) {
            var group = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                            && text.getKey().equals("modernmusic.search.room_target")).findFirst().orElseThrow();
            if (group.active != name.contains("server")) throw new AssertionError("Audio destination permission incorrect");
            return;
        }
        if (name.equals("audio-room-browse.png") && (!field(AudioBrowserScreen.class, "server").getBoolean(screen)
                || !((PlaybackController.SearchDestination) field(AudioBrowserScreen.class, "destination").get(screen)).shared()))
            throw new AssertionError("Room Browse did not default to server files and room queue");
        if (name.contains("nonadmin") && ((net.minecraft.client.gui.components.AbstractWidget) field(AudioBrowserScreen.class, "upload").get(screen)).active)
            throw new AssertionError("Non-admin upload enabled");
        if (name.contains("uploading") && field(AudioBrowserScreen.class, "pendingUpload").get(screen) == null)
            throw new AssertionError("Upload progress state missing");
        if (name.contains("uploaded") || name.contains("upload-error")) {
            var notice = (net.minecraft.network.chat.Component) field(AudioBrowserScreen.class, "notice").get(screen);
            String expected = name.contains("uploaded") ? "modernmusic.audio.uploaded" : "modernmusic.audio.upload_permission";
            if (notice == null || !(notice.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text) || !text.getKey().equals(expected))
                throw new AssertionError("Upload result missing: " + expected);
        }
        int resultsEnd = field(AudioBrowserScreen.class, "top").getInt(screen) + field(AudioBrowserScreen.class, "resultsY").getInt(screen)
                + field(AudioBrowserScreen.class, "pageSize").getInt(screen) * field(AudioBrowserScreen.class, "rowHeight").getInt(screen) + 2;
        if (resultsEnd > ((net.minecraft.client.gui.components.AbstractWidget) field(AudioBrowserScreen.class, "previous").get(screen)).getY())
            throw new AssertionError("Audio rows overlap pager");
    }
    private static byte[] silentWav() {
        var bytes = java.nio.ByteBuffer.allocate(16044).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16036)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16000);
        return bytes.array();
    }
    private AccountController cookieImportAccount;
    private void cookieImport(Minecraft game, MusicPlatform platform, boolean malformed) {
        if (cookieImportAccount != null) cookieImportAccount.close();
        AccountProvider provider = new AccountProvider() {
            public MusicPlatform platform() { return platform; }
            public AccountSession validate(SessionCookie cookie) { return new AccountSession("123", "导入测试账号", cookie, Instant.now()); }
            public QrChallenge beginQr() { throw new UnsupportedOperationException(); }
            public QrPoll pollQr(QrChallenge challenge) { throw new UnsupportedOperationException(); }
        };
        cookieImportAccount = new AccountController(provider, new RememberedSessionStore(output.resolve("cookie-json-fixture-" + platform.name())),
                game::execute, () -> {}, Clock.systemUTC());
        var screen = new AccountScreen(null, cookieImportAccount); game.setScreen(screen);
        var input = screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.EditBox)
                .map(child -> (net.minecraft.client.gui.components.EditBox) child).findFirst().orElseThrow();
        String json = """
                {"cookies":[
                  {"name":"MUSIC_U","value":"netease-fixture","domain":".music.163.com"},
                  {"name":"uin","value":"123","domain":".y.qq.com"},
                  {"name":"qqmusic_key","value":"qq-fixture","domain":".y.qq.com"},
                  {"name":"userid","value":"456","domain":".kugou.com"},
                  {"name":"token","value":"kugou-fixture","domain":".kugou.com"},
                  {"name":"token","value":"unrelated-secret","domain":".example.com"}
                ],"metadata":"%s"}
                """.formatted("无关".repeat(10_000));
        // Vanilla's paste handler calls insertText with the complete clipboard contents.
        input.insertText(json);
        String expected = SessionCookie.parse(json, platform).header();
        if (!input.getValue().equals(expected) || input.getValue().length() > SessionCookie.MAX_LENGTH)
            throw new AssertionError("JSON paste was altered or truncated by the widget");
        // Pasting another export replaces the previous import even when text is not selected.
        input.insertText(json);
        if (!input.getValue().equals(expected)) throw new AssertionError("Repeated export paste appended credentials");
        if (malformed) {
            input.insertText("{\"MUSIC_U\":\"fixture\",}");
            try {
                if (!input.getValue().isEmpty() || !"modernmusic.auth.invalid_cookie".equals(field(AccountScreen.class, "localError").get(screen)))
                    throw new AssertionError("Malformed paste retained old credentials or lost error feedback");
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
    private AccountController qqAccount;
    private void qqPlayer(Minecraft game, boolean shared) {
        SharedProtocolSmoke.verify();
        if (qqAccount == null) {
            AccountProvider auth = new AccountProvider() {
                public MusicPlatform platform() { return MusicPlatform.QQ; }
                public AccountSession validate(SessionCookie cookie) { return new AccountSession("123", "QQ音乐测试账号", cookie, Instant.now()); }
                public List<QrMethod> qrMethods() { return List.of(QrMethod.QQ, QrMethod.WECHAT); }
                public QrChallenge beginQr() { return beginQr(QrMethod.QQ); }
                public QrChallenge beginQr(QrMethod method) { return new QrChallenge("gallery-fixture-key", Instant.now().plusSeconds(120),
                        "https://example.test/qq-music-gallery/" + method.name().toLowerCase(Locale.ROOT), method); }
                public QrPoll pollQr(QrChallenge challenge) { return new QrPoll(QrState.WAITING, null); }
            };
            qqAccount = new AccountController(auth, new RememberedSessionStore(output.resolve("qq-fixture")), game::execute, () -> {}, Clock.systemUTC());
        }
        var source = new SharedPlaybackSource(fixture, request -> {}, () -> {}, Clock.systemUTC(), MusicPlatform.QQ);
        source.state(1, null); source.profile(1, new dev.modernmusic.shared.SharedAccountProfile("服务器QQ音乐账号", "")); source.select(shared);
        var multi = new MultiPlatformProvider(fixture, source); setProvider(multi);
        try { field(SongSearchController.class, "provider").set(player.search(), multi); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        var queue = player.queue(); var entries = List.copyOf(queue.entries()); int index = queue.index();
        game.setScreen(new PlayerScreen(null, player, null, hud, platform -> platform == MusicPlatform.QQ ? qqAccount : null));
        var button = game.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button) child)
                .filter(child -> child.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("modernmusic.platform.netease")).findFirst().orElseThrow();
        button.onPress();
        if (player.platform() != MusicPlatform.QQ || player.queue() != queue || !entries.equals(queue.entries()) || index != queue.index())
            throw new AssertionError("Platform switch changed queue");
    }
    private AccountController kugouAccount;
    private void kugouPlayer(Minecraft game, boolean shared) {
        SharedProtocolSmoke.verify();
        if (kugouAccount == null) {
            AccountProvider auth = new AccountProvider() {
                public MusicPlatform platform() { return MusicPlatform.KUGOU; }
                public AccountSession validate(SessionCookie cookie) { return new AccountSession("123", "酷狗音乐测试账号", cookie, Instant.now()); }
                public QrChallenge beginQr() { return new QrChallenge("gallery-fixture-key", Instant.now().plusSeconds(120),
                        "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=gallery-fixture-key", QrMethod.APP); }
                public QrPoll pollQr(QrChallenge challenge) { return new QrPoll(QrState.WAITING, null); }
            };
            kugouAccount = new AccountController(auth, new RememberedSessionStore(output.resolve("kugou-fixture")), game::execute, () -> {}, Clock.systemUTC());
        }
        var source = new SharedPlaybackSource(fixture, request -> {}, () -> {}, Clock.systemUTC(), MusicPlatform.KUGOU);
        source.state(1, null); source.profile(1, new dev.modernmusic.shared.SharedAccountProfile("服务器酷狗音乐账号", "")); source.select(shared);
        var multi = new MultiPlatformProvider(fixture, fixture, source); setProvider(multi);
        try { field(SongSearchController.class, "provider").set(player.search(), multi); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        var queue = player.queue(); var entries = List.copyOf(queue.entries()); int index = queue.index();
        game.setScreen(new PlayerScreen(null, player, null, hud, platform -> platform == MusicPlatform.KUGOU ? kugouAccount : null));
        var button = game.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button) child)
                .filter(child -> child.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("modernmusic.platform.netease")).findFirst().orElseThrow();
        button.onPress();
        var secondButton = game.screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button) child)
                .filter(child -> child.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("modernmusic.platform.qq")).findFirst().orElseThrow();
        secondButton.onPress();
        if (player.platform() != MusicPlatform.KUGOU || player.queue() != queue || !entries.equals(queue.entries()) || index != queue.index())
            throw new AssertionError("Platform switch changed queue");
    }
    private AccountProvider.QrMethod expectedQr;
    private void platformQr(Minecraft game, MusicPlatform platform, AccountProvider.QrMethod method) {
        var previous = game.screen;
        AccountController previousAccount = null;
        if (previous instanceof AccountScreen) try { previousAccount = (AccountController) field(AccountScreen.class, "account").get(previous); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        if (platform == MusicPlatform.QQ) qqPlayer(game, false); else kugouPlayer(game, false);
        if (previousAccount != null && previousAccount.qrActive()) throw new AssertionError("Closing account screen did not cancel QR");
        var account = platform == MusicPlatform.QQ ? qqAccount : kugouAccount;
        var screen = new AccountScreen(null, account); game.setScreen(screen); expectedQr = method;
        String key = switch (method) {
            case APP -> "modernmusic.auth.qr_login";
            case QQ -> "modernmusic.qq.auth.qr_qq";
            case WECHAT -> "modernmusic.qq.auth.qr_wechat";
        };
        var button = screen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button)
                .map(child -> (net.minecraft.client.gui.components.Button) child)
                .filter(child -> child.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals(key)).findFirst().orElseThrow();
        if (!screen.mouseClicked(screen.toGuiCoordinate(button.getX() + button.getWidth() / 2.0),
                screen.toGuiCoordinate(button.getY() + button.getHeight() / 2.0), 0) || !account.qrActive())
            throw new AssertionError("QR button was not clickable");
    }
    /** Switches the theme and re-seeds the fixture artwork, whose corner mask depends on the theme. */
    private void theme(Minecraft game, HudSettings.Theme theme) {
        PlayerTheme.apply(theme);
        try { ArtworkCache.clearIfCreated(); seedArtwork(game); } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private void planHudShots(Minecraft game) {
        add("hud-all-interactions", 1280, 800, 2, () -> HudEditorScenario.verify(game, player, output.resolve("hud-edit-fixture.json")));
        for (int[] size : new int[][]{{1280, 800}, {680, 480}, {640, 400}}) {
            add("hud-all-" + size[0], size[0], size[1], 2, () -> {
                hud.reset(); game.setScreen(new HudEditorScreen(null, player, hud));
                HudEditorScenario.expand(game); HudEditorScenario.press(game, "modernmusic.hud.all");
                HudEditorScenario.checkWidgets(game);
            });
            add("hud-lyrics-" + size[0], size[0], size[1], 2, () -> {
                hud.reset(); game.setScreen(new HudEditorScreen(null, player, hud));
                HudEditorScenario.expand(game); HudEditorScenario.press(game, "modernmusic.hud.part.lyrics");
                HudEditorScenario.checkWidgets(game);
            });
        }
        add("hud-all-pixel", 1280, 800, 2, () -> {
            theme(game, HudSettings.Theme.OREUI); hud.reset(); game.setScreen(new HudEditorScreen(null, player, hud));
            HudEditorScenario.press(game, "modernmusic.hud.all"); HudEditorScenario.checkWidgets(game);
        });
    }
    private void add(String name, int width, int height, int scale, Runnable setup) { shots.add(new Shot(name + ".png", width, height, scale, setup, -100, -100)); }

    /** Focused theme regression for fractional-scale context-menu sprite seams. */
    private void planAe2MenuShots(Minecraft game) {
        dev.modernmusic.network.RoomNetwork.clientUpdate = update -> {};
        String[] states = {"focus", "requester", "dj", "listener"};
        for (int[] size : new int[][]{{2560, 1334, 3}, {1280, 800, 2}, {680, 480, 2}}) {
            for (int pointer = -1; pointer < 3; pointer++) {
                final int selectedPointer = pointer;
                add("room-owner-menu-ae2-" + size[0] + "x" + size[1] + "-" + states[pointer + 1], size[0], size[1], size[2], () -> {
                    theme(game, HudSettings.Theme.AE2); roomFixture(game, "team"); roomMenu(game, true); menuHover = selectedPointer;
                });
            }
            for (int focused = 0; focused < 3; focused++) {
                final int keyboardIndex = focused;
                add("room-owner-menu-ae2-" + size[0] + "x" + size[1] + "-keyboard-" + states[focused + 1], size[0], size[1], size[2], () -> {
                    theme(game, HudSettings.Theme.AE2); roomFixture(game, "team"); roomMenu(game, true);
                    menuHover = 2; menuKeyboardSteps = keyboardIndex + 1;
                });
            }
            add("room-owner-menu-ae2-" + size[0] + "x" + size[1] + "-mouse-after-keyboard", size[0], size[1], size[2], () -> {
                theme(game, HudSettings.Theme.AE2); roomFixture(game, "team"); roomMenu(game, true);
                menuHover = 2; menuKeyboardSteps = 1; menuLateHover = 1;
            });
        }
        for (int offset : new int[]{1, 2, 3, 4, 7, 11, 17, 23})
            add("room-owner-menu-ae2-2560x1334-keyboard-requester-offset" + offset, 2560, 1334, 3, () -> {
                menuOriginOffset = offset; theme(game, HudSettings.Theme.AE2); roomFixture(game, "team"); roomMenu(game, true);
                menuHover = 2; menuKeyboardSteps = 1;
            });
    }

    private void moveMenuPointer(Minecraft game, int index) throws Exception {
        var screen = (MusicScreen) game.screen;
        var rows = (List<?>) field(RoomScreen.class, "menuButtons").get(screen);
        var row = (net.minecraft.client.gui.components.AbstractWidget) rows.get(index);
        field(MouseHandler.class, "xpos").set(game.mouseHandler, screen.toGuiCoordinate(row.getX() + row.getWidth() / 2.0) * game.getWindow().getGuiScale());
        field(MouseHandler.class, "ypos").set(game.mouseHandler, screen.toGuiCoordinate(row.getY() + row.getHeight() / 2.0) * game.getWindow().getGuiScale());
    }

    private void verifyAe2Menu(Minecraft game, String name) throws Exception {
        var rows = (List<?>) field(RoomScreen.class, "menuButtons").get(game.screen);
        int highlighted = 0, currentRoles = 0;
        for (Object row : rows) {
            if (((net.minecraft.client.gui.components.AbstractWidget) row).isHoveredOrFocused()) highlighted++;
            if (field(Class.forName("dev.modernmusic.client.ui.ThemedButton"), "selected").getBoolean(row))
                throw new IllegalStateException("Current role still uses a selection fill in " + name);
            if (field(row.getClass(), "currentRole").getBoolean(row)) currentRoles++;
        }
        int expected = name.endsWith("-focus.png") ? 0 : 1;
        if (highlighted != expected || currentRoles != 1)
            throw new IllegalStateException("Menu focus/hover or role marker disagrees in " + name + ": highlighted=" + highlighted + ", role=" + currentRoles);
        if (field(RoomScreen.class, "menuKeyboard").getBoolean(game.screen) != name.contains("-keyboard-"))
            throw new IllegalStateException("Menu did not switch input mode correctly in " + name);
        if (name.contains("-keyboard-requester")) {
            var screen = (MusicScreen) game.screen;
            var requester = (net.minecraft.client.gui.components.AbstractWidget) rows.getFirst();
            double scale = screen.toGuiCoordinate(1) * game.getWindow().getGuiScale();
            int y = (int) Math.floor((requester.getY() + requester.getHeight() - 5) * scale);
            int left = (int) Math.ceil((requester.getX() + 4) * scale), right = (int) Math.floor((requester.getRight() - 4) * scale);
            try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
                int color = frame.getPixelRGBA(left, y);
                for (int x = left + 1; x < right; x++) if (frame.getPixelRGBA(x, y) != color)
                    throw new IllegalStateException("Focus sprite has an interior seam at physical x=" + x + " in " + name);
            }
        }
    }

    /** Saved-room management, import and navigation at real scaled input coordinates. */
    private void planRoomManagementShots(Minecraft game) {
        dev.modernmusic.network.RoomNetwork.clientUpdate = update -> {};
        for (int[] size : new int[][]{{1280, 800}, {680, 480}, {320, 240}}) {
            String suffix = size[0] + "x" + size[1];
            for (String state : List.of("lobby", "empty", "limit", "owner"))
                add("room-v2-" + state + "-" + suffix, size[0], size[1], 2, () -> roomManagementFixture(game, state));
            add("room-v2-dissolve-" + suffix, size[0], size[1], 2, () -> roomDissolve(game, false));
        }
        for (var platform : MusicPlatform.onlineValues()) {
            String suffix = platform.name().toLowerCase(Locale.ROOT);
            for (int[] size : new int[][]{{1280, 800}, {680, 480}, {320, 240}}) for (boolean playlist : List.of(false, true))
                add("room-v2-import-" + (playlist ? "playlist-" : "song-") + suffix + "-" + size[0], size[0], size[1], 2,
                        () -> roomInlineInput(game, platform, playlist));
        }
        for (int[] size : new int[][]{{1280, 800}, {680, 480}, {320, 240}})
            add("room-v2-search-" + size[0], size[0], size[1], 2, () -> roomInlineSearch(game));
        for (String change : List.of("queue", "permission", "room", "epoch", "leave"))
            add("room-v2-input-transition-" + change, 680, 480, 2, () -> roomInlineTransition(game, change));
        add("room-v2-saved-dissolve", 1280, 800, 2, () -> roomDissolve(game, true));
        add("room-v2-listener", 680, 480, 2, () -> roomManagementFixture(game, "listener"));
        add("room-v2-team", 680, 480, 2, () -> roomManagementFixture(game, "team"));
        for (var selectedTheme : List.of(HudSettings.Theme.AE2, HudSettings.Theme.CREATE, HudSettings.Theme.DAYLIGHT, HudSettings.Theme.OREUI))
            add("room-v2-theme-" + selectedTheme.name().toLowerCase(Locale.ROOT), 680, 480, 2, () -> {
                theme(game, selectedTheme); roomManagementFixture(game, "owner");
            });
        add("room-v2-back-personal", 1280, 800, 2, () -> roomNavigate(game, false));
        add("room-v2-leave", 680, 480, 2, () -> roomNavigate(game, true));
    }

    private void roomManagementFixture(Minecraft game, String state) {
        try {
            setProvider(new MultiPlatformProvider(fixture, fixture, fixture));
            boolean lobby = List.of("lobby", "empty", "limit").contains(state);
            roomFixture(game, lobby ? "unjoined" : state, false);
            var rooms = RoomClient.instance();
            var value = rooms.update();
            UUID local = game.player.getUUID(), remote = new UUID(7, 7), epoch = new UUID(151, 151);
            var saved = new ArrayList<RoomUpdate.SavedRoom>();
            var invitations = new ArrayList<RoomUpdate.Invitation>();
            if (!state.equals("empty")) {
                saved.add(new RoomUpdate.SavedRoom(new UUID(42, 42), epoch, "晚风与海 · 我的音乐空间", local, 3, 24));
                saved.add(new RoomUpdate.SavedRoom(new UUID(44, 44), epoch, "星河漫游 · 已加入", remote, 8, 36));
                invitations.add(new RoomUpdate.Invitation(new UUID(45, 45), epoch, "一起听歌 · 好友邀请"));
                invitations.add(new RoomUpdate.Invitation(new UUID(44, 44), epoch, "已加入群组的旧邀请"));
                if (state.equals("limit")) saved.add(new RoomUpdate.SavedRoom(new UUID(46, 46), epoch, "清晨电台 · 我的第二个群组", local, 1, 12));
            }
            field(RoomClient.class, "update").set(rooms, new RoomUpdate(value.sequence(), value.room(), invitations,
                    "", value.team(), saved, 2));
            game.setScreen(new RoomScreen(new PlayerScreen(null, player, null, hud), rooms));
            if (state.equals("limit"))
                ((net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "input").get(game.screen)).setValue("第三个群组");
            game.screen.tick();
        } catch (Exception failure) { throw new IllegalStateException("Cannot install saved-room fixture: " + state, failure); }
    }

    private void roomInlineInput(Minecraft game, MusicPlatform selected, boolean playlist) {
        try {
            roomManagementFixture(game, "owner");
            for (int i = 0; i < selected.ordinal(); i++) clickField(game, "platform");
            if (player.platform() != selected) throw new IllegalStateException("Room footer failed to switch provider");
            var screen = (RoomScreen) game.screen;
            ((net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(screen)).setValue("待切换平台的输入");
            clickField(game, "platform");
            if (!((net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(screen)).getValue().isEmpty())
                throw new IllegalStateException("Changing import provider retained a reference for the previous provider");
            clickField(game, "platform"); clickField(game, "platform");
            if (player.platform() != selected) throw new IllegalStateException("Importer provider cycle lost the selected provider");
            if (playlist) clickField(game, "inputType");
            if (field(RoomScreen.class, "playlistInput").getBoolean(screen) != playlist)
                throw new IllegalStateException("Inline song/playlist toggle failed");
            var input = (net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(screen);
            input.setValue(switch (selected) {
                case NETEASE -> playlist ? "https://music.163.com/#/playlist?id=123456" : "https://music.163.com/#/song?id=42";
                case QQ -> playlist ? "https://y.qq.com/n/ryqq/playlist/123456" : "004Z8Ihr0JIu5s";
                case KUGOU -> playlist ? "https://www.kugou.com/yy/special/single/123456.html" : "ABCDEF0123456789ABCDEF0123456789";
                case LOCAL -> throw new IllegalArgumentException("Local audio uses the audio gallery");
            });
            if (game.screen != screen) throw new IllegalStateException("Inline input unexpectedly opened another screen");
            screen.tick();
        } catch (Exception failure) { throw new IllegalStateException("Inline room reference input interaction", failure); }
    }

    private void roomInlineSearch(Minecraft game) {
        try {
            roomManagementFixture(game, "owner");
            var parent = (RoomScreen) game.screen;
            var room = RoomClient.instance().update().room();
            var input = (net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(parent);
            input.setValue("晚风"); parent.tick(); parent.setFocused(input);
            if (!parent.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0))
                throw new IllegalStateException("Enter did not submit the inline room query");
            verifyInlineSearch(game, room);
            clickKey(game, "gui.back");
            if (game.screen != parent || !((net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(parent)).getValue().equals("晚风"))
                throw new IllegalStateException("Returning from search lost the inline room draft");
            clickField(game, "search"); verifyInlineSearch(game, room);
        } catch (Exception failure) { throw new IllegalStateException("Inline room search interaction", failure); }
    }
    private static void verifyInlineSearch(Minecraft game, RoomSnapshot room) throws Exception {
        if (!(game.screen instanceof SongSearchScreen)) throw new IllegalStateException("Search did not open results");
        var input = (net.minecraft.client.gui.components.EditBox) field(SongSearchScreen.class, "input").get(game.screen);
        var destination = (PlaybackController.SearchDestination) field(SongSearchScreen.class, "destination").get(game.screen);
        if (!input.getValue().equals("晚风") || destination == null || !destination.shared()
                || !destination.id().equals(room.id().toString()) || !destination.roomEpoch().equals(room.epoch()))
            throw new IllegalStateException("Inline search lost its query or changed the target room");
    }

    private void roomInlineTransition(Minecraft game, String change) {
        try {
            roomInlineInput(game, MusicPlatform.NETEASE, true);
            var screen = (RoomScreen) game.screen;
            var rooms = RoomClient.instance(); var update = rooms.update(); var previous = update.room();
            var oldButton = (net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, "importButton").get(screen);
            double x = screen.toGuiCoordinate(oldButton.getX() + oldButton.getWidth() / 2.0);
            double y = screen.toGuiCoordinate(oldButton.getY() + oldButton.getHeight() / 2.0);
            var members = change.equals("permission") ? previous.members().stream().map(member -> member.id().equals(game.player.getUUID())
                    ? new RoomSnapshot.Member(member.id(), member.name(), false, false) : member).toList() : previous.members();
            RoomSnapshot next = change.equals("leave") ? null : new RoomSnapshot(
                    change.equals("room") ? new UUID(184, 184) : previous.id(),
                    change.equals("epoch") || change.equals("room") ? UUID.randomUUID() : previous.epoch(),
                    previous.name(), previous.revision() + 1, previous.queueRevision() + (change.equals("queue") ? 1 : 0),
                    previous.playbackRevision(), change.equals("permission") ? new UUID(7, 7) : previous.owner(), members,
                    change.equals("queue") ? previous.songs().reversed() : previous.songs(), previous.index(), previous.mode(),
                    previous.state(), previous.positionMs(), previous.anchorMs(), previous.durationMs(), previous.teamId());
            field(RoomClient.class, "update").set(rooms, new RoomUpdate(update.sequence() + 1, next, update.invitations(), "",
                    update.team(), update.savedRooms(), update.roomLimit()));
            player.applyRoom(next);
            String sentinel = "modernmusic.room.song_added";
            field(RoomClient.class, "message").set(rooms, sentinel);
            // No tick or reinitialization between the authoritative update and this click on the old button.
            screen.mouseClicked(x, y, 0);
            if (!field(RoomClient.class, "message").get(rooms).equals(sentinel) || player.importing())
                throw new IllegalStateException("A stale inline import was submitted after " + change);
            field(RoomClient.class, "message").set(rooms, "");
            var input = (net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(screen);
            if (change.equals("leave")) {
                if (input != null) throw new IllegalStateException("Leaving retained room-only import controls");
            } else {
                boolean differentTarget = change.equals("room") || change.equals("epoch");
                if (input.getValue().isEmpty() != differentTarget)
                    throw new IllegalStateException("Inline draft lifetime did not match the room/epoch after " + change);
                if (differentTarget && field(RoomScreen.class, "playlistInput").getBoolean(screen))
                    throw new IllegalStateException("A new target inherited the old playlist mode");
                var submit = (net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, "importButton").get(screen);
                if (change.equals("permission") && (submit.active || input.active))
                    throw new IllegalStateException("Revoking request permission retained active import controls");
            }
            ModernMusic.LOGGER.info("PASS: inline room input reconciled {} before the next tick", change);
        } catch (Exception failure) { throw new IllegalStateException("Inline room input transition: " + change, failure); }
    }

    private void roomDissolve(Minecraft game, boolean saved) {
        try {
            roomManagementFixture(game, saved ? "lobby" : "owner");
            var parent = game.screen;
            clickKey(game, "modernmusic.room.dissolve");
            if (!game.screen.getClass().getSimpleName().equals("DissolveRoomScreen"))
                throw new IllegalStateException("Dissolve did not request confirmation");
            String sentinel = "modernmusic.room.song_added";
            field(RoomClient.class, "message").set(RoomClient.instance(), sentinel);
            clickField(game, "cancel");
            if (game.screen != parent || !field(RoomClient.class, "message").get(RoomClient.instance()).equals(sentinel))
                throw new IllegalStateException("Cancelling dissolution changed the room or sent a command");
            field(RoomClient.class, "message").set(RoomClient.instance(), "");
            clickKey(game, "modernmusic.room.dissolve");
        } catch (Exception failure) { throw new IllegalStateException("Dissolution confirmation interaction", failure); }
    }

    private void roomNavigate(Minecraft game, boolean leave) {
        try {
            roomManagementFixture(game, "owner");
            var screen = game.screen;
            String sentinel = "modernmusic.room.song_added";
            field(RoomClient.class, "message").set(RoomClient.instance(), sentinel);
            clickKey(game, leave ? "modernmusic.room.leave" : "gui.back");
            boolean commandSent = !field(RoomClient.class, "message").get(RoomClient.instance()).equals(sentinel);
            if (commandSent != leave) throw new IllegalStateException("Back and leave did not have distinct network behavior");
            field(RoomClient.class, "message").set(RoomClient.instance(), "");
            if (!leave && (!(game.screen instanceof PlayerScreen) || RoomClient.instance().update().room() == null))
                throw new IllegalStateException("Back did not preserve room playback while returning to the personal UI");
            if (leave) {
                var previous = RoomClient.instance().update();
                roomFixture(game, "unjoined", false);
                field(RoomClient.class, "update").set(RoomClient.instance(), new RoomUpdate(previous.sequence() + 1, null,
                        previous.invitations(), "", previous.team(), previous.savedRooms(), previous.roomLimit()));
                if (game.screen != screen) throw new IllegalStateException("Leave unexpectedly closed the room UI");
                game.screen.tick();
                if (((List<?>) field(RoomScreen.class, "lobbyEntries").get(game.screen)).size() != 3)
                    throw new IllegalStateException("Leaving hid the saved room from the lobby");
            }
        } catch (Exception failure) { throw new IllegalStateException("Room back/leave navigation", failure); }
    }

    private static void clickField(Minecraft game, String name) throws Exception {
        clickWidget(game, (net.minecraft.client.gui.components.AbstractWidget) field(game.screen.getClass(), name).get(game.screen));
    }
    private static void clickKey(Minecraft game, String key) throws Exception {
        var button = game.screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals(key)).findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key));
        clickWidget(game, button);
    }
    private static void clickWidget(Minecraft game, net.minecraft.client.gui.components.AbstractWidget button) {
        if (button == null || !button.active || !button.visible) throw new IllegalStateException("Target control is unavailable");
        var screen = (MusicScreen) game.screen;
        if (!screen.mouseClicked(screen.toGuiCoordinate(button.getX() + button.getWidth() / 2.0),
                screen.toGuiCoordinate(button.getY() + button.getHeight() / 2.0), 0))
            throw new IllegalStateException("Scaled control click missed: " + button.getMessage().getString());
    }

    private void verifyRoomManagementScreen(Minecraft game, String name) throws Exception {
        if (game.screen instanceof RoomScreen screen) {
            var reference = new PlayerScreen(null, player, null, hud);
            reference.init(game, game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight());
            for (String dimension : List.of("left", "top", "panelWidth", "panelHeight"))
                if (field(RoomScreen.class, dimension).getInt(screen) != field(PlayerScreen.class, dimension).getInt(reference))
                    throw new IllegalStateException("Saved-room and player frames differ: " + dimension);
            if (name.contains("-limit-") && ((net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, "submit").get(screen)).active)
                throw new IllegalStateException("Create remained enabled at the owned-room limit");
            if (name.contains("-lobby-") && ((List<?>) field(RoomScreen.class, "lobbyEntries").get(screen)).size() != 3)
                throw new IllegalStateException("Owned, joined and invited rooms did not appear once each");
            if (name.contains("-lobby-")) {
                var entries = (List<?>) field(RoomScreen.class, "lobbyEntries").get(screen);
                var roles = new HashSet<String>();
                for (var entry : entries) roles.add((String) field(entry.getClass(), "roleKey").get(entry));
                if (!roles.equals(Set.of("modernmusic.room.saved_owned", "modernmusic.room.saved_joined", "modernmusic.room.saved_invitation")))
                    throw new IllegalStateException("Saved-room ownership labels were incorrect");
            }
            if ((name.endsWith("-listener.png") || name.endsWith("-team.png")) && field(RoomScreen.class, "dissolve").get(screen) != null)
                throw new IllegalStateException("A listener or FTB room exposed room dissolution");
            if (RoomClient.instance().update().room() != null) {
                var input = (net.minecraft.client.gui.components.EditBox) field(RoomScreen.class, "musicInput").get(screen);
                var bar = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
                for (String control : List.of("inputType", "musicInput", "importButton", "search"))
                    bar.add((net.minecraft.client.gui.components.AbstractWidget) field(RoomScreen.class, control).get(screen));
                for (int i = 0; i < bar.size(); i++) {
                    if (bar.get(i).getBottom() > field(RoomScreen.class, "bodyY").getInt(screen))
                        throw new IllegalStateException("Inline input overlaps the member/queue panels in " + name);
                    for (int j = i + 1; j < bar.size(); j++) {
                        var a = bar.get(i); var b = bar.get(j);
                        if (a.getX() < b.getRight() && a.getRight() > b.getX() && a.getY() < b.getBottom() && a.getBottom() > b.getY())
                            throw new IllegalStateException("Inline input controls overlap in " + name);
                    }
                }
                if (name.startsWith("room-v2-import-")) {
                    if (input.getValue().isEmpty() || !bar.get(2).active)
                        throw new IllegalStateException("Inline importer lost the URL/ID draft or request permission");
                    if (field(RoomScreen.class, "playlistInput").getBoolean(screen) != name.contains("-playlist-"))
                        throw new IllegalStateException("Inline input mode changed before rendering");
                }
            }
        } else if (game.screen instanceof SongSearchScreen) {
            verifyInlineSearch(game, RoomClient.instance().update().room());
        }
        if (player.library().active().queue().entries().size() != 12
                || player.library().active().queue().entries().getFirst().value() != 42)
            throw new IllegalStateException("Room management changed the personal queue");
    }

    /** Targeted room fixtures: run with MODERN_MUSIC_GALLERY_ONLY=room (no live provider/account data). */
    private final UUID avatarRemote = new UUID(7, 7), avatarFailed = new UUID(8, 8), avatarOffline = new UUID(9, 9);
    private final Map<UUID, net.minecraft.client.resources.PlayerSkin> avatarSkins = new HashMap<>();
    private boolean avatarTransitioned;

    /** Production PlayerInfo lookup and vanilla face/hat drawing, backed exclusively by local textures. */
    private void planRoomAvatarShots(Minecraft game) {
        dev.modernmusic.network.RoomNetwork.clientUpdate = value -> {};
        try {
            avatarSkins.put(game.player.getUUID(), avatarSkin(game, "owner", false));
            avatarSkins.put(avatarRemote, avatarSkin(game, "guest", true));
        } catch (Exception failure) { throw new IllegalStateException("Room avatar texture setup", failure); }
        for (var style : new HudSettings.Theme[] {HudSettings.Theme.ADAPTIVE, HudSettings.Theme.CREATE}) {
            String theme = style == HudSettings.Theme.ADAPTIVE ? "adaptive" : "create";
            add("room-avatars-" + theme + "-wide", 1280, 800, 2, () -> roomAvatarFixture(game, style));
            add("room-avatars-" + theme + "-narrow", 420, 360, 2, () -> roomAvatarFixture(game, style));
        }
        add("room-avatars-adaptive-late-info", 1280, 800, 2, () -> {
            roomAvatarFixture(game, HudSettings.Theme.ADAPTIVE);
            try {
                avatarInfoMap(game).remove(avatarRemote);
                assertAvatarLookup(game, avatarRemote, net.minecraft.client.resources.DefaultPlayerSkin.get(avatarRemote));
                avatarTransitioned = false;
                shotTick = () -> {
                    if (avatarTransitioned || frames > 6) return;
                    try {
                        installAvatarInfo(game, avatarRemote, "GalleryGuest", avatarSkins.get(avatarRemote));
                        assertAvatarLookup(game, avatarRemote, avatarSkins.get(avatarRemote)); avatarTransitioned = true;
                    } catch (Exception failure) { throw new IllegalStateException("Late PlayerInfo was not reflected in avatar", failure); }
                };
            } catch (Exception failure) { throw new IllegalStateException("Late avatar fixture", failure); }
        });
        add("room-avatars-create-offline", 1280, 800, 2, () -> {
            roomAvatarFixture(game, HudSettings.Theme.CREATE); avatarTransitioned = false;
            shotTick = () -> {
                if (avatarTransitioned || frames > 6) return;
                try {
                    assertAvatarLookup(game, avatarRemote, avatarSkins.get(avatarRemote));
                    avatarInfoMap(game).remove(avatarRemote);
                    assertAvatarLookup(game, avatarRemote, net.minecraft.client.resources.DefaultPlayerSkin.get(avatarRemote)); avatarTransitioned = true;
                } catch (Exception failure) { throw new IllegalStateException("Offline avatar did not fall back", failure); }
            };
        });
        add("room-avatars-adaptive-menu", 680, 480, 2, () -> { roomAvatarFixture(game, HudSettings.Theme.ADAPTIVE); roomMenu(game, true); });
        add("room-avatars-create-menu-narrow", 420, 360, 2, () -> { roomAvatarFixture(game, HudSettings.Theme.CREATE); roomMenu(game, true); });
    }
    private net.minecraft.client.resources.PlayerSkin avatarSkin(Minecraft game, String name, boolean guest) {
        var pixels = new com.mojang.blaze3d.platform.NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) pixels.setPixelRGBA(x, y, 0);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int face = y < 2 ? 0xFF39291F : guest ? 0xFFDEB58F : 0xFFF2C6A0;
            if (y == 3 && (x == 2 || x == 5)) face = 0xFF243846;
            if (y == 6 && (x == 3 || x == 4)) face = 0xFF874D43;
            pixels.setPixelRGBA(8 + x, 8 + y, abgr(face));
            // Transparent center leaves the face visible; a saturated hat brim proves the overlay is drawn.
            int hat = y == 0 || y == 1 && x >= 1 && x <= 6 ? guest ? 0xFF25CAD0 : 0xFFF0A42C : 0;
            if (guest && x == 7 && y > 1 && y < 6) hat = 0xFF25CAD0;
            pixels.setPixelRGBA(40 + x, 8 + y, abgr(hat));
        }
        var texture = game.getTextureManager().register("room-avatar-" + name, new net.minecraft.client.renderer.texture.DynamicTexture(pixels));
        return new net.minecraft.client.resources.PlayerSkin(texture, null, null, null,
                net.minecraft.client.resources.PlayerSkin.Model.WIDE, true);
    }
    private static int abgr(int argb) { return argb & 0xFF00FF00 | (argb & 255) << 16 | argb >>> 16 & 255; }
    @SuppressWarnings("unchecked") private Map<UUID, net.minecraft.client.multiplayer.PlayerInfo> avatarInfoMap(Minecraft game) throws Exception {
        return (Map<UUID, net.minecraft.client.multiplayer.PlayerInfo>) field(net.minecraft.client.multiplayer.ClientPacketListener.class, "playerInfoMap").get(game.getConnection());
    }
    private void installAvatarInfo(Minecraft game, UUID id, String name, net.minecraft.client.resources.PlayerSkin skin) throws Exception {
        var info = new net.minecraft.client.multiplayer.PlayerInfo(new com.mojang.authlib.GameProfile(id, name), false);
        field(net.minecraft.client.multiplayer.PlayerInfo.class, "skinLookup").set(info, (java.util.function.Supplier<net.minecraft.client.resources.PlayerSkin>) () -> skin);
        avatarInfoMap(game).put(id, info);
    }
    private void roomAvatarFixture(Minecraft game, HudSettings.Theme style) {
        try {
            theme(game, style);
            installAvatarInfo(game, game.player.getUUID(), game.player.getGameProfile().getName(), avatarSkins.get(game.player.getUUID()));
            installAvatarInfo(game, avatarRemote, "GalleryGuest", avatarSkins.get(avatarRemote));
            var failed = new net.minecraft.client.multiplayer.PlayerInfo(new com.mojang.authlib.GameProfile(avatarFailed, "GalleryFailed"), false);
            field(net.minecraft.client.multiplayer.PlayerInfo.class, "skinLookup").set(failed,
                    (java.util.function.Supplier<net.minecraft.client.resources.PlayerSkin>) () -> {
                        throw new CompletionException(new java.io.IOException("Synthetic skin lookup failure"));
                    });
            avatarInfoMap(game).put(avatarFailed, failed);
            avatarInfoMap(game).remove(avatarOffline);
            roomFixture(game, "owner");
        } catch (Exception failure) { throw new IllegalStateException("Room avatar fixture", failure); }
    }
    private net.minecraft.client.gui.components.AbstractWidget avatarRow(Minecraft game, UUID memberId) throws Exception {
        for (var child : game.screen.children()) if (child.getClass().getSimpleName().equals("MemberRow")) {
            var member = (RoomSnapshot.Member) field(child.getClass(), "member").get(child);
            if (member.id().equals(memberId)) return (net.minecraft.client.gui.components.AbstractWidget) child;
        }
        throw new AssertionError("Expected avatar member is not visible: " + memberId);
    }
    private void assertAvatarLookup(Minecraft game, UUID member, net.minecraft.client.resources.PlayerSkin expected) throws Exception {
        var row = avatarRow(game, member); var lookup = row.getClass().getDeclaredMethod("skin"); lookup.setAccessible(true);
        if (!expected.equals(lookup.invoke(row))) throw new AssertionError("Member avatar does not match PlayerInfo/default UUID: " + member);
    }
    private void verifyRoomAvatarScreen(Minecraft game, String name) throws Exception {
        var screen = (RoomScreen) game.screen;
        if ((name.contains("late-info") || name.contains("offline")) && !avatarTransitioned) throw new AssertionError("Avatar transition never ran");
        int verified = 0;
        for (var child : screen.children()) if (child.getClass().getSimpleName().equals("MemberRow")) {
            var member = (RoomSnapshot.Member) field(child.getClass(), "member").get(child);
            var info = game.getConnection().getPlayerInfo(member.id());
            var expected = info != null && !member.id().equals(avatarFailed) ? info.getSkin()
                    : net.minecraft.client.resources.DefaultPlayerSkin.get(member.id());
            assertAvatarLookup(game, member.id(), expected); verified++;
        }
        if (verified == 0) throw new AssertionError("No member avatars rendered");
        if (name.contains("wide") || name.contains("late-info") || name.contains("offline")) {
            assertAvatarLookup(game, game.player.getUUID(), avatarSkins.get(game.player.getUUID()));
            assertAvatarLookup(game, avatarFailed, net.minecraft.client.resources.DefaultPlayerSkin.get(avatarFailed));
            assertAvatarLookup(game, avatarOffline, net.minecraft.client.resources.DefaultPlayerSkin.get(avatarOffline));
        }
        if (name.contains("menu")) {
            UUID member = (UUID) field(RoomScreen.class, "menuMember").get(screen);
            if (!avatarRemote.equals(member)) throw new AssertionError("Avatar right-click targeted wrong member");
            var items = (List<?>) field(RoomScreen.class, "menuButtons").get(screen);
            if (items.isEmpty()) throw new AssertionError("Member management actions disappeared");
            screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            if (game.screen != screen || field(RoomScreen.class, "menuMember").get(screen) != null) throw new AssertionError("Avatar menu Escape changed the room screen");
            var row = avatarRow(game, avatarRemote);
            // The face itself stays inside the existing row hit target.
            screen.mouseClicked(screen.toGuiCoordinate(row.getX() + 17), screen.toGuiCoordinate(row.getY() + row.getHeight() / 2.0), 1);
            if (!avatarRemote.equals(field(RoomScreen.class, "menuMember").get(screen))) throw new AssertionError("Right-clicking the avatar did not reopen member actions");
        }
        ModernMusic.LOGGER.info("PASS: {} verified {} production member skin lookups", name, verified);
    }

    private void planRoomShots(Minecraft game) {
        // The integrated server is still used for channel availability. Its snapshots must not replace these fixtures.
        dev.modernmusic.network.RoomNetwork.clientUpdate = update -> {};
        for (String transition : List.of("join", "leave", "room-switch", "member-left", "owner-changed"))
            add("room-transition-" + transition, 1280, 800, 2, () -> roomTransition(game, transition));
        for (String change : List.of("shrink", "reorder")) for (boolean remove : List.of(false, true))
            add("room-transition-queue-" + change + (remove ? "-remove" : "-play"), 1280, 800, 2,
                    () -> roomQueueTransition(game, change, remove));
        for (int[] size : new int[][]{{1280, 800}, {680, 480}, {1920, 1080}}) {
            String sizeName = size[0] + "x" + size[1];
            add("room-owner-" + sizeName, size[0], size[1], 2, () -> roomFixture(game, "owner"));
            add("room-owner-menu-" + sizeName, size[0], size[1], 2, () -> {
                roomFixture(game, "owner"); roomMenu(game, true);
            });
            add("room-personal-" + sizeName, size[0], size[1], 2, () -> {
                roomFixture(game, "owner"); game.setScreen(new PlayerScreen(null, player, null, hud));
            });
        }
        for (String state : List.of("listener", "team", "empty", "invitations", "unjoined", "muted")) {
            add("room-" + state, 1280, 800, 2, () -> roomFixture(game, state));
            add("room-" + state + "-compact", 680, 480, 4, () -> roomFixture(game, state));
        }
        add("room-personal-listener", 1280, 800, 2, () -> {
            roomFixture(game, "listener"); game.setScreen(new PlayerScreen(null, player, null, hud));
        });
        add("room-personal-duplicate", 1280, 800, 2, () -> {
            roomFixture(game, "duplicate"); game.setScreen(new PlayerScreen(null, player, null, hud));
        });
        add("room-owner-gs4", 1280, 800, 4, () -> roomFixture(game, "owner"));
        add("room-theme-daylight", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.DAYLIGHT); roomFixture(game, "owner"); });
        add("room-theme-create", 1280, 800, 2, () -> { theme(game, HudSettings.Theme.CREATE); roomFixture(game, "owner"); });
        add("room-owner-minimum", 320, 240, 2, () -> roomFixture(game, "owner"));
        add("room-owner-menu-minimum", 320, 240, 2, () -> { roomFixture(game, "owner"); roomMenu(game, true); });
        add("room-unjoined-minimum", 320, 240, 2, () -> roomFixture(game, "unjoined"));
        add("room-invitations-minimum", 320, 240, 2, () -> roomFixture(game, "invitations"));
    }

    private void roomFixture(Minecraft game, String state) { roomFixture(game, state, true); }
    private void roomFixture(Minecraft game, String state, boolean initializeScreen) {
        try {
            var rooms = RoomClient.instance();
            UUID local = game.player.getUUID(), remote = new UUID(7, 7);
            UUID owner = state.equals("listener") ? remote : local;
            var members = new ArrayList<RoomSnapshot.Member>();
            members.add(new RoomSnapshot.Member(owner, state.equals("listener") ? "EveningHost" : game.player.getGameProfile().getName(), true, true));
            members.add(new RoomSnapshot.Member(state.equals("listener") ? local : remote, "晚风听众_Aurora", false, false));
            members.add(new RoomSnapshot.Member(new UUID(8, 8), "NeonCoast", true, false));
            members.add(new RoomSnapshot.Member(new UUID(9, 9), "山海之间", false, true));
            for (int i = 4; i < 14; i++) members.add(new RoomSnapshot.Member(new UUID(i + 20, i), "Listener_" + (i + 1), false, false));
            var songs = state.equals("empty") ? List.<String>of() : state.equals("duplicate") ? List.of("42", "55", "56")
                    : java.util.stream.LongStream.range(55, 79).mapToObj(Long::toString).toList();
            RoomSnapshot snapshot = state.equals("invitations") || state.equals("unjoined") ? null
                    : new RoomSnapshot(new UUID(42, 42), UUID.randomUUID(), "晚风与海 · Listening Together", 1, 1, 1,
                    owner, members, songs, songs.isEmpty() ? -1 : 0, PlaybackMode.SEQUENTIAL, RoomSnapshot.State.STOPPED,
                    0, 0, 252_000, state.equals("team") ? new UUID(90, 90) : null);
            var invitations = state.equals("invitations") ? java.util.stream.IntStream.range(0, 9)
                    .mapToObj(i -> new RoomUpdate.Invitation(new UUID(100 + i, i), new UUID(i, 100 + i), "一起听歌 · 晚风电台 " + (i + 1))).toList()
                    : List.<RoomUpdate.Invitation>of();
            field(RoomClient.class, "update").set(rooms, new RoomUpdate(100, snapshot, invitations, "",
                    new TeamStatus(TeamStatus.State.READY, "Aurora Team")));
            field(RoomClient.class, "message").set(rooms, "");
            field(RoomClient.class, "listening").setBoolean(rooms, !state.equals("muted"));
            field(RoomClient.class, "lastReceived").setLong(rooms, dev.modernmusic.network.RoomNetwork.now());
            player.applyRoom(snapshot);
            if (initializeScreen) {
                game.setScreen(new RoomScreen(null, rooms));
                game.screen.tick();
            }
        } catch (Exception failure) { throw new IllegalStateException("Cannot install room gallery fixture", failure); }
    }

    /** A network task can change membership after Screen.tick() and before that same frame's render. */
    private void roomTransition(Minecraft game, String transition) {
        try {
            roomFixture(game, transition.equals("join") ? "unjoined" : transition.equals("leave") ? "team" : "owner");
            var screen = (RoomScreen) game.screen;
            var rooms = RoomClient.instance();
            if (transition.equals("join")) {
                if (!((List<?>) field(RoomScreen.class, "songs").get(screen)).isEmpty() || rooms.update().room() != null)
                    throw new IllegalStateException("Join regression did not start from a fresh lobby screen");
                roomFixture(game, "owner", false);
            } else if (transition.equals("leave")) {
                if (field(RoomScreen.class, "input").get(screen) != null)
                    throw new IllegalStateException("Leave regression did not start from a team room without an input");
                roomFixture(game, "unjoined", false);
            } else {
                roomMenu(game, true);
                var previous = rooms.update().room();
                UUID selected = (UUID) field(RoomScreen.class, "menuMember").get(screen);
                var members = transition.equals("member-left")
                        ? previous.members().stream().filter(member -> !member.id().equals(selected)).toList() : previous.members();
                var snapshot = new RoomSnapshot(transition.equals("room-switch") ? new UUID(84, 84) : previous.id(),
                        previous.epoch(), transition.equals("room-switch") ? "另一个群组 · New Session" : previous.name(),
                        previous.revision() + 1, previous.queueRevision(), previous.playbackRevision(),
                        transition.equals("owner-changed") ? selected : previous.owner(), members, previous.songs(), previous.index(),
                        previous.mode(), previous.state(), previous.positionMs(), previous.anchorMs(), previous.durationMs(),
                        transition.equals("room-switch") ? new UUID(85, 85) : previous.teamId());
                field(RoomClient.class, "update").set(rooms, new RoomUpdate(rooms.update().sequence() + 1, snapshot,
                        rooms.update().invitations(), "", rooms.update().team()));
                player.applyRoom(snapshot);
            }
            if (game.screen != screen) throw new IllegalStateException("Transition accidentally replaced the existing screen");
            // Deliberately render synchronously: no tick, setScreen, resize, or re-init is allowed after the update.
            // This is the same entry point used by the game renderer and reproduces the lobby queueSlots=0 crash.
            var graphics = new net.minecraft.client.gui.GuiGraphics(game, game.renderBuffers().bufferSource());
            screen.renderWithTooltip(graphics, -100, -100, 0);
            graphics.flush();
            if (field(RoomScreen.class, "menuMember").get(screen) != null)
                throw new IllegalStateException("Membership transition left a stale owner menu open: " + transition);
            if (rooms.update().room() == null) {
                if (field(RoomScreen.class, "input").get(screen) == null || !((List<?>) field(RoomScreen.class, "songs").get(screen)).isEmpty())
                    throw new IllegalStateException("Leave frame still contains room-only controls");
            } else if (((List<?>) field(RoomScreen.class, "songs").get(screen)).isEmpty())
                throw new IllegalStateException("Joined room rendered with lobby controls");
            ModernMusic.LOGGER.info("PASS: room {} update rendered before the next screen tick", transition);
        } catch (Exception failure) { throw new IllegalStateException("Room render-boundary regression: " + transition, failure); }
    }

    private void roomQueueTransition(Minecraft game, String change, boolean remove) {
        try {
            roomFixture(game, "owner");
            var screen = (RoomScreen) game.screen;
            var rooms = RoomClient.instance();
            var previous = rooms.update().room();
            var rows = (List<?>) field(RoomScreen.class, remove ? "removes" : "songs").get(screen);
            var oldTarget = (net.minecraft.client.gui.components.AbstractWidget) rows.getFirst();
            double x = screen.toGuiCoordinate(oldTarget.getX() + oldTarget.getWidth() / 2.0);
            double y = screen.toGuiCoordinate(oldTarget.getY() + oldTarget.getHeight() / 2.0);
            var changed = change.equals("shrink") ? previous.songs().subList(1, 4) : previous.songs().reversed();
            var snapshot = new RoomSnapshot(previous.id(), previous.epoch(), previous.name(), previous.revision() + 1,
                    previous.queueRevision() + 1, previous.playbackRevision(), previous.owner(), previous.members(), changed, 0,
                    previous.mode(), previous.state(), previous.positionMs(), previous.anchorMs(), previous.durationMs(), previous.teamId());
            field(RoomClient.class, "update").set(rooms, new RoomUpdate(rooms.update().sequence() + 1, snapshot,
                    rooms.update().invitations(), "", rooms.update().team()));
            player.applyRoom(snapshot);
            // Sending any command clears RoomClient.message synchronously, before network I/O can complete.
            // The stale click must be consumed during reconciliation rather than act on a different song at index 0.
            String sentinel = "modernmusic.room.song_added";
            field(RoomClient.class, "message").set(rooms, sentinel);
            screen.mouseClicked(x, y, 0);
            if (!field(RoomClient.class, "message").get(rooms).equals(sentinel))
                throw new IllegalStateException("Stale " + (remove ? "remove" : "play") + " click submitted a command after queue " + change);
            field(RoomClient.class, "message").set(rooms, "");
            var graphics = new net.minecraft.client.gui.GuiGraphics(game, game.renderBuffers().bufferSource());
            screen.renderWithTooltip(graphics, -100, -100, 0); graphics.flush();
            ModernMusic.LOGGER.info("PASS: stale room {} click consumed after queue {} before the next screen tick", remove ? "remove" : "play", change);
        } catch (Exception failure) { throw new IllegalStateException("Room queue input-boundary regression: " + change, failure); }
    }

    private void roomMenu(Minecraft game, boolean expected) {
        try {
            var screen = (MusicScreen) game.screen;
            var members = screen.children().stream().filter(child -> child.getClass().getSimpleName().equals("MemberRow"))
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).toList();
            if (members.isEmpty()) throw new IllegalStateException("Room member list is empty");
            if (members.size() == 1 && ((RoomSnapshot.Member) field(members.getFirst().getClass(), "member").get(members.getFirst()))
                    .id().equals(RoomClient.instance().update().room().owner())) {
                screen.mouseScrolled(screen.toGuiCoordinate(members.getFirst().getX() + 20),
                        screen.toGuiCoordinate(members.getFirst().getY() + 8), 0, -1);
                members = screen.children().stream().filter(child -> child.getClass().getSimpleName().equals("MemberRow"))
                        .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).toList();
            }
            var member = members.size() > 1 ? members.get(1) : members.getFirst();
            screen.mouseClicked(screen.toGuiCoordinate(member.getX() + 20 + menuOriginOffset), screen.toGuiCoordinate(member.getY() + member.getHeight() / 2.0), 1);
            if ((field(RoomScreen.class, "menuMember").get(screen) != null) != expected)
                throw new IllegalStateException("Incorrect owner right-click permission");
        } catch (Exception failure) { throw new IllegalStateException("Room member context menu interaction failed", failure); }
    }

    private void verifyRoomScreen(Minecraft game, String name) throws Exception {
        var screen = (MusicScreen) game.screen;
        if (screen instanceof RoomScreen) {
            var reference = new PlayerScreen(null, player, null, hud);
            reference.init(game, game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight());
            for (String dimension : List.of("left", "top", "panelWidth", "panelHeight"))
                if (field(RoomScreen.class, dimension).getInt(screen) != field(PlayerScreen.class, dimension).getInt(reference))
                    throw new IllegalStateException("Room and player frames differ in " + name + ": " + dimension);
            if (name.startsWith("room-listener")) roomMenu(game, false);
            if (name.startsWith("room-owner-") && !name.startsWith("room-owner-menu")) {
                for (String pane : List.of("member", "queue")) {
                    double y = screen.toGuiCoordinate(field(RoomScreen.class, "bodyY").getInt(screen) + 46);
                    double x = screen.toGuiCoordinate(field(RoomScreen.class, pane + "X").getInt(screen) + 30);
                    String offset = pane.equals("member") ? "memberOffset" : "queuePage";
                    if (!screen.mouseScrolled(x, y, 0, -1) || field(RoomScreen.class, offset).getInt(screen) == 0)
                        throw new IllegalStateException("Scaled " + pane + " list did not scroll in " + name);
                    screen.mouseScrolled(x, y, 0, 1);
                }
            }
            if (!name.startsWith("room-owner-menu") && field(RoomScreen.class, "menuMember").get(screen) != null)
                throw new IllegalStateException("Unexpected member context menu in " + name);
            if (name.startsWith("room-owner-menu")) {
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
                if (game.screen != screen || field(RoomScreen.class, "menuMember").get(screen) != null)
                    throw new IllegalStateException("Escape did not dismiss the member menu");
                roomMenu(game, true);
                screen.mouseClicked(screen.toGuiCoordinate(2), screen.toGuiCoordinate(2), 0);
                if (field(RoomScreen.class, "menuMember").get(screen) != null)
                    throw new IllegalStateException("Outside click did not dismiss the member menu");
                roomMenu(game, true);
            }
        } else if (screen instanceof PlayerScreen) {
            var rows = (List<?>) field(PlayerScreen.class, "rows").get(screen);
            if (rows.isEmpty() || !field(rows.getFirst().getClass(), "song").get(rows.getFirst()).equals(track(42).title()))
                throw new IllegalStateException("Main UI replaced the personal queue with room songs");
            var groupRows = (List<?>) field(PlayerScreen.class, "groupRows").get(screen);
            if (groupRows.size() != rows.size()) throw new IllegalStateException("A personal row has no group-add button");
            for (int i = 0; i < groupRows.size(); i++) {
                boolean expected = !name.endsWith("listener.png") && !(name.endsWith("duplicate.png") && i == 0);
                if (((net.minecraft.client.gui.components.AbstractWidget) groupRows.get(i)).active != expected)
                    throw new IllegalStateException("Personal song group-add permission or duplicate state was incorrect");
            }
        }
        if (player.library().active().queue().entries().size() != 12
                || player.library().active().queue().entries().getFirst().value() != 42)
            throw new IllegalStateException("Room fixture changed the personal queue");
    }
    /** Just the HUD over the world, drawn from settings other than the client's own. */
    private Screen hudOnly(HudSettings settings) {
        return new Screen(net.minecraft.network.chat.Component.empty()) {
            @ParametersAreNonnullByDefault @Override public void renderBackground(net.minecraft.client.gui.GuiGraphics g, int x, int y, float delta) {}
            @ParametersAreNonnullByDefault @Override public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float delta) { MusicHud.render(g, player, settings, width, height, true); }
        };
    }

    private AccountController account(Minecraft game, boolean signedIn) {
        var provider = new AccountProvider() {
            public AccountSession validate(SessionCookie cookie) { return new AccountSession("42", "晚风听众 · Gallery", cookie, Instant.now(), AVATAR); }
            public QrChallenge beginQr() { return new QrChallenge("modernmusic-gallery-not-a-real-login", Instant.now().plusSeconds(120)); }
            public QrPoll pollQr(QrChallenge challenge) { return new QrPoll(QrState.WAITING, null); }
            public void sendSms(PhoneLogin phone) {}
            public AccountSession loginSms(PhoneLogin phone, String code) { throw new AuthException("sms_login_failed"); }
        };
        return new AccountController(provider, new RememberedSessionStore(output.resolve(signedIn ? "account-signed" : "account-guest")),
                game::execute, () -> {}, Clock.systemUTC());
    }

    private void installFixture(Minecraft game) throws Exception {
        player = (PlaybackController) field(RoomClient.class, "player").get(RoomClient.instance());
        hud = new HudSettings(game.gameDirectory.toPath().resolve("config/modernmusic-hud.json"));
        var provider = field(PlaybackController.class, "provider"); ((MusicProvider) provider.get(player)).close();
        var transport = field(PlaybackController.class, "transport"); ((BoundedHttpTransport) transport.get(player)).close();
        provider.set(player, fixture);
        transport.set(player, (HttpTransport) (uri, max) -> silentMp3());
        var outputField = field(PlaybackController.class, "output"); ((AudioOutput) outputField.get(player)).close();
        outputField.set(player, new GalleryOutput());
        field(PlaybackController.class, "search").set(player, new SongSearchController(fixture, game::execute));
    }

    private void seedArtwork(Minecraft game) throws Exception {
        @SuppressWarnings("unchecked") var entries = (Map<URI, Object>) field(ArtworkCache.class, "entries").get(ArtworkCache.get());
        Class<?> type = Class.forName("dev.modernmusic.client.ui.ArtworkCache$Entry");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        var install = ArtworkCache.class.getDeclaredMethod("install", type, int[].class); install.setAccessible(true);
        for (URI uri : new URI[]{ART, AVATAR}) {
            Object entry = constructor.newInstance(); field(type, "avatar").set(entry, uri == AVATAR);
            field(type, "square").set(entry, PlayerTheme.pixel());
            int[] pixels = new int[256 * 256];
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) pixels[y * 256 + x] = uri == ART ? cover(x, y) : avatar(x, y);
            install.invoke(ArtworkCache.get(), entry, pixels);
            entries.put(NeteaseUris.image(uri.toString()), entry);
        }
    }
    /** A deterministic "album cover": dusk gradient, sun and sea lines (ARGB). */
    private static int cover(int x, int y) {
        double t = y / 255.0;
        int r = (int) (40 + 190 * t), g = (int) (40 + 70 * t), b = (int) (110 + 40 * (1 - t));
        double sun = Math.hypot(x - 170, y - 120);
        if (sun < 42) { r = 255; g = 214 - (int) sun; b = 150; }
        if (y > 170) { double wave = Math.sin(x / 9.0 + y / 3.0); r = (int) (30 + 20 * wave); g = (int) (70 + 30 * wave); b = (int) (120 + 30 * wave); }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
    private static int avatar(int x, int y) {
        boolean head = Math.hypot(x - 128, y - 100) < 50, body = Math.hypot(x - 128, y - 250) < 100;
        int r = head || body ? 250 : 120, g = head || body ? 220 : 180, b = head || body ? 200 : 230;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static final String[][] SONGS = {
            {"晚风与海 (Evening Breeze)", "林夕 / Aurora"}, {"群青の夏", "Aoi Hoshi"}, {"Starlight Avenue", "Neon Coast"},
            {"時光倒流的旅程", "陳綺"}, {"山海之间", "青柠乐队"}, {"Paper Planes in July", "Mira Lane"},
            {"雨后初晴", "周遥"}, {"夜航星", "北辰"}, {"Lighthouse", "The Tides"}, {"微光", "苏木"},
            {"千里之外的你", "阿澈"}, {"Midnight Radio", "Echo Park"}};
    private static Track track(long id) {
        var song = SONGS[(int) ((id - 42) % SONGS.length + SONGS.length) % SONGS.length];
        return new Track(new SongId(id), song[0], List.of(song[1].split(" / ")), 252_000 - (id % 5) * 17_000, ART);
    }
    private static final class Fixture implements MusicProvider {
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(track(id.value())); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/gallery.mp3"), Instant.now().plusSeconds(600)));
        }
        public CompletableFuture<Lyrics> getLyrics(SongId id) {
            String[][] rows = {{"晚风轻轻吹过海面", "The evening breeze drifts across the sea"}, {"把星光揉进你的眼", "Folding starlight into your eyes"},
                    {"我们沿着潮汐走远", "We walk away along the tide"}, {"直到夜色温柔不再", "Until the gentle night is gone"},
                    {"灯塔在远处闪烁", "A lighthouse flickers far away"}, {"像未说出口的告白", "Like a confession never spoken"},
                    {"若时光可以倒流", "If time could flow backwards"}, {"我仍会选择与你相遇", "I would still choose to meet you"}};
            var lines = new ArrayList<Lyrics.Line>();
            for (int i = 0; i < 40; i++) lines.add(new Lyrics.Line(i * 6_000L, rows[i % rows.length][0], rows[i % rows.length][1]));
            return CompletableFuture.completedFuture(new Lyrics(lines));
        }
        public CompletableFuture<SongSearchResult> searchSongs(String query, int offset, int limit) {
            var tracks = new ArrayList<Track>(); for (int i = 0; i < limit; i++) tracks.add(track(42 + offset + i));
            return CompletableFuture.completedFuture(new SongSearchResult(tracks, 60, offset + limit < 60));
        }
        public void close() {}
    }
    private static final class GalleryOutput implements AudioOutput {
        private volatile State state = State.STOPPED;
        private volatile long position = 84_000;
        public void load(PcmAudio audio) { state = State.PLAYING; position = 84_000; }
        @Override public void load(PcmAudio audio, long positionMs, boolean paused) { load(audio); }
        public void pause(boolean paused) { state = paused ? State.PAUSED : State.PLAYING; }
        public void seek(long positionMs) { position = positionMs; }
        public void volume(float value) {}
        public void stop() { state = State.STOPPED; }
        public Snapshot snapshot() { return new Snapshot(state, state == State.STOPPED ? 0 : position, state == State.STOPPED ? 0 : 252_000); }
        public void close() {}
    }
    private static byte[] silentMp3() {
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) { byte[] frame = new byte[417]; frame[0] = (byte) 0xff; frame[1] = (byte) 0xfb; frame[2] = (byte) 0x90; out.writeBytes(frame); }
        return out.toByteArray();
    }
    private static PcmAudio waveformAudio() {
        int rate = 8000;
        byte[] pcm = new byte[rate * 252 * 2];
        for (int frame = 0; frame < pcm.length / 2; frame++) {
            double time = frame / (double) rate;
            double pulse = .15 + .85 * Math.pow(.5 + .5 * Math.sin(time * 2 * Math.PI * 7), 2);
            short value = (short) (28000 * pulse * Math.sin(time * 2 * Math.PI * 220));
            pcm[frame * 2] = (byte) value; pcm[frame * 2 + 1] = (byte) (value >> 8);
        }
        return new PcmAudio(pcm, rate, 1);
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void verifyPlayerScale(Minecraft game) throws Exception {
        var screen = (MusicScreen) game.screen;
        if (field(PlayerScreen.class, "narrow").getBoolean(screen)
                || field(PlayerScreen.class, "bodyHeight").getInt(screen) < 328
                || ((List<?>) field(PlayerScreen.class, "rows").get(screen)).size() != 9)
            throw new IllegalStateException("Full player composition was lost at " + game.getWindow().getWidth() + "x" + game.getWindow().getHeight());
        var input = (net.minecraft.client.gui.components.EditBox) field(PlayerScreen.class, "input").get(screen);
        input.setValue("缩放后保留输入");
        screen.resize(game, game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight());
        input = (net.minecraft.client.gui.components.EditBox) field(PlayerScreen.class, "input").get(screen);
        if (!input.getValue().equals("缩放后保留输入")) throw new IllegalStateException("Resize lost the player's draft");
        input.setValue("");
        var volume = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractSliderButton.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractSliderButton.class::cast)
                .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("modernmusic.volume")).findFirst().orElseThrow();
        float savedVolume = player.volume();
        double x = screen.toGuiCoordinate(volume.getX() + 4), y = screen.toGuiCoordinate(volume.getY() + volume.getHeight() / 2.0);
        double end = screen.toGuiCoordinate(volume.getX() + 4 + .75 * (volume.getWidth() - 8));
        if (!screen.mouseClicked(x, y, 0)) throw new IllegalStateException("Scaled volume click missed");
        screen.mouseDragged(end, y, 0, end - x, 0); screen.mouseReleased(end, y, 0);
        if (Math.abs(player.volume() - .75) > .01) throw new IllegalStateException("Scaled volume drag missed");
        player.volume(savedVolume);
        double queueX = screen.toGuiCoordinate(field(PlayerScreen.class, "queueX").getInt(screen) + 40);
        double queueY = screen.toGuiCoordinate(field(PlayerScreen.class, "bodyY").getInt(screen) + 50);
        if (!screen.mouseScrolled(queueX, queueY, 0, -1) || field(PlayerScreen.class, "page").getInt(screen) != 1)
            throw new IllegalStateException("Scaled queue scroll missed");
        screen.mouseScrolled(queueX, queueY, 0, 1);
        screen.setFocused(input);
    }

    private void capture(Minecraft game, String name) throws Exception {
        if (roomAvatarGallery) verifyRoomAvatarScreen(game, name);
        if (audioDropGallery) verifyAudioDropScreen(game, name);
        if (localAudioGallery) verifyAudioScreen(game, name);
        if (roomManagementGallery) verifyRoomManagementScreen(game, name);
        if (ae2MenuGallery) verifyAe2Menu(game, name);
        if (roomGallery) verifyRoomScreen(game, name);
        if (name.startsWith("qr-login-") && !name.contains("tabs")) {
            var account = (AccountController) field(AccountScreen.class, "account").get(game.screen);
            if (account.qr() == null || account.qr().method() != expectedQr || field(AccountScreen.class, "matrix").get(game.screen) == null)
                throw new AssertionError("QR method or rendered image missing in " + name);
        }
        if (name.startsWith("scaling-")) verifyPlayerScale(game);
        if (game.screen instanceof MusicScreen screen) {
            for (var child : screen.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget && widget.visible
                    && (widget.getX() < 0 || widget.getY() < 0 || widget.getRight() > screen.width || widget.getBottom() > screen.height))
                throw new IllegalStateException("Widget outside scaled viewport in " + name);
        }
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(output.resolve(name)); }
    }
    private void finish(Minecraft game, String result) {
        step = -1;
        try { Files.writeString(game.gameDirectory.toPath().resolve("gallery-result.txt"), result + "\n"); }
        catch (Exception failure) { ModernMusic.LOGGER.error("Cannot write gallery marker", failure); }
        ModernMusic.LOGGER.info("{}", result); game.stop();
    }
}
