package dev.modernmusic.client.ui;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Function;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.stb.STBTruetype.*;

/** Uses 1.20.1's STB rasterizer with the EM sizing and baseline of the original 1.21 UI. */
public final class EmGlyphProvider implements GlyphProvider {
    private ByteBuffer memory;
    private STBTTFontinfo font;
    private final float oversample, scale, shiftY;

    private EmGlyphProvider(ByteBuffer memory, STBTTFontinfo font, float size, float oversample, float shift) {
        this.memory = memory;
        this.font = font;
        this.oversample = oversample;
        this.shiftY = shift * oversample;
        // FreeType sizes the EM square; vanilla 1.20 STB sizes ascent minus descent instead.
        this.scale = stbtt_ScaleForMappingEmToPixels(font, Math.round(size * oversample));
    }

    public static EmGlyphProvider load(ResourceManager resources, ResourceLocation file, float size, float oversample, float shift) throws IOException {
        ByteBuffer memory = null;
        STBTTFontinfo font = STBTTFontinfo.malloc();
        try (var input = resources.open(new ResourceLocation(file.getNamespace(), "font/" + file.getPath()))) {
            memory = TextureUtil.readResource(input);
            memory.flip();
            if (!stbtt_InitFont(font, memory)) throw new IOException("Invalid UI TrueType font: " + file);
            return new EmGlyphProvider(memory, font, size, oversample, shift);
        } catch (IOException | RuntimeException | Error failure) {
            font.free();
            MemoryUtil.memFree(memory);
            throw failure;
        }
    }

    private STBTTFontinfo openFont() {
        if (font == null) throw new IllegalStateException("UI font is closed");
        return font;
    }

    @Override public GlyphInfo getGlyph(int codepoint) {
        int index = stbtt_FindGlyphIndex(openFont(), codepoint);
        if (index == 0) return null;
        try (var stack = MemoryStack.stackPush()) {
            var advance = stack.mallocInt(1); var bearing = stack.mallocInt(1);
            var x0 = stack.mallocInt(1); var y0 = stack.mallocInt(1);
            var x1 = stack.mallocInt(1); var y1 = stack.mallocInt(1);
            stbtt_GetGlyphHMetrics(font, index, advance, bearing);
            stbtt_GetGlyphBitmapBoxSubpixel(font, index, scale, scale, 0, shiftY, x0, y0, x1, y1);
            float width = advance.get(0) * scale / oversample;
            int pixelsWide = x1.get(0) - x0.get(0), pixelsHigh = y1.get(0) - y0.get(0);
            if (pixelsWide <= 0 || pixelsHigh <= 0) return (GlyphInfo.SpaceGlyphInfo) () -> width;
            return new Glyph(index, pixelsWide, pixelsHigh, x0.get(0), y0.get(0), width, this);
        }
    }

    private record Glyph(int index, int width, int height, int left, int top, float advance,
                         EmGlyphProvider owner) implements GlyphInfo {
        @Override public float getAdvance() { return advance; }
        @Override public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> stitch) {
            return stitch.apply(new SheetGlyphInfo() {
                public int getPixelWidth() { return width; }
                public int getPixelHeight() { return height; }
                public float getOversample() { return owner.oversample; }
                public float getBearingX() { return left / owner.oversample; }
                // 1.20 BakedGlyph subtracts 3; the UI baseline is 7 pixels below its origin.
                public float getBearingY() { return 10 + top / owner.oversample; }
                public boolean isColored() { return false; }
                public void upload(int x, int y) {
                    try (var image = new NativeImage(NativeImage.Format.LUMINANCE, width, height, false)) {
                        image.copyFromFont(owner.openFont(), index, width, height, owner.scale, owner.scale, 0, owner.shiftY, 0, 0);
                        image.upload(0, x, y, 0, 0, width, height, false, false);
                    }
                }
            });
        }
    }

    @Override public IntSet getSupportedGlyphs() {
        var result = new IntOpenHashSet();
        var current = openFont();
        for (int codepoint = 0; codepoint <= Character.MAX_CODE_POINT; codepoint++)
            if (stbtt_FindGlyphIndex(current, codepoint) != 0) result.add(codepoint);
        return result;
    }

    @Override public void close() {
        if (font != null) { font.free(); font = null; }
        MemoryUtil.memFree(memory); memory = null;
    }
}
