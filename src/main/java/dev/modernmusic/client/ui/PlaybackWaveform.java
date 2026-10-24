package dev.modernmusic.client.ui;

import dev.modernmusic.client.PlaybackController;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

import static dev.modernmusic.client.ui.PlayerTheme.*;

/** A small audio-reactive ribbon, sharing the seek rail's width and the active theme's ink and geometry. */
final class PlaybackWaveform {
    private float[] target = new float[0], displayed = new float[0];
    private long sampledAt, renderedAt;

    void render(GuiGraphics g, PlaybackController player, int x, int y, int width, int height) {
        if (width < 8 || height < 2) return;
        int count = dev.modernmusic.compat.Java17.clamp(width / (pixel() ? 5 : 4), 2, 64);
        long now = Util.getMillis();
        if (target.length != count) {
            target = new float[count]; displayed = new float[count]; sampledAt = renderedAt = 0;
        }
        // Analysis is capped at 30 Hz and only happens while this part of the player is visible.
        if (sampledAt == 0 || now - sampledAt >= 33) {
            player.sampleWaveform(target); sampledAt = now;
        }
        float elapsed = renderedAt == 0 ? 16 : dev.modernmusic.compat.Java17.clamp(now - renderedAt, 0, 100);
        renderedAt = now;
        float spacing = width / (float) count, barWidth = pixel() ? 3 : Math.min(3, spacing * .5f);
        UiRenderMetrics.beginWaveform();
        // The smooth bars are adjacent, share a pose/scissor, and never interleave other GUI work.
        try (var batch = pixel() ? null : Shapes.batch(g)) {
            for (int i = 0; i < count; i++) {
                float response = target[i] > displayed[i] ? 55 : 160;
                displayed[i] += (target[i] - displayed[i]) * (float) (1 - Math.exp(-elapsed / response));
                float taper = .35f + .65f * (float) Math.sin(Math.PI * (i + .5) / count);
                float level = displayed[i] * taper;
                float barHeight = 2 + (height - 2) * level;
                float barX = x + (i + .5f) * spacing - barWidth / 2;
                float barY = y + (height - barHeight) / 2;
                if (batch == null) {
                    int h = Math.max(2, Math.round(barHeight / 2) * 2);
                    int left = Math.round(barX), top = y + (height - h) / 2;
                    var wave = skin() == null ? null : skin().wave;
                    int color = wave == null ? mix(SELECTED, ACCENT, .25f + .75f * level) : mix(wave.low(), wave.high(), .25f + .75f * level);
                    g.fill(left, top, left + 3, top + h, color);
                    if (h >= 6) g.fill(left, top, left + 3, top + 1, wave == null ? ACCENT_HI : wave.tip());
                } else {
                    int color = mix(QUIET, ACCENT, .45f + .55f * level);
                    batch.gradient(barX, barY, barWidth, barHeight, barWidth / 2,
                            alpha(color, .4 + .6 * level), alpha(color, .22 + .48 * level));
                }
            }
        } finally { UiRenderMetrics.endWaveform(); }
    }
}
