package dev.modernmusic.smoke;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.ui.LazyFontSet;
import dev.modernmusic.client.ui.UiFont;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.font.*;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Real GL/provider comparison with the previous eager FontSet, including a resource reload. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class FontSmoke {
    private static final float[] SCALES = {1, 2, 1.5f, 3.75f};
    private int scenario, frames;
    private boolean started, done;
    private Comparison screen;
    private CompletableFuture<Void> reload;
    private final long deadline = System.nanoTime() + 240_000_000_000L;
    public FontSmoke() {
        if (Boolean.getBoolean("modernmusic.fontSmoke")) NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        var game = Minecraft.getInstance();
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Font smoke timed out");
            if (game.getOverlay() != null) return;
            if (!started) {
                var selector = FontSet.class.getDeclaredMethod("selectProviders", List.class, Set.class);
                if (!java.lang.reflect.Modifier.isPrivate(selector.getModifiers()))
                    throw new AssertionError("FontSet.selectProviders must stay private for font optimizer mixins");
                game.options.onboardingAccessibilityFinished(); game.options.guiScale().set(2);
                game.options.pauseOnLostFocus = false; game.getWindow().setWindowed(1280, 720); game.resizeDisplay();
                started = true; frames = 5; return;
            }
            if (frames-- > 0) return;
            if (reload != null) { if (!reload.isDone()) return; reload.join(); reload = null; }
            if (screen != null) {
                if (screen.renderedFrames < 4) return;
                verifyPixels(game);
                screen.verifyObfuscated();
                screen.close(); screen = null; scenario++;
                game.setScreen(new TitleScreen());
                if (scenario == 16) { reload = game.reloadResourcePacks(); return; }
                if (scenario == 17) { finish("PASS: 17 pixel-identical eager/lazy font comparisons including 1x; Chinese/Latin/kana/rare glyph metrics; four font-option combinations; fractional scales; obfuscated index and atlas lifetime; UiFont smooth/pixel mode at 1x before and after resource reload"); return; }
            }
            if (scenario == 0 || scenario == 16) verifyNativeScaleFont(game);
            game.options.guiScale().set(scenario % 4 == 0 ? 1 : 2); game.resizeDisplay();
            screen = new Comparison(SCALES[scenario % 4], scenario / 4 % 4);
            game.setScreen(screen); frames = 4;
        } catch (Throwable error) {
            ModernMusic.LOGGER.error("Font smoke failed in scenario {}", scenario, error);
            finish("FAIL: scenario=" + scenario + " " + error);
        }
    }
    private void verifyPixels(Minecraft game) throws Exception {
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            int half = frame.getWidth() / 2, ink = 0;
            for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < half; x++) {
                int left = frame.getPixelRGBA(x, y), right = frame.getPixelRGBA(x + half, y);
                if (left != right) { frame.writeToFile(Path.of("font-mismatch-" + scenario + ".png")); throw new AssertionError("Pixels differ at " + x + "," + y); }
                if (left != frame.getPixelRGBA(0, 0)) ink++;
            }
            if (ink < 100) throw new AssertionError("No glyph pixels rendered");
            if (scenario == 0 || scenario == 16) frame.writeToFile(Path.of("font-equality-" + scenario + ".png"));
        }
    }
    private void verifyNativeScaleFont(Minecraft game) {
        game.options.guiScale().set(1); game.resizeDisplay();
        UiFont.smooth(true);
        if (UiFont.get() == game.font || UiFont.get(1.5) == game.font)
            throw new AssertionError("Smooth font fell back to bitmap text at 1x");
        UiFont.smooth(false);
        if (UiFont.get() != game.font) throw new AssertionError("Explicit pixel-font setting was ignored");
        UiFont.smooth(true);
    }
    private void finish(String result) {
        done = true;
        Minecraft.getInstance().setScreen(new TitleScreen());
        if (screen != null) screen.close();
        try { Files.writeString(Path.of("font-result.txt"), result + System.lineSeparator()); }
        catch (Exception error) { ModernMusic.LOGGER.error("Cannot save font result", error); }
        ModernMusic.LOGGER.info("{}", result); Minecraft.getInstance().stop();
    }
    private static final class Comparison extends Screen implements AutoCloseable {
        private int renderedFrames;
        private final GlyphProvider provider;
        private final FontSet eager;
        private final LazyFontSet lazy;
        private final Font oldFont, newFont;
        private final Set<FontOption> options;
        private final List<GlyphProvider.Conditional> providers;
        Comparison(float scale, int bits) throws Exception {
            super(Component.literal("Font comparison"));
            var game = Minecraft.getInstance();
            var definition = new TrueTypeGlyphProviderDefinition(ResourceLocation.fromNamespaceAndPath("modernmusic", "ui_sans.ttf"),
                    9.5f, scale, new TrueTypeGlyphProviderDefinition.Shift(0, Math.round(.5f * scale) / scale), "");
            provider = definition.unpack().left().orElseThrow().load(game.getResourceManager());
            providers = new ArrayList<>(); providers.add(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS));
            providers.addAll(game.font.getFontSet(Style.DEFAULT_FONT).allProviders);
            options = EnumSet.noneOf(FontOption.class);
            if ((bits & 1) != 0) options.add(FontOption.UNIFORM);
            if ((bits & 2) != 0) options.add(FontOption.JAPANESE_VARIANTS);
            eager = new FontSet(game.getTextureManager(), ResourceLocation.fromNamespaceAndPath("modernmusic", "smoke_eager"));
            lazy = new LazyFontSet(game.getTextureManager(), ResourceLocation.fromNamespaceAndPath("modernmusic", "smoke_lazy"));
            eager.reload(providers, options); lazy.reload(providers, options);
            // Exercise option changes on an existing set, not only on newly created variants.
            eager.reload(Set.of()); lazy.reload(Set.of()); eager.reload(options); lazy.reload(options);
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
            draw(g, oldFont, 0); draw(g, newFont, width / 2); g.flush();
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
            var delegate = LazyFontSet.class.getDeclaredField("obfuscated"); delegate.setAccessible(true);
            if (delegate.get(lazy) != null) throw new AssertionError("Obfuscated font was built before it was needed");
            var before = lazy.getGlyph('A');
            lazy.getRandomGlyph(lazy.getGlyphInfo('A', false));
            if (before != lazy.getGlyph('A')) throw new AssertionError("Obfuscated index invalidated a queued atlas glyph");
            var obfuscated = (FontSet) delegate.get(lazy);
            var index = FontSet.class.getDeclaredField("glyphsByWidth"); index.setAccessible(true);
            if (!index.get(eager).equals(index.get(obfuscated))) throw new AssertionError("Obfuscated candidate pool changed");
            lazy.getRandomGlyph(lazy.getGlyphInfo('A', false));
            if (delegate.get(lazy) != obfuscated) throw new AssertionError("Obfuscated font was rebuilt for every glyph");
            var textures = FontSet.class.getDeclaredField("textures"); textures.setAccessible(true);
            lazy.reload(Set.of());
            if (delegate.get(lazy) != null || !((List<?>) textures.get(obfuscated)).isEmpty())
                throw new AssertionError("Font option reload retained an obsolete obfuscated atlas");
            lazy.getRandomGlyph(lazy.getGlyphInfo('A', false));
            obfuscated = (FontSet) delegate.get(lazy);
            eager.reload(Set.of());
            if (!index.get(eager).equals(index.get(obfuscated))) throw new AssertionError("Obfuscated font ignored new options");
            lazy.close();
            if (delegate.get(lazy) != null || !((List<?>) textures.get(obfuscated)).isEmpty()
                    || !((List<?>) textures.get(lazy)).isEmpty())
                throw new AssertionError("Closing the font retained glyph atlases");
        }
        public void close() { eager.close(); lazy.close(); provider.close(); }
    }
}
