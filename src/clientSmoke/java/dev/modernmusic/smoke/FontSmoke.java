package dev.modernmusic.smoke;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.ui.LazyFontSet;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.font.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

/** Real GL/provider comparison with the previous eager FontSet, including a resource reload. */
public final class FontSmoke {
    private static final float[] SCALES = {2, 1.5f, 3.75f};
    private int scenario, startupTicks;
    private boolean started, done;
    private Comparison screen;
    private com.mojang.blaze3d.platform.NativeImage referenceFrame;
    private CompletableFuture<Void> reload;
    private final long deadline = System.nanoTime() + 240_000_000_000L;
    public FontSmoke() {
        if (Boolean.getBoolean("modernmusic.fontSmoke")) MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (done) return;
        var game = Minecraft.getInstance();
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Font smoke timed out");
            if (game.getOverlay() != null) return;
            if (!started) {
                 game.options.guiScale().set(2);
                game.options.pauseOnLostFocus = false; game.getWindow().setWindowed(1280, 720); game.resizeDisplay();
                started = true; startupTicks = 5; return;
            }
            if (startupTicks-- > 0) return;
            if (reload != null) { if (!reload.isDone()) return; reload.join(); reload = null; }
            if (screen != null) {
                // Client catch-up can execute ten ticks before the next render. Font construction
                // is deliberately expensive here, so tick counts cannot prove either image was drawn.
                if (screen.renderedFrames < 4) return;
                if (referenceFrame == null) {
                    referenceFrame = Screenshot.takeScreenshot(game.getMainRenderTarget());
                    screen.compareNew = true; screen.renderedFrames = 0; return;
                }
                verifyPixels(game);
                screen.verifyObfuscated();
                screen.close(); screen = null; scenario++;
                game.setScreen(new TitleScreen());
                if (scenario == 12) { reload = game.reloadResourcePacks(); return; }
                if (scenario == 13) { finish("PASS: 13 pixel-identical eager/lazy font comparisons; Chinese/Latin/kana/rare glyph metrics; fractional scales; obfuscated index and atlas lifetime; resource reload"); return; }
            }
            screen = new Comparison(SCALES[scenario % 3], scenario % 4);
            game.setScreen(screen);
        } catch (Throwable error) {
            ModernMusic.LOGGER.error("Font smoke failed in scenario {}", scenario, error);
            finish("FAIL: scenario=" + scenario + " " + error);
        }
    }
    private void verifyPixels(Minecraft game) throws Exception {
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            int half = frame.getWidth() / 2, ink = 0;
            for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < half; x++) {
                // Use identical screen coordinates across frames: italic vertices at fractional scales
                // can round differently on the left and right halves of a 1.20.1 framebuffer.
                int left = referenceFrame.getPixelRGBA(x, y), right = frame.getPixelRGBA(x, y);
                if (left != right) {
                    referenceFrame.writeToFile(Path.of("font-reference-" + scenario + ".png"));
                    frame.writeToFile(Path.of("font-mismatch-" + scenario + ".png"));
                    throw new AssertionError("Pixels differ at " + x + "," + y);
                }
                if (left != frame.getPixelRGBA(0, 0)) ink++;
            }
            if (ink < 100) {
                referenceFrame.writeToFile(Path.of("font-reference-" + scenario + ".png"));
                frame.writeToFile(Path.of("font-empty-" + scenario + ".png"));
                throw new AssertionError("No glyph pixels rendered");
            }
            if (scenario == 0 || scenario == 12) frame.writeToFile(Path.of("font-equality-" + scenario + ".png"));
        } finally { referenceFrame.close(); referenceFrame = null; }
    }
    private void finish(String result) {
        done = true;
        if (screen != null) screen.close();
        if (referenceFrame != null) { referenceFrame.close(); referenceFrame = null; }
        try { Files.writeString(Path.of("font-result.txt"), result + System.lineSeparator()); }
        catch (Exception error) { ModernMusic.LOGGER.error("Cannot save font result", error); }
        ModernMusic.LOGGER.info("{}", result); Minecraft.getInstance().stop();
    }
    private static final class Comparison extends Screen implements AutoCloseable {
        private final GlyphProvider provider;
        private final FontSet eager;
        private final LazyFontSet lazy;
        private final Font oldFont, newFont;
        private boolean compareNew;
        private int renderedFrames;

        private final List<GlyphProvider> providers;
        Comparison(float scale, int bits) throws Exception {
            super(Component.literal("Font comparison"));
            var game = Minecraft.getInstance();
            provider = dev.modernmusic.client.ui.EmGlyphProvider.load(game.getResourceManager(),
                    new ResourceLocation("modernmusic", "ui_sans.ttf"), 9.5f, scale, Math.round(.5f * scale) / scale);
            float ideographAdvance = provider.getGlyph('晴').getAdvance();
            if (Math.abs(ideographAdvance - Math.round(9.5f * scale) / scale) > .01f)
                throw new AssertionError("UI font no longer uses EM sizing: " + ideographAdvance);
            providers = new ArrayList<>(); providers.add(provider);
            providers.addAll(game.font.getFontSet(Style.DEFAULT_FONT).providers);
            eager = new FontSet(game.getTextureManager(), new ResourceLocation("modernmusic", "smoke_eager"));
            lazy = new LazyFontSet(game.getTextureManager(), new ResourceLocation("modernmusic", "smoke_lazy"));
            eager.reload(providers); lazy.reload(providers);
            // Exercise option changes on an existing set, not only on newly created variants.
            eager.providers.clear(); eager.reload(providers); lazy.reload(providers);
            var codes = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(provider.getSupportedGlyphs());
            for (int cp : new int[]{0, 0x20, 0x301, 0x4e00, 0x9fff, 0x20000, 0x1f642, 0x10ffff}) codes.add(cp);
            for (int cp = 0; cp <= 0x10ffff; cp += 997) codes.add(cp);
            for (int cp : codes) for (boolean filter : new boolean[]{false, true}) {
                var a = eager.getGlyphInfo(cp, filter); var b = lazy.getGlyphInfo(cp, filter);
                if (a.getAdvance() != b.getAdvance() || a.getBoldOffset() != b.getBoldOffset() || a.getShadowOffset() != b.getShadowOffset())
                    throw new AssertionError("Metrics differ for U+" + Integer.toHexString(cp));
            }
            oldFont = new Font(id -> id.equals(Style.DEFAULT_FONT) ? eager : game.font.getFontSet(id), false);
            newFont = new Font(id -> id.equals(Style.DEFAULT_FONT) ? lazy : game.font.getFontSet(id), false);
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0xff101218);
            draw(g, compareNew ? newFont : oldFont, 0); draw(g, newFont, width / 2); g.flush();
            renderedFrames++;
        }
        private void draw(GuiGraphics g, Font font, int offset) {
            String[] lines = {"Modern Music 0123456789", "晴天 · 测试歌曲 / 帳號與歌詞", "かな カナ 日本語 音楽", "Fallback: 𠀀 🙂 Ω Ж ß á", "§lBold 粗体 §r§oItalic 斜体", "§nUnderline 下划线 §r§mStrike", "§c彩色 §aColor §9文字 §rText", "§kHidden§r visible"};
            // Randomized obfuscated glyphs are checked separately; deterministic text must match bit for bit.
            for (int i = 0; i < lines.length - 1; i++) {
                g.drawString(font, lines[i], offset + 12, 22 + i * 30, 0xffeeeeee, (i & 1) != 0);
            }
        }
        void verifyObfuscated() throws Exception {
            var before = lazy.getGlyph('A');
            lazy.getRandomGlyph(lazy.getGlyphInfo('A', false));
            if (before != lazy.getGlyph('A')) throw new AssertionError("Obfuscated index invalidated a queued atlas glyph");
            var index = FontSet.class.getDeclaredField("glyphsByWidth"); index.setAccessible(true);
            if (!index.get(eager).equals(index.get(lazy))) throw new AssertionError("Obfuscated candidate pool changed");
        }
        public void close() { eager.providers.clear(); eager.close(); lazy.close(); provider.close(); }
    }
}
