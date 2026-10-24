package dev.modernmusic.client;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HudSettingsTest {
    @TempDir Path directory;
    @Test void lyricFontPersistsCancelsAndResetsWithoutChangingOtherParts() {
        Path file = directory.resolve("font.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        var cover = hud.snapshot(800, 600).parts().get(HudSettings.Part.COVER);
        hud.lyricStyle(HudSettings.LyricStyle.BOLD_ITALIC); hud.component(HudSettings.Part.LYRICS).scale(1.5);
        assertTrue(hud.save()); var loaded = new HudSettings(file);
        assertEquals(HudSettings.LyricStyle.BOLD_ITALIC, loaded.lyricStyle());
        assertEquals(1.5, loaded.component(HudSettings.Part.LYRICS).scale());
        var saved = loaded.snapshot(800, 600);
        loaded.lyricStyle(HudSettings.LyricStyle.ITALIC); loaded.component(HudSettings.Part.LYRICS).scale(2);
        loaded.restore(saved); assertEquals(saved, loaded.snapshot(800, 600));
        loaded.reset(HudSettings.Part.COVER, 800, 600); assertEquals(HudSettings.LyricStyle.BOLD_ITALIC, loaded.lyricStyle());
        loaded.reset(HudSettings.Part.LYRICS, 800, 600);
        assertEquals(HudSettings.LyricStyle.REGULAR, loaded.lyricStyle()); assertEquals(1, loaded.component(HudSettings.Part.LYRICS).scale());
        assertEquals(cover, loaded.snapshot(800, 600).parts().get(HudSettings.Part.COVER));
    }
    @Test void missingOrUnknownLyricStylePreservesExistingLayout() throws Exception {
        Path file = directory.resolve("font-legacy.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        hud.component(HudSettings.Part.LYRICS).scale(1.7); assertTrue(hud.save());
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject(); json.remove("lyricStyle");
        Files.writeString(file, json.toString());
        var old = new HudSettings(file); assertEquals(HudSettings.LyricStyle.REGULAR, old.lyricStyle());
        assertEquals(1.7, old.component(HudSettings.Part.LYRICS).scale());
        json.addProperty("lyricStyle", "UNKNOWN"); Files.writeString(file, json.toString());
        var unknown = new HudSettings(file); assertEquals(HudSettings.LyricStyle.REGULAR, unknown.lyricStyle());
        assertEquals(1.7, unknown.component(HudSettings.Part.LYRICS).scale());
    }
    @Test void interfaceFontPersistsCancelsAndToleratesOldOrUnknownFiles() throws Exception {
        Path file = directory.resolve("typeface.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        assertEquals(HudSettings.FontStyle.SMOOTH, hud.font());
        var before = hud.snapshot(800, 600);
        hud.font(HudSettings.FontStyle.PIXEL); hud.restore(before);
        assertEquals(HudSettings.FontStyle.SMOOTH, hud.font(), "cancel restores the font");
        hud.font(HudSettings.FontStyle.PIXEL); hud.component(HudSettings.Part.COVER).scale(1.4); assertTrue(hud.save());
        var loaded = new HudSettings(file);
        assertEquals(HudSettings.FontStyle.PIXEL, loaded.font());
        loaded.reset(); assertEquals(HudSettings.FontStyle.PIXEL, loaded.font(), "layout reset keeps the typeface preference");
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("font", "COMIC"); Files.writeString(file, json.toString());
        var unknown = new HudSettings(file);
        assertEquals(HudSettings.FontStyle.SMOOTH, unknown.font()); assertEquals(1.4, unknown.component(HudSettings.Part.COVER).scale());
        json.remove("font"); Files.writeString(file, json.toString());
        assertEquals(HudSettings.FontStyle.SMOOTH, new HudSettings(file).font());
        assertEquals(HudSettings.FontStyle.PIXEL, HudSettings.FontStyle.SMOOTH.next());
    }
    @Test void themePersistsCancelsAndToleratesOldOrUnknownFiles() throws Exception {
        Path file = directory.resolve("theme.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        assertEquals(HudSettings.Theme.ADAPTIVE, hud.theme(), "the cover theme is the default");
        var before = hud.snapshot(800, 600);
        hud.theme(HudSettings.Theme.OREUI); hud.restore(before);
        assertEquals(HudSettings.Theme.ADAPTIVE, hud.theme(), "cancel restores the theme");
        hud.theme(HudSettings.Theme.DAYLIGHT); hud.component(HudSettings.Part.INFO).scale(1.3); assertTrue(hud.save());
        var loaded = new HudSettings(file);
        assertEquals(HudSettings.Theme.DAYLIGHT, loaded.theme());
        loaded.reset(); assertEquals(HudSettings.Theme.DAYLIGHT, loaded.theme(), "layout reset keeps the theme preference");
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("theme", "NEON"); Files.writeString(file, json.toString());
        var unknown = new HudSettings(file);
        assertEquals(HudSettings.Theme.ADAPTIVE, unknown.theme()); assertEquals(1.3, unknown.component(HudSettings.Part.INFO).scale());
        json.remove("theme"); Files.writeString(file, json.toString());
        assertEquals(HudSettings.Theme.ADAPTIVE, new HudSettings(file).theme(), "files from 0.9 open in the default theme");
    }
    @Test void individualVisibilityPersistsWithoutChangingOtherComponentsOrGlobalSwitch() {
        Path file = directory.resolve("visibility.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        hud.component(HudSettings.Part.INFO).enabled(false); hud.component(HudSettings.Part.PROGRESS).enabled(false);
        assertTrue(hud.save()); var restored = new HudSettings(file);
        assertTrue(restored.enabled());
        assertFalse(restored.component(HudSettings.Part.INFO).enabled()); assertFalse(restored.component(HudSettings.Part.PROGRESS).enabled());
        assertTrue(restored.component(HudSettings.Part.COVER).enabled()); assertTrue(restored.component(HudSettings.Part.LYRICS).enabled());
        restored.enabled(false); restored.enabled(true);
        assertFalse(restored.component(HudSettings.Part.INFO).enabled());
    }
    @Test void oldComponentFilesDefaultToVisibleWithoutLosingLayout() throws Exception {
        Path file = directory.resolve("old.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        hud.component(HudSettings.Part.LYRICS).scale(1.3); hud.component(HudSettings.Part.LYRICS).opacity(.7); hud.enabled(false);
        assertTrue(hud.save());
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (var row : json.getAsJsonObject("parts").asMap().values()) row.getAsJsonObject().remove("enabled");
        Files.writeString(file, json.toString());
        var restored = new HudSettings(file);
        for (var part : HudSettings.Part.values()) assertTrue(restored.component(part).enabled());
        assertFalse(restored.enabled()); assertEquals(1.3, restored.component(HudSettings.Part.LYRICS).scale());
        assertEquals(.7, restored.component(HudSettings.Part.LYRICS).opacity());
    }
    @Test void cancelRestoresImmutableSnapshotAndDoesNotWriteEdits() {
        Path file = directory.resolve("cancel.json"); var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        assertTrue(hud.save()); var original = hud.snapshot(800, 600);
        hud.enabled(false); hud.component(HudSettings.Part.LYRICS).enabled(false);
        hud.component(HudSettings.Part.INFO).position(300, 400, 800, 600);
        hud.component(HudSettings.Part.COVER).scale(2); hud.component(HudSettings.Part.COVER).opacity(.2);
        hud.restore(original); assertEquals(original, hud.snapshot(800, 600));
        assertEquals(original, new HudSettings(file).snapshot(800, 600));
        hud.component(HudSettings.Part.LYRICS).enabled(false);
        assertTrue(original.parts().get(HudSettings.Part.LYRICS).enabled());
    }
    @Test void resetOneComponentLeavesOtherCustomizationsAndGlobalStateUntouched() {
        var hud = new HudSettings(directory.resolve("reset.json")); hud.ensureLayout(800, 600);
        var defaults = hud.snapshot(800, 600);
        hud.enabled(false); hud.component(HudSettings.Part.COVER).enabled(false); hud.component(HudSettings.Part.COVER).scale(2);
        hud.component(HudSettings.Part.LYRICS).scale(.5); hud.component(HudSettings.Part.LYRICS).opacity(.4);
        var lyrics = hud.snapshot(800, 600).parts().get(HudSettings.Part.LYRICS);
        hud.reset(HudSettings.Part.COVER, 800, 600);
        assertFalse(hud.enabled()); assertEquals(defaults.parts().get(HudSettings.Part.COVER), hud.snapshot(800, 600).parts().get(HudSettings.Part.COVER));
        assertEquals(lyrics, hud.snapshot(800, 600).parts().get(HudSettings.Part.LYRICS));
        hud.reset(); assertEquals(defaults, hud.snapshot(800, 600));
    }
    @Test void componentsPersistIndependentPositionScaleAndOpacityAcrossResize() {
        Path file = directory.resolve("config/hud.json");
        var hud = new HudSettings(file); hud.ensureLayout(800, 600);
        var cover = hud.component(HudSettings.Part.COVER); var lyrics = hud.component(HudSettings.Part.LYRICS);
        int lyricLeft = lyrics.left(800, 600), lyricTop = lyrics.top(800, 600);
        cover.scale(2.5); cover.opacity(.35); cover.position(10000, 10000, 800, 600); hud.enabled(false);
        assertEquals(lyricLeft, lyrics.left(800, 600)); assertEquals(lyricTop, lyrics.top(800, 600)); assertEquals(1, lyrics.opacity());
        assertTrue(hud.save()); var restored = new HudSettings(file); restored.ensureLayout(800, 600);
        assertFalse(restored.enabled()); assertEquals(2.5, restored.component(HudSettings.Part.COVER).scale()); assertEquals(.35, restored.component(HudSettings.Part.COVER).opacity());
        for (int width : new int[]{320, 800, 1920}) for (var part : HudSettings.Part.values()) {
            var transform = restored.component(part);
            assertTrue(transform.left(width, 240) >= 0);
            assertTrue(transform.left(width, 240) + part.width * transform.fitScale(width, 240) <= width);
            assertTrue(transform.top(width, 240) + part.height * transform.fitScale(width, 240) <= 240);
        }
    }
    @Test void malformedSettingsAndNonFiniteValuesUseSafeDefaults() throws Exception {
        Path file = directory.resolve("hud.json"); Files.writeString(file, "broken");
        var hud = new HudSettings(file); hud.ensureLayout(800, 600); assertTrue(hud.enabled());
        var info = hud.component(HudSettings.Part.INFO);
        info.scale(Double.NaN); assertEquals(1, info.scale()); info.scale(100); assertEquals(3, info.scale());
        info.opacity(Double.NaN); assertEquals(1, info.opacity()); info.opacity(-1); assertEquals(0, info.opacity());
    }
    @Test void migratesSinglePanelSettingsAndKeepsDisabledState() throws Exception {
        Path file = directory.resolve("hud.json"); Files.writeString(file, "{\"enabled\":false,\"x\":0.2,\"y\":0.3,\"scale\":1.5}");
        var hud = new HudSettings(file); hud.ensureLayout(1000, 700);
        assertFalse(hud.enabled());
        for (var part : HudSettings.Part.values()) assertEquals(1.5, hud.component(part).scale());
        assertTrue(hud.component(HudSettings.Part.INFO).left(1000, 700) > hud.component(HudSettings.Part.COVER).left(1000, 700));
        assertTrue(hud.save()); assertTrue(Files.readString(file).contains("\"version\":2"));
        assertFalse(new HudSettings(file).enabled());
    }
    @Test void defaultsHaveSeparateNonOverlappingBoundsAtSmallViewport() {
        var hud = new HudSettings(directory.resolve("hud.json")); hud.ensureLayout(320, 240);
        assertTrue(hud.component(HudSettings.Part.INFO).left(320, 240) >= hud.component(HudSettings.Part.COVER).left(320, 240) + 48);
        assertTrue(hud.component(HudSettings.Part.PROGRESS).top(320, 240) >= hud.component(HudSettings.Part.LYRICS).top(320, 240) + 28);
    }
    @Test void defaultGroupKeepsItsGuiPixelLayoutWhenTheGuiScaleChanges() {
        // A 1280x800 window at GUI scale 2, then scale 3 and 4 and other windows: the stack must not drift into itself.
        var hud = new HudSettings(directory.resolve("scale.json")); hud.ensureLayout(640, 400);
        var laidOut = bounds(hud, 640, 400);
        for (int[] size : new int[][]{{427, 267}, {426, 266}, {320, 200}, {854, 480}, {1920, 1080}}) {
            var resized = bounds(hud, size[0], size[1]);
            assertEquals(laidOut, resized, "default group moved at " + size[0] + "x" + size[1]);
            assertSeparateAndOnScreen(resized, size[0], size[1]);
        }
        var small = new HudSettings(directory.resolve("small.json")); small.ensureLayout(426, 266);
        assertEquals(bounds(small, 426, 266), bounds(small, 640, 400), "growing the window keeps the group too");
    }
    @Test void groupsKeepTheirDistanceToTheNearestEdgeOrCenter() {
        var hud = new HudSettings(directory.resolve("anchors.json")); hud.ensureLayout(640, 400);
        // Cover and info in the top-right corner; lyrics and progress centered above the hotbar.
        hud.component(HudSettings.Part.COVER).position(352, 12, 640, 400); hud.component(HudSettings.Part.INFO).position(410, 12, 640, 400);
        hud.component(HudSettings.Part.LYRICS).position(180, 312, 640, 400); hud.component(HudSettings.Part.PROGRESS).position(180, 344, 640, 400);
        var laidOut = bounds(hud, 640, 400);
        int width = 426, height = 266; var resized = bounds(hud, width, height);
        assertSeparateAndOnScreen(resized, width, height);
        for (var part : List.of(HudSettings.Part.COVER, HudSettings.Part.INFO))
            assertEquals(laidOut.get(part).shift(width - 640, 0), resized.get(part), part + " keeps its top and right margins");
        for (var part : List.of(HudSettings.Part.LYRICS, HudSettings.Part.PROGRESS))
            assertEquals(laidOut.get(part).shift((width - 640) / 2, height - 400), resized.get(part), part + " stays centered above the bottom");
        hud.component(HudSettings.Part.INFO).enabled(false); hud.component(HudSettings.Part.LYRICS).enabled(false);
        assertEquals(resized, bounds(hud, width, height), "hiding components does not move the others");
    }
    @Test void scaledSinglePanelMigrationMovesAsOneGroup() throws Exception {
        Path file = directory.resolve("legacy-scaled.json"); Files.writeString(file, "{\"enabled\":true,\"x\":0.5,\"y\":0.5,\"scale\":1.4}");
        var hud = new HudSettings(file); hud.ensureLayout(640, 400);
        var laidOut = bounds(hud, 640, 400); var resized = bounds(hud, 426, 266);
        assertSeparateAndOnScreen(resized, 426, 266);
        for (var part : HudSettings.Part.values()) assertEquals(laidOut.get(part).shift(-107, -67), resized.get(part), part.name());
    }
    @Test void everyComponentStaysOnScreenWhenItsGroupNoLongerFits() {
        var hud = new HudSettings(directory.resolve("tiny.json")); hud.ensureLayout(1280, 720);
        hud.component(HudSettings.Part.INFO).scale(2.5); hud.component(HudSettings.Part.INFO).position(10000, 10000, 1280, 720);
        hud.component(HudSettings.Part.LYRICS).scale(3); hud.component(HudSettings.Part.LYRICS).position(400, 300, 1280, 720);
        for (int[] size : new int[][]{{1280, 720}, {640, 360}, {427, 240}, {200, 120}, {60, 40}}) for (var part : HudSettings.Part.values()) {
            var t = hud.component(part); int width = size[0], height = size[1], left = t.left(width, height), top = t.top(width, height);
            assertTrue(left >= 0 && top >= 0 && left + part.width * t.fitScale(width, height) <= width
                    && top + part.height * t.fitScale(width, height) <= height, part + " off-screen at " + width + "x" + height);
        }
    }
    @Test void editingAtAnotherSizeKeepsWhatIsShownAndCancelRestoresTheFrame() throws Exception {
        Path file = directory.resolve("rebase.json"); var hud = new HudSettings(file); hud.ensureLayout(640, 400);
        var original = hud.snapshot(640, 400); var laidOut = bounds(hud, 640, 400); var shown = bounds(hud, 426, 266);
        hud.component(HudSettings.Part.INFO).position(200, 150, 426, 266);
        var edited = bounds(hud, 426, 266);
        assertEquals(new Rect(200, 150, 420, 194), edited.get(HudSettings.Part.INFO));
        for (var part : List.of(HudSettings.Part.COVER, HudSettings.Part.LYRICS, HudSettings.Part.PROGRESS)) {
            assertEquals(shown.get(part), edited.get(part), part + " moved while editing another component");
            assertEquals(laidOut.get(part), bounds(hud, 640, 400).get(part), part + " lost its place at the original size");
        }
        hud.rebase(426, 266); assertEquals(edited, bounds(hud, 426, 266));
        assertEquals(426, hud.snapshot(426, 266).layoutWidth()); assertEquals(266, hud.snapshot(426, 266).layoutHeight());
        assertTrue(hud.save());
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(2, json.get("version").getAsInt()); assertEquals(426, json.get("layoutWidth").getAsInt()); assertEquals(266, json.get("layoutHeight").getAsInt());
        assertEquals(edited, bounds(new HudSettings(file), 426, 266));
        hud.restore(original);
        assertEquals(original, hud.snapshot(640, 400)); assertEquals(shown, bounds(hud, 426, 266));
    }
    @Test void versionTwoFilesWithoutAFrameRecoverTheSizeOfAnUntouchedDefaultGroup() throws Exception {
        for (int[] size : new int[][]{{640, 400}, {854, 480}, {1000, 700}}) {
            Path file = directory.resolve("v2-" + size[0] + ".json"); var hud = new HudSettings(file); hud.ensureLayout(size[0], size[1]);
            var laidOut = bounds(hud, size[0], size[1]); assertTrue(hud.save()); withoutFrame(file);
            // First shown at GUI scale 3 of a 1280x800 window, the file still yields the group it was saved as.
            var legacy = new HudSettings(file);
            assertEquals(laidOut, bounds(legacy, 427, 267));
            assertEquals(size[0], legacy.snapshot(427, 267).layoutWidth()); assertEquals(size[1], legacy.snapshot(427, 267).layoutHeight());
        }
        Path migrated = directory.resolve("v2-migrated.json"); Files.writeString(migrated, "{\"enabled\":true,\"x\":0.3,\"y\":0.6,\"scale\":1.3}");
        var hud = new HudSettings(migrated); hud.ensureLayout(800, 600); assertTrue(hud.save()); withoutFrame(migrated);
        var legacy = new HudSettings(migrated); assertSeparateAndOnScreen(bounds(legacy, 427, 267), 427, 267);
        assertEquals(800, legacy.snapshot(427, 267).layoutWidth()); assertEquals(600, legacy.snapshot(427, 267).layoutHeight());
    }
    @Test void customizedVersionTwoFilesKeepTheOldLayoutAtTheFirstSizeShown() throws Exception {
        Path file = directory.resolve("v2-custom.json"); var hud = new HudSettings(file); hud.ensureLayout(640, 400);
        hud.component(HudSettings.Part.INFO).position(300, 200, 640, 400); hud.component(HudSettings.Part.COVER).scale(1.2);
        assertTrue(hud.save()); withoutFrame(file);
        var legacy = new HudSettings(file); var state = legacy.snapshot(427, 267);
        assertEquals(427, state.layoutWidth()); assertEquals(267, state.layoutHeight());
        for (var part : HudSettings.Part.values()) {
            var t = legacy.component(part); double s = t.fitScale(427, 267); var row = state.parts().get(part);
            assertEquals(4 + Math.round(Math.max(0, 427 - 8 - part.width * s) * row.x()), t.left(427, 267), part.name());
            assertEquals(4 + Math.round(Math.max(0, 267 - 8 - part.height * s) * row.y()), t.top(427, 267), part.name());
        }
    }
    @Test void unreadableFrameFieldsFallBackWithoutLosingTheLayout() throws Exception {
        Path file = directory.resolve("frame-broken.json"); var hud = new HudSettings(file); hud.ensureLayout(640, 400);
        hud.component(HudSettings.Part.LYRICS).scale(1.6); assertTrue(hud.save());
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("layoutWidth", "wide"); json.addProperty("layoutHeight", -5); Files.writeString(file, json.toString());
        var loaded = new HudSettings(file);
        assertEquals(1.6, loaded.component(HudSettings.Part.LYRICS).scale()); assertEquals(500, loaded.snapshot(500, 300).layoutWidth());
    }

    private record Rect(int left, int top, int right, int bottom) {
        boolean overlaps(Rect other) { return left < other.right && other.left < right && top < other.bottom && other.top < bottom; }
        Rect shift(int dx, int dy) { return new Rect(left + dx, top + dy, right + dx, bottom + dy); }
    }
    private static Map<HudSettings.Part, Rect> bounds(HudSettings hud, int width, int height) {
        var result = new EnumMap<HudSettings.Part, Rect>(HudSettings.Part.class);
        for (var part : HudSettings.Part.values()) {
            var t = hud.component(part); int left = t.left(width, height), top = t.top(width, height); double s = t.fitScale(width, height);
            result.put(part, new Rect(left, top, left + (int) Math.ceil(part.width * s), top + (int) Math.ceil(part.height * s)));
        }
        return result;
    }
    private static void assertSeparateAndOnScreen(Map<HudSettings.Part, Rect> rects, int width, int height) {
        for (var a : rects.entrySet()) {
            var rect = a.getValue();
            assertTrue(rect.left() >= 0 && rect.top() >= 0 && rect.right() <= width && rect.bottom() <= height, a.getKey() + " off-screen at " + width + "x" + height);
            for (var b : rects.entrySet()) if (a.getKey().ordinal() < b.getKey().ordinal())
                assertFalse(rect.overlaps(b.getValue()), a.getKey() + " overlaps " + b.getKey() + " at " + width + "x" + height);
        }
    }
    /** Rewrites a saved file the way versions before the layout frame wrote it. */
    private static void withoutFrame(Path file) throws Exception {
        var json = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertNotNull(json.remove("layoutWidth")); assertNotNull(json.remove("layoutHeight")); Files.writeString(file, json.toString());
    }
}
