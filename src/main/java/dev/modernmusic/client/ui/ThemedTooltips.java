package dev.modernmusic.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraftforge.client.event.RenderTooltipEvent;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Tooltips matching the music screens' theme; every other screen keeps the vanilla style. */
public final class ThemedTooltips {
    private ThemedTooltips() {}
    private static final Map<Tooltip, Component> messages = new WeakHashMap<>();

    static Tooltip create(Component message) {
        var tooltip = Tooltip.create(message);
        messages.put(tooltip, message);
        return tooltip;
    }

    static List<FormattedCharSequence> split(Tooltip tooltip, Font font, int screenWidth) {
        var message = messages.get(tooltip);
        // Vanilla pre-wraps with Minecraft's font. Measure with the font actually used by music menus.
        return message == null ? tooltip.toCharSequence(Minecraft.getInstance())
                : UiText.wrap(font, message, Math.max(1, Math.min(240, screenWidth - 24)));
    }

    public static void render(RenderTooltipEvent.Pre event) {
        var screen = Minecraft.getInstance().screen;
        if (screen == null || !event.getItemStack().isEmpty()
                || !screen.getClass().getPackageName().equals(ThemedTooltips.class.getPackageName())) return;
        event.setCanceled(true);
        var g = event.getGraphics(); var font = event.getFont(); var lines = event.getComponents();
        int width = 0, height = lines.size() == 1 ? -2 : 0;
        for (var line : lines) { width = Math.max(width, line.getWidth(font)); height += line.getHeight(); }
        var position = event.getTooltipPositioner().positionTooltip(event.getScreenWidth(), event.getScreenHeight(), event.getX(), event.getY(), width, height);
        int x = position.x(), y = position.y();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        var skin = skin();
        if (skin != null) {
            int pad = skin.geometry.tooltip();
            sprite(g, skin, Skin.Part.TOOLTIP, x - pad, y - pad, width + 2 * pad, height + 2 * pad, 1);
        } else if (blocky()) {
            g.fill(x - 5, y - 5, x + width + 5, y + height + 5, TOOLTIP);
            frame(g, x - 5, y - 5, width + 10, height + 10, ORE_OUTLINE);
            g.fill(x - 4, y - 4, x + width + 4, y - 3, 0x1AFFFFFF);
        } else {
            Shapes.shadow(g, x - 5, y - 3, width + 10, height + 8, 7, 8, 0x80000000);
            Shapes.rect(g, x - 5, y - 5, width + 10, height + 10, 7, alpha(TOOLTIP, .97));
            Shapes.ring(g, x - 5, y - 5, width + 10, height + 10, 7, 1, 0x1FFFFFFF);
        }
        int row = y;
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).renderText(font, x, row, g.pose().last().pose(), g.bufferSource());
            row += lines.get(i).getHeight() + (i == 0 ? 2 : 0);
        }
        g.flush();
        row = y;
        for (int i = 0; i < lines.size(); i++) {
            lines.get(i).renderImage(font, x, row, g);
            row += lines.get(i).getHeight() + (i == 0 ? 2 : 0);
        }
        g.pose().popPose();
    }
}
