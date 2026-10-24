package dev.modernmusic.client.ui;

import com.google.zxing.common.BitMatrix;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Shared QR presentation; generation and login state belong to the calling screen. */
final class QrDrawing {
    private QrDrawing() {}

    static void draw(GuiGraphics graphics, BitMatrix matrix, int left, int top, int available) {
        int scale = Math.max(1, available / matrix.getWidth()), size = matrix.getWidth() * scale;
        int x = left + (available - size) / 2;
        if (!pixel()) Shapes.shadow(graphics, x - 4, top - 2, size + 8, size + 8, 8, 6, 0x66000000);
        surface(graphics, x - 4, top - 4, size + 8, size + 8, 8, 0xFFFFFFFF);
        // GuiGraphics.fill flushes every quad outside a managed scope. All QR modules share one
        // pose and material, so submit them together without adding a cached GPU resource.
        graphics.flush();
        var vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        var pose = graphics.pose().last().pose();
        modules(matrix, x, top, scale, (x0, y0, x1, y1) -> {
            vertices.addVertex(pose, x1, y1, 0).setColor(0xFF000000);
            vertices.addVertex(pose, x1, y0, 0).setColor(0xFF000000);
            vertices.addVertex(pose, x0, y0, 0).setColor(0xFF000000);
            vertices.addVertex(pose, x0, y1, 0).setColor(0xFF000000);
        });
        graphics.flush();
    }

    @FunctionalInterface interface ModuleRun { void draw(int x0, int y0, int x1, int y1); }

    /** Merge adjacent modules without filtering or changing their integer pixel boundaries. */
    static void modules(BitMatrix matrix, int left, int top, int scale, ModuleRun output) {
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int col = 0; col < matrix.getWidth();) {
                if (!matrix.get(col, row)) { col++; continue; }
                int start = col++;
                while (col < matrix.getWidth() && matrix.get(col, row)) col++;
                output.draw(left + start * scale, top + row * scale,
                        left + col * scale, top + (row + 1) * scale);
            }
        }
    }
}
