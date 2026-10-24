package dev.modernmusic.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import javax.imageio.ImageIO;
import dev.modernmusic.client.HudSettings.Theme;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The skins' sprites as shipped: every part is present, sliced as the game expects, and keeps its text legible. */
class SkinSpritesTest {
    /** Parts drawn at their own size; everything else stretches over its bounds and must be nine-sliced. */
    private static final Set<Skin.Part> FIXED = EnumSet.of(Skin.Part.CREST, Skin.Part.SLIDER_HANDLE, Skin.Part.SLIDER_HANDLE_HOVERED,
            Skin.Part.SLIDER_HANDLE_DISABLED, Skin.Part.TOGGLE_OFF, Skin.Part.TOGGLE_ON, Skin.Part.TOGGLE_HANDLE,
            Skin.Part.TOGGLE_HANDLE_HOVERED, Skin.Part.CHECKBOX, Skin.Part.CHECKBOX_HOVERED, Skin.Part.CHECKBOX_CHECKED);

    @Test void everySkinShipsEveryPart() throws IOException {
        for (var skin : Skin.values()) for (var part : Skin.Part.values()) {
            if (part == Skin.Part.CREST && !skin.crest()) continue;
            assertNotNull(image(skin, part), skin.sprite(part));
        }
    }

    @Test void stretchedPartsAreNineSlicedWithEqualSides() throws IOException {
        for (var skin : Skin.values()) for (var part : Skin.Part.values()) {
            if (part == Skin.Part.CREST && !skin.crest()) continue;
            var meta = meta(skin, part);
            if (FIXED.contains(part)) { assertNull(meta, skin.sprite(part) + " is drawn at its own size"); continue; }
            assertNotNull(meta, skin.sprite(part) + " needs nine-slice metadata");
            var scaling = meta.getAsJsonObject("gui").getAsJsonObject("scaling");
            assertEquals("nine_slice", scaling.get("type").getAsString());
            var image = image(skin, part);
            assertEquals(image.getWidth(), scaling.get("width").getAsInt(), skin.sprite(part));
            assertEquals(image.getHeight(), scaling.get("height").getAsInt(), skin.sprite(part));
            int[] border = border(scaling);
            // 1.21.1 sizes the right column by the left border, so unequal sides would overdraw.
            assertEquals(border[0], border[2], skin.sprite(part) + " left and right borders");
            assertTrue(border[0] + border[2] < image.getWidth() && border[1] + border[3] < image.getHeight(), skin.sprite(part) + " leaves a centre");
        }
    }

    @Test void skinsMatchTheirThemes() {
        assertEquals(Skin.CREATE, Palette.of(Theme.CREATE).skin());
        assertEquals(Skin.AE2, Palette.of(Theme.AE2).skin());
        assertEquals(Skin.ARS_NOUVEAU, Palette.of(Theme.ARS_NOUVEAU).skin());
        assertEquals(Skin.ASTRAL_SORCERY, Palette.of(Theme.ASTRAL_SORCERY).skin());
        for (var theme : Theme.values()) if (Palette.of(theme).skin() != null) assertFalse(Palette.of(theme).blocky(), theme + " is not OreUI");
    }

    @Test void focusCentreHasATransparentSamplingGuardAtFractionalScales() throws IOException {
        for (var skin : Skin.values()) {
            var image = image(skin, Skin.Part.FOCUS);
            int[] sides = border(meta(skin, Skin.Part.FOCUS).getAsJsonObject("gui").getAsJsonObject("scaling"));
            // Adjacent coloured texels can leak into a repeated transparent centre after UV rounding.
            // Keep the centre and one texel surrounding it transparent; the outline stays in the corners/edges.
            assertTrue(sides[0] > 0 && sides[1] > 0 && sides[2] > 0 && sides[3] > 0);
            for (int y = sides[1] - 1; y <= image.getHeight() - sides[3]; y++)
                for (int x = sides[0] - 1; x <= image.getWidth() - sides[2]; x++)
                    assertEquals(0, image.getRGB(x, y) >>> 24, skin + " focus guard at " + x + "," + y);
        }
    }

    /** Every pixel text can land on, translucent parts composited over the panel, keeps the palette's text legible. */
    @Test void textStaysLegibleOnEveryTexel() throws IOException {
        for (var skin : Skin.values()) {
            var palette = palette(skin);
            for (var part : new Skin.Part[]{Skin.Part.PANEL, Skin.Part.CARD, Skin.Part.STAGE, Skin.Part.WELL, Skin.Part.ROW_HOVERED, Skin.Part.ROW_SELECTED}) {
                for (int texel : interior(skin, part, palette.shell())) {
                    assertTrue(contrast(palette.text(), texel) >= 6.5, skin.sprite(part) + " text on " + Integer.toHexString(texel));
                    assertTrue(contrast(palette.muted(), texel) >= 4.4, skin.sprite(part) + " secondary text on " + Integer.toHexString(texel));
                }
            }
            for (int texel : interior(skin, Skin.Part.ROW_SELECTED, palette.card()))
                assertTrue(contrast(palette.accent(), texel) >= 4.5, skin + " selected song title");
            for (int texel : interior(skin, Skin.Part.BADGE, palette.shell())) {
                assertTrue(contrast(palette.accent(), texel) >= 3, skin + " badge accent on " + Integer.toHexString(texel));
                assertTrue(contrast(palette.muted(), texel) >= 3, skin + " badge text on " + Integer.toHexString(texel));
            }
            int fieldText = skin.field.text() != 0 ? skin.field.text() : palette.text();
            for (var part : new Skin.Part[]{Skin.Part.FIELD, Skin.Part.FIELD_FOCUSED})
                for (int texel : interior(skin, part, palette.shell())) assertTrue(contrast(fieldText, texel) >= 7, skin.sprite(part) + " input text");
            int headText = skin.header.text() != 0 ? skin.header.text() : palette.text();
            for (int texel : interior(skin, Skin.Part.HEADER, palette.shell()))
                assertTrue(contrast(headText, texel) >= 4.5, skin + " title on " + Integer.toHexString(texel));
        }
    }

    /** Labels on every enabled face: 4.5:1, or 3:1 for light labels that carry the skin's pixel shadow. */
    @Test void labelsReadOnTheirFaces() throws IOException {
        for (var skin : Skin.values()) {
            var labels = skin.labels;
            check(skin, Skin.Part.BUTTON, labels.normal());
            check(skin, Skin.Part.BUTTON_HOVERED, labels.hovered());
            check(skin, Skin.Part.BUTTON_PRESSED, labels.selected());
            check(skin, Skin.Part.BUTTON_SELECTED, labels.selected());
            check(skin, Skin.Part.PRIMARY, labels.primary());
            check(skin, Skin.Part.PRIMARY_HOVERED, labels.primary());
            check(skin, Skin.Part.PRIMARY_PRESSED, labels.primary());
        }
    }

    private static void check(Skin skin, Skin.Part part, int label) throws IOException {
        boolean shadowed = skin.labels.shadow() && Palette.luminance(label) > .5;
        var image = image(skin, part);
        // The middle of the face, where labels sit.
        int cx = image.getWidth() / 2, cy = image.getHeight() / 2;
        for (int y = cy - 2; y <= cy + 2; y++) for (int x = cx - 6; x <= cx + 6; x++) {
            int texel = image.getRGB(x, y);
            if (texel >>> 24 < 255) continue;
            assertTrue(contrast(label, texel) >= (shadowed ? 3 : 4.5), skin.sprite(part) + " label on " + Integer.toHexString(texel));
        }
    }

    private static Palette palette(Skin skin) {
        for (var theme : Theme.values()) if (Palette.of(theme).skin() == skin) return Palette.of(theme);
        throw new AssertionError(skin + " has no theme");
    }

    /** The opaque colors inside a part's border, translucent texels composited over {@code base}. */
    private static int[] interior(Skin skin, Skin.Part part, int base) throws IOException {
        var image = image(skin, part);
        var meta = meta(skin, part);
        int[] border = meta == null ? new int[4] : border(meta.getAsJsonObject("gui").getAsJsonObject("scaling"));
        var colors = new java.util.LinkedHashSet<Integer>();
        for (int y = border[1]; y < image.getHeight() - border[3]; y++)
            for (int x = border[0]; x < image.getWidth() - border[2]; x++) colors.add(Shapes.mix(base, image.getRGB(x, y) | 0xFF000000, (image.getRGB(x, y) >>> 24) / 255f));
        return colors.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] border(JsonObject scaling) {
        var border = scaling.get("border");
        if (border.isJsonPrimitive()) { int b = border.getAsInt(); return new int[]{b, b, b, b}; }
        var sides = border.getAsJsonObject();
        return new int[]{sides.get("left").getAsInt(), sides.get("top").getAsInt(), sides.get("right").getAsInt(), sides.get("bottom").getAsInt()};
    }

    private static BufferedImage image(Skin skin, Skin.Part part) throws IOException {
        try (InputStream in = resource(skin, part, ".png")) { return in == null ? null : ImageIO.read(in); }
    }

    private static JsonObject meta(Skin skin, Skin.Part part) throws IOException {
        try (InputStream in = resource(skin, part, ".png.mcmeta")) {
            return in == null ? null : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static InputStream resource(Skin skin, Skin.Part part, String suffix) {
        return SkinSpritesTest.class.getResourceAsStream("/assets/modernmusic/textures/gui/sprites/" + skin.sprite(part) + suffix);
    }

    private static double contrast(int a, int b) {
        double x = Palette.luminance(a), y = Palette.luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
