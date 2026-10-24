package dev.modernmusic.client.ui;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 1.20.1 equivalent of GUI atlas nine-slice metadata, using the original theme textures. */
final class ThemeSprites {
    private static final Map<ResourceLocation, Sprite> CACHE = new HashMap<>();
    record Sprite(ResourceLocation texture, int width, int height, int left, int top, int right, int bottom) {
        void draw(GuiGraphics g, int x, int y, int w, int h) {
            if (left + top + right + bottom == 0) {
                g.blit(texture, x, y, w, h, 0, 0, width, height, width, height);
                return;
            }
            int l = Math.min(left, w / 2), r = Math.min(right, w / 2);
            int t = Math.min(top, h / 2), b = Math.min(bottom, h / 2);
            int[] xs = {x, x + l, x + w - r, x + w}, ys = {y, y + t, y + h - b, y + h};
            int[] us = {0, left, width - right, width}, vs = {0, top, height - bottom, height};
            for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                int sw = us[col + 1] - us[col], sh = vs[row + 1] - vs[row];
                if (sw <= 0 || sh <= 0) continue;
                // Tile edge and centre texels instead of stretching pixel-art borders.
                for (int yy = ys[row]; yy < ys[row + 1]; yy += sh) {
                    int dh = Math.min(sh, ys[row + 1] - yy);
                    for (int xx = xs[col]; xx < xs[col + 1]; xx += sw) {
                        int dw = Math.min(sw, xs[col + 1] - xx);
                        g.blit(texture, xx, yy, us[col], vs[row], dw, dh, width, height);
                    }
                }
            }
        }
    }
    static Sprite get(ResourceLocation id) { return CACHE.computeIfAbsent(id, ThemeSprites::load); }
    static void clear() { CACHE.clear(); }
    private static Sprite load(ResourceLocation id) {
        var texture = new ResourceLocation(id.getNamespace(), "textures/gui/sprites/" + id.getPath() + ".png");
        var resources = Minecraft.getInstance().getResourceManager();
        try (var stream = resources.open(texture); var image = NativeImage.read(stream)) {
            int l = 0, t = 0, r = 0, b = 0;
            var meta = resources.getResource(new ResourceLocation(texture.getNamespace(), texture.getPath() + ".mcmeta"));
            if (meta.isPresent()) try (var reader = new InputStreamReader(meta.get().open(), StandardCharsets.UTF_8)) {
                var border = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("gui")
                        .getAsJsonObject("scaling").get("border");
                if (border.isJsonPrimitive()) l = t = r = b = border.getAsInt();
                else {
                    var value = border.getAsJsonObject();
                    l = value.get("left").getAsInt(); t = value.get("top").getAsInt();
                    r = value.get("right").getAsInt(); b = value.get("bottom").getAsInt();
                }
            }
            return new Sprite(texture, image.getWidth(), image.getHeight(), l, t, r, b);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load theme sprite " + id, error);
        }
    }
}
