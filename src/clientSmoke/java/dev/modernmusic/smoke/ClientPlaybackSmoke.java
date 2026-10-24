package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.GameAudioBridge;
import dev.modernmusic.client.audio.JavaSoundOutput;
import dev.modernmusic.client.audio.AudioOutput;
import dev.modernmusic.client.audio.PcmAudio;
import dev.modernmusic.client.ui.PlayerScreen;
import dev.modernmusic.client.ui.AccountScreen;
import dev.modernmusic.auth.*;
import net.minecraft.client.gui.screens.Screen;
import java.time.Instant;
import dev.modernmusic.provider.BoundedHttpTransport;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.BooleanSupplier;

/** Explicit runClientSmoke only: real client classloading, screen and silent playback regression. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class ClientPlaybackSmoke {
    private PlaybackController player;
    private final GameAudioBridge audio = new GameAudioBridge(() -> player);
    private final SilentOutput output = new SilentOutput();
    private Screen screen;
    private AccountController account;
    private final FixtureAccountProvider fixtureAccounts = new FixtureAccountProvider();
    private long deadline = System.nanoTime() + 180_000_000_000L;
    private int step;
    private long pausePosition;
    private int settleTicks;
    private String waitingScreen = "";
    private dev.modernmusic.client.HudSettings featureHud;

    public ClientPlaybackSmoke() {
        if (System.getProperty("modernmusic.roomSmoke") != null || Boolean.getBoolean("modernmusic.lifecycleSmoke") || Boolean.getBoolean("modernmusic.searchSmoke")
                || Boolean.getBoolean("modernmusic.uiGallery") || Boolean.getBoolean("modernmusic.optionalClientSmoke") || Boolean.getBoolean("modernmusic.fontSmoke")) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(audio::selectMusic);
        NeoForge.EVENT_BUS.addListener(audio::playSound);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft game = Minecraft.getInstance();
        if (step == -1) return;
        try {
            if (player == null) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Client startup timed out");
                if (game.getOverlay() != null) return;
                // This test owns a fresh, separate game directory and does not open a world.
                if (game.screen instanceof AccessibilityOnboardingScreen) {
                    game.options.onboardingAccessibilityFinished();
                    game.setScreen(new TitleScreen());
                }
                if (!(game.screen instanceof TitleScreen)) {
                    String type = game.screen == null ? "none" : game.screen.getClass().getName();
                    if (!type.equals(waitingScreen)) ModernMusic.LOGGER.info("Client smoke waiting for title: {}", type);
                    waitingScreen = type;
                    return;
                }
                var http = new BoundedHttpTransport();
                player = new PlaybackController(new NeteaseMusicProvider(http, Clock.systemUTC()), http,
                        output, game::execute);
                player.volume(0); // Open the real device while keeping this automated check silent.
                account = fixtureAccount(game);
                screen = new PlayerScreen(game.screen, player, account);
                game.setScreen(screen);
                screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                        .findFirst().orElseThrow().setValue("1973665667");
                press("modernmusic.add");
                deadline = System.nanoTime() + 90_000_000_000L;
                return;
            }
            audio.tick(game);
            account.tick();
            if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException(player.message());
            if (System.nanoTime() > deadline) throw new IllegalStateException("Smoke step timed out: " + step);
            switch (step) {
                case 0 -> {
                    if (!ready(() -> player.audio().positionMs() >= 300)) return;
                    verifyGameMusic(true);
                    verifyGameVolume(game);
                    ModernMusic.LOGGER.info("Client smoke: actual game playback reached PLAYING for 1973665667");
                    fixtureAccounts.avatar = player.track().artwork();
                    capture(game, "smoke-player.png");
                    press("modernmusic.pause"); step++;
                }
                case 1 -> {
                    if (!ready(() -> player.state() == PlaybackController.State.PAUSED)) return;
                    verifyGameMusic(false);
                    pausePosition = player.audio().positionMs(); step++;
                }
                case 2 -> {
                    if (Math.abs(player.audio().positionMs() - pausePosition) > 60) throw new IllegalStateException("Pause moved");
                    dragSlider("modernmusic.ui.progress", 120000.0 / player.audio().durationMs());
                    step++;
                }
                case 3 -> {
                    if (!ready(() -> player.audio().positionMs() >= 119990)) return;
                    press("modernmusic.play"); step++;
                }
                case 4 -> {
                    if (!ready(() -> player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() >= 120300)) return;
                    press("modernmusic.stop"); step++;
                }
                case 5 -> {
                    if (player.state() != PlaybackController.State.STOPPED) throw new IllegalStateException("Stop failed");
                    verifyGameMusic(false);
                    dragSlider("modernmusic.volume", 0.65);
                    if (Math.abs(player.volume() - 0.65) > 0.01) throw new IllegalStateException("Volume drag failed");
                    input().setValue("1973665667"); // Must survive resize and switching between compact tabs.
                    game.options.guiScale().set(1);
                    game.resizeDisplay(); settleTicks = 5; step++;
                }
                case 6 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-player-wide.png");
                    game.getWindow().setWindowed(680, 480);
                    game.options.guiScale().set(2);
                    game.resizeDisplay(); settleTicks = 5; step++;
                }
                case 7 -> {
                    if (settleTicks-- > 0) return;
                    // Music menus now size from physical pixels, independently of the vanilla GUI scale.
                    // A 680px window still shows both panes; use an actually narrow viewport for tabs.
                    capture(game, "smoke-player-680.png");
                    if (!input().getValue().equals("1973665667")) throw new IllegalStateException("Resize lost input");
                    if (screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .noneMatch(button -> button.visible && button.getMessage().getContents() instanceof TranslatableContents content
                                    && content.getKey().equals("modernmusic.clear")))
                        throw new IllegalStateException("680px layout lost queue controls");
                    game.getWindow().setWindowed(380, 480);
                    game.resizeDisplay(); settleTicks = 5; step = 70;
                }
                case 70 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-player-narrow.png");
                    press("modernmusic.ui.queue_tab"); settleTicks = 5; step = 8;
                }
                case 8 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-queue-narrow.png");
                    press("modernmusic.clear");
                    if (!player.queue().entries().isEmpty()) throw new IllegalStateException("Clear failed");
                    settleTicks = 5; step++;
                }
                case 9 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-queue-empty.png");
                    press("modernmusic.ui.player_tab");
                    if (!input().getValue().equals("1973665667")) throw new IllegalStateException("Resize lost input");
                    game.getWindow().setWindowed(854, 480);
                    game.resizeDisplay();
                    press("modernmusic.auth.title"); settleTicks = 5; step++;
                }
                case 10 -> {
                    if (settleTicks-- > 0) return;
                    input().setValue("MUSIC_U=smoke-fixture-not-a-real-cookie");
                    settleTicks = 5; step++;
                }
                case 11 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-account-masked.png");
                    press("modernmusic.auth.cookie_login"); step++;
                }
                case 12 -> {
                    if (account.busy()) return;
                    if (account.session() == null) throw new IllegalStateException("Fixture cookie sign-in failed");
                    screen.tick();
                    press("modernmusic.auth.remember"); step++;
                }
                case 13 -> {
                    if (account.busy()) return;
                    if (!account.hasSaved()) throw new IllegalStateException("Fixture save failed");
                    capture(game, "smoke-account-signed-in.png");
                    account.close(); account = fixtureAccount(game);
                    screen = new AccountScreen(new PlayerScreen(new TitleScreen(), player, account), account);
                    game.setScreen(screen); settleTicks = 5; step++;
                }
                case 14 -> {
                    if (settleTicks-- > 0) return;
                    if (account.busy()) return;
                    if (account.session() == null) throw new IllegalStateException("Automatic login did not restore");
                    step++;
                }
                case 15 -> {
                    if (account.busy()) return;
                    if (account.session() == null) throw new IllegalStateException("Fixture restore failed");
                    press("modernmusic.auth.logout"); step++;
                }
                case 16 -> {
                    if (account.busy()) return;
                    if (account.session() != null || account.hasSaved()) throw new IllegalStateException("Fixture logout failed");
                    press("modernmusic.auth.qr_login"); step++;
                }
                case 17 -> {
                    if (account.qr() == null) return;
                    settleTicks = 5; step++;
                }
                case 18 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-account-qr.png");
                    verifyRenderedQr(game);
                    press("gui.back");
                    if (account.qrActive()) throw new IllegalStateException("Closed screen kept QR active");
                    press("modernmusic.auth.title"); press("modernmusic.auth.qr_login");
                    fixtureAccounts.verificationRequired = true; step++;
                }
                case 19 -> {
                    if (account.session() == null) {
                        if (account.message().equals("modernmusic.auth.qr_security_verification") && !fixtureAccounts.authorized) {
                            if (!account.qrActive() || account.qr() == null) throw new IllegalStateException("Verification lost QR");
                            // A fixture challenge only: never open or solve a real verification page.
                            if (++verificationFrames < 5) return;
                            capture(game, "smoke-account-qr-verification.png"); verifyRenderedQr(game);
                            fixtureAccounts.authorized = true;
                            press("modernmusic.auth.qr_resume");
                        }
                        return;
                    }
                    if (account.hasSaved()) throw new IllegalStateException("QR login persisted without consent");
                    press("modernmusic.auth.logout"); step++;
                }
                case 20 -> {
                    if (account.busy()) return;
                    press("modernmusic.auth.tab_phone");
                    secret("modernmusic.auth.country_label").setValue("1");
                    secret("modernmusic.auth.phone_label").setValue("2025550123");
                    press("modernmusic.auth.sms_send"); settleTicks = 5; step++;
                }
                case 21 -> {
                    if (account.busy() || settleTicks-- > 0) return;
                    if (fixtureAccounts.sends.get() != 1 || account.smsCooldownSeconds() == 0) throw new IllegalStateException("SMS cooldown missing");
                    capture(game, "smoke-account-phone.png");
                    game.getWindow().setWindowed(680, 480); game.resizeDisplay(); settleTicks = 5; step++;
                }
                case 22 -> {
                    if (settleTicks-- > 0) return;
                    if (!secret("modernmusic.auth.phone_label").getValue().equals("2025550123")) throw new IllegalStateException("Resize lost phone draft");
                    secret("modernmusic.auth.code_label").setValue("000000");
                    settleTicks = 5; step++;
                }
                case 23 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-account-phone-narrow.png");
                    press("modernmusic.auth.sms_login"); step++;
                }
                case 24 -> {
                    if (account.busy()) return;
                    if (account.session() != null || !account.message().equals("modernmusic.auth.sms_login_failed")) throw new IllegalStateException("Wrong code accepted");
                    if (!secret("modernmusic.auth.code_label").getValue().isEmpty()) throw new IllegalStateException("Submitted code retained");
                    if (account.smsLoginCooldownSeconds() > 0) return;
                    capture(game, "smoke-account-phone-error.png");
                    secret("modernmusic.auth.code_label").setValue("100004");
                    press("modernmusic.auth.sms_login"); step++;
                }
                case 25 -> {
                    if (account.busy()) return;
                    if (account.session() != null || !account.message().equals("modernmusic.auth.login_rejected_10004")) throw new IllegalStateException("10004 diagnostic lost");
                    if (account.smsLoginCooldownSeconds() > 0) return;
                    capture(game, "smoke-account-phone-rejected.png");
                    secret("modernmusic.auth.code_label").setValue("123456");
                    press("modernmusic.auth.sms_login"); step++;
                }
                case 26 -> {
                    if (account.busy()) return;
                    if (account.session() == null || account.hasSaved() || fixtureAccounts.sends.get() != 1) throw new IllegalStateException("SMS fixture sign-in failed");
                    press("modernmusic.auth.logout"); step++;
                }
                case 27 -> {
                    if (account.busy()) return;
                    fixtureAccounts.authorized = false; fixtureAccounts.verificationLink = false;
                    press("modernmusic.auth.qr_login"); step++;
                }
                case 28 -> {
                    if (!account.qrNeedsVerification()) return;
                    if (++verificationFrames < 10) return;
                    capture(game, "smoke-account-qr-no-link.png");
                    if (account.qrVerificationUri() != null) throw new IllegalStateException("Unexpected verification URL");
                    press("modernmusic.auth.qr_use_sms");
                    if (account.qrActive() || secret("modernmusic.auth.phone_label") == null || fixtureAccounts.sends.get() != 1)
                        throw new IllegalStateException("QR to SMS handoff failed");
                    featureHud = new dev.modernmusic.client.HudSettings(game.gameDirectory.toPath().resolve("smoke-hud.json"));
                    featureHud.reset();
                    game.getWindow().setWindowed(1100, 720); game.options.guiScale().set(2); game.resizeDisplay();
                    screen = new PlayerScreen(new TitleScreen(), player, account, featureHud); game.setScreen(screen);
                    player.add("1973665667");
                    deadline = System.nanoTime() + 90_000_000_000L; step++;
                }
                case 29 -> {
                    if (player.state() != PlaybackController.State.PLAYING) return;
                    player.importPlaylist("https://music.163.com/#/playlist?id=3778678"); step++;
                }
                case 30 -> {
                    if (player.importing() || player.lyrics().lines().isEmpty()) return;
                    if (player.queue().entries().size() != player.importedCount() + 1 || player.importedCount() < 100
                            || player.queue().entries().size() > 400) throw new IllegalStateException("Playlist UI import failed");
                    verifyQueueManagement(game);
                    player.seek(120000); press("modernmusic.mode.sequential");
                    if (player.queue().mode() != dev.modernmusic.domain.PlaybackMode.REPEAT_ONE) throw new IllegalStateException("Mode button failed");
                    press("modernmusic.mode.repeat_one"); press("modernmusic.mode.repeat_all");
                    if (player.queue().mode() != dev.modernmusic.domain.PlaybackMode.SHUFFLE) throw new IllegalStateException("Shuffle button failed");
                    settleTicks = 15; step++;
                }
                case 31 -> {
                    if (player.state() != PlaybackController.State.PLAYING) return;
                    if (settleTicks == 15) player.seek(120000);
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-library-player.png"); press("HUD"); settleTicks = 5; step++;
                }
                case 32 -> {
                    if (settleTicks-- > 0) return;
                    int w = game.getWindow().getGuiScaledWidth(), h = game.getWindow().getGuiScaledHeight();
                    var cover = featureHud.component(dev.modernmusic.client.HudSettings.Part.COVER);
                    var info = featureHud.component(dev.modernmusic.client.HudSettings.Part.INFO);
                    int infoLeft = info.left(w, h), infoTop = info.top(w, h);
                    double x = cover.left(w, h) + 20, y = cover.top(w, h) + 20;
                    screen.mouseClicked(x, y, 0); screen.mouseDragged(x + 70, y + 45, 0, 70, 45); screen.mouseReleased(x + 70, y + 45, 0);
                    dragSlider("modernmusic.hud.scale", (.85 - .25) / 2.75);
                    dragSlider("modernmusic.hud.opacity", .4);
                    if (cover.left(w, h) < 60 || Math.abs(cover.scale() - .85) > .02 || Math.abs(cover.opacity() - .6) > .02)
                        throw new IllegalStateException("HUD drag/resize/opacity failed");
                    if (infoLeft != info.left(w, h) || infoTop != info.top(w, h) || info.scale() != 1 || info.opacity() != 1)
                        throw new IllegalStateException("Editing cover changed another component");
                    press("modernmusic.hud.part.lyrics");
                    dragSlider("modernmusic.hud.lyric_size", (1.2 - .25) / 2.75);
                    dragSlider("modernmusic.hud.opacity", .2);
                    verifyHudEditor(game);
                    settleTicks = 5; step++;
                }
                case 33 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-music-hud.png"); press("gui.done");
                    var restoredHud = new dev.modernmusic.client.HudSettings(game.gameDirectory.toPath().resolve("smoke-hud.json"));
                    for (var part : dev.modernmusic.client.HudSettings.Part.values()) {
                        if (Math.abs(restoredHud.component(part).scale() - featureHud.component(part).scale()) > .001
                                || Math.abs(restoredHud.component(part).opacity() - featureHud.component(part).opacity()) > .001
                                || restoredHud.component(part).enabled() != featureHud.component(part).enabled())
                            throw new IllegalStateException("HUD settings did not persist");
                    }
                    game.options.renderDistance().set(2); game.options.simulationDistance().set(5);
                    deadline = System.nanoTime() + 150_000_000_000L;
                    step++;
                    game.createWorldOpenFlows().createFreshLevel("modernmusic-hud-smoke-" + System.currentTimeMillis(),
                            new net.minecraft.world.level.LevelSettings("Modern Music HUD Smoke", net.minecraft.world.level.GameType.CREATIVE,
                                    false, net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(),
                                    net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                            new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false),
                            registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(),
                            new TitleScreen());
                }
                case 34 -> {
                    if (game.level == null || game.player == null || game.getOverlay() != null) return;
                    game.setScreen(null);
                    queueOpenPlayer(game);
                    step++;
                }
                case 35 -> {
                    if (!(game.screen instanceof PlayerScreen)) return;
                    screen = game.screen;
                    // Inspect the shortcut-opened screen's controller, so this checks the production HUD registration too.
                    var field = PlayerScreen.class.getDeclaredField("player"); field.setAccessible(true);
                    var productionPlayer = (PlaybackController) field.get(screen);
                    if (!productionPlayer.queuesReady()) return;
                    var hudField = PlayerScreen.class.getDeclaredField("hud"); hudField.setAccessible(true);
                    var productionHud = (dev.modernmusic.client.HudSettings) hudField.get(screen);
                    productionHud.restore(featureHud.snapshot(screen.width, screen.height));
                    featureHud = productionHud;
                    player.close(); player = productionPlayer; player.clear(); player.volume(0);
                    input().setValue("1973665667"); press("modernmusic.add"); step++;
                }
                case 36 -> {
                    if (player.state() != PlaybackController.State.PLAYING || player.lyrics().lines().isEmpty()) return;
                    player.seek(120000); screen.onClose(); settleTicks = 20; step++;
                }
                case 37 -> {
                    if (settleTicks-- > 0) return;
                    if (game.screen != null || game.level == null) throw new IllegalStateException("In-game HUD not reached");
                    capture(game, "smoke-hud-in-world.png");
                    queueOpenPlayer(game); step++;
                }
                case 38 -> {
                    if (!(game.screen instanceof PlayerScreen)) return;
                    screen = game.screen; press(player.library().active().name());
                    input().setValue("游戏音乐"); press("modernmusic.queues.rename");
                    input().setValue("轻音乐"); press("modernmusic.queues.create"); settleTicks = 5; step++;
                }
                case 39 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-queue-manager.png");
                    press("gui.back"); player.switchQueue(player.library().queues().stream().filter(q -> q.name().equals("游戏音乐")).findFirst().orElseThrow().id());
                    player.play(0); press("HUD"); settleTicks = 10; step++;
                }
                case 40 -> {
                    if (player.state() != PlaybackController.State.PLAYING || player.lyrics().lines().isEmpty()) return;
                    if (settleTicks == 10) player.seek(120000);
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-hud-editor-world.png");
                    toggleHudPart(dev.modernmusic.client.HudSettings.Part.PROGRESS); press("gui.done"); screen.onClose();
                    settleTicks = 10; step++;
                }
                case 41 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-hud-all-visible.png");
                    game.getWindow().setWindowed(640, 480); game.options.guiScale().set(2); game.resizeDisplay();
                    featureHud.reset();
                    screen = new dev.modernmusic.client.ui.HudEditorScreen(null, player, featureHud); game.setScreen(screen);
                    settleTicks = 5; step++;
                }
                case 42 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-hud-compact-canvas.png"); press("modernmusic.hud.expand"); settleTicks = 5; step++;
                }
                case 43 -> {
                    if (settleTicks-- > 0) return;
                    capture(game, "smoke-hud-compact-controls.png");
                    var before = featureHud.snapshot(screen.width, screen.height);
                    toggleHudPart(dev.modernmusic.client.HudSettings.Part.COVER);
                    dragSlider("modernmusic.hud.scale", .3); press("gui.cancel");
                    if (!before.equals(featureHud.snapshot(game.getWindow().getGuiScaledWidth(), game.getWindow().getGuiScaledHeight())))
                        throw new IllegalStateException("Cancel did not restore HUD changes");
                    finish(game, "PASS: playback/account/queue regressions; shared themed UI; HUD component visibility, corner resize, snapping, arrow nudging, alignment, per-part reset, cancel, compact layout and persistence; actual in-world HUD with progress hidden / shown; no real SMS sent");
                }
                default -> throw new IllegalStateException("Unknown smoke step");
            }
        } catch (Throwable error) {
            // Only error type and code locations are logged. Real playback URLs must not reach the log.
            ModernMusic.LOGGER.error("Client smoke failed: {} at {}", error.getClass().getName(), java.util.Arrays.toString(error.getStackTrace()));
            finish(game, "FAIL: step=" + step + " state=" + (player == null ? "initializing" : player.message()));
        }
    }

    private static void queueOpenPlayer(Minecraft game) throws ReflectiveOperationException {
        var binding = java.util.Arrays.stream(game.options.keyMappings)
                .filter(key -> key.getName().equals("key.modernmusic.open")).findFirst().orElseThrow();
        // Queue the UI action without relying on physical modifier keys or saved key bindings.
        var clicks = net.minecraft.client.KeyMapping.class.getDeclaredField("clickCount");
        clicks.setAccessible(true);
        clicks.setInt(binding, clicks.getInt(binding) + 1);
    }

    private int verificationFrames;
    private void toggleHudPart(dev.modernmusic.client.HudSettings.Part part) {
        String key = featureHud.component(part).enabled() ? "modernmusic.hud.component_on" : "modernmusic.hud.component_off";
        press(net.minecraft.network.chat.Component.translatable(key, net.minecraft.network.chat.Component.translatable(part.key())).getString());
    }
    private void dragHudPart(dev.modernmusic.client.HudSettings.Part part, int left, int top) {
        press(part.key());
        // Hide the inspector so overlap cannot intercept canvas gestures.
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_H, 0, 0);
        var t = featureHud.component(part); double x = t.left(screen.width, screen.height) + 10, y = t.top(screen.width, screen.height) + 10;
        double dx = left - t.left(screen.width, screen.height), dy = top - t.top(screen.width, screen.height);
        screen.mouseClicked(x, y, 0); screen.mouseDragged(x + dx, y + dy, 0, dx, dy); screen.mouseReleased(x + dx, y + dy, 0);
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_H, 0, 0);
    }
    private void verifyHudEditor(Minecraft game) {
        int w = screen.width, h = screen.height;
        for (var part : dev.modernmusic.client.HudSettings.Part.values()) {
            var before = featureHud.snapshot(w, h); toggleHudPart(part);
            if (featureHud.component(part).enabled() || !featureHud.enabled()) throw new IllegalStateException("Independent visibility toggle failed");
            for (var other : dev.modernmusic.client.HudSettings.Part.values()) if (other != part
                    && !before.parts().get(other).equals(featureHud.snapshot(w, h).parts().get(other)))
                throw new IllegalStateException("Visibility toggle changed another component");
            toggleHudPart(part);
        }
        press("modernmusic.hud.part.cover");
        var cover = featureHud.component(dev.modernmusic.client.HudSettings.Part.COVER);
        double right = cover.left(w, h) + 48 * cover.fitScale(w, h), bottom = cover.top(w, h) + 48 * cover.fitScale(w, h);
        screen.mouseClicked(right, bottom, 0); screen.mouseDragged(right + 24, bottom + 24, 0, 24, 24); screen.mouseReleased(right + 24, bottom + 24, 0);
        if (Math.abs(cover.scale() - 1.35) > .02) throw new IllegalStateException("Corner resize failed");
        int oldLeft = cover.left(w, h), oldTop = cover.top(w, h);
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        if (cover.left(w, h) != oldLeft + 1 || cover.top(w, h) != oldTop + 10) throw new IllegalStateException("Arrow nudge failed");
        press("modernmusic.hud.align_center");
        if (Math.abs(cover.left(w, h) - (w - 48 * cover.scale()) / 2) > 1) throw new IllegalStateException("Center alignment failed");
        press("modernmusic.hud.align_right");
        if (Math.abs(cover.left(w, h) + 48 * cover.scale() - w + 4) > 1) throw new IllegalStateException("Right alignment failed");
        press("modernmusic.hud.align_left"); if (cover.left(w, h) != 4) throw new IllegalStateException("Left alignment failed");
        dragHudPart(dev.modernmusic.client.HudSettings.Part.COVER, 7, 65);
        if (cover.left(w, h) != 4) throw new IllegalStateException("Edge snapping failed");
        var lyrics = featureHud.snapshot(w, h).parts().get(dev.modernmusic.client.HudSettings.Part.LYRICS);
        press("modernmusic.hud.reset_part");
        if (!lyrics.equals(featureHud.snapshot(w, h).parts().get(dev.modernmusic.client.HudSettings.Part.LYRICS))) throw new IllegalStateException("Reset changed other components");
        dragSlider("modernmusic.hud.scale", (.85 - .25) / 2.75); dragSlider("modernmusic.hud.opacity", .15);
        dragHudPart(dev.modernmusic.client.HudSettings.Part.COVER, 24, 50);
        dragHudPart(dev.modernmusic.client.HudSettings.Part.INFO, 82, 50);
        dragHudPart(dev.modernmusic.client.HudSettings.Part.LYRICS, 24, 123);
        dragSlider("modernmusic.hud.lyric_size", (1.05 - .25) / 2.75);
        dragHudPart(dev.modernmusic.client.HudSettings.Part.PROGRESS, 24, 177);
        toggleHudPart(dev.modernmusic.client.HudSettings.Part.PROGRESS);
        press("modernmusic.hud.on"); press("modernmusic.hud.off");
        if (featureHud.component(dev.modernmusic.client.HudSettings.Part.PROGRESS).enabled()) throw new IllegalStateException("Global toggle lost component preference");
        press("modernmusic.hud.part.lyrics");
    }
    private void verifyQueueManagement(Minecraft game) {
        String first = player.library().active().id(); int count = player.queue().entries().size();
        press(player.library().active().name());
        input().setValue("本地队列 A"); press("modernmusic.queues.rename");
        if (!player.library().active().name().equals("本地队列 A") || player.state() != PlaybackController.State.PLAYING)
            throw new IllegalStateException("Queue rename interrupted playback");
        input().setValue("本地队列 B"); press("modernmusic.queues.create");
        if (player.library().queues().size() != 2 || !player.queue().entries().isEmpty()) throw new IllegalStateException("Queue creation failed");
        press("本地队列 A  (" + count + "/400)");
        if (!player.library().active().id().equals(first) || player.queue().entries().size() != count) throw new IllegalStateException("Queue switch lost songs");
        press("gui.back");
        screen.tick();
        var remove = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.visible && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable("modernmusic.queues.remove_song").getString()))
                .skip(1).findFirst().orElseThrow();
        double removeX = guiCoordinate(remove.getX() + 10), removeY = guiCoordinate(remove.getY() + 10);
        screen.mouseClicked(removeX, removeY, 0); screen.mouseReleased(removeX, removeY, 0);
        if (player.queue().entries().size() != count - 1) throw new IllegalStateException("Individual queue removal failed");
        player.play(0);
    }
    private void verifyGameMusic(boolean suppressed) {
        if (GameAudioBridge.SOURCE != net.minecraft.sounds.SoundSource.RECORDS) throw new IllegalStateException("Wrong sound category");
        var music = net.minecraft.sounds.Musics.MENU;
        var selected = new net.neoforged.neoforge.client.event.SelectMusicEvent(music, null);
        NeoForge.EVENT_BUS.post(selected);
        if ((selected.getMusic() == null) != suppressed) throw new IllegalStateException("Music selection suppression failed");
        var sound = net.minecraft.client.resources.sounds.SimpleSoundInstance.forMusic(music.getEvent().value());
        var playing = new net.neoforged.neoforge.client.event.sound.PlaySoundEvent(null, sound);
        NeoForge.EVENT_BUS.post(playing);
        if ((playing.getSound() == null) != suppressed) throw new IllegalStateException("Music sound suppression failed");
        var click = net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1);
        var effect = new net.neoforged.neoforge.client.event.sound.PlaySoundEvent(null, click);
        NeoForge.EVENT_BUS.post(effect);
        if (effect.getSound() == null) throw new IllegalStateException("Non-music sound was suppressed");
    }
    private void verifyGameVolume(Minecraft game) {
        var master = game.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER);
        var records = game.options.getSoundSourceOptionInstance(GameAudioBridge.SOURCE);
        var music = game.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC);
        double oldMaster = master.get(), oldRecords = records.get(), oldMusic = music.get();
        try {
            master.set(0.5); records.set(0.4); music.set(0.0); player.volume(1); audio.tick(game);
            if (Math.abs(output.gain - 0.2f) > 0.001f) throw new IllegalStateException("Category volume multiplication failed");
            if (music.get() != 0) throw new IllegalStateException("Changed saved music volume");
        } finally { master.set(oldMaster); records.set(oldRecords); music.set(oldMusic); player.volume(0); }
    }
    private static final class SilentOutput implements AudioOutput {
        private final JavaSoundOutput device = new JavaSoundOutput();
        private float gain;
        public void load(PcmAudio audio) { device.volume(0); device.load(audio); }
        public void pause(boolean paused) { device.pause(paused); }
        public void seek(long position) { device.seek(position); }
        public void volume(float volume) { gain = volume; device.volume(0); }
        public void stop() { device.stop(); }
        public Snapshot snapshot() { return device.snapshot(); }
        public void close() { device.close(); }
    }
    private boolean ready(BooleanSupplier condition) { return condition.getAsBoolean(); }

    private void press(String key) {
        String label = net.minecraft.network.chat.Component.translatable(key).getString();
        screen.tick();
        Button target = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing active button: " + key + " in " + screen.getClass().getSimpleName()));
        double x = target.getX() + target.getWidth() / 2.0, y = target.getY() + target.getHeight() / 2.0;
        x = guiCoordinate(x); y = guiCoordinate(y);
        if (!screen.mouseClicked(x, y, 0)) throw new IllegalStateException("Button hit missed");
        screen.mouseReleased(x, y, 0);
        screen = Minecraft.getInstance().screen;
    }

    private EditBox input() {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private EditBox secret(String key) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents content && content.getKey().equals(key))
                .findFirst().orElseThrow();
    }
    private AccountController fixtureAccount(Minecraft game) {
        return new AccountController(fixtureAccounts, new RememberedSessionStore(Path.of("smoke-account-fixture")), game::execute, player::clear, Clock.systemUTC());
    }
    private static final class FixtureAccountProvider implements AccountProvider {
        volatile boolean authorized;
        volatile boolean verificationRequired;
        volatile boolean verificationLink = true;
        final java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        volatile java.net.URI avatar;
        public AccountSession validate(SessionCookie cookie) { return new AccountSession("42", "测试账号 · Fixture", cookie, Instant.now(), avatar); }
        public QrChallenge beginQr() { return new QrChallenge("modernmusic-smoke-not-a-real-login", Instant.now().plusSeconds(120)); }
        public QrPoll pollQr(QrChallenge challenge) {
            return authorized ? new QrPoll(QrState.AUTHORIZED, SessionCookie.parse("MUSIC_U=smoke-fixture-not-a-real-cookie"))
                    : new QrPoll(verificationRequired ? QrState.VERIFY_REQUIRED : QrState.WAITING, null,
                            verificationRequired && verificationLink ? java.net.URI.create("https://music.163.com/st/fixture-verification") : null);
        }
        public void sendSms(PhoneLogin phone) {
            if (!phone.equals(new PhoneLogin("1", "2025550123"))) throw new IllegalStateException("Unexpected fixture phone");
            sends.incrementAndGet();
        }
        public AccountSession loginSms(PhoneLogin phone, String code) {
            if (code.equals("100004")) throw new AuthException("login_rejected_10004");
            if (!phone.equals(new PhoneLogin("1", "2025550123")) || !code.equals("123456")) throw new AuthException("sms_login_failed");
            return validate(SessionCookie.parse("MUSIC_U=smoke-fixture-not-a-real-cookie"));
        }
    }
    private void verifyRenderedQr(Minecraft game) throws Exception {
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            int[] rgb = new int[frame.getWidth() * frame.getHeight()];
            for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
                int abgr = frame.getPixelRGBA(x, y);
                rgb[y * frame.getWidth() + x] = 0xFF000000 | (abgr & 255) << 16 | abgr & 0xFF00 | (abgr >> 16 & 255);
            }
            var source = new com.google.zxing.RGBLuminanceSource(frame.getWidth(), frame.getHeight(), rgb);
            var decoded = new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source)));
            if (!decoded.getText().equals("https://music.163.com/login?codekey=modernmusic-smoke-not-a-real-login"))
                throw new IllegalStateException("Rendered QR did not match fixture");
        }
    }

    private double guiCoordinate(double coordinate) {
        return screen instanceof dev.modernmusic.client.ui.MusicScreen music ? music.toGuiCoordinate(coordinate) : coordinate;
    }

    private void dragSlider(String key, double fraction) {
        screen.tick();
        AbstractSliderButton slider = screen.children().stream().filter(AbstractSliderButton.class::isInstance)
                .map(AbstractSliderButton.class::cast).filter(widget -> widget.getMessage().getContents()
                        instanceof TranslatableContents content && content.getKey().equals(key)).findFirst().orElseThrow();
        double x = slider.getX() + 4, y = slider.getY() + slider.getHeight() / 2.0;
        double destination = x + fraction * (slider.getWidth() - 8);
        x = guiCoordinate(x); y = guiCoordinate(y); destination = guiCoordinate(destination);
        if (!screen.mouseClicked(x, y, 0)) throw new IllegalStateException("Slider hit missed");
        screen.mouseDragged(destination, y, 0, destination - x, 0);
        screen.mouseReleased(destination, y, 0);
    }

    private void capture(Minecraft game, String name) throws java.io.IOException {
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget && widget.visible && (widget.getX() < 0 || widget.getY() < 0
                    || widget.getRight() > screen.width || widget.getBottom() > screen.height)) {
                throw new IllegalStateException("Widget outside viewport");
            }
        }
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) { frame.writeToFile(Path.of(name)); }
    }

    private void finish(Minecraft game, String result) {
        step = -1;
        try { Files.writeString(Path.of("smoke-result.txt"), result + System.lineSeparator()); }
        catch (java.io.IOException exception) { ModernMusic.LOGGER.error("Could not save client smoke result"); }
        ModernMusic.LOGGER.info("{}", result);
        if (account != null) account.close();
        if (player != null) player.close();
        game.stop();
    }
}
