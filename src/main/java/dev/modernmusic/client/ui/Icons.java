package dev.modernmusic.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Smooth icon atlases are rasterized per physical scale.
 * OreUI and sprite-based skins share a 16-pixel atlas with nearest-neighbour filtering.
 */
final class Icons {
    static final int BOX = 16, COLUMNS = 8;
    private static final int MAX_SCALE = 12;
    /** Index 0 holds the pixel-art atlas; 1–12 the smooth atlas per physical scale. */
    private static final ResourceLocation[] atlases = new ResourceLocation[MAX_SCALE + 1];
    /** The pixel atlas's one-pixel outline ring, for glowing glyphs. */
    private static ResourceLocation halo;

    private Icons() {}

    /** Draws a {@link #BOX}-unit icon centered on the pixel at (x, y). */
    static void draw(GuiGraphics g, PlayerTheme.Icon icon, float x, float y, int color) {
        Matrix4f pose = g.pose().last().pose();
        float gui = (float) Minecraft.getInstance().getWindow().getGuiScale();
        int scale = dev.modernmusic.compat.Java17.clamp(Math.round(pose.m00() * gui), 1, MAX_SCALE);
        boolean pixel = PlayerTheme.pixel();
        // Every glyph follows the complete transform; the raster atlas may have a different sampling scale.
        int size = Math.max(1, Math.round(BOX * pose.m00() * gui));
        int pixelStep = Math.max(1, Math.round(size / (float) BOX));
        int left = Math.round(((x + .5f) * pose.m00() + pose.m30()) * gui) - size / 2;
        int top = Math.round(((y + .5f) * pose.m11() + pose.m31()) * gui) - size / 2;
        var atlas = atlas(pixel ? 0 : scale);
        int cell = icon.ordinal(), rows = (PlayerTheme.Icon.values().length + COLUMNS - 1) / COLUMNS;
        int row = cell / COLUMNS;
        float u0 = (cell % COLUMNS) / (float) COLUMNS, v0 = row / (float) rows;
        float[] uv = {u0, v0, u0 + 1f / COLUMNS, v0 + 1f / rows};
        g.flush();
        RenderSystem.setShader(Shapes::shader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        var matrix = new Matrix4f().translation(0, 0, pose.m32()).scale(1 / gui, 1 / gui, 1);
        var skin = PlayerTheme.skin();
        int alpha = color >>> 24;
        if (pixel && skin != null && alpha > 0) {
            // `scale` screen pixels are one art pixel, so every treatment moves by exactly one GUI pixel.
            boolean lightInk = Palette.luminance(color) > .45;
            switch (skin.glyphs) {
                case SHADOW -> quad(matrix, atlas, uv, left + pixelStep, top + pixelStep, size, (color & 0xFCFCFC) >> 2 | alpha << 24);
                case ENGRAVED -> quad(matrix, atlas, uv, left, top + pixelStep, size,
                        lightInk ? (alpha * 3 / 5) << 24 | 0x413F54 : (alpha * 9 / 10) << 24 | 0xF2F2F2);
                case GLOW -> quad(matrix, halo(), uv, left, top, size, (alpha * 9 / 40) << 24 | (color & 0xFFFFFF));
                case PLAIN -> {}
            }
        }
        quad(matrix, atlas, uv, left, top, size, color);
        RenderSystem.disableBlend();
    }

    private static void quad(Matrix4f matrix, ResourceLocation atlas, float[] uv, int left, int top, int size, int color) {
        RenderSystem.setShaderTexture(0, atlas);
        var buffer = Shapes.beginBuffer();
        buffer.vertex(matrix, left, top, 0).uv(uv[0], uv[1]).color(color).endVertex();
        buffer.vertex(matrix, left, top + size, 0).uv(uv[0], uv[3]).color(color).endVertex();
        buffer.vertex(matrix, left + size, top + size, 0).uv(uv[2], uv[3]).color(color).endVertex();
        buffer.vertex(matrix, left + size, top, 0).uv(uv[2], uv[1]).color(color).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    /** Every transparent texel of the pixel atlas that touches a glyph texel, including diagonally. */
    private static ResourceLocation halo() {
        if (halo != null) return halo;
        var glyphs = raster(true, 1);
        int w = glyphs.getWidth(), h = glyphs.getHeight(), cell = BOX;
        var image = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ring = false;
            if (glyphs.getRGB(x, y) >>> 24 == 0)
                // Stay within the glyph's own cell so neighbors never glow into each other.
                for (int dy = -1; dy <= 1 && !ring; dy++) for (int dx = -1; dx <= 1 && !ring; dx++) {
                    int nx = x + dx, ny = y + dy;
                    ring = nx >= 0 && ny >= 0 && nx < w && ny < h && nx / cell == x / cell && ny / cell == y / cell
                            && glyphs.getRGB(nx, ny) >>> 24 != 0;
                }
            image.setPixelRGBA(x, y, ring ? 0xFFFFFFFF : 0x00FFFFFF);
        }
        return halo = UploadedTexture.register("icons", new NativeImage[]{image}, false);
    }

    private static ResourceLocation atlas(int index) {
        if (atlases[index] != null) return atlases[index];
        var canvas = raster(index == 0, Math.max(1, index));
        var image = new NativeImage(canvas.getWidth(), canvas.getHeight(), false);
        for (int y = 0; y < canvas.getHeight(); y++) for (int x = 0; x < canvas.getWidth(); x++)
            image.setPixelRGBA(x, y, (canvas.getRGB(x, y) >>> 24) << 24 | 0xFFFFFF);
        return atlases[index] = UploadedTexture.register("icons", new NativeImage[]{image}, false);
    }

    /** Every icon in its cell: aliased 2-pixel strokes for the pixel atlas, smooth strokes at {@code scale} otherwise. */
    private static BufferedImage raster(boolean pixel, int scale) {
        var icons = PlayerTheme.Icon.values();
        int rows = (icons.length + COLUMNS - 1) / COLUMNS, cell = BOX * scale;
        var canvas = new BufferedImage(COLUMNS * cell, rows * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, pixel ? RenderingHints.VALUE_ANTIALIAS_OFF : RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, pixel ? RenderingHints.VALUE_STROKE_NORMALIZE : RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            for (var icon : icons) {
                var saved = g.getTransform();
                g.translate(icon.ordinal() % COLUMNS * cell, icon.ordinal() / COLUMNS * cell);
                g.scale(scale, scale);
                g.clipRect(0, 0, BOX, BOX); // Strokes must never bleed into a neighboring cell.
                g.setStroke(pixel ? new BasicStroke(2f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER)
                        : new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paint(g, icon);
                g.setTransform(saved); g.setClip(null);
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** Paths in a 16 × 16 box; the visual center is (8, 8). */
    private static void paint(Graphics2D g, PlayerTheme.Icon icon) {
        switch (icon) {
            case PLAY -> filled(g, triangle(5.4, 3.6, 12.6, 8, 5.4, 12.4));
            case PAUSE -> {
                g.fill(new RoundRectangle2D.Double(4.2, 3.4, 2.9, 9.2, 1.4, 1.4));
                g.fill(new RoundRectangle2D.Double(8.9, 3.4, 2.9, 9.2, 1.4, 1.4));
            }
            case STOP -> g.fill(new RoundRectangle2D.Double(4, 4, 8, 8, 2.6, 2.6));
            case NEXT -> {
                filled(g, triangle(3.8, 3.8, 10, 8, 3.8, 12.2));
                g.fill(new RoundRectangle2D.Double(10.6, 3.6, 1.9, 8.8, 1.2, 1.2));
            }
            case PREVIOUS -> {
                filled(g, triangle(12.2, 3.8, 6, 8, 12.2, 12.2));
                g.fill(new RoundRectangle2D.Double(3.5, 3.6, 1.9, 8.8, 1.2, 1.2));
            }
            case CLOSE -> { line(g, 4.6, 4.6, 11.4, 11.4); line(g, 11.4, 4.6, 4.6, 11.4); }
            case PLUS -> { line(g, 8, 3.6, 8, 12.4); line(g, 3.6, 8, 12.4, 8); }
            case MINUS -> line(g, 3.6, 8, 12.4, 8);
            case CHECK -> polyline(g, 3.4, 8.4, 6.6, 11.4, 12.6, 4.8);
            case LEFT -> polyline(g, 9.8, 3.6, 5.6, 8, 9.8, 12.4);
            case RIGHT -> polyline(g, 6.2, 3.6, 10.4, 8, 6.2, 12.4);
            case CLEAR -> {
                line(g, 2.8, 4.6, 13.2, 4.6);
                polyline(g, 6.2, 4.4, 6.6, 2.8, 9.4, 2.8, 9.8, 4.4);
                var body = new Path2D.Double();
                body.moveTo(4.2, 4.8); body.lineTo(4.9, 12.2); body.quadTo(5, 13.2, 6, 13.2);
                body.lineTo(10, 13.2); body.quadTo(11, 13.2, 11.1, 12.2); body.lineTo(11.8, 4.8);
                g.draw(body);
                line(g, 6.9, 7.2, 7.1, 10.8); line(g, 9.1, 7.2, 8.9, 10.8);
            }
            case VOLUME -> {
                var speaker = new Path2D.Double();
                speaker.moveTo(2.4, 6.2); speaker.lineTo(4.8, 6.2); speaker.lineTo(8, 3.4); speaker.lineTo(8, 12.6);
                speaker.lineTo(4.8, 9.8); speaker.lineTo(2.4, 9.8); speaker.closePath();
                filled(g, speaker);
                g.draw(new Arc2D.Double(6.6, 5.2, 5.6, 5.6, -50, 100, Arc2D.OPEN));
                g.draw(new Arc2D.Double(4.8, 3.2, 9.6, 9.6, -50, 100, Arc2D.OPEN));
            }
            case SEARCH -> { g.draw(new Ellipse2D.Double(2.8, 2.8, 8.4, 8.4)); line(g, 10.2, 10.2, 13.2, 13.2); }
            case QUEUE -> {
                for (double y : new double[]{4.4, 8, 11.6}) { g.fill(new Ellipse2D.Double(2.4, y - 1, 2, 2)); line(g, 6.6, y, 13.4, y); }
            }
            case ACCOUNT -> {
                g.fill(new Ellipse2D.Double(5.3, 2.4, 5.4, 5.4));
                g.fill(new Arc2D.Double(2.8, 8.8, 10.4, 9.6, 0, 180, Arc2D.CHORD));
            }
            case REPEAT, REPEAT_ONE -> {
                var top = new Path2D.Double();
                top.moveTo(3.2, 8.6); top.lineTo(3.2, 7.2); top.quadTo(3.2, 4.8, 5.6, 4.8); top.lineTo(12.4, 4.8);
                g.draw(top); polyline(g, 10.6, 3, 12.4, 4.8, 10.6, 6.6);
                var bottom = new Path2D.Double();
                bottom.moveTo(12.8, 7.4); bottom.lineTo(12.8, 8.8); bottom.quadTo(12.8, 11.2, 10.4, 11.2); bottom.lineTo(3.6, 11.2);
                g.draw(bottom); polyline(g, 5.4, 9.4, 3.6, 11.2, 5.4, 13);
                if (icon == PlayerTheme.Icon.REPEAT_ONE) {
                    g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    polyline(g, 7.2, 7.4, 8.3, 6.6, 8.3, 9.6);
                }
            }
            case SHUFFLE -> {
                var a = new Path2D.Double(); a.moveTo(2.6, 4.8); a.curveTo(7.4, 4.8, 8.6, 11.2, 12.8, 11.2); g.draw(a);
                var b = new Path2D.Double(); b.moveTo(2.6, 11.2); b.curveTo(7.4, 11.2, 8.6, 4.8, 12.8, 4.8); g.draw(b);
                polyline(g, 11, 3, 12.8, 4.8, 11, 6.6); polyline(g, 11, 9.4, 12.8, 11.2, 11, 13);
            }
            case ORDER -> {
                line(g, 2.8, 4.6, 9.4, 4.6); line(g, 2.8, 8, 12.6, 8); line(g, 2.8, 11.4, 9.4, 11.4);
                polyline(g, 10.8, 6.2, 12.6, 8, 10.8, 9.8);
            }
            case MUSIC -> {
                var note = new Path2D.Double();
                note.moveTo(7.2, 11.6); note.lineTo(7.2, 3.6); note.lineTo(12.6, 2.6); note.lineTo(12.6, 10.4);
                g.draw(note); line(g, 7.2, 6, 12.6, 5);
                g.fill(new Ellipse2D.Double(3.4, 9.6, 4.4, 3.6)); g.fill(new Ellipse2D.Double(8.8, 8.4, 4.4, 3.6));
            }
            case GROUP -> {
                g.fill(new Ellipse2D.Double(3.2, 3.2, 4.6, 4.6)); g.fill(new Arc2D.Double(1, 8.6, 9, 8.6, 0, 180, Arc2D.CHORD));
                g.draw(new Ellipse2D.Double(9.4, 3.6, 3.8, 3.8)); g.draw(new Arc2D.Double(8.6, 9.2, 6.4, 6.4, 0, 120, Arc2D.OPEN));
            }
            case LAYOUT -> {
                g.draw(new RoundRectangle2D.Double(2.6, 3.4, 10.8, 9.2, 2.4, 2.4));
                g.fill(new RoundRectangle2D.Double(4.4, 5.2, 3.2, 3.2, 1, 1)); line(g, 9.2, 6, 11.6, 6); line(g, 4.6, 10.6, 11.6, 10.6);
            }
            case HELP -> {
                var mark = new Path2D.Double();
                mark.moveTo(5.4, 6); mark.curveTo(5.4, 2.4, 10.8, 2.4, 10.8, 5.8); mark.curveTo(10.8, 8, 8, 8, 8, 10);
                g.draw(mark); g.fill(new Ellipse2D.Double(7, 12, 2, 2));
            }
            case PALETTE -> {
                // A painter's palette: a round board with a thumb notch and three paint dabs.
                var board = new Path2D.Double();
                board.moveTo(8, 2.6); board.curveTo(11.6, 2.6, 13.4, 5, 13.4, 7.4); board.curveTo(13.4, 9.4, 11.8, 9.8, 10.6, 9.6);
                board.curveTo(9.4, 9.4, 8.8, 10.4, 9.4, 11.4); board.curveTo(10, 12.6, 9.2, 13.4, 8, 13.4);
                board.curveTo(4.8, 13.4, 2.6, 11, 2.6, 8); board.curveTo(2.6, 5, 5, 2.6, 8, 2.6); board.closePath();
                g.draw(board);
                for (double[] dab : new double[][]{{5.6, 6.4}, {8.4, 5}, {5.4, 9.6}}) g.fill(new Ellipse2D.Double(dab[0] - 1.1, dab[1] - 1.1, 2.2, 2.2));
            }
        }
    }

    private static Path2D triangle(double x1, double y1, double x2, double y2, double x3, double y3) {
        var path = new Path2D.Double(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.closePath(); return path;
    }
    /** Filled with a round outline so sharp corners read as softly rounded. */
    private static void filled(Graphics2D g, Path2D path) { g.fill(path); g.draw(path); }
    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) { g.draw(new Line2D.Double(x1, y1, x2, y2)); }
    private static void polyline(Graphics2D g, double... points) {
        var path = new Path2D.Double(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        g.draw(path);
    }

    static void clear() {
        for (int i = 0; i < atlases.length; i++) if (atlases[i] != null) { UploadedTexture.release(atlases[i]); atlases[i] = null; }
        if (halo != null) { UploadedTexture.release(halo); halo = null; }
    }
}
