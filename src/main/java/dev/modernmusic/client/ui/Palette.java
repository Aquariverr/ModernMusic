package dev.modernmusic.client.ui;

import dev.modernmusic.client.HudSettings.Theme;

/**
 * Base colors used by {@link PlayerTheme} to derive interaction states.
 * {@code glow} and {@code glowStrength} control the now-playing card's backdrop and top-edge opacity.
 * {@code accentPlay} uses the accent for the play button. With a {@code skin}, surface colors represent its sprites.
 */
record Palette(boolean light, boolean blocky, int shell, int card, int field, int text, int muted, int quiet,
               int accent, int ink, int error, int warning, int glow, float glowStrength, boolean accentPlay, Skin skin) {

    static Palette of(Theme theme) {
        return switch (theme) {
            // The accent follows the current cover.
            case ADAPTIVE -> new Palette(false, false, 0xFF19191C, 0xFF222226, 0xFF2A2A2F,
                    0xFFF4F4F5, 0xFFA5A5AD, 0xFF6E6E77, 0xFFFF7A90, 0xFF1A1014, 0xFFFF8A80, 0xFFE8C27A, 0xFF3E3E48, .62f, false, null);
            case VINYL -> new Palette(false, false, 0xFF1D1B18, 0xFF27241F, 0xFF2D2A25,
                    0xFFF3EEE5, 0xFFB4AB9C, 0xFF7A7166, 0xFFE9B44C, 0xFF241A06, 0xFFFF8F7A, 0xFFE9B44C, 0xFFB8862E, .18f, false, null);
            case MIDNIGHT -> new Palette(false, false, 0xFF121729, 0xFF1A2138, 0xFF1D2541,
                    0xFFEEF1FA, 0xFF9CA6C6, 0xFF66729A, 0xFF8FA3FF, 0xFF0F1433, 0xFFFF8F9E, 0xFFF0C978, 0xFF4B5BC4, .3f, false, null);
            case DAYLIGHT -> new Palette(true, false, 0xFFF4F4F6, 0xFFFFFFFF, 0xFFE8E8EC,
                    0xFF1C1C1F, 0xFF5D5D66, 0xFF8E8E97, 0xFFD81B60, 0xFFFFFFFF, 0xFFC2410C, 0xFF9A6200, 0xFFF06292, .1f, false, null);
            case MINT -> new Palette(true, false, 0xFFF1F8F4, 0xFFFFFFFF, 0xFFE8EFEB,
                    0xFF10291D, 0xFF4F6B5E, 0xFF86A094, 0xFF0A8550, 0xFFFFFFFF, 0xFFC0392B, 0xFF946000, 0xFF7EE0AE, .3f, true, null);
            // Buttons use the raised faces in PlayerTheme.
            case OREUI -> new Palette(false, true, 0xFF48494A, 0xFF313233, 0xFF313233,
                    0xFFFFFFFF, 0xFFC6C7CA, 0xFF9A9B9E, 0xFF6CC349, 0xFFFFFFFF, 0xFFFF8080, 0xFFFFD84A, 0, 0, false, null);
            case CREATE -> new Palette(false, false, 0xFF412A14, 0xFF424242, 0xFF14130B,
                    0xFFEEEEEE, 0xFFB8B8B8, 0xFF858585, 0xFFF7CB6C, 0xFF592424, 0xFFFF8F85, 0xFFFFD27A, 0, 0, false, Skin.CREATE);
            case AE2 -> new Palette(true, false, 0xFFCBCCD4, 0xFFC4C6D1, 0xFF4D4D67,
                    0xFF2E2C40, 0xFF4E5068, 0xFF7E8399, 0xFF5A479E, 0xFFFFFFFF, 0xFFA81C00, 0xFF8A5600, 0, 0, false, Skin.AE2);
            case ARS_NOUVEAU -> new Palette(true, false, 0xFFFBF4E0, 0xFFF7EDD3, 0xFFF3E7C8,
                    0xFF3D1F2F, 0xFF6A5048, 0xFFA08C78, 0xFF7E3AA6, 0xFFFBEAFB, 0xFFB3261E, 0xFF8A5A00, 0, 0, false, Skin.ARS_NOUVEAU);
            case ASTRAL_SORCERY -> new Palette(false, false, 0xFF131D31, 0xFF202020, 0xFF1A1A1A,
                    0xFFE6E6EE, 0xFFA3ABC7, 0xFF6F7795, 0xFFE8C964, 0xFF1E1E1E, 0xFFFF7A6B, 0xFFFFE539, 0, 0, false, Skin.ASTRAL_SORCERY);
        };
    }

    /** Hue (0–1), saturation and lightness of an RGB color; alpha is ignored. */
    static float[] hsl(int rgb) {
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), l = (max + min) / 2, d = max - min;
        if (d < 1e-6f) return new float[]{0, 0, l};
        float s = d / (1 - Math.abs(2 * l - 1));
        float h = max == r ? (g - b) / d + (g < b ? 6 : 0) : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        return new float[]{h / 6, Math.min(1, s), l};
    }

    /** An opaque color from hue (0–1), saturation and lightness. */
    static int rgb(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s, hp = (h - (float) Math.floor(h)) * 6, x = c * (1 - Math.abs(hp % 2 - 1)), m = l - c / 2;
        float r, g, b;
        if (hp < 1) { r = c; g = x; b = 0; } else if (hp < 2) { r = x; g = c; b = 0; } else if (hp < 3) { r = 0; g = c; b = x; }
        else if (hp < 4) { r = 0; g = x; b = c; } else if (hp < 5) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xFF000000 | channel(r + m) << 16 | channel(g + m) << 8 | channel(b + m);
    }
    private static int channel(float value) { return Math.clamp(Math.round(value * 255), 0, 255); }

    /**
     * An accent that reads on dark surfaces, keeping the cover's hue; 0 when the cover is too gray to carry one. Dark
     * hues such as blue and violet are lightened until they keep 4.5:1 contrast against the cover theme's cards.
     */
    static int accentFrom(int cover) {
        if (cover == 0) return 0;
        float[] c = hsl(cover);
        if (c[1] < .16f) return 0;
        float s = Math.clamp(c[1], .5f, .85f), l = Math.clamp(c[2], .64f, .74f);
        int accent = rgb(c[0], s, l);
        while (luminance(accent) < .25 && l < .9f) accent = rgb(c[0], s, l += .02f);
        return accent;
    }

    /** WCAG relative luminance of an RGB color. */
    static double luminance(int rgb) {
        double sum = 0;
        double[] weights = {.0722, .7152, .2126};
        for (int i = 0; i < 3; i++) {
            double c = (rgb >> (i * 8) & 255) / 255.0;
            sum += weights[i] * (c <= .03928 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return sum;
    }

    /** A deep shade of the cover for the stage light; gray covers give a neutral lift. */
    static int glowFrom(int cover) {
        if (cover == 0) return 0;
        float[] c = hsl(cover);
        return rgb(c[0], Math.min(c[1], .6f), .34f);
    }

    /**
     * The cover's most prominent color. Opaque pixels vote into 24 hue bins, weighted toward vivid mid-tones, and the
     * strongest neighborhood's average is returned. Covers without a meaningful hue return their average color.
     */
    static int dominant(int[] argb) {
        final int bins = 24;
        double[] weight = new double[bins], red = new double[bins], green = new double[bins], blue = new double[bins];
        double sumR = 0, sumG = 0, sumB = 0;
        int count = 0;
        for (int i = 0; i < argb.length; i += 3) {
            int pixel = argb[i];
            if (pixel >>> 24 < 128) continue;
            int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
            sumR += r; sumG += g; sumB += b; count++;
            float[] c = hsl(pixel);
            double w = c[1] * (1 - Math.min(1, Math.abs(c[2] - .5) * 1.7));
            if (w <= .04) continue;
            int bin = Math.min(bins - 1, (int) (c[0] * bins));
            weight[bin] += w; red[bin] += r * w; green[bin] += g * w; blue[bin] += b * w;
        }
        if (count == 0) return 0;
        int best = -1; double strongest = 0;
        for (int i = 0; i < bins; i++) {
            double w = weight[i] + (weight[(i + 1) % bins] + weight[(i + bins - 1) % bins]) * .5;
            if (w > strongest) { strongest = w; best = i; }
        }
        if (best < 0 || strongest < count * .03)
            return 0xFF000000 | (int) Math.round(sumR / count) << 16 | (int) Math.round(sumG / count) << 8 | (int) Math.round(sumB / count);
        double w = weight[best];
        return 0xFF000000 | (int) Math.round(red[best] / w) << 16 | (int) Math.round(green[best] / w) << 8 | (int) Math.round(blue[best] / w);
    }
}
