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
    private final TextureManager textures;
    private Set<FontOption> options = Set.of();
    private FontSet obfuscated;

    public LazyFontSet(TextureManager textures, ResourceLocation name) {
        super(textures, name);
        this.textures = textures;
    }

    @Override public void reload(Set<FontOption> options) {
        this.options = Set.copyOf(options);
        closeObfuscated();
        resetTextures();
        activeProviders = allProviders.stream().filter(provider -> provider.filter().apply(options))
                .map(GlyphProvider.Conditional::provider).toList();
    }

    @Override public BakedGlyph getRandomGlyph(GlyphInfo glyph) {
        if (obfuscated == null) {
            // Use the public reload path so font optimizers can keep selectProviders private.
            // A separate atlas preserves normal glyphs whose vertices may already be queued.
            var set = new FontSet(textures, name().withSuffix("/obfuscated"));
            try {
                set.reload(allProviders, options);
                obfuscated = set;
            } catch (RuntimeException | Error error) {
                set.close();
                throw error;
            }
        }
        return obfuscated.getRandomGlyph(glyph);
    }

    @Override public void close() {
        closeObfuscated();
        super.close();
    }

    private void closeObfuscated() {
        if (obfuscated != null) {
            obfuscated.close();
            obfuscated = null;
        }
    }
}
