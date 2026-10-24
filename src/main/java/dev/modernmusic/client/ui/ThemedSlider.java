package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** A labelled setting slider: the label above, the theme's rail below. */
abstract class ThemedSlider extends AbstractSliderButton {
    private float hover;
    private final long[] clock = new long[1];
    ThemedSlider(int x, int y, int width, double value) { super(x, y, width, 28, Component.empty(), value); }
    void syncValue(double next) { value = Math.clamp(next, 0, 1); updateMessage(); }
    @ParametersAreNonnullByDefault @Override public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
        hover = approach(hover, active && isHoveredOrFocused() ? 1 : 0, clock);
        var font = PlayerTheme.font();
        int x = getX(), y = getY();
        if (skin() != null) {
            card(g, x, y, width, height, 0);
            if (isFocused()) focusRing(g, x, y, width, height, 0);
        } else if (blocky()) {
            card(g, x, y, width, height, 0);
            if (isFocused()) frame(g, x, y, width, height, 0xFFFFFFFF);
        } else {
            Shapes.rect(g, x, y, width, height, 8, alpha(OVERLAY, (light() ? .035 : .045) + .03 * hover));
            if (isFocused()) focusRing(g, x, y, width, height, 8);
        }
        String label = ellipsis(font, getMessage().getString(), width - 12);
        g.drawString(font, label, x + 6, y + 4, active ? TEXT : QUIET, false);
        track(g, x + 6, y + height - 8, width - 12, value, active, hover); // Matches the vanilla value mapping.
    }

    /** The theme's rail; shared with the player's thin sliders. */
    static void track(GuiGraphics g, int x, int centerY, int width, double value, boolean active, float hover) {
        PlayerTheme.track(g, x, centerY, width, value, active, hover);
    }
}
