package dev.modernmusic.client.ui;

import dev.modernmusic.client.HudSettings.Theme;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {
    @Test void hslRoundTripsOpaqueColors() {
        for (int color : new int[]{0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF19191C, 0xFF79E4C5, 0xFFD81B60, 0xFF8FA3FF}) {
            float[] c = Palette.hsl(color);
            int back = Palette.rgb(c[0], c[1], c[2]);
            for (int shift = 0; shift < 24; shift += 8) assertEquals(color >> shift & 255, back >> shift & 255, 1, Integer.toHexString(color));
        }
    }

    @Test void dominantPrefersTheVividColorOverAGrayBackground() {
        int[] cover = new int[256 * 256];
        Arrays.fill(cover, 0xFF808080);
        for (int i = 0; i < cover.length / 4; i++) cover[i] = 0xFFD03030;
        float[] dominant = Palette.hsl(Palette.dominant(cover));
        assertTrue(dominant[0] < .05 || dominant[0] > .95, "red hue: " + dominant[0]);
        assertTrue(dominant[1] > .5, "kept its saturation");
    }

    @Test void grayCoversKeepTheThemeAccentButStillLiftTheStage() {
        int[] gray = new int[64 * 64];
        Arrays.fill(gray, 0xFF7A7A7A);
        int dominant = Palette.dominant(gray);
        assertEquals(0xFF7A7A7A, dominant, "average of a hueless cover");
        assertEquals(0, Palette.accentFrom(dominant), "no hue to carry");
        assertNotEquals(0, Palette.glowFrom(dominant));
        assertEquals(0, Palette.dominant(new int[16]), "fully transparent");
    }

    @Test void coverAccentKeepsTheHueAndReadsOnDarkSurfaces() {
        int navy = 0xFF14215A;
        float[] source = Palette.hsl(navy), accent = Palette.hsl(Palette.accentFrom(navy));
        assertEquals(source[0], accent[0], .02);
        assertTrue(accent[2] >= .63 && accent[2] <= .9, "lightness " + accent[2]);
        for (float hue = 0; hue < 1; hue += 1 / 36f) {
            int cover = Palette.rgb(hue, .7f, .3f);
            assertTrue(contrast(Palette.accentFrom(cover), Palette.of(Theme.ADAPTIVE).card()) >= 4.5, "hue " + hue);
        }
    }

    @Test void everyThemeKeepsTextLegible() {
        for (var theme : Theme.values()) {
            var p = Palette.of(theme);
            assertTrue(contrast(p.text(), p.card()) >= 7, theme + " text");
            assertTrue(contrast(p.text(), p.shell()) >= 7, theme + " text on panel");
            assertTrue(contrast(p.muted(), p.card()) >= 4.5, theme + " secondary text");
            assertTrue(contrast(p.muted(), p.shell()) >= 4.5, theme + " secondary text on panel");
            assertTrue(contrast(p.accent(), p.shell()) >= 3, theme + " accent");
            if (!p.blocky()) assertTrue(contrast(p.ink(), p.accent()) >= 4.5, theme + " primary button label");
        }
    }

    private static double contrast(int a, int b) {
        double x = Palette.luminance(a), y = Palette.luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
