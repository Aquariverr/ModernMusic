package dev.modernmusic.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.HudSettings;
import java.net.URI;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

/** Shared rendering primitives for rounded, OreUI and sprite-based themes. */
public final class PlayerTheme {
    // Current palette, reassigned on the render thread by apply() and, for the cover theme, follow().
    static int SHELL, CARD, FIELD, SELECTED;
    static int ACCENT, ACCENT_HI, INK, TEXT, MUTED, QUIET, ERROR, WARNING;
    static int HAIRLINE, SHADOW, TRACK, OVERLAY, TOOLTIP, GLOW;
    /** The accent over the game world: light themes' deep accents are lifted so they read over grass and caves. */
    static int WORLD_ACCENT;
    /** Title-band text colors, adjusted for skins with a light header. */
    static int HEAD_TEXT, HEAD_MUTED, HEAD_ACCENT;
    /** Input text colors, adjusted independently of the surrounding panel. */
    static int FIELD_TEXT, FIELD_HINT;
    private static boolean fieldLight;
    // OreUI chrome: dark outline, title band, and the raised gray / green faces with their highlight and depth.
    static final int ORE_OUTLINE = 0xFF1E1E1F, ORE_HEADER = 0xFF313233;
    static final int ORE_FACE = 0xFFD0D1D4, ORE_FACE_HOVER = 0xFFE8E9EC, ORE_FACE_HI = 0xFFF6F7F9, ORE_FACE_DEPTH = 0xFF58585A;
    static final int ORE_FACE_PRESSED = 0xFFA9AAAD, ORE_FACE_TEXT = 0xFF1E1E1F;
    static final int ORE_GREEN = 0xFF3C8527, ORE_GREEN_HOVER = 0xFF4C9A35, ORE_GREEN_HI = 0xFF5FB043, ORE_GREEN_DEPTH = 0xFF1D4D13;
    static final int ORE_DISABLED = 0xFF7B7C7F, ORE_DISABLED_DEPTH = 0xFF5B5C5E, ORE_DISABLED_TEXT = 0xFFB4B5B8;
    /** Text drawn over the game world keeps light ink in every theme. */
    static final int WORLD_TEXT = 0xFFF5F5F7, WORLD_MUTED = 0xFFCDD1D6;

    enum Icon {
        PLAY, PAUSE, STOP, NEXT, PREVIOUS, CLOSE, PLUS, MINUS, CHECK, LEFT, RIGHT, CLEAR, VOLUME, SEARCH, QUEUE, ACCOUNT,
        REPEAT, REPEAT_ONE, SHUFFLE, ORDER, MUSIC, GROUP, LAYOUT, HELP, PALETTE
    }

    private static HudSettings.Theme theme;
    private static Palette palette;
    private static Skin skin;
    private static final long[] followClock = new long[1];
    private static URI followed;
    private static int followedTint;
    private static long followLookup;
    private static final int PARTS = Skin.Part.values().length;
    private static final ResourceLocation[] sprites = new ResourceLocation[Skin.values().length * PARTS];
    // The title band drawn in the current frame (one GuiGraphics per frame), so ghost buttons on it take its ink.
    private static GuiGraphics bandFrame;
    private static int bandLeft, bandTop, bandRight, bandBottom;
    static { apply(HudSettings.Theme.ADAPTIVE); }

    private PlayerTheme() {}

    /** Switches every music screen and the HUD to {@code next}; artwork re-shapes itself on its next draw. */
    public static void apply(HudSettings.Theme next) {
        theme = next; palette = Palette.of(next); skin = palette.skin();
        boolean light = palette.light(), blocky = palette.blocky();
        OVERLAY = light ? 0xFF000000 : 0xFFFFFFFF;
        SHELL = palette.shell();
        CARD = palette.card(); FIELD = palette.field();
        TEXT = palette.text(); MUTED = palette.muted(); QUIET = palette.quiet();
        INK = palette.ink(); ERROR = palette.error(); WARNING = palette.warning();
        HAIRLINE = alpha(OVERLAY, light ? .1 : .07);
        SHADOW = light ? 0x38000000 : 0x8C000000;
        TRACK = blocky ? ORE_OUTLINE : alpha(OVERLAY, light ? .13 : .17);
        TOOLTIP = blocky ? ORE_HEADER : light ? mix(TEXT, 0xFFFFFFFF, .08f) : mix(SHELL, 0xFFFFFFFF, .07f);
        accent(palette.accent()); GLOW = palette.glow();
        var header = skin == null ? new Skin.Header(0, 0, 0) : skin.header;
        HEAD_TEXT = or(header.text(), TEXT); HEAD_MUTED = or(header.muted(), MUTED);
        var field = skin == null ? new Skin.Field(0, 0) : skin.field;
        FIELD_TEXT = or(field.text(), TEXT); FIELD_HINT = or(field.hint(), QUIET);
        fieldLight = Palette.luminance(FIELD) > .4;
        followed = null; followedTint = 0; followClock[0] = followLookup = 0;
    }
    private static int or(int color, int fallback) { return color != 0 ? color : fallback; }
    public static HudSettings.Theme theme() { return theme; }
    /** OreUI's own chrome: raised Bedrock faces, its outline and greens. */
    static boolean blocky() { return palette.blocky(); }
    /** Square geometry, pixel glyphs and GUI-pixel frames: OreUI and every textured skin. */
    public static boolean pixel() { return palette.blocky() || skin != null; }
    /** The textured skin of the current theme, or null for the drawn themes. */
    static Skin skin() { return skin; }
    static boolean light() { return palette.light(); }
    static boolean accentPlay() { return palette.accentPlay(); }
    /** Whether input fields are light, so a dark caret is needed inside text. */
    static boolean fieldLight() { return fieldLight; }

    private static void accent(int color) {
        ACCENT = color;
        // The cover theme's accent moves with the artwork; a plated title band keeps its own ink.
        HEAD_ACCENT = skin != null && skin.header.accent() != 0 ? skin.header.accent() : color;
        ACCENT_HI = mix(color, light() ? 0xFF000000 : 0xFFFFFFFF, light() ? .12f : .2f);
        SELECTED = mix(CARD, color, light() ? .18f : .2f);
        WORLD_ACCENT = skin != null ? skin.worldAccent : light() ? mix(color, 0xFFFFFFFF, .35f) : color;
    }

    /** The GUI-atlas sprite of a skin part. */
    private static ResourceLocation sprite(Skin skin, Skin.Part part) {
        int index = skin.ordinal() * PARTS + part.ordinal();
        var id = sprites[index];
        if (id == null) sprites[index] = id = new ResourceLocation(ModernMusic.MOD_ID, skin.sprite(part));
        return id;
    }
    public static void reloadSprites() { ThemeSprites.clear(); }

    /** A fixed-size part's own width or height in GUI pixels, e.g. a slider handle. */
    static int spriteSize(Skin skin, Skin.Part part, boolean height) {
        var contents = ThemeSprites.get(sprite(skin, part));
        return height ? contents.height() : contents.width();
    }
    /** A nine-sliced part's side border (left and right are equal), e.g. how thick a skin's panel frame is. */
    static int spriteBorder(Skin skin, Skin.Part part) {
        return ThemeSprites.get(sprite(skin, part)).left();
    }
    /** Draws a skin part over the bounds; nine-sliced parts tile their edges and centre, one texel per GUI pixel. */
    static void sprite(GuiGraphics g, Skin skin, Skin.Part part, int x, int y, int w, int h, float alpha) {
        if (w <= 0 || h <= 0 || alpha < .004f) return;
        g.flush(); // Text queued before the sprite must stay beneath it.
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        if (alpha < 1) g.setColor(1, 1, 1, alpha);
        ThemeSprites.get(sprite(skin, part)).draw(g, x, y, w, h);
        if (alpha < 1) g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }
    static void sprite(GuiGraphics g, Skin.Part part, int x, int y, int w, int h) { sprite(g, skin, part, x, y, w, h, 1); }
    /** A fixed-size part at its own size with its top-left at (x, y). */
    static void sprite(GuiGraphics g, Skin.Part part, int x, int y) {
        sprite(g, skin, part, x, y, spriteSize(skin, part, false), spriteSize(skin, part, true), 1);
    }

    /** Whether (x, y) lies on the title band drawn in this frame, where the header ink applies. */
    static boolean onHeader(GuiGraphics g, int x, int y) {
        return g == bandFrame && x >= bandLeft && x < bandRight && y >= bandTop && y < bandBottom;
    }

    /** The cover theme eases its accent and stage light toward {@code artwork}'s colors; other themes keep theirs. */
    static void follow(URI artwork) {
        if (theme != HudSettings.Theme.ADAPTIVE) return;
        long now = Util.getMillis();
        // Until the cover has loaded its color is unknown; look again a few times a second, not every frame.
        if (!java.util.Objects.equals(artwork, followed) || followedTint == 0 && now - followLookup > 250) {
            followed = artwork; followLookup = now; followedTint = artwork == null ? 0 : ArtworkCache.get().tint(artwork);
        }
        int accent = Palette.accentFrom(followedTint), glow = Palette.glowFrom(followedTint);
        if (accent == 0) accent = palette.accent();
        if (glow == 0) glow = palette.glow();
        long elapsed = followClock[0] == 0 ? 10_000 : now - followClock[0];
        followClock[0] = now;
        float step = Math.min(1, elapsed / 350f);
        accent(mix(ACCENT, accent, step)); GLOW = mix(GLOW, glow, step);
    }

    static int playerPanelWidth(int width) { return Math.min(740, width - 20); }
    static int playerPanelHeight(int height) { return Math.min(460, height - 16); }

    /** The UI font for the current GUI scale (smooth TTF unless disabled). */
    static Font font() { return UiFont.get(); }

    /** Keep the reference's 3:2 title/body ratio as the music viewport scales. */
    static float titleScale() {
        return UiFont.get() == Minecraft.getInstance().font ? 1 : 1.5f;
    }
    static Font titleFont() { return UiFont.get(titleScale()); }
    static int titleWidth(String text) { return (int) Math.ceil(titleFont().width(text) * titleScale()); }
    /**
     * Draws a headline whose glyphs are vertically centered on {@code centerY}. The single-weight smooth font gains a
     * semibold weight from a second pass one physical pixel to the right.
     */
    static void title(GuiGraphics g, String text, int x, int centerY, int color) {
        float scale = titleScale();
        var font = titleFont();
        g.pose().pushPose();
        g.pose().translate(x, centerY - 4 * scale, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, color, false);
        if (font != Minecraft.getInstance().font) g.drawString(font, text, 1 / Shapes.pixelScale(g), 0, color, false);
        g.pose().popPose();
    }

    /**
     * A screen's own heading. OreUI sets it in the Minecraft typeface with its shadow, as Bedrock title bars do; the
     * skins use the same pixel typeface their reference mods title screens with, shadowed only on dark skins.
     */
    static void heading(GuiGraphics g, String text, int x, int centerY, int color) {
        if (pixel()) g.drawString(Minecraft.getInstance().font, text, x, centerY - 4, color, blocky() || skin.headingShadow);
        else title(g, text, x, centerY, color);
    }
    static int headingWidth(String text) { return pixel() ? Minecraft.getInstance().font.width(text) : titleWidth(text); }

    /** Body text at its natural size; never uses the vanilla drop shadow, which smears on light surfaces. */
    static void text(GuiGraphics g, String text, int x, int y, int color) { g.drawString(font(), text, x, y, color, false); }
    static void centered(GuiGraphics g, String text, int centerX, int y, int color) {
        var font = font();
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** Text over the game world: a soft one-pixel drop shadow for the smooth font, the vanilla shadow otherwise. */
    static void worldText(GuiGraphics g, Font font, FormattedCharSequence text, float x, float y, int color) {
        if (font == Minecraft.getInstance().font) { g.drawString(font, text, x, y, color, true); UiRenderMetrics.hudText(1); return; }
        // A tight shadow plus a softer one a pixel further keeps light text readable over sky and snow.
        float pixels = Shapes.pixelScale(g), offset = Math.max(1, Math.round(pixels * .5f)) / pixels;
        int alpha = color >>> 24;
        // Keep all three layers in order in the same text batch. Flush preceding GUI work before the scope and
        // finish before any later shapes or scissor changes; atlas switches may still submit within the batch.
        g.flush();
        var pose = g.pose().last().pose();
        var buffers = g.bufferSource();
        font.drawInBatch(text, x + offset * 2, y + offset * 2, Math.max(4, Math.round(alpha * .28f)) << 24 | 0x020509,
                false, pose, buffers, Font.DisplayMode.NORMAL, 0, 15728880);
        font.drawInBatch(text, x + offset, y + offset, Math.max(4, Math.round(alpha * .78f)) << 24 | 0x020509,
                false, pose, buffers, Font.DisplayMode.NORMAL, 0, 15728880);
        font.drawInBatch(text, x, y, color, false, pose, buffers, Font.DisplayMode.NORMAL, 0, 15728880);
        g.flush();
        UiRenderMetrics.hudText(2);
    }
    static void worldText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        worldText(g, font, FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY), x, y, color);
    }

    static String ellipsis(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    /** A filled shape: rounded in modern themes, square in OreUI and the skins. */
    static void surface(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        Shapes.rect(g, x, y, w, h, pixel() ? 0 : r, color);
    }
    static void round(GuiGraphics g, int x, int y, int w, int h, int r, int color) { surface(g, x, y, w, h, r, color); }
    /** A dot centered on (cx, cy); pixel themes draw it as a square pixel block. */
    static void dot(GuiGraphics g, float cx, float cy, float r, int color) {
        if (pixel()) Shapes.rect(g, cx - r, cy - r, r * 2, r * 2, 0, color);
        else Shapes.circle(g, cx, cy, r, color);
    }
    /** A disc centered on the pixel (x, y), as wide as the old pixel circle of the same radius. */
    static void circle(GuiGraphics g, int x, int y, int radius, int color) { dot(g, x + .5f, y + .5f, radius + .5f, color); }

    static void icon(GuiGraphics g, Icon icon, int x, int y, int color) { Icons.draw(g, icon, x, y, color); }

    /** One-GUI-pixel border inside the given bounds, in GUI pixels so OreUI edges stay chunky. */
    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color); g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A dialog surface: soft lift in modern themes, an outlined slab in OreUI, the skin's casing otherwise. */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int r) {
        if (skin != null) {
            g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x59000000);
            sprite(g, Skin.Part.PANEL, x, y, w, h);
            if (skin.crest()) {
                int cw = spriteSize(skin, Skin.Part.CREST, false);
                sprite(g, Skin.Part.CREST, x + (w - cw) / 2, y + skin.geometry.crest());
            }
            return;
        }
        if (blocky()) {
            g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
            g.fill(x, y, x + w, y + h, SHELL);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x1AFFFFFF);
            frame(g, x, y, w, h, ORE_OUTLINE);
            return;
        }
        Shapes.shadow(g, x, y + 6, w, h, r + 2, 18, SHADOW);
        Shapes.rect(g, x, y, w, h, r + 2, SHELL);
        Shapes.ring(g, x, y, w, h, r + 2, 1, HAIRLINE);
    }

    /**
     * The title band of a panel {@code height} tall: OreUI's darker strip; a skin's plate or title rule, which reaches
     * three pixels further so its ornament sits below the header's buttons; modern panels stay one surface.
     */
    static void header(GuiGraphics g, int x, int y, int w, int height) {
        if (skin != null) {
            sprite(g, Skin.Part.HEADER, x, y, w, height + 3);
            bandFrame = g; bandLeft = x; bandTop = y; bandRight = x + w; bandBottom = y + height + 3;
            return;
        }
        if (!blocky()) return;
        g.fill(x + 1, y + 1, x + w - 1, y + height, ORE_HEADER);
        g.fill(x + 1, y + height, x + w - 1, y + height + 1, ORE_OUTLINE);
    }

    /** A raised section in modern themes; a sunken, outlined well in OreUI; the skin's card. */
    static void card(GuiGraphics g, int x, int y, int w, int h, int r) {
        if (skin != null) { sprite(g, Skin.Part.CARD, x, y, w, h); return; }
        if (blocky()) {
            g.fill(x, y, x + w, y + h, CARD);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x33000000);
            frame(g, x, y, w, h, ORE_OUTLINE);
            return;
        }
        Shapes.rect(g, x, y, w, h, r, CARD);
    }

    /** Behind a list: OreUI and the skins sink it into a well; modern themes leave rows directly on the panel. */
    static void well(GuiGraphics g, int x, int y, int w, int h, int r) {
        if (skin != null) sprite(g, Skin.Part.WELL, x, y, w, h);
        else if (blocky()) card(g, x, y, w, h, r);
    }
    static void tabStrip(GuiGraphics g, int x, int y, int w, int h, int r) {
        if (skin != null) sprite(g, Skin.Part.WELL, x, y, w, h);
        else surface(g, x, y, w, h, r, FIELD);
    }

    /**
     * The now-playing card, lit from its top edge by the theme's glow (the cover's color in the cover theme). The
     * light is mixed into opaque colors, so it fades smoothly with any shader; it ends on the card color, which hides
     * the rounded lower corners of the lit area.
     */
    static void stage(GuiGraphics g, int x, int y, int w, int h, int r) {
        if (skin != null) { sprite(g, Skin.Part.STAGE, x, y, w, h); return; }
        card(g, x, y, w, h, r);
        float strength = palette.glowStrength();
        if (strength <= 0) return;
        Shapes.gradient(g, x, y, w, h * .78f, r, mix(CARD, GLOW | 0xFF000000, strength), CARD);
    }

    /** Input well; focus adds a quiet accent edge. */
    static void field(GuiGraphics g, int x, int y, int w, int h, int r, boolean focused) {
        if (skin != null) { sprite(g, focused ? Skin.Part.FIELD_FOCUSED : Skin.Part.FIELD, x, y, w, h); return; }
        if (blocky()) {
            g.fill(x, y, x + w, y + h, FIELD);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x4D000000);
            frame(g, x, y, w, h, focused ? 0xFFFFFFFF : ORE_OUTLINE);
            return;
        }
        Shapes.rect(g, x, y, w, h, r, FIELD);
        if (focused) Shapes.ring(g, x, y, w, h, r, focusPixels(g), alpha(ACCENT, .75));
        else if (!light()) Shapes.ring(g, x, y, w, h, r, 1, HAIRLINE);
    }

    /** Keyboard focus outline around a control; never shown for mouse hover alone. */
    static void focusRing(GuiGraphics g, int x, int y, int w, int h, float r) {
        if (skin != null) { sprite(g, Skin.Part.FOCUS, x - 2, y - 2, w + 4, h + 4); return; }
        if (blocky()) { frame(g, x - 1, y - 1, w + 2, h + 2, 0xFFFFFFFF); return; }
        Shapes.ring(g, x - 2, y - 2, w + 4, h + 4, r + 2, focusPixels(g), alpha(TEXT, .7));
    }
    private static int focusPixels(GuiGraphics g) { return Math.max(1, Math.round(Shapes.pixelScale(g) * .75f)); }

    /** A thin divider line; skins draw their rule (a brass rail, a curled line, a starred gold line) centered on it. */
    static void divider(GuiGraphics g, int x, int y, int w) {
        if (skin != null) {
            int h = spriteSize(skin, Skin.Part.DIVIDER, true);
            sprite(g, Skin.Part.DIVIDER, x, y - h / 2, w, h);
        } else if (blocky()) g.fill(x, y, x + w, y + 1, ORE_OUTLINE);
        else Shapes.rect(g, x, y, w, 1, 0, HAIRLINE);
    }

    /**
     * A raised OreUI face inside an outline, {@code depth} GUI pixels tall at the bottom unless pressed in.
     * Returns the vertical offset that centers content on the visible face.
     */
    static int raised(GuiGraphics g, int x, int y, int w, int h, int face, int highlight, int depth, boolean pressed) {
        int d = h >= 24 ? 3 : 2;
        g.fill(x, y, x + w, y + h, ORE_OUTLINE);
        int top = y + 1 + (pressed ? d : 0), bottom = y + h - 1 - (pressed ? 0 : d);
        if (!pressed) g.fill(x + 1, bottom, x + w - 1, y + h - 1, depth);
        g.fill(x + 1, top, x + w - 1, bottom, face);
        if (highlight != 0) g.fill(x + 1, top, x + w - 1, top + 1, highlight);
        return pressed ? d / 2 + d % 2 : -(d / 2);
    }

    /** Darkens whatever is behind a screen (world blur, panorama) so panels read clearly. */
    static void scrim(GuiGraphics g, int width, int height) {
        if (skin != null) { g.fillGradient(0, 0, width, height, skin.scrimTop, skin.scrimBottom); return; }
        if (blocky()) { g.fill(0, 0, width, height, 0xA6000000); return; }
        int tint = light() ? 0x000000 : SHELL & 0xFFFFFF;
        g.fillGradient(0, 0, width, height, (light() ? 0x66 : 0x9E) << 24 | tint, (light() ? 0x8C : 0xC8) << 24 | tint);
    }

    /** A pill rail with an accent fill and a knob that grows on hover; shared by every slider. */
    static void track(GuiGraphics g, int x, int centerY, int width, double value, boolean active, float hover) {
        float knob = x + (float) (width * dev.modernmusic.compat.Java17.clamp(value, 0, 1));
        if (skin != null) {
            int rail = spriteSize(skin, Skin.Part.SLIDER_TRACK, true), top = centerY - rail / 2, fill = Math.round(knob) - x;
            sprite(g, Skin.Part.SLIDER_TRACK, x, top, width, rail);
            if (active && fill > 0) sprite(g, Skin.Part.SLIDER_FILL, x, top, Math.max(fill, 2), rail);
            var handle = !active ? Skin.Part.SLIDER_HANDLE_DISABLED : hover > .5f ? Skin.Part.SLIDER_HANDLE_HOVERED : Skin.Part.SLIDER_HANDLE;
            int hw = spriteSize(skin, handle, false), hh = spriteSize(skin, handle, true);
            sprite(g, handle, dev.modernmusic.compat.Java17.clamp(Math.round(knob) - hw / 2, x - hw / 2, x + width - hw / 2 - 1), centerY - hh / 2);
            return;
        }
        if (blocky()) {
            g.fill(x, centerY - 2, x + width, centerY + 2, ORE_OUTLINE);
            g.fill(x + 1, centerY - 1, x + width - 1, centerY + 1, ORE_HEADER);
            int fill = Math.round(knob);
            if (fill > x + 1) g.fill(x + 1, centerY - 1, Math.min(x + width - 1, fill), centerY + 1, active ? ORE_GREEN_HI : QUIET);
            int left = dev.modernmusic.compat.Java17.clamp(fill - 3, x - 3, x + width - 3);
            raised(g, left, centerY - 6, 6, 12, active ? mix(ORE_FACE, ORE_FACE_HOVER, hover) : ORE_DISABLED,
                    active ? ORE_FACE_HI : 0, active ? ORE_FACE_DEPTH : ORE_DISABLED_DEPTH, false);
            return;
        }
        Shapes.rect(g, x, centerY - 1.5f, width, 3, 1.5f, TRACK);
        if (knob - x >= 1) Shapes.rect(g, x, centerY - 1.5f, knob - x, 3, 1.5f, active ? ACCENT : QUIET);
        // Light themes keep a white knob lifted by its shadow at rest; dark themes show a small light dot.
        float radius = (light() ? 4 : 2.5f) + hover * (light() ? 1 : 2.5f);
        if (light() || hover > .05f) Shapes.shadow(g, knob - radius, centerY - radius + .5f, radius * 2, radius * 2, radius, 1.5f, light() ? 0x40000000 : 0x55000000);
        Shapes.circle(g, knob, centerY, radius, active ? light() ? 0xFFFFFFFF : TEXT : QUIET);
        if (light()) Shapes.ring(g, knob - radius, centerY - radius, radius * 2, radius * 2, radius, 1, HAIRLINE);
    }

    /** An on/off switch 24 × 12 GUI pixels with its top-left at (x, y). */
    static void toggle(GuiGraphics g, int x, int y, boolean on, float hover) {
        if (skin != null) {
            sprite(g, on ? Skin.Part.TOGGLE_ON : Skin.Part.TOGGLE_OFF, x, y);
            var handle = hover > .5f ? Skin.Part.TOGGLE_HANDLE_HOVERED : Skin.Part.TOGGLE_HANDLE;
            int hw = spriteSize(skin, handle, false), hh = spriteSize(skin, handle, true);
            sprite(g, handle, (on ? x + 12 : x) + (12 - hw) / 2, y + (12 - hh) / 2);
            return;
        }
        if (blocky()) {
            g.fill(x, y, x + 24, y + 12, ORE_OUTLINE);
            g.fill(x + 1, y + 1, x + 23, y + 11, on ? ORE_GREEN : ORE_HEADER);
            if (on) g.fill(x + 5, y + 4, x + 6, y + 8, 0xFFFFFFFF);
            else frame(g, x + 15, y + 4, 4, 4, 0xFF8C8D90);
            raised(g, on ? x + 12 : x, y - 1, 12, 14, mix(ORE_FACE, ORE_FACE_HOVER, hover), ORE_FACE_HI, ORE_FACE_DEPTH, false);
            return;
        }
        Shapes.rect(g, x, y, 24, 12, 6, on ? ACCENT : alpha(OVERLAY, light() ? .18 : .22));
        float knob = on ? x + 18 : x + 6;
        Shapes.shadow(g, knob - 4.5f, y + 1.5f, 9, 9, 4.5f, 1, 0x55000000);
        Shapes.circle(g, knob, y + 6, 4.5f, 0xFFFFFFFF);
    }

    /** A 16-pixel checkbox with its top-left at (x, y), in skins only; other themes draw their own. */
    static void checkbox(GuiGraphics g, int x, int y, boolean checked, boolean hovered) {
        sprite(g, checked ? Skin.Part.CHECKBOX_CHECKED : hovered ? Skin.Part.CHECKBOX_HOVERED : Skin.Part.CHECKBOX, x, y);
    }
    /** The skin's frame around a square image of {@code size}; it sits outside the image bounds. */
    static void artFrame(GuiGraphics g, int x, int y, int size, double opacity) {
        int inset = skin.geometry.slot();
        sprite(g, skin, Skin.Part.SLOT, x - inset, y - inset, size + 2 * inset, size + 2 * inset, (float) opacity);
    }
    /** A small count badge: the skin's chip, or a filled pill. */
    static void badge(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (skin != null) sprite(g, Skin.Part.BADGE, x, y, w, h);
        else surface(g, x, y, w, h, h / 2f, color);
    }
    /** A list row's highlight in skins: the selected marker, or the hover wash. */
    static void row(GuiGraphics g, int x, int y, int w, int h, boolean selected, float hover) {
        if (selected) sprite(g, Skin.Part.ROW_SELECTED, x, y, w, h);
        else if (hover > .5f) sprite(g, Skin.Part.ROW_HOVERED, x, y, w, h);
    }

    static int alpha(int color, double opacity) {
        return (color & 0xFFFFFF) | (int) Math.round((color >>> 24) * dev.modernmusic.compat.Java17.clamp(opacity, 0, 1)) << 24;
    }

    static int mix(int from, int to, float t) { return Shapes.mix(from, to, t); }

    /** Eases {@code current} toward {@code target} at a frame-rate independent speed (~120 ms). */
    static float approach(float current, float target, long[] clock) {
        long now = Util.getMillis(), elapsed = clock[0] == 0 ? 1000 : now - clock[0];
        clock[0] = now;
        float step = Math.min(1, elapsed / 120f);
        return current + (target - current) * step;
    }

    static void sharedAccountBadge(GuiGraphics g, int x, int y, int color) {
        circle(g, x, y, 5, SHELL);
        circle(g, x, y, 4, color);
        Shapes.rect(g, x - 2, y - .5f, 4, 1.5f, .5f, INK);
        circle(g, x - 2, y, 1, INK); circle(g, x + 2, y - 2, 1, INK); circle(g, x + 2, y + 2, 1, INK);
    }

    /** Four bars 11 units wide and 10 tall; they dance while playing and rest as a static glyph otherwise. */
    static void equalizer(GuiGraphics g, int x, int y, boolean playing, long position, int color) {
        for (int i = 0; i < 4; i++) {
            float h = playing ? 3 + (float) (7 * (0.5 + Math.sin(position / 180.0 + i * 1.7) * 0.5)) : 3 + (i % 2) * 4;
            surface(g, x + i * 3, y + 10 - h, 2, h, 1, color);
        }
    }
}
