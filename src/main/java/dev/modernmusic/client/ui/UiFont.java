package dev.modernmusic.client.ui;

import com.mojang.blaze3d.font.GlyphProvider;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.HudSettings;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;

/**
 * Smooth UI text. The bundled TTF is rasterized at the exact physical pixel scale it is drawn at,
 * so glyphs map 1:1 onto screen pixels; characters outside the subset use the vanilla providers.
 * Font sets are built lazily for the scales in use and rebuilt after the vanilla font reloads.
 */
public final class UiFont {
    private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath(ModernMusic.MOD_ID, "ui_sans.ttf");
    static final float SIZE = 9.5f;
    // Centers ideographs on the vanilla 0–8 glyph box; snapped per scale so outlines stay on the pixel grid.
    private static final float SHIFT_Y = .5f;
    // Native UI, menu body/title and the three independently scaled HUD text components.
    private static final int MAX_OVERSAMPLE = 12, SCALE_STEPS = 64, CACHED = 6;
    private static final long REBUILD_INTERVAL_NANOS = 400_000_000L;
    private static final Font[] fonts = new Font[MAX_OVERSAMPLE * SCALE_STEPS + 1];
    private static final Variant[] variants = new Variant[fonts.length];
    private static final long[] used = new long[fonts.length];
    private static FontSet vanillaDefault;
    private static Set<FontOption> options = Set.of();
    private static boolean smooth = true, failed;
    // Zero means the native HUD viewport; positive values mark a scoped music menu, even at exactly 1x.
    private static double layoutScale;
    private static long lastBuild;
    private static int optionBits = -1;
    private static final int[] warmScales = new int[CACHED];
    private static int warmIndex;
    private static long warmAfter;

    private record Variant(GlyphProvider provider, FontSet set) {
        void close() { set.close(); provider.close(); }
    }

    private UiFont() {}

    public static boolean smooth() { return smooth; }
    public static void smooth(boolean value) { smooth = value; }

    /** Scoped by MusicScreen during layout and rendering; HUD text keeps the native GUI scale. */
    static double layoutScale(double value) {
        double previous = layoutScale; layoutScale = value; return previous;
    }

    /** UI text at the active viewport scale; the pixel font only when disabled or if the TTF failed. */
    public static Font get() { return get(1); }

    /** Text that the pose additionally scales, e.g. a resized HUD component. */
    public static Font get(double poseScale) {
        var game = Minecraft.getInstance();
        double physicalScale = physicalScale();
        int gui = guiScale();
        if (!smooth || failed) return game.font;
        synchronizeFonts(game);
        int oversample = scaleKey(physicalScale * poseScale);
        // Sliders sweep through scales; reuse the closest atlas instead of building one per step.
        if (layoutScale == 0 && oversample != gui && variants[oversample] == null && System.nanoTime() - lastBuild < REBUILD_INTERVAL_NANOS)
            oversample = nearestBuilt(oversample, gui);
        used[oversample] = System.nanoTime();
        if (fonts[oversample] == null) {
            final int size = oversample;
            fonts[size] = new Font(id -> Style.DEFAULT_FONT.equals(id) ? set(size) : Minecraft.getInstance().font.getFontSet(id), false);
        }
        set(oversample);
        return failed ? game.font : fonts[oversample];
    }

    /**
     * Warm the actual menu viewport and saved HUD sizes while the front end/world loading screen is visible.
     * A resize must settle first; build at most one extra variant per tick. Never insert this work into an
     * already running world merely because a hidden menu might need it. Reloads reset the pending warmup.
     */
    public static void prewarm(HudSettings hud) {
        var game = Minecraft.getInstance();
        if (!smooth || failed || hud == null || game.getOverlay() != null) return;
        if (game.level != null && !(game.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen)) return;
        var window = game.getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        double gui = window.getGuiScale();
        double menu = MusicScreenLayout.fit(window.getWidth(), window.getHeight()).pixelsPerUnit();
        hud.ensureLayout(width, height);
        boolean changed = warmScale(0, gui) | warmScale(1, menu) | warmScale(2, menu * 1.5);
        changed |= warmHudScale(3, hud, HudSettings.Part.INFO, gui, width, height);
        changed |= warmHudScale(4, hud, HudSettings.Part.LYRICS, gui, width, height);
        changed |= warmHudScale(5, hud, HudSettings.Part.PROGRESS, gui, width, height);
        long now = System.nanoTime();
        if (changed) { warmIndex = 0; warmAfter = now + REBUILD_INTERVAL_NANOS; }
        if (now < warmAfter) return;
        synchronizeFonts(game);
        // A menu/HUD editor may have evicted a target since the last visit to the front end.
        for (int checked = 0; checked < warmScales.length; checked++) {
            int key = warmScales[warmIndex];
            warmIndex = (warmIndex + 1) % warmScales.length;
            if (key != 0 && variants[key] == null) { set(key); return; }
        }
    }

    private static void synchronizeFonts(Minecraft game) {
        FontSet vanilla = game.font.getFontSet(Style.DEFAULT_FONT);
        // Do this before option reloads: old variants may still refer to closed vanilla providers.
        if (vanilla != vanillaDefault) { clear(); vanillaDefault = vanilla; }
        int bits = (game.options.forceUnicodeFont().get() ? 1 : 0) | (game.options.japaneseGlyphVariants().get() ? 2 : 0);
        if (bits != optionBits) {
            optionBits = bits;
            options = fontOptions(game);
            for (var variant : variants) if (variant != null) variant.set.reload(options);
        }
    }

    private static boolean warmHudScale(int slot, HudSettings hud, HudSettings.Part part, double gui, int width, int height) {
        var component = hud.component(part);
        return warmKey(slot, hud.enabled() && component.enabled() && component.opacity() >= .01
                ? scaleKey(gui * component.fitScale(width, height)) : 0);
    }

    private static boolean warmScale(int slot, double scale) {
        return warmKey(slot, scale > 0 ? scaleKey(scale) : 0);
    }

    private static boolean warmKey(int slot, int key) {
        if (warmScales[slot] == key) return false;
        warmScales[slot] = key;
        return true;
    }

    private static int guiScale() {
        return scaleKey(physicalScale());
    }

    private static double physicalScale() {
        return Minecraft.getInstance().getWindow().getGuiScale() * (layoutScale == 0 ? 1 : layoutScale);
    }

    private static int scaleKey(double scale) {
        // Fractional menu scales need their own raster size; 1/64 steps bound cache keys during resizing.
        return Math.clamp(Math.round(scale * SCALE_STEPS), SCALE_STEPS, MAX_OVERSAMPLE * SCALE_STEPS);
    }

    private static int nearestBuilt(int wanted, int fallback) {
        int best = fallback;
        for (int i = SCALE_STEPS; i < variants.length; i++) if (variants[i] != null && Math.abs(i - wanted) < Math.abs(best - wanted)) best = i;
        return best;
    }

    /** Called per glyph: must stay allocation-free on the common path. */
    private static FontSet set(int oversample) {
        var game = Minecraft.getInstance();
        FontSet vanilla = game.font.getFontSet(Style.DEFAULT_FONT);
        if (failed) return vanilla;
        // A resource reload closes the vanilla providers this set falls back to.
        if (vanilla != vanillaDefault) { clear(); vanillaDefault = vanilla; }
        if (variants[oversample] != null) return variants[oversample].set;
        try {
            variants[oversample] = build(game, vanilla, oversample);
        } catch (Exception error) {
            failed = true; clear();
            ModernMusic.LOGGER.warn("Smooth UI font unavailable; using the default font", error);
            return vanilla;
        }
        lastBuild = used[oversample] = System.nanoTime();
        evict(oversample);
        return variants[oversample].set;
    }

    private static void evict(int keep) {
        int gui = guiScale();
        while (true) {
            int count = 0, oldest = -1;
            for (int i = SCALE_STEPS; i < variants.length; i++) {
                if (variants[i] == null) continue;
                count++;
                if (i != keep && i != gui && (oldest < 0 || used[i] < used[oldest])) oldest = i;
            }
            if (count <= CACHED || oldest < 0) return;
            variants[oldest].close(); variants[oldest] = null;
        }
    }

    private static Variant build(Minecraft game, FontSet vanilla, int key) throws Exception {
        long started = System.nanoTime();
        float oversample = key / (float) SCALE_STEPS;
        float shift = Math.round(SHIFT_Y * oversample) / oversample;
        var definition = new TrueTypeGlyphProviderDefinition(FILE, SIZE, oversample,
                new TrueTypeGlyphProviderDefinition.Shift(0, shift), "");
        GlyphProvider provider = definition.unpack().left().orElseThrow().load(game.getResourceManager());
        try {
            var set = new LazyFontSet(game.getTextureManager(), ResourceLocation.fromNamespaceAndPath(ModernMusic.MOD_ID, "ui_" + key));
            var providers = new ArrayList<GlyphProvider.Conditional>();
            providers.add(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS));
            providers.addAll(vanilla.allProviders);
            set.reload(providers, options);
            ModernMusic.LOGGER.info("UI font x{} ready in {} ms", oversample, (System.nanoTime() - started) / 1_000_000);
            return new Variant(provider, set);
        } catch (RuntimeException error) {
            provider.close(); throw error;
        }
    }

    private static Set<FontOption> fontOptions(Minecraft game) {
        var set = EnumSet.noneOf(FontOption.class);
        if (game.options.forceUnicodeFont().get()) set.add(FontOption.UNIFORM);
        if (game.options.japaneseGlyphVariants().get()) set.add(FontOption.JAPANESE_VARIANTS);
        return set;
    }

    /** Releases glyph atlases and font memory; the next use rebuilds what it needs. */
    public static void clear() {
        for (int i = 0; i < variants.length; i++) if (variants[i] != null) { variants[i].close(); variants[i] = null; }
        warmIndex = 0;
    }
}
