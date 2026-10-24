package dev.modernmusic.client.ui;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import java.util.Set;
import net.minecraft.client.gui.font.FontOption;
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
    private Set<FontOption> options = Set.of();
    private boolean indexed;

    public LazyFontSet(TextureManager textures, ResourceLocation name) { super(textures, name); }

    @Override public void reload(Set<FontOption> options) {
        this.options = Set.copyOf(options);
        indexed = false;
        resetTextures();
        activeProviders = allProviders.stream().filter(provider -> provider.filter().apply(options))
                .map(GlyphProvider.Conditional::provider).toList();
    }

    @Override public BakedGlyph getRandomGlyph(GlyphInfo glyph) {
        if (!indexed) {
            // Preserve vanilla's full candidate pool and advances for the obfuscated style.
            // Do not reload textures here: a string may already have queued vertices from them.
            activeProviders = selectProviders(allProviders, options);
            indexed = true;
        }
        return super.getRandomGlyph(glyph);
    }
}
