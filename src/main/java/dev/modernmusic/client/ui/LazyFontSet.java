package dev.modernmusic.client.ui;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import java.util.List;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Keeps vanilla glyph lookup, filtering, baking and caching, without scanning every supported
 * codepoint during reload. Only obfuscated text needs the complete glyphs-by-width index.
 * Like FontSet itself, this set and its providers are confined to the render thread.
 */
public final class LazyFontSet extends FontSet {
    private boolean indexed;
    public LazyFontSet(TextureManager textures, ResourceLocation name) {
        super(textures, name);
    }
    @Override public void reload(List<GlyphProvider> providers) {
        this.providers.clear();
        // Reset atlases without scanning every supported Unicode codepoint.
        super.reload(List.of());
        this.providers.addAll(providers);
        indexed = false;
    }
    @Override public void close() {
        // UiFont owns the smooth provider; Minecraft owns the borrowed vanilla providers.
        providers.clear();
        super.close();
    }
    @Override public BakedGlyph getRandomGlyph(GlyphInfo glyph) {
        if (!indexed) {
            var codepoints = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            for (var provider : providers) codepoints.addAll(provider.getSupportedGlyphs());
            codepoints.forEach((int cp) -> {
                for (var provider : providers) {
                    var info = provider.getGlyph(cp);
                    if (info == null) continue;
                    if (info != net.minecraft.client.gui.font.glyphs.SpecialGlyphs.MISSING)
                        glyphsByWidth.computeIfAbsent(net.minecraft.util.Mth.ceil(info.getAdvance(false)),
                                key -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(cp);
                    break;
                }
            });
            indexed = true;
        }
        return super.getRandomGlyph(glyph);
    }
}
