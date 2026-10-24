package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.HudSettings;
import dev.modernmusic.client.HudSettings.Theme;
import java.net.URI;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Theme and font previews apply immediately; Cancel restores both choices, Done or Esc saves them. */
public final class ThemeScreen extends MusicScreen {
    private final Screen parent;
    private final HudSettings settings;
    private final Supplier<URI> artwork;
    private final Theme originalTheme;
    private final HudSettings.FontStyle originalFont;
    private int left, top, panelWidth, panelHeight, gridY, rows, tileHeight, footerY;
    private boolean saveFailed;

    /** {@code artwork} is the current cover, shown on the cover theme's sample and followed by its accent. */
    public ThemeScreen(Screen parent, HudSettings settings, Supplier<URI> artwork) {
        super(Component.translatable("modernmusic.theme.title"));
        this.parent = parent; this.settings = settings; this.artwork = artwork;
        originalTheme = settings.theme(); originalFont = settings.font();
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        panelWidth = Math.min(560, width - 24);
        int columns = panelWidth >= 380 ? 5 : panelWidth >= 300 ? 4 : panelWidth >= 230 ? 3 : 2, gap = 8;
        rows = (Theme.values().length + columns - 1) / columns;
        // Header, tiles, a three-line description and the action row; tiles shrink first in short windows.
        panelHeight = Math.min(44 + rows * (64 + gap) + 36 + 34, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        gridY = top + 44; footerY = top + panelHeight - 32;
        tileHeight = dev.modernmusic.compat.Java17.clamp((footerY - 30 - gridY - (rows - 1L) * gap) / rows, 30, 64);
        int tileWidth = (panelWidth - 24 - (columns - 1) * gap) / columns;
        for (var theme : Theme.values()) {
            int column = theme.ordinal() % columns, row = theme.ordinal() / columns;
            addRenderableWidget(new Tile(theme, left + 12 + column * (tileWidth + gap), gridY + row * (tileHeight + gap), tileWidth, tileHeight));
        }
        var typeface = addRenderableWidget(new ThemedButton(left + 12, footerY, Math.min(130, panelWidth / 3), 22,
                Component.translatable("modernmusic.hud.font", Component.translatable(settings.font().key())), false, () -> {
            settings.font(settings.font().next()); UiFont.smooth(settings.font() == HudSettings.FontStyle.SMOOTH); rebuildWidgets();
        }));
        int action = Math.min(76, (panelWidth - 24 - typeface.getWidth() - 16) / 2), right = left + panelWidth - 12;
        addRenderableWidget(new ThemedButton(right - action * 2 - 6, footerY, action, 22, Component.translatable("gui.cancel"), false, this::cancel));
        addRenderableWidget(new ThemedButton(right - action, footerY, action, 22, Component.translatable("gui.done"), true, this::onClose));
        // Also remove the default tooltips inherited from themed buttons after every rebuild.
        children().forEach(child -> { if (child instanceof AbstractWidget widget) widget.setTooltip(null); });
    }
    private void choose(Theme theme) { settings.theme(theme); PlayerTheme.apply(theme); rebuildWidgets(); }
    private void cancel() {
        settings.theme(originalTheme); settings.font(originalFont);
        PlayerTheme.apply(originalTheme); UiFont.smooth(originalFont == HudSettings.FontStyle.SMOOTH);
        client().setScreen(parent);
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g) {
        if (client().level == null) renderDirtBackground(g);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        follow(artwork.get());
        super.renderMusic(g, x, y, delta);
        icon(g, Icon.PALETTE, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        int textY = gridY + rows * (tileHeight + 8) + 2, lines = Math.max(0, (footerY - 6 - textY) / 11);
        var help = font.split(Component.translatable(saveFailed ? "modernmusic.hud.save_failed" : settings.theme().key() + ".help"), panelWidth - 24);
        for (int i = 0; i < Math.min(lines, help.size()); i++) g.drawString(font, help.get(i), left + 12, textY + i * 11, saveFailed ? ERROR : MUTED, false);
    }
    @Override public void onClose() { if (settings.save()) client().setScreen(parent); else saveFailed = true; }

    /** A miniature player drawn with {@code theme}'s own palette, whatever the current theme is. */
    private final class Tile extends ThemedButton {
        private final Theme theme;
        private final Palette colors;
        private Tile(Theme theme, int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable(theme.key()), false, () -> choose(theme));
            this.theme = theme; colors = Palette.of(theme);
            plain();
        }
        @Override protected void drawContent(GuiGraphics g, int ignored) {
            int x = getX(), y = getY(), w = width, h = height;
            boolean chosen = settings.theme() == theme, square = colors.blocky() || colors.skin() != null;
            float r = square ? 0 : 9;
            // The chosen sample is framed in the current theme's accent.
            if (chosen) {
                if (blocky()) frame(g, x - 2, y - 2, w + 4, h + 4, 0xFFFFFFFF);
                else if (pixel()) { frame(g, x - 2, y - 2, w + 4, h + 4, ACCENT); frame(g, x - 3, y - 3, w + 6, h + 6, ACCENT); }
                else Shapes.ring(g, x - 3, y - 3, w + 6, h + 6, r + 3, 2, ACCENT);
            }
            if (colors.skin() != null) { drawSkin(g, colors.skin(), chosen); return; }
            Shapes.rect(g, x, y, w, h, r, colors.shell());
            // The cover theme's sample takes its colors from the current cover, as the theme itself does.
            int tint = theme == Theme.ADAPTIVE ? ArtworkCache.get().tint(artwork.get()) : 0;
            int accent = Palette.accentFrom(tint) != 0 ? Palette.accentFrom(tint) : colors.accent();
            int glow = Palette.glowFrom(tint) != 0 ? Palette.glowFrom(tint) : colors.glow();
            if (colors.glowStrength() > 0 && !square)
                Shapes.gradient(g, x, y, w, h * .8f, r, mix(colors.shell(), glow, colors.glowStrength()), colors.shell());
            if (square) { frame(g, x, y, w, h, ORE_OUTLINE); g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x1AFFFFFF); }
            else Shapes.ring(g, x, y, w, h, r, 1, alpha(colors.light() ? 0xFF000000 : 0xFFFFFFFF, hover() * .25 + .08));
            boolean roomy = h >= 50;
            int art = roomy ? h - 34 : h - 12, ax = x + 7, ay = y + 6;
            if (tint != 0) ArtworkCache.get().draw(g, artwork.get(), ax, ay, art, false, 1, false);
            else {
                int coverTop = theme == Theme.ADAPTIVE ? 0xFFFF8A65 : mix(colors.accent(), 0xFFFFFFFF, .25f);
                int coverBottom = theme == Theme.ADAPTIVE ? 0xFF7E57C2 : mix(colors.accent(), colors.shell(), .45f);
                Shapes.gradient(g, ax, ay, art, art, square ? 0 : Math.max(2, art * .14f), coverTop, coverBottom);
            }
            int bx = ax + art + 7, barWidth = x + w - 8 - bx;
            if (barWidth > 12) {
                Shapes.rect(g, bx, ay + 3, barWidth * .7f, 3, square ? 0 : 1.5f, colors.text());
                Shapes.rect(g, bx, ay + 10, barWidth * .45f, 3, square ? 0 : 1.5f, colors.muted());
                int railY = ay + art - 3;
                if (square) {
                    g.fill(bx, railY - 1, bx + barWidth, railY + 2, ORE_OUTLINE);
                    g.fill(bx + 1, railY, bx + barWidth * 11 / 20, railY + 1, ORE_GREEN_HI);
                } else {
                    Shapes.rect(g, bx, railY - 1, barWidth, 2, 1, alpha(colors.light() ? 0xFF000000 : 0xFFFFFFFF, .18));
                    Shapes.rect(g, bx, railY - 1, barWidth * .55f, 2, 1, accent);
                }
            }
            if (!roomy) return;
            var font = PlayerTheme.font();
            String name = ellipsis(font, getMessage().getString(), w - 30);
            g.drawString(font, name, x + 8, y + h - 15, colors.text(), square);
            if (chosen) {
                int cx = x + w - 13, cy = y + h - 11;
                if (square) Shapes.rect(g, cx - 6, cy - 6, 12, 12, 0, ORE_GREEN);
                else Shapes.circle(g, cx, cy, 6, accent);
                g.pose().pushPose(); g.pose().translate(cx - .5f, cy - .5f, 0); g.pose().scale(.7f, .7f, 1);
                icon(g, Icon.CHECK, 0, 0, square ? 0xFFFFFFFF : colors.ink());
                g.pose().popPose();
            }
        }
        /** A skin's sample from its own sprites: casing, title band, framed cover, two lines of text and its rail. */
        private void drawSkin(GuiGraphics g, Skin skin, boolean chosen) {
            int x = getX(), y = getY(), w = width, h = height;
            int border = spriteBorder(skin, Skin.Part.PANEL), inset = border + 3, frame = skin.geometry.slot();
            boolean roomy = h >= 50;
            sprite(g, skin, Skin.Part.PANEL, x, y, w, h, 1);
            sprite(g, skin, Skin.Part.HEADER, x, y, w, 13, 1);
            if (skin.crest()) {
                int cw = spriteSize(skin, Skin.Part.CREST, false), ch = spriteSize(skin, Skin.Part.CREST, true);
                sprite(g, skin, Skin.Part.CREST, x + (w - cw) / 2, y + skin.geometry.crest(), cw, ch, 1);
            }
            int ax = x + inset, ay = y + Math.max(inset, 16), bottom = roomy ? y + h - border - 16 : y + h - inset;
            int art = Math.max(6, Math.min(bottom - ay, (w - 2 * inset) / 2));
            sprite(g, skin, Skin.Part.SLOT, ax - frame, ay - frame, art + 2 * frame, art + 2 * frame, 1);
            Shapes.gradient(g, ax, ay, art, art, 0, mix(colors.accent(), 0xFFFFFFFF, .25f), mix(colors.accent(), colors.shell(), .45f));
            int bx = ax + art + frame + 4, barWidth = x + w - inset - bx;
            if (barWidth > 10) {
                Shapes.rect(g, bx, ay + 1, barWidth * .7f, 3, 0, colors.text());
                Shapes.rect(g, bx, ay + 7, barWidth * .45f, 3, 0, colors.muted());
                int rail = spriteSize(skin, Skin.Part.SLIDER_TRACK, true), railY = ay + art - rail;
                sprite(g, skin, Skin.Part.SLIDER_TRACK, bx, railY, barWidth, rail, 1);
                sprite(g, skin, Skin.Part.SLIDER_FILL, bx, railY, Math.max(2, barWidth * 11 / 20), rail, 1);
            }
            if (!roomy) return;
            var font = PlayerTheme.font();
            g.drawString(font, ellipsis(font, getMessage().getString(), w - 2 * inset - (chosen ? 18 : 0)), x + inset, y + h - border - 11, colors.text(), false);
            if (chosen) sprite(g, skin, Skin.Part.CHECKBOX_CHECKED, x + w - border - 17, y + h - border - 17, 16, 16, 1);
        }
    }
}
