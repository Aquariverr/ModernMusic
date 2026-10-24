package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import dev.modernmusic.ModernMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** A GPU-only texture uploaded once with edge clamping; mip levels enable trilinear filtering. */
final class UploadedTexture extends AbstractTexture {
    private static int counter;

    /** Uploads and closes every level; level n must be half the size of level n - 1. */
    private UploadedTexture(NativeImage[] levels, boolean smooth) {
        try {
            TextureUtil.prepareImage(getId(), levels.length - 1, levels[0].getWidth(), levels[0].getHeight());
            for (int level = 0; level < levels.length; level++) {
                var image = levels[level];
                image.upload(level, 0, 0, 0, 0, image.getWidth(), image.getHeight(), smooth, true, levels.length > 1, false);
            }
        } catch (RuntimeException | Error failure) {
            releaseId();
            throw failure;
        } finally {
            for (var image : levels) image.close();
        }
    }

    static ResourceLocation register(String kind, NativeImage[] levels, boolean smooth) {
        var id = new ResourceLocation(ModernMusic.MOD_ID, "dynamic/" + kind + "_" + ++counter);
        var texture = new UploadedTexture(levels, smooth);
        try {
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return id;
        } catch (RuntimeException | Error failure) {
            texture.releaseId();
            throw failure;
        }
    }

    static void release(ResourceLocation id) { Minecraft.getInstance().getTextureManager().release(id); }

    /** 2×2 box-filtered mip chain; RGB is kept under transparent pixels so edges do not darken. */
    static NativeImage[] mipChain(NativeImage base, int levels) {
        var chain = new NativeImage[levels];
        chain[0] = base;
        try {
            for (int level = 1; level < levels; level++) {
                var source = chain[level - 1];
                int width = source.getWidth() / 2, height = source.getHeight() / 2;
                var target = new NativeImage(width, height, false);
                chain[level] = target;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int a = 0, b = 0, g = 0, r = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                        int abgr = source.getPixelRGBA(x * 2 + dx, y * 2 + dy);
                        a += abgr >>> 24; b += abgr >> 16 & 255; g += abgr >> 8 & 255; r += abgr & 255;
                    }
                    target.setPixelRGBA(x, y, (a + 2) / 4 << 24 | (b + 2) / 4 << 16 | (g + 2) / 4 << 8 | (r + 2) / 4);
                }
            }
            return chain;
        } catch (RuntimeException | Error failure) {
            for (var image : chain) if (image != null) image.close();
            throw failure;
        }
    }

    @ParametersAreNonnullByDefault @Override public void load(ResourceManager manager) {}
}
