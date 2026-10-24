package dev.modernmusic.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.*;
import dev.modernmusic.client.audio.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Deterministic lifecycle smoke test with generated silent audio and no account or network access. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class LifecycleSmoke {
    private PlaybackController player;
    private int step, cycle, worlds, frames;
    private long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
    private CompletableFuture<Void> reload;
    private final List<DynamicTexture> textures = new ArrayList<>();
    private final List<CompletableFuture<?>> cancelledImages = new ArrayList<>();
    private final Map<String, KeyMapping> keys = new HashMap<>();
    private boolean initialized;
    private int loggedStep = -1;
    private long stepDeadline;
    private Thread audioThread;
    public LifecycleSmoke() {
        if (Boolean.getBoolean("modernmusic.lifecycleSmoke")) NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(ClientTickEvent.Post event) {
        if (step < 0) return;
        Minecraft game = Minecraft.getInstance();
        try {
            if (step != loggedStep) {
                loggedStep = step; stepDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
                if (step < 20 || step > 23) ModernMusic.LOGGER.info("M6 step={} cycle={} player={}", step, cycle, player == null ? "initial" : player.state());
            }
            if (System.nanoTime() > stepDeadline) throw new IllegalStateException("Step timeout: " + step + " player=" + (player == null ? "initial" : player.state())
                    + " screen=" + (game.screen == null ? "none" : game.screen.getClass().getSimpleName()));
            if (System.nanoTime() > deadline) throw new IllegalStateException("Lifecycle timeout at " + step);
            if (game.getOverlay() != null) return;
            if (step == 0) {
                if (game.screen instanceof AccessibilityOnboardingScreen) { game.options.onboardingAccessibilityFinished(); game.setScreen(new TitleScreen()); }
                if (!(game.screen instanceof TitleScreen)) return;
                if (!game.options.languageCode.equals("zh_cn")) {
                    game.options.languageCode = "zh_cn"; game.getLanguageManager().setSelected("zh_cn"); game.reloadResourcePacks(); return;
                }
                game.options.pauseOnLostFocus = false; game.options.renderDistance().set(2); game.options.simulationDistance().set(5);
                game.options.framerateLimit().set(60); game.options.guiScale().set(2); game.getWindow().setWindowed(1000, 680);
                game.createWorldOpenFlows().createFreshLevel("m6-lifecycle-" + System.currentTimeMillis(),
                        new net.minecraft.world.level.LevelSettings("M6 lifecycle", net.minecraft.world.level.GameType.CREATIVE, false,
                                net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                        new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false),
                        registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                frames = 40; step = 1; return;
            }
            if (step == 1) {
                if (game.level == null || game.player == null || RoomClient.instance() == null || game.screen != null) return;
                // World-entry mouse capture clears held/clicked keys after the initial screen closes.
                if (frames-- > 0) return;
                if (!initialized) installFixture(game);
                if (!player.queuesReady()) return;
                bindKeys(game);
                game.setScreen(null); player.clear(); player.add("42"); step = 2; return;
            }
            if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException(player.message());
            switch (step) {
                case 2 -> {
                    if (player.audio().positionMs() < 40) return;
                    audioThread = (Thread) field(JavaSoundOutput.class, "worker").get(field(PlaybackController.class, "output").get(player));
                    check(audioThread != null && audioThread.isAlive(), "First playback did not start audio worker");
                    click("pause"); step++;
                }
                case 3 -> {
                    if (player.state() != PlaybackController.State.PAUSED) return;
                    click("pause"); step++;
                }
                case 4 -> {
                    if (player.state() != PlaybackController.State.PLAYING) return;
                    player.add("43"); click("next"); step++;
                }
                case 5 -> {
                    if (player.queue().index() != 1 || player.state() != PlaybackController.State.PLAYING) return;
                    click("stop"); step++;
                }
                case 6 -> {
                    if (player.state() != PlaybackController.State.STOPPED) return;
                    check(player.bufferedBytes() == 0, "Stop retained PCM");
                    click("open"); step++;
                }
                case 7 -> {
                    if (!(game.screen instanceof PlayerScreen)) return;
                    click("pause"); frames = 4; step++;
                }
                case 8 -> {
                    if (frames-- > 0) return;
                    check(player.state() == PlaybackController.State.STOPPED, "Hotkey triggered in text screen");
                    game.setScreen(null); frames = 4; step++;
                }
                case 9 -> {
                    if (frames-- > 0) return;
                    check(player.state() == PlaybackController.State.STOPPED, "Stale key replayed after closing screen");
                    click("hud"); frames = 4; step++;
                }
                case 10 -> {
                    if (frames-- > 0) return;
                    var stored = new HudSettings(game.gameDirectory.toPath().resolve("config/modernmusic-hud.json"));
                    check(!stored.enabled(), "HUD hotkey was not persisted"); click("hud");
                    player.play(0); step++;
                }
                case 11 -> {
                    if (player.state() != PlaybackController.State.PLAYING) return;
                    seedTextures(game);
                    game.setScreen(new PlayerScreen(null, player));
                    press(game, "modernmusic.cache.title"); frames = 5; step++;
                }
                case 12 -> {
                    if (frames-- > 0) return;
                    capture(game, "m6-cache.png"); press(game, "modernmusic.cache.clear");
                    checkReleased(); check(player.bufferedBytes() == 0 && player.queue().entries().size() == 2, "Cache changed queue / retained PCM");
                    game.getWindow().setWindowed(680, 480); game.resizeDisplay(); frames = 5; step++;
                }
                case 13 -> {
                    if (frames-- > 0) return;
                    capture(game, "m6-cache-compact.png"); game.setScreen(null); step = 20;
                }
                case 20 -> { player.play(cycle % 2); step = 21; }
                case 21 -> {
                    if (player.audio().positionMs() < 40) return;
                    seedTextures(game);
                    if (cycle % 12 == 0) { reload = game.reloadResourcePacks(); step = 22; }
                    else { player.clearTemporaryCache(); ArtworkCache.clearIfCreated(); step = 23; }
                }
                case 22 -> {
                    if (!reload.isDone()) return;
                    reload.join(); check(player.state() == PlaybackController.State.STOPPED, "Reload did not stop production player"); step = 23;
                }
                case 23 -> {
                    checkReleased(); check(player.bufferedBytes() == 0, "Cycle retained PCM");
                    check(player.queue().entries().size() == 2, "Cycle erased queue");
                    cycle++;
                    if (cycle % 20 == 0) ModernMusic.LOGGER.info("M6 lifecycle: {} play/clear cycles, {} worlds", cycle, worlds + 1);
                    if (cycle % 40 != 0) { step = 20; return; }
                    player.play(0); step = 24;
                }
                case 24 -> {
                    if (player.audio().positionMs() < 40) return;
                    seedTextures(game); game.level.disconnect(); game.disconnect(new TitleScreen()); game.setScreen(new TitleScreen()); frames = 5; step = 25;
                }
                case 25 -> {
                    if (frames-- > 0) return;
                    checkReleased(); check(player.bufferedBytes() == 0 && player.state() == PlaybackController.State.STOPPED, "Logout retained playback");
                    check(player.queue().entries().size() == 2, "Logout erased queue");
                    worlds++;
                    if (worlds < 3) { step = 0; return; }
                    player.close(); frames = 10; step = 26;
                }
                case 26 -> {
                    if (frames-- > 0) return;
                    check(!audioThread.isAlive(), "Audio thread survived close");
                    finish(game, "PASS: 120 playback/clear cycles; 10 actual resource reloads; 3 integrated worlds and playing disconnects; 2880 dynamic textures freed; pending image cancellation; real silent JavaSound; keyboard controls and screen isolation; Chinese cache UI; persisted queues/HUD");
                }
                default -> throw new IllegalStateException("Unknown step " + step);
            }
        } catch (Throwable error) {
            ModernMusic.LOGGER.error("M6 lifecycle failed at {}", step, error);
            finish(game, "FAIL: step=" + step + " type=" + error.getClass().getSimpleName());
        }
    }
    private void installFixture(Minecraft game) throws Exception {
        player = (PlaybackController) field(RoomClient.class, "player").get(RoomClient.instance());
        var provider = field(PlaybackController.class, "provider"); ((MusicProvider) provider.get(player)).close();
        var transport = field(PlaybackController.class, "transport"); ((BoundedHttpTransport) transport.get(player)).close();
        byte[] mp3 = silentMp3();
        provider.set(player, new MusicProvider() {
            public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "生命周期测试", List.of("静音测试"), 1044)); }
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m801.music.126.net/fixture.mp3"), Instant.now().plusSeconds(60))); }
            public CompletableFuture<Lyrics> getLyrics(SongId id) { return CompletableFuture.completedFuture(Lyrics.EMPTY); }
            public void close() {}
        });
        transport.set(player, (HttpTransport) (uri, max) -> mp3); player.volume(0);
        var output = (JavaSoundOutput) field(PlaybackController.class, "output").get(player);
        check(field(JavaSoundOutput.class, "worker").get(output) == null, "Unused production player started audio worker");
        initialized = true;
    }
    private void bindKeys(Minecraft game) {
        int code = org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
        for (var key : game.options.keyMappings) if (key.getName().startsWith("key.modernmusic.")) {
            String name = key.getName().substring("key.modernmusic.".length()); keys.put(name, key);
            if (!name.equals("open")) check(key.getDefaultKey().getValue() == -1, "New hotkey should be unbound by default");
            key.setKey(InputConstants.Type.KEYSYM.getOrCreate(code++));
        }
        check(keys.size() == 5, "Hotkeys missing"); KeyMapping.resetMapping(); game.options.save();
    }
    private static byte[] silentMp3() {
        var out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            byte[] frame = new byte[417]; frame[0] = (byte) 0xff; frame[1] = (byte) 0xfb; frame[2] = (byte) 0x90; out.writeBytes(frame);
        }
        return out.toByteArray();
    }
    private void seedTextures(Minecraft game) throws Exception {
        ArtworkCache.clearIfCreated(); textures.clear(); cancelledImages.clear();
        @SuppressWarnings("unchecked") var entries = (Map<URI, Object>) field(ArtworkCache.class, "entries").get(ArtworkCache.get());
        Class<?> type = Class.forName("dev.modernmusic.client.ui.ArtworkCache$Entry");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        for (int i = 0; i < 24; i++) {
            URI uri = URI.create("https://p1.music.126.net/fixture" + i + ".png"); Object entry = constructor.newInstance();
            var image = new NativeImage(256, 256, true); var texture = new DynamicTexture(image); textures.add(texture);
            field(type, "texture").set(entry, game.getTextureManager().register("m6-fixture", texture));
            var pending = new CompletableFuture<int[]>(); cancelledImages.add(pending); field(type, "pending").set(entry, pending);
            pending.whenComplete((value, error) -> entries.get(uri)); // Exercise synchronous completion during clear.
            entries.put(uri, entry);
        }
        check(ArtworkCache.textureCount() == 24, "Fixture textures not registered");
    }
    private void checkReleased() {
        check(ArtworkCache.textureCount() == 0, "Texture cache retained entries");
        for (var texture : textures) check(texture.getPixels() == null, "NativeImage retained after release");
        for (var future : cancelledImages) check(future.isCancelled(), "Pending image not cancelled");
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void click(String name) {
        check(!keys.get(name).isUnbound(), "Fixture key was reset: " + name);
        KeyMapping.click(keys.get(name).getKey());
    }
    private static void press(Minecraft game, String key) {
        String label = net.minecraft.network.chat.Component.translatable(key).getString();
        var button = game.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow();
        check(button.active && button.visible, "Inactive button " + key); button.onPress();
    }
    private static void capture(Minecraft game, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(game.gameDirectory.toPath().resolve(name)); }
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private void finish(Minecraft game, String result) {
        step = -1;
        try { Files.writeString(game.gameDirectory.toPath().resolve("lifecycle-result.txt"), result + "\n"); }
        catch (Exception failure) { ModernMusic.LOGGER.error("Cannot write M6 marker", failure); }
        ModernMusic.LOGGER.info("{}", result); game.stop();
    }
}
