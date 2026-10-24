package dev.modernmusic.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** A themed button showing an icon, optionally followed by its label; the pair is centered. */
class IconButton extends ThemedButton {
    Icon symbol;
    private final boolean labelled;
    IconButton(int x, int y, int width, int height, Component label, Icon icon, boolean primary, boolean labelled, Runnable action) {
        super(x, y, width, height, label, primary, action);
        symbol = icon; this.labelled = labelled;
    }
    @Override protected void drawContent(GuiGraphics g, int color) {
        var font = PlayerTheme.font();
        int centerY = getY() + height / 2;
        if (symbol == null) { super.drawContent(g, color); return; }
        if (!labelled) { icon(g, symbol, getX() + width / 2, centerY - 1 + height % 2, color); return; }
        String label = ellipsis(font, getMessage().getString(), width - 30);
        int content = 12 + 5 + font.width(label), start = getX() + Math.max(6, (width - content) / 2);
        icon(g, symbol, start + 5, centerY - 1 + height % 2, color);
        label(g, label, start + 17, getY() + (height - 8) / 2, color);
    }
}
