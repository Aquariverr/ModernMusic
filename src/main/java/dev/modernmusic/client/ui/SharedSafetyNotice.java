package dev.modernmusic.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Persistent, measured account warning using the same font and surfaces as the surrounding UI. */
final class SharedSafetyNotice {
    private final Font font;
    private final int width, padding;
    private final List<FormattedCharSequence> lines;

    SharedSafetyNotice(Font font, int width, String key) {
        this(font, width, key, false);
    }

    SharedSafetyNotice(Font font, int width, String key, boolean compact) {
        this.font = font; this.width = width; padding = compact ? 4 : 8;
        var text = Component.translatable("modernmusic.shared.security_title").withStyle(style -> style.withColor(WARNING))
                .append(Component.literal(" ").append(Component.translatable(key)).withStyle(style -> style.withColor(MUTED)));
        lines = UiText.wrap(font, text, Math.max(1, width - 34));
    }

    int height() { return padding * 2 + lines.size() * 11; }

    void render(GuiGraphics g, int x, int y) {
        card(g, x, y, width, height(), 10);
        if (skin() != null) frame(g, x + 2, y + 2, width - 4, height() - 4, alpha(WARNING, .6));
        else if (blocky()) frame(g, x, y, width, height(), alpha(WARNING, .6));
        else Shapes.ring(g, x, y, width, height(), 10, 1, alpha(WARNING, .3));
        icon(g, Icon.HELP, x + 12, y + padding + 5, WARNING);
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + 24, y + padding + i * 11, MUTED, false);
    }
}
