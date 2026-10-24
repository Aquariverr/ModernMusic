package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** The native edit/cursor behavior inside the same field as the player. */
class ThemedEditBox extends EditBox {
    private final int fieldHeight, inset;
    private final Icon symbol;
    private Component placeholder;

    ThemedEditBox(Font font, int x, int y, int width, int height, Component label) { this(font, x, y, width, height, label, null); }
    /** {@code icon} sits inside the field before the text, e.g. a magnifier for search. */
    ThemedEditBox(Font font, int x, int y, int width, int height, Component label, Icon icon) {
        super(font, x + (icon == null ? 8 : 24), y + (height - 8) / 2, width - (icon == null ? 16 : 32), 8, label);
        fieldHeight = height; inset = icon == null ? 8 : 24; symbol = icon;
        setBordered(false); setTextColor(FIELD_TEXT); setTextColorUneditable(FIELD_HINT); setTextShadow(false);
    }
    @ParametersAreNonnullByDefault @Override public void setHint(Component hint) { placeholder = hint; super.setHint(hint); }
    @ParametersAreNonnullByDefault @Override public void renderWidget(GuiGraphics g, int mx, int my, float delta) {
        int left = getX() - inset, top = fieldTop();
        field(g, left, top, fieldWidth(), fieldHeight, Math.min(12, fieldHeight / 2), isFocused());
        if (symbol != null) icon(g, symbol, left + 13, top + fieldHeight / 2 - 1, isFocused() ? FIELD_TEXT : mix(FIELD_HINT, FIELD_TEXT, .35f));
        super.renderWidget(g, mx, my, delta);
        // Vanilla hides the hint while focused; keep it so an empty field still explains itself.
        if (isFocused() && getValue().isEmpty() && placeholder != null) {
            var font = PlayerTheme.font();
            g.drawString(font, ellipsis(font, placeholder.getString(), width - 6), getX() + 3, getY(), FIELD_HINT, false);
        }
        cursor(g, this);
    }
    /** Vanilla draws a light gray bar inside text, which vanishes on light fields; redraw it in ink. */
    static void cursor(GuiGraphics g, EditBox box) {
        if (!fieldLight() || !box.isFocused() || box.getCursorPosition() >= box.getValue().length()
                || net.minecraft.Util.getMillis() / 300 % 2 != 0) return;
        int x = box.getScreenX(box.getCursorPosition()) - 1;
        if (x >= box.getX() && x <= box.getX() + box.getInnerWidth()) g.fill(x, box.getY() - 1, x + 1, box.getY() + 9, FIELD_TEXT);
    }
    private int fieldTop() { return getY() - (fieldHeight - 8) / 2; }
    private int fieldWidth() { return width + inset + 8; }
    @Override public boolean isMouseOver(double x, double y) {
        return active && visible && x >= getX() - inset && x < getX() - inset + fieldWidth() && y >= fieldTop() && y < fieldTop() + fieldHeight;
    }
    @Override protected boolean clicked(double x, double y) { return isMouseOver(x, y); }
}
