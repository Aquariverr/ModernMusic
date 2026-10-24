package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Live canvas with an inspector that can be hidden to reach every part of the screen. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private final PlaybackController player;
    private final HudSettings settings;
    private HudSettings.Snapshot original;
    private HudSettings.Part selected = HudSettings.Part.COVER;
    private ThemedSlider sizeSlider, opacitySlider;
    private boolean dragging, resizing, saveFailed, collapsed, compact, showHelp;
    private int panelX, panelY, panelWidth, panelHeight;
    private double dragX, dragY;
    private int resizeLeft, resizeTop;
    private boolean guideX, guideY;

    public HudEditorScreen(Screen parent, PlaybackController player, HudSettings settings) {
        super(Component.translatable("modernmusic.hud.edit")); this.parent = parent; this.player = player; this.settings = settings;
    }
    @Override protected void init() {
        font = UiFont.get();
        settings.ensureLayout(width, height);
        if (original == null) { original = settings.snapshot(width, height); collapsed = width < 520 || height < 340; }
        // Edit exactly what is shown at this size; cancel still restores the layout's original frame.
        settings.rebase(width, height);
        int styleHeight = selected == HudSettings.Part.LYRICS ? 24 : 0;
        compact = width < 520 || height < 340 + styleHeight;
        sizeSlider = null; opacitySlider = null;
        if (collapsed) {
            panelWidth = 168; panelHeight = 32; panelX = width - panelWidth - 8; panelY = 8;
            button("modernmusic.hud.expand", panelX + 6, panelY + 6, 100, false, this::togglePanel);
            button("gui.done", panelX + 112, panelY + 6, 50, true, this::onClose);
            return;
        }
        panelWidth = compact ? Math.min(460, width - 16) : 190;
        panelHeight = (compact ? 166 : 326) + styleHeight;
        panelX = compact ? (width - panelWidth) / 2 : width - panelWidth - 8;
        panelY = compact ? height - panelHeight - 8 : (height - panelHeight) / 2;
        int x = panelX + 10, w = panelWidth - 20, y = panelY;
        var collapse = addRenderableWidget(new IconButton(panelX + panelWidth - 30, y + 6, 20, 20, tr("−"), Icon.MINUS, false, false, this::togglePanel));
        collapse.bare(); collapse.setTooltip(Tooltip.create(tr("modernmusic.hud.collapse")));
        addRenderableWidget(new IconButton(panelX + (compact ? 94 : panelWidth - 56), y + 6, 20, 20, tr("?"), Icon.HELP, false, false, () -> showHelp = !showHelp))
                .bare().setTooltip(Tooltip.create(tr("modernmusic.hud.help")));
        // Theme cycles in place, so the canvas previews it; Cancel restores it with the layout.
        var theme = addRenderableWidget(new IconButton(panelX + (compact ? 118 : panelWidth - 82), y + 6, 20, 20, tr("modernmusic.theme.title"), Icon.PALETTE, false, false, () -> {
            var themes = HudSettings.Theme.values();
            settings.theme(themes[(settings.theme().ordinal() + 1) % themes.length]); PlayerTheme.apply(settings.theme()); rebuildWidgets();
        }));
        theme.bare(); theme.setTooltip(Tooltip.create(Component.translatable("modernmusic.hud.theme", tr(settings.theme().key()))));
        int globalX = compact ? panelX + panelWidth - 136 : x, globalWidth = compact ? 102 : (w - 4) / 2;
        button(settings.enabled() ? "modernmusic.hud.on" : "modernmusic.hud.off", globalX, y + (compact ? 6 : 32), globalWidth,
                false, () -> { settings.enabled(!settings.enabled()); rebuildWidgets(); }).selected(settings.enabled());
        for (var part : HudSettings.Part.values()) {
            int cell = (w - 9) / 4;
            int bx = compact ? x + part.ordinal() * (cell + 3) : x;
            int by = y + (compact ? 32 : 60 + part.ordinal() * 24);
            button(part.key(), bx, by, compact ? cell : w - 36, false, () -> select(part)).selected(part == selected);
            addRenderableWidget(new VisibilityButton(part, compact ? bx : x + w - 30, compact ? by + 23 : by, compact ? cell : 30));
        }
        int sliderY = y + (compact ? 80 : 177), sliderWidth = compact ? (w - 6) / 2 : w;
        sizeSlider = addRenderableWidget(new ThemedSlider(x, sliderY, sliderWidth, (current().scale() - .25) / 2.75) {
            { updateMessage(); }
            @Override protected void updateMessage() {
                setMessage(selected == HudSettings.Part.LYRICS
                        ? Component.translatable("modernmusic.hud.lyric_size", String.format(java.util.Locale.ROOT, "%.1f", font.lineHeight * current().scale()))
                        : Component.translatable("modernmusic.hud.scale", tr(selected.key()), Math.round(current().scale() * 100)));
            }
            @Override protected void applyValue() { rescale(.25 + value * 2.75); updateMessage(); }
        });
        sizeSlider.setTooltip(Tooltip.create(tr("modernmusic.hud.resize_help")));
        opacitySlider = addRenderableWidget(new ThemedSlider(compact ? x + sliderWidth + 6 : x, compact ? sliderY : sliderY + 32, sliderWidth, 1 - current().opacity()) {
            { updateMessage(); }
            @Override protected void updateMessage() { setMessage(Component.translatable("modernmusic.hud.opacity", Math.round((1 - current().opacity()) * 100))); }
            @Override protected void applyValue() { current().opacity(1 - value); updateMessage(); }
        });
        if (selected == HudSettings.Part.LYRICS) {
            var style = button("modernmusic.hud.lyric_style", x, y + (compact ? 108 : 240), w, false,
                    () -> { settings.lyricStyle(settings.lyricStyle().next()); rebuildWidgets(); });
            style.setMessage(Component.translatable("modernmusic.hud.lyric_style", tr(settings.lyricStyle().key())));
            style.setTooltip(Tooltip.create(tr("modernmusic.hud.lyric_style_help")));
        }
        int alignY = y + (compact ? 113 : 244) + styleHeight;
        int alignWidth = compact ? (w / 2 - 8) / 3 : (w - 8) / 3;
        button("modernmusic.hud.align_left", x, alignY, alignWidth, false, () -> align(0));
        button("modernmusic.hud.align_center", x + alignWidth + 4, alignY, alignWidth, false, () -> align(.5));
        button("modernmusic.hud.align_right", x + 2 * (alignWidth + 4), alignY, alignWidth, false, () -> align(1));
        int resetY = compact ? alignY : y + 268 + styleHeight, resetX = compact ? x + w / 2 + 4 : x;
        int resetWidth = compact ? (w - w / 2 - 8) / 2 : (w - 4) / 2;
        button("modernmusic.hud.reset_part", resetX, resetY, resetWidth, false, () -> { settings.reset(selected, width, height); rebuildWidgets(); });
        button("modernmusic.hud.reset", resetX + resetWidth + 4, resetY, resetWidth, false, () -> { settings.reset(); rebuildWidgets(); });
        int bottom = y + (compact ? 140 : 296) + styleHeight;
        int fontX = compact ? x : x + globalWidth + 4, fontY = compact ? bottom : y + 32, fontWidth = compact ? (w - 8) / 3 : w - globalWidth - 4;
        var typeface = button("modernmusic.hud.font", fontX, fontY, fontWidth, false, () -> {
            settings.font(settings.font().next()); UiFont.smooth(settings.font() == HudSettings.FontStyle.SMOOTH); rebuildWidgets();
        });
        typeface.setMessage(Component.translatable("modernmusic.hud.font", tr(settings.font().key())));
        typeface.setTooltip(Tooltip.create(tr("modernmusic.hud.font_help")));
        int actionX = compact ? x + fontWidth + 4 : x, actionWidth = compact ? fontWidth : (w - 4) / 2;
        button("gui.cancel", actionX, bottom, actionWidth, false, this::cancel);
        button("gui.done", actionX + actionWidth + 4, bottom, compact ? w - 2 * (fontWidth + 4) : actionWidth, true, this::onClose)
                .setTooltip(Tooltip.create(tr("modernmusic.hud.save_help")));
    }
    private ThemedButton button(String key, int x, int y, int w, boolean primary, Runnable action) {
        return addRenderableWidget(new ThemedButton(x, y, w, 20, tr(key), primary, action));
    }
    private static Component tr(String key) { return Component.translatable(key); }
    private HudSettings.Transform current() { return settings.component(selected); }
    private void select(HudSettings.Part part) { selected = part; rebuildWidgets(); }
    private void togglePanel() { collapsed = !collapsed; showHelp = false; rebuildWidgets(); setFocused(null); }
    private void cancel() {
        settings.restore(original); UiFont.smooth(settings.font() == HudSettings.FontStyle.SMOOTH); PlayerTheme.apply(settings.theme());
        net.minecraft.client.Minecraft.getInstance().setScreen(parent);
    }
    private void align(double fraction) {
        current().position(4 + (width - 8 - selected.width * current().fitScale(width, height)) * fraction, current().top(width, height), width, height);
    }
    private void rescale(double scale) {
        int left = current().left(width, height), top = current().top(width, height);
        current().scale(scale); current().position(left, top, width, height);
    }
    private void syncSliders() {
        if (sizeSlider != null) sizeSlider.syncValue((current().scale() - .25) / 2.75);
        if (opacitySlider != null) opacitySlider.syncValue(1 - current().opacity());
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g) { if (net.minecraft.client.Minecraft.getInstance().level == null) renderDirtBackground(g); }
    @ParametersAreNonnullByDefault @Override public void render(GuiGraphics g, int x, int y, float delta) {
        renderBackground(g);
        MusicHud.render(g, player, settings, width, height, true);
        for (var part : HudSettings.Part.values()) {
            var transform = settings.component(part);
            int left = transform.left(width, height), top = transform.top(width, height);
            int w = (int) Math.ceil(part.width * transform.fitScale(width, height)), h = (int) Math.ceil(part.height * transform.fitScale(width, height));
            int color = part == selected ? WORLD_ACCENT : transform.enabled() ? 0x8CCDD1D6 : 0x598A8F96;
            if (part == HudSettings.Part.LYRICS) Shapes.rect(g, left - 2, top + h + 1, w + 4, 1.5f, .75f, color);
            else if (pixel()) { frame(g, left - 2, top - 2, w + 4, h + 4, color); if (part == selected) frame(g, left - 3, top - 3, w + 6, h + 6, color); }
            else Shapes.ring(g, left - 2, top - 2, w + 4, h + 4, 4, part == selected ? 2 : 1, color);
            if (!transform.enabled()) worldText(g, font, ellipsis(font, tr("modernmusic.hud.hidden").getString(), w), left + 2, top + 2, WORLD_MUTED);
            if (part == selected) {
                String label = tr(part.key()).getString() + " · " + Math.round(transform.scale() * 100) + "%";
                int labelWidth = font.width(label) + 12;
                int labelX = dev.modernmusic.compat.Java17.clamp(left - 2, 2, Math.max(2, width - labelWidth - 2)), labelY = Math.max(1, top - 16);
                surface(g, labelX, labelY, labelWidth, 13, 6.5f, alpha(TOOLTIP, .92));
                g.drawString(font, label, labelX + 6, labelY + 3, 0xFFFFFFFF, false);
                if (part != HudSettings.Part.LYRICS) {
                    if (!pixel()) Shapes.shadow(g, left + w - 5, top + h - 4, 10, 10, 5, 2, 0x80000000);
                    circle(g, left + w, top + h, 5, WORLD_ACCENT);
                    circle(g, left + w, top + h, 2, INK);
                }
            }
        }
        if (guideX) g.fill(current().left(width, height), 0, current().left(width, height) + 1, height, alpha(WORLD_ACCENT, .4));
        if (guideY) g.fill(0, current().top(width, height), width, current().top(width, height) + 1, alpha(WORLD_ACCENT, .4));
        // Font glyphs have their own depth offset. Put the entire inspector above the canvas,
        // including when a component is positioned underneath it in a small window.
        g.pose().pushPose(); g.pose().translate(0, 0, 200);
        panel(g, panelX, panelY, panelWidth, panelHeight, 10);
        if (!collapsed) {
            header(g, panelX, panelY, panelWidth, 31);
            heading(g, tr("modernmusic.hud.inspector").getString(), panelX + 10, panelY + 16, HEAD_TEXT);
            if (!compact) g.drawString(font, tr("modernmusic.hud.selection"), panelX + 10, panelY + 164, MUTED, false);
        }
        for (var widget : renderables) widget.render(g, x, y, delta);
        g.pose().popPose();
        if (!compact && !collapsed) {
            var lines = font.split(tr("modernmusic.hud.help"), Math.min(330, panelX - 20));
            for (int i = 0; i < Math.min(3, lines.size()); i++) worldText(g, font, lines.get(i), 10, height - 38 + i * 11, WORLD_TEXT);
        }
        if (!settings.enabled()) worldText(g, font, ellipsis(font, tr("modernmusic.hud.preview_only").getString(), width - 20), 10, height - 50, WORLD_ACCENT);
        if (saveFailed) worldText(g, font, ellipsis(font, tr("modernmusic.hud.save_failed").getString(), width - 20), 10, 1, ERROR);
        if (showHelp && !collapsed) g.renderTooltip(font, font.split(tr("modernmusic.hud.help"), Math.min(300, width - 24)), panelX + 12, panelY + 24);
    }
    private boolean inPanel(double x, double y) { return x >= panelX && x < panelX + panelWidth && y >= panelY && y < panelY + panelHeight; }
    private boolean inHandle(double x, double y) {
        double right = current().left(width, height) + selected.width * current().fitScale(width, height);
        double bottom = current().top(width, height) + selected.height * current().fitScale(width, height);
        return Math.abs(x - right) <= 7 && Math.abs(y - bottom) <= 7;
    }
    private HudSettings.Part hit(double x, double y) {
        if (current().contains(x, y, width, height)) return selected;
        var parts = HudSettings.Part.values();
        for (int i = parts.length - 1; i >= 0; i--) if (settings.component(parts[i]).contains(x, y, width, height)) return parts[i];
        return null;
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) {
            // A selection/toggle rebuilds the inspector; discard the now-detached button focus
            // so arrows can immediately nudge a component selected by its name.
            if (getFocused() != null && !children().contains(getFocused())) setFocused(null);
            return true;
        }
        if (inPanel(x, y) || button != 0) return false;
        if (inHandle(x, y)) {
            resizing = true; resizeLeft = current().left(width, height); resizeTop = current().top(width, height);
            setFocused(null); return true;
        }
        var part = hit(x, y);
        if (part != null) {
            select(part); setFocused(null); dragging = true;
            dragX = x - current().left(width, height); dragY = y - current().top(width, height); return true;
        }
        setFocused(null); return false;
    }
    private double snap(double value, double size, int extent) {
        if (hasAltDown()) return value;
        for (double target : new double[]{4, (extent - size) / 2, extent - 4 - size}) if (Math.abs(value - target) <= 5) return target;
        return value;
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && resizing) {
            // Project onto the component's diagonal, preserving aspect ratio without favoring its long edge.
            double scale = ((x - resizeLeft) * selected.width + (y - resizeTop) * selected.height)
                    / (selected.width * selected.width + selected.height * selected.height);
            current().scale(scale); current().position(resizeLeft, resizeTop, width, height); syncSliders(); return true;
        }
        if (button == 0 && dragging) {
            double scale = current().fitScale(width, height);
            double left = snap(x - dragX, selected.width * scale, width), top = snap(y - dragY, selected.height * scale, height);
            guideX = left != x - dragX; guideY = top != y - dragY;
            current().position(left, top, width, height); return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        boolean handled = dragging || resizing;
        dragging = resizing = guideX = guideY = false;
        return super.mouseReleased(x, y, button) || handled;
    }
    @Override public boolean mouseScrolled(double x, double y, double vertical) {
        if (!inPanel(x, y)) {
            var part = hit(x, y);
            if (part != null) { if (part != selected) select(part); rescale(current().scale() + vertical * .05); syncSliders(); return true; }
        }
        return super.mouseScrolled(x, y, vertical);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_H) { togglePanel(); return true; }
        if (key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_UP) {
            int direction = key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP ? 1 : -1;
            // Adjust the displayed unit, independently of the slider's width.
            if (sizeSlider != null && getFocused() == sizeSlider) {
                if (selected == HudSettings.Part.LYRICS) {
                    double fontSize = Math.round(font.lineHeight * current().scale() * 10) / 10.0;
                    rescale((fontSize + direction) / font.lineHeight);
                } else rescale((Math.round(current().scale() * 100) + direction) / 100.0);
                syncSliders(); return true;
            }
            if (opacitySlider != null && getFocused() == opacitySlider) {
                current().opacity(1 - (Math.round((1 - current().opacity()) * 100) + direction) / 100.0);
                syncSliders(); return true;
            }
            if (getFocused() == null) {
                int step = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
                current().position(current().left(width, height) + (key == GLFW.GLFW_KEY_RIGHT ? step : key == GLFW.GLFW_KEY_LEFT ? -step : 0),
                        current().top(width, height) + (key == GLFW.GLFW_KEY_DOWN ? step : key == GLFW.GLFW_KEY_UP ? -step : 0), width, height); return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { if (settings.save()) net.minecraft.client.Minecraft.getInstance().setScreen(parent); else saveFailed = true; }
    @Override public boolean isPauseScreen() { return false; }

    private final class VisibilityButton extends ThemedButton {
        private final HudSettings.Part part;
        private VisibilityButton(HudSettings.Part part, int x, int y, int w) {
            super(x, y, w, 20, Component.translatable(settings.component(part).enabled() ? "modernmusic.hud.component_on" : "modernmusic.hud.component_off", tr(part.key())), false,
                    () -> { settings.component(part).enabled(!settings.component(part).enabled()); rebuildWidgets(); });
            this.part = part; plain();
        }
        @Override protected void drawContent(GuiGraphics g, int color) {
            toggle(g, getX() + (width - 24) / 2, getY() + 4, settings.component(part).enabled(), hover());
        }
    }
}
