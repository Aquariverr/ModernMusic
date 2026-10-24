package dev.modernmusic.client.ui;

import dev.modernmusic.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/**
 * Background-free HUD components with independent opacity. Text stays light over the world; accents follow the theme.
 */
public final class MusicHud {
    private MusicHud() {}
    public static void render(GuiGraphics g, PlaybackController player, HudSettings settings, int width, int height, boolean preview) {
        var game = Minecraft.getInstance();
        if (!preview && (!settings.enabled() || game.options.hideGui || game.level == null || game.screen != null || player == null || player.track() == null)) return;
        settings.ensureLayout(width, height);
        follow(player == null || player.track() == null ? null : player.track().artwork());
        for (var part : HudSettings.Part.values()) {
            var transform = settings.component(part);
            if (!transform.enabled() || transform.opacity() < .01) continue;
            g.pose().pushPose();
            g.pose().translate(transform.left(width, height), transform.top(width, height), 0);
            float scale = (float) transform.fitScale(width, height); g.pose().scale(scale, scale, 1);
            // The cover has no text and must not allocate a font atlas for its independent size.
            drawPart(g, part, player, transform.opacity(), settings.lyricStyle(),
                    part == HudSettings.Part.COVER ? null : UiFont.get(scale)); g.pose().popPose();
        }
    }
    private static void drawPart(GuiGraphics g, HudSettings.Part part, PlaybackController player, double opacity, HudSettings.LyricStyle lyricStyle, Font font) {
        var track = player == null ? null : player.track(); var audio = player == null ? null : player.audio();
        long position = audio == null ? 0 : audio.positionMs();
        long duration = audio == null || audio.durationMs() <= 0 ? track == null ? 180000 : track.durationMs() : audio.durationMs();
        int textColor = alpha(WORLD_TEXT, opacity), muted = alpha(WORLD_MUTED, opacity), accent = alpha(WORLD_ACCENT, opacity);
        switch (part) {
            case COVER -> {
                if (skin() != null) artFrame(g, 0, 0, part.width, opacity);
                else if (blocky()) frame(g, -1, -1, part.width + 2, part.width + 2, alpha(ORE_OUTLINE, opacity));
                else Shapes.shadow(g, 0, 1.5f, part.width, part.width, part.width * ArtworkCache.CORNER, 4, PlayerTheme.alpha(0x59000000, opacity));
                ArtworkCache.get().draw(g, track == null ? null : track.artwork(), 0, 0, part.width, false, opacity, false);
            }
            case INFO -> {
                String song = ellipsis(font, track == null ? text("modernmusic.hud.preview_song") : track.title(), part.width);
                worldText(g, font, song, 0, 1, textColor);
                // The song title is the one heavier line: one physical pixel of extra weight on the smooth font.
                if (font != Minecraft.getInstance().font) g.drawString(font, song, 1 / Shapes.pixelScale(g), 1, textColor, false);
                worldText(g, font, ellipsis(font, track == null ? text("modernmusic.hud.preview_artist") : track.artistText(), part.width), 0, 15, muted);
                var source = player == null ? null : player.sharedSource();
                boolean unavailable = source != null && source.selected() && !source.available();
                boolean playing = player == null || player.state() == PlaybackController.State.PLAYING;
                if (!unavailable) equalizer(g, 0, 30, playing, position, accent);
                worldText(g, font, ellipsis(font, status(player), part.width - (unavailable ? 0 : 16)), unavailable ? 0 : 16, 31,
                        unavailable ? alpha(ERROR, opacity) : muted);
            }
            case LYRICS -> {
                var line = player == null ? null : player.lyrics().at(position);
                String lyric = line != null ? line.text() : text(track == null ? "modernmusic.hud.preview_lyric" : player.lyrics().lines().isEmpty() ? player.lyricMessage() : "modernmusic.lyrics.waiting");
                drawLyric(g, font, lyric, 1, part.width, textColor, lyricStyle);
                if (line != null && !line.translation().isBlank()) drawLyric(g, font, line.translation(), 16, part.width, muted, lyricStyle);
            }
            case PROGRESS -> {
                String current = time(position), total = time(duration);
                int slot = Math.max(font.width(current), font.width(total)), left = slot + 6, bar = part.width - 2 * left;
                worldText(g, font, current, slot - font.width(current), 7, muted);
                worldText(g, font, total, part.width - slot, 7, muted);
                float fraction = (float) dev.modernmusic.compat.Java17.clamp((double) position / Math.max(1, duration), 0, 1);
                if (skin() != null) {
                    // The skin's own gauge: its slider rail with the fill, without the handle.
                    int rail = spriteSize(skin(), Skin.Part.SLIDER_TRACK, true), fill = Math.round(bar * fraction);
                    sprite(g, skin(), Skin.Part.SLIDER_TRACK, left, 11 - rail / 2, bar, rail, (float) opacity);
                    if (fill > 0) sprite(g, skin(), Skin.Part.SLIDER_FILL, left, 11 - rail / 2, Math.max(2, fill), rail, (float) opacity);
                } else if (blocky()) {
                    g.fill(left, 8, left + bar, 14, alpha(ORE_OUTLINE, opacity));
                    g.fill(left + 1, 9, left + bar - 1, 13, alpha(0xFF3A3B3C, opacity));
                    int fill = Math.round((bar - 2) * fraction);
                    if (fill > 0) {
                        g.fill(left + 1, 9, left + 1 + fill, 13, alpha(ORE_GREEN, opacity));
                        g.fill(left + 1, 9, left + 1 + fill, 10, alpha(ORE_GREEN_HI, opacity));
                    }
                } else {
                    Shapes.rect(g, left, 9.5f, bar, 3, 1.5f, PlayerTheme.alpha(0x8C000000, opacity * .7));
                    if (fraction > 0) Shapes.rect(g, left, 9.5f, Math.max(3, bar * fraction), 3, 1.5f, accent);
                }
            }
        }
    }
    public static String status(PlaybackController player) {
        if (player == null) return text("modernmusic.mode.repeat_all");
        var source = player.sharedSource();
        boolean server = source != null && source.selected();
        String status = server && !source.available() ? text(source.statusKey())
                : text(player.shared() ? player.sharedModeKey() : player.queue().mode().translationKey()) + " · "
                + text(player.state() == PlaybackController.State.PLAYING ? "modernmusic.hud.playing"
                : player.state() == PlaybackController.State.STOPPED ? "modernmusic.hud.stopped"
                : "modernmusic.status." + player.state().name().toLowerCase(java.util.Locale.ROOT));
        return server ? text("modernmusic.shared.source_short") + " · " + status : status;
    }
    private static void drawLyric(GuiGraphics g, Font font, String text, int y, int width, int color, HudSettings.LyricStyle setting) {
        // Vanilla bold re-draws one GUI pixel to the right, which smears smooth glyphs; offset one screen pixel instead.
        boolean smoothBold = setting.bold && font != Minecraft.getInstance().font;
        float bold = smoothBold ? 1 / Shapes.pixelScale(g) : 0;
        var style = Style.EMPTY.withBold(setting.bold && !smoothBold).withItalic(setting.italic);
        var component = Component.literal(text).setStyle(style);
        FormattedText display = component;
        if (font.width(component) + bold > width) {
            var suffix = Component.literal("…").setStyle(style);
            display = FormattedText.composite(font.substrByWidth(component, Math.max(0, width - font.width(suffix) - (int) Math.ceil(bold))), suffix);
        }
        var sequence = net.minecraft.locale.Language.getInstance().getVisualOrder(display);
        worldText(g, font, sequence, 0, y, color);
        if (smoothBold) g.drawString(font, sequence, bold, y, color, false);
    }
    static int alpha(int color, double opacity) { return (color & 0xFFFFFF) | Math.max(4, (int) Math.round(255 * dev.modernmusic.compat.Java17.clamp(opacity, 0, 1))) << 24; }
    static String text(String key) { return Component.translatable(key).getString(); }
    static String time(long ms) {
        long sec = Math.max(0, ms) / 1000, remainder = sec % 60;
        return sec / 60 + (remainder < 10 ? ":0" : ":") + remainder;
    }
}
