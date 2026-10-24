package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Shared focus, hover, pressed, selected and disabled states across themes. */
class ThemedButton extends Button {
    protected final boolean primary;
    private boolean selected, selectedOnly, ghost, bare, solid, plain, held;
    private float hover;
    private long flashUntil;
    /** The drop shadow under light labels on the current skin face, or 0. */
    private int labelShadow;
    private final long[] clock = new long[1];
    ThemedButton(int x, int y, int width, int height, Component label, boolean primary, Runnable action) {
        super(x, y, width, height, label, ignored -> action.run(), DEFAULT_NARRATION);
        this.primary = primary; setTooltip(ThemedTooltips.create(label));
    }
    ThemedButton selected(boolean value) { selected = value; return this; }
    /** A fixed input-type label: selected material without mouse or keyboard activation. */
    ThemedButton selectedOnly() { selected = selectedOnly = true; active = false; return this; }
    private boolean enabledAppearance() { return active || selectedOnly; }
    /** Borderless until hovered in every theme, for row actions. */
    ThemedButton ghost() { ghost = true; return this; }
    /** Borderless in modern themes, a raised face in OreUI and the skins: transport and header icons. */
    ThemedButton bare() { bare = true; return this; }
    /** A filled disc in the text color, for the one action that leads a group (play / pause). */
    ThemedButton solid() { solid = true; return this; }
    /** No chrome at all; drawContent paints the whole button (e.g. a theme sample). Focus is still outlined. */
    ThemedButton plain() { plain = true; return this; }
    boolean selected() { return selected; }
    /** Hover emphasis between 0 and 1, eased over a few frames. */
    float hover() { return hover; }
    int radius() { return height / 2; }
    @Override public void onClick(double x, double y) { held = true; super.onClick(x, y); }
    @Override public void onPress() {
        if (selectedOnly) return;
        // Keyboard activation has no release to wait for; show the press for a moment instead.
        if (!held) flashUntil = Util.getMillis() + 110;
        super.onPress();
    }
    /** Held down by the mouse, or just activated from the keyboard. */
    boolean pressed() {
        if (held && GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) held = false;
        return active && (held && isHovered() || Util.getMillis() < flashUntil);
    }
    @ParametersAreNonnullByDefault @Override protected void renderWidget(GuiGraphics g, int mx, int my, float delta) {
        hover = approach(hover, active && isHoveredOrFocused() ? 1 : 0, clock);
        boolean enabled = enabledAppearance();
        int x = getX(), y = getY();
        labelShadow = 0;
        if (plain) {
            if (isFocused()) { if (pixel()) frame(g, x - 1, y - 1, width + 2, height + 2, skin() != null ? ACCENT : 0xFFFFFFFF); else focusRing(g, x, y, width, height, 9); }
            drawContent(g, TEXT);
            return;
        }
        if (skin() != null) { renderSkin(g, skin(), x, y); return; }
        if (blocky()) { renderBlock(g, x, y); return; }
        int r = radius(), fg;
        if (isFocused()) focusRing(g, x, y, width, height, r);
        if (solid) {
            if (accentPlay()) {
                Shapes.rect(g, x, y, width, height, r, enabled ? mix(ACCENT, ACCENT_HI, hover) : alpha(OVERLAY, .08));
                fg = enabled ? INK : QUIET;
            } else {
                Shapes.rect(g, x, y, width, height, r, enabled ? mix(TEXT, ACCENT_HI, hover * .25f) : alpha(OVERLAY, .08));
                fg = enabled ? SHELL : QUIET;
            }
        } else if (primary) {
            Shapes.rect(g, x, y, width, height, r, enabled ? mix(ACCENT, ACCENT_HI, hover) : alpha(OVERLAY, .06));
            fg = enabled ? INK : QUIET;
        } else if ((ghost || bare) && selected) {
            // An engaged toggle among bare icons: accent glyph and a dot beneath, as music apps mark shuffle / repeat.
            if (hover > .01f) Shapes.rect(g, x, y, width, height, r, alpha(OVERLAY, .08 * hover));
            fg = enabled ? ACCENT : QUIET;
            Shapes.circle(g, x + width / 2f, y + height - 2.5f, 1.25f, fg);
        } else if (selected) {
            Shapes.rect(g, x, y, width, height, r, enabled ? mix(SELECTED, ACCENT, hover * .15f) : alpha(OVERLAY, .05));
            fg = enabled ? ACCENT : QUIET;
        } else if (ghost || bare) {
            if (hover > .01f) Shapes.rect(g, x, y, width, height, r, alpha(OVERLAY, .08 * hover));
            fg = enabled ? mix(MUTED, TEXT, hover) : alpha(QUIET, .7);
        } else {
            Shapes.rect(g, x, y, width, height, r, alpha(OVERLAY, enabled ? (light() ? .06 : .08) + .06 * hover : .04));
            fg = enabled ? TEXT : QUIET;
        }
        drawContent(g, fg);
    }
    /** OreUI: raised gray faces, green for primary actions; selected faces are pressed in. */
    private void renderBlock(GuiGraphics g, int x, int y) {
        boolean enabled = enabledAppearance();
        int fg;
        if (ghost && !selected) {
            if (hover > .01f) g.fill(x, y, x + width, y + height, alpha(0xFFFFFFFF, .14 * hover));
            fg = enabled ? mix(MUTED, TEXT, hover) : QUIET;
            if (isFocused()) frame(g, x, y, width, height, 0xFFFFFFFF);
            drawContent(g, fg);
            return;
        }
        int shift;
        if (!enabled) {
            shift = raised(g, x, y, width, height, ORE_DISABLED, 0, ORE_DISABLED_DEPTH, false); fg = ORE_DISABLED_TEXT;
        } else if (primary || solid) {
            shift = raised(g, x, y, width, height, mix(ORE_GREEN, ORE_GREEN_HOVER, hover), ORE_GREEN_HI, ORE_GREEN_DEPTH, false); fg = 0xFFFFFFFF;
        } else if (selected) {
            shift = raised(g, x, y, width, height, ORE_FACE_PRESSED, 0, ORE_FACE_DEPTH, true); fg = ORE_FACE_TEXT;
        } else {
            shift = raised(g, x, y, width, height, mix(ORE_FACE, ORE_FACE_HOVER, hover), ORE_FACE_HI, ORE_FACE_DEPTH, false); fg = ORE_FACE_TEXT;
        }
        if (isFocused()) frame(g, x - 1, y - 1, width + 2, height + 2, 0xFFFFFFFF);
        g.pose().pushPose(); g.pose().translate(0, shift, 0);
        drawContent(g, fg);
        g.pose().popPose();
    }
    /**
     * Skins: ghost actions stay bare until hovered (rows get the list highlight, header actions a face); everything
     * else is a sprite face. Pixel-art faces switch outright rather than fade, as the reference mods' buttons do.
     */
    private void renderSkin(GuiGraphics g, Skin skin, int x, int y) {
        boolean enabled = enabledAppearance();
        boolean hovered = hover > .5f, down = pressed(), header = onHeader(g, x + width / 2, y + height / 2);
        var labels = skin.labels;
        if (ghost && !(header && (hovered || down))) {
            int fg;
            if (selected || down) { row(g, x, y, width, height, true, 0); fg = enabled ? ACCENT : QUIET; }
            else {
                row(g, x, y, width, height, false, header ? 0 : hover);
                int ink = header ? HEAD_TEXT : TEXT, quiet = header ? HEAD_MUTED : MUTED;
                fg = enabled ? hovered ? ink : quiet : alpha(header ? HEAD_MUTED : QUIET, .7);
            }
            if (isFocused()) focusRing(g, x, y, width, height, 0);
            drawContent(g, fg);
            return;
        }
        Skin.Part face; int fg, shift;
        var geometry = skin.geometry;
        if (!enabled) {
            face = primary || solid ? Skin.Part.PRIMARY_DISABLED : Skin.Part.BUTTON_DISABLED;
            fg = primary || solid ? labels.primaryDisabled() : labels.disabled(); shift = geometry.pressed();
        } else if (primary || solid) {
            face = down ? Skin.Part.PRIMARY_PRESSED : hovered ? Skin.Part.PRIMARY_HOVERED : Skin.Part.PRIMARY;
            fg = labels.primary(); shift = down ? geometry.pressed() : hovered ? geometry.hovered() : geometry.rest();
        } else if (down || selected && !ghost) {
            face = down ? Skin.Part.BUTTON_PRESSED : Skin.Part.BUTTON_SELECTED;
            fg = labels.selected(); shift = geometry.pressed();
        } else {
            face = hovered ? Skin.Part.BUTTON_HOVERED : Skin.Part.BUTTON;
            fg = hovered ? labels.hovered() : labels.normal(); shift = hovered ? geometry.hovered() : geometry.rest();
        }
        sprite(g, face, x, y, width, height);
        if (isFocused()) focusRing(g, x, y, width, height, 0);
        // Light labels on mid-tone faces (Create's gray, AE2's slate, celestial blue) keep a hard pixel shadow.
        if (labels.shadow() && enabled && Palette.luminance(fg) > .5) labelShadow = 0x66000000;
        g.pose().pushPose(); g.pose().translate(0, shift, 0);
        drawContent(g, fg);
        g.pose().popPose();
        labelShadow = 0;
    }
    /** Draws a label in {@code color}, with the face's pixel shadow one GUI pixel below it when the skin wants one. */
    protected void label(GuiGraphics g, String text, int x, int y, int color) {
        var font = PlayerTheme.font();
        if (labelShadow != 0) g.drawString(font, text, x, y + 1, labelShadow, false);
        g.drawString(font, text, x, y, color, false);
    }
    protected void drawContent(GuiGraphics g, int color) {
        var font = PlayerTheme.font();
        String label = ellipsis(font, getMessage().getString(), width - 10);
        label(g, label, getX() + (width - font.width(label)) / 2, getY() + (height - 8) / 2, color);
    }
}
