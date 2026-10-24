package dev.modernmusic.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Anti-aliased rounded rectangles, rings and soft shadows on the physical pixel grid.
 * Each shape is a nine-slice from a cached corner tile, so edges stay crisp at every GUI scale.
 */
public final class Shapes {
    private record Key(int radius, int soft, int ring) {}
    private record Tile(ResourceLocation texture, int size) {}
    private static final int MAX_TILES = 128;
    private static final Map<Key, Tile> tiles = new LinkedHashMap<>(32, .75f, true);
    /** Preserves faint fragments; null uses the vanilla fallback until this shader loads. */
    private static ShaderInstance shader;

    private Shapes() {}

    public static void shader(ShaderInstance loaded) { shader = loaded; }
    /** The shader for UI shapes and icons: every non-zero alpha is kept, so soft fades stay smooth. */
    static ShaderInstance shader() { return shader != null ? shader : GameRenderer.getPositionTexColorShader(); }

    static void rect(GuiGraphics g, float x, float y, float w, float h, float radius, int color) {
        draw(g, x, y, w, h, radius, 0, 0, color, color);
    }
    static void gradient(GuiGraphics g, float x, float y, float w, float h, float radius, int top, int bottom) {
        draw(g, x, y, w, h, radius, 0, 0, top, bottom);
    }
    /** A border of {@code pixels} physical pixels; the inside stays untouched. */
    static void ring(GuiGraphics g, float x, float y, float w, float h, float radius, int pixels, int color) {
        draw(g, x, y, w, h, radius, 0, Math.max(1, pixels), color, color);
    }
    /** A blurred silhouette that fades {@code blur} GUI pixels beyond the shape's edge. */
    static void shadow(GuiGraphics g, float x, float y, float w, float h, float radius, float blur, int color) {
        draw(g, x, y, w, h, radius, blur, 0, color, color);
    }
    static void circle(GuiGraphics g, float cx, float cy, float radius, int color) {
        draw(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, 0, 0, color, color);
    }

    /** Physical pixels per local unit of the current pose (GUI scale times any pose scaling). */
    static float pixelScale(GuiGraphics g) {
        return g.pose().last().pose().m00() * (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /**
     * A local run of shapes with an unchanged pose and scissor. Only draw through the returned batch until it is
     * closed; other GUI drawing must stay outside this scope. Texture changes submit the preceding run in order.
     */
    static Batch batch(GuiGraphics g) { return new Batch(g); }

    static final class Batch implements AutoCloseable {
        private final GuiGraphics graphics;
        private final Matrix4f matrix;
        private Tile texture;
        private BufferBuilder buffer;
        private boolean started, closed;

        private Batch(GuiGraphics graphics) {
            this.graphics = graphics;
            float gui = (float) Minecraft.getInstance().getWindow().getGuiScale();
            matrix = new Matrix4f().translation(0, 0, graphics.pose().last().pose().m32()).scale(1 / gui, 1 / gui, 1);
        }

        void gradient(float x, float y, float w, float h, float radius, int top, int bottom) {
            if (closed) throw new IllegalStateException("Shape batch is closed");
            draw(graphics, x, y, w, h, radius, 0, 0, top, bottom, this);
        }

        private BufferBuilder buffer(Tile next) {
            if (!started) { graphics.flush(); started = true; }
            if (texture != next) { flush(); texture = next; }
            if (buffer == null) buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            return buffer;
        }

        private void flush() {
            if (buffer == null) return;
            var pending = buffer;
            buffer = null;
            submit(pending, texture);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            flush();
        }
    }

    private static void draw(GuiGraphics g, float x, float y, float w, float h, float radius, float blur, int ring, int top, int bottom) {
        draw(g, x, y, w, h, radius, blur, ring, top, bottom, null);
    }

    private static void draw(GuiGraphics g, float x, float y, float w, float h, float radius, float blur, int ring, int top, int bottom, Batch batch) {
        if (w <= 0 || h <= 0 || (top >>> 24 == 0 && bottom >>> 24 == 0)) return;
        Matrix4f pose = g.pose().last().pose();
        float gui = (float) Minecraft.getInstance().getWindow().getGuiScale();
        float scale = pose.m00() * gui;
        int x0 = Math.round((x * pose.m00() + pose.m30()) * gui), x1 = Math.round(((x + w) * pose.m00() + pose.m30()) * gui);
        int y0 = Math.round((y * pose.m11() + pose.m31()) * gui), y1 = Math.round(((y + h) * pose.m11() + pose.m31()) * gui);
        if (x1 <= x0 || y1 <= y0) return;
        int r = Math.clamp(Math.round(radius * scale), 0, Math.min(x1 - x0, y1 - y0) / 2);
        // Blur reaches equally inside the edge, so it must fit within half the shape.
        int soft = Math.clamp(Math.round(blur * scale), 0, Math.min(x1 - x0, y1 - y0) / 2 - r);
        int border = ring == 0 ? 0 : Math.min(ring, Math.min(x1 - x0, y1 - y0) / 2);
        Tile tile = tile(new Key(r, soft, border));
        int n = tile.size - 1;
        int ox0 = x0 - soft, ox1 = x1 + soft, oy0 = y0 - soft, oy1 = y1 + soft;
        int[] xs = {ox0, ox0 + n, ox1 - n, ox1}, ys = {oy0, oy0 + n, oy1 - n, oy1};
        float edge = n + .5f;
        float[] us = {0, n, edge, edge, n, 0}, vs = us;

        if (batch == null) g.flush();
        var matrix = batch == null ? new Matrix4f().translation(0, 0, pose.m32()).scale(1 / gui, 1 / gui, 1) : batch.matrix;
        BufferBuilder buffer = batch == null ? Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR) : batch.buffer(tile);
        float size = tile.size;
        for (int row = 0; row < 3; row++) {
            if (ys[row + 1] <= ys[row]) continue;
            for (int column = 0; column < 3; column++) {
                if (xs[column + 1] <= xs[column]) continue;
                if (border > 0 && row == 1 && column == 1) continue; // A ring's middle is transparent.
                float u0 = us[column * 2] / size, u1 = us[column * 2 + 1] / size;
                float v0 = vs[row * 2] / size, v1 = vs[row * 2 + 1] / size;
                int c0 = mix(top, bottom, (ys[row] - oy0) / (float) (oy1 - oy0)), c1 = mix(top, bottom, (ys[row + 1] - oy0) / (float) (oy1 - oy0));
                buffer.addVertex(matrix, xs[column], ys[row], 0).setUv(u0, v0).setColor(c0);
                buffer.addVertex(matrix, xs[column], ys[row + 1], 0).setUv(u0, v1).setColor(c1);
                buffer.addVertex(matrix, xs[column + 1], ys[row + 1], 0).setUv(u1, v1).setColor(c1);
                buffer.addVertex(matrix, xs[column + 1], ys[row], 0).setUv(u1, v0).setColor(c0);
            }
        }
        if (batch == null) submit(buffer, tile);
    }

    private static void submit(BufferBuilder buffer, Tile tile) {
        var mesh = buffer.build();
        if (mesh == null) return;
        RenderSystem.setShaderTexture(0, tile.texture);
        RenderSystem.setShader(Shapes::shader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        try {
            BufferUploader.drawWithShader(mesh);
            UiRenderMetrics.shapeSubmitted();
        } finally { RenderSystem.disableBlend(); }
    }

    static int mix(int from, int to, float t) {
        if (from == to || t <= 0) return from;
        if (t >= 1) return to;
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = from >>> shift & 255, b = to >>> shift & 255;
            result |= Math.round(a + (b - a) * t) << shift;
        }
        return result;
    }

    private static Tile tile(Key key) {
        Tile tile = tiles.get(key);
        if (tile != null) return tile;
        int n = key.soft > 0 ? key.radius + 2 * key.soft : Math.max(key.radius, key.ring), size = n + 1;
        var image = new NativeImage(size, size, false);
        try {
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++)
                image.setPixelRGBA(x, y, Math.round(coverage(key.radius, key.soft, key.ring, n, x, y) * 255) << 24 | 0xFFFFFF);
        } catch (RuntimeException | Error failure) { image.close(); throw failure; }
        tile = new Tile(UploadedTexture.register("shape", new NativeImage[]{image}, false), size);
        tiles.put(key, tile);
        var iterator = tiles.values().iterator();
        while (tiles.size() > MAX_TILES) { UploadedTexture.release(iterator.next().texture); iterator.remove(); }
        return tile;
    }

    /**
     * Coverage of texel (x, y) in a top-left corner tile. The last row and column hold the straight-edge
     * profile and the texel at (n, n) is the interior, which the nine-slice stretches.
     */
    static float coverage(int radius, int soft, int ring, int n, int x, int y) {
        if (soft > 0) {
            // Signed distance to a quarter-plane with a rounded corner whose edges sit `soft` into the tile.
            double edge = soft, center = edge + radius;
            double px = x == n ? Double.POSITIVE_INFINITY : x + .5, py = y == n ? Double.POSITIVE_INFINITY : y + .5;
            double distance = px < center && py < center ? Math.hypot(center - px, center - py) - radius
                    : Math.max(edge - px, edge - py);
            return (float) (1 - smoothstep(-soft, soft, distance));
        }
        final int samples = 8;
        // The corner is monotone in both coordinates. Only pixels crossing an arc or border
        // need the 64 coverage samples; fully inside/outside pixels have an exact answer.
        double minX = x == n ? n + 1 : x + .5 / samples, minY = y == n ? n + 1 : y + .5 / samples;
        double maxX = x == n ? n + 1 : x + (samples - .5) / samples, maxY = y == n ? n + 1 : y + (samples - .5) / samples;
        if (!inside(maxX, maxY, 0, radius)) return 0;
        if (ring > 0 && inside(minX, minY, ring, radius - ring)) return 0;
        if (inside(minX, minY, 0, radius) && (ring == 0 || !inside(maxX, maxY, ring, radius - ring))) return 1;
        int inside = 0;
        for (int sy = 0; sy < samples; sy++) for (int sx = 0; sx < samples; sx++) {
            double px = x == n ? n + 1 : x + (sx + .5) / samples, py = y == n ? n + 1 : y + (sy + .5) / samples;
            if (inside(px, py, 0, radius) && (ring == 0 || !inside(px, py, ring, radius - ring))) inside++;
        }
        return inside / (float) (samples * samples);
    }

    /** Inside a shape whose straight edges sit at {@code edge} and whose corner arc has {@code radius}. */
    private static boolean inside(double px, double py, int edge, int radius) {
        if (px < edge || py < edge) return false;
        if (radius <= 0) return true;
        double center = edge + radius, dx = Math.max(0, center - px), dy = Math.max(0, center - py);
        return dx * dx + dy * dy <= (double) radius * radius;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.clamp((value - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    static void clear() {
        tiles.values().forEach(tile -> UploadedTexture.release(tile.texture));
        tiles.clear();
    }
}
