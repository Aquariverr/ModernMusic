package dev.modernmusic.client;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/**
 * Independent components; legacy single-panel settings migrate. Positions are fractions of the free space at the GUI
 * size they were laid out at (the frame). At any other size, components that sat next to or below each other move as
 * one group that keeps its GUI-pixel distance to the nearest edge or the center, so they cannot drift into each other,
 * and every component stays fully on-screen.
 */
public final class HudSettings {
    public enum LyricStyle {
        REGULAR(false, false), BOLD(true, false), ITALIC(false, true), BOLD_ITALIC(true, true);
        public final boolean bold, italic;
        LyricStyle(boolean bold, boolean italic) { this.bold = bold; this.italic = italic; }
        public String key() { return "modernmusic.hud.lyric_style." + name().toLowerCase(Locale.ROOT); }
        public LyricStyle next() { return values()[(ordinal() + 1) % values().length]; }
    }
    /** Typeface for every music screen and the HUD: the bundled smooth font or the vanilla pixel font. */
    public enum FontStyle {
        SMOOTH, PIXEL;
        public String key() { return "modernmusic.font." + name().toLowerCase(Locale.ROOT); }
        public FontStyle next() { return values()[(ordinal() + 1) % values().length]; }
    }
    /**
     * Color and shape theme for every music screen and the HUD; a preference like the typeface, not part of the layout.
     * The last four are textured pixel-art skins in the visual language of other mods.
     */
    public enum Theme {
        ADAPTIVE, VINYL, MIDNIGHT, DAYLIGHT, MINT, OREUI, CREATE, AE2, ARS_NOUVEAU, ASTRAL_SORCERY;
        public String key() { return "modernmusic.theme." + name().toLowerCase(Locale.ROOT); }
    }
    public enum Part {
        COVER(48, 48, 0, 0), INFO(220, 44, 58, 0), LYRICS(280, 28, 0, 52), PROGRESS(280, 22, 0, 84);
        public final int width, height, offsetX, offsetY;
        Part(int width, int height, int offsetX, int offsetY) { this.width = width; this.height = height; this.offsetX = offsetX; this.offsetY = offsetY; }
        public String key() { return "modernmusic.hud.part." + name().toLowerCase(Locale.ROOT); }
    }
    /** Components closer than this many GUI pixels (times the larger scale) move as one group on resize. */
    private static final double GROUP_GAP = 16;
    public final class Transform {
        private final Part part;
        private double x, y, scale = 1, opacity = 1;
        private boolean enabled = true;
        private Transform(Part part) { this.part = part; }
        public boolean enabled() { return enabled; }
        public void enabled(boolean value) { enabled = value; }
        public double scale() { return scale; }
        public void scale(double value) { scale = finite(value, 1, .25, 3); }
        public double opacity() { return opacity; }
        public void opacity(double value) { opacity = finite(value, 1, 0, 1); }
        public double fitScale(int width, int height) { return Math.max(.01, Math.min(scale, Math.min(Math.max(1, width - 8.0) / part.width, Math.max(1, height - 8.0) / part.height))); }
        public int left(int width, int height) { return (int) Math.round(layout(width, height)[part.ordinal()][0]); }
        public int top(int width, int height) { return (int) Math.round(layout(width, height)[part.ordinal()][1]); }
        /** Moves this component as shown at this size; every other component keeps its place on screen. */
        public void position(double left, double top, int width, int height) { rebase(width, height); place(left, top, width, height); }
        public boolean contains(double px, double py, int width, int height) {
            return px >= left(width, height) && py >= top(width, height) && px < left(width, height) + part.width * fitScale(width, height)
                    && py < top(width, height) + part.height * fitScale(width, height);
        }
        private void place(double left, double top, int width, int height) {
            x = finite((left - 4) / Math.max(1, width - 8 - part.width * fitScale(width, height)), 0, 0, 1);
            y = finite((top - 4) / Math.max(1, height - 8 - part.height * fitScale(width, height)), 0, 0, 1);
        }
        /** Bounds at the frame size, where the stored fractions apply directly. */
        private Box frameBox() {
            double s = fitScale(frameWidth, frameHeight), w = part.width * s, h = part.height * s;
            return new Box(4 + Math.max(0, frameWidth - 8 - w) * x, 4 + Math.max(0, frameHeight - 8 - h) * y, w, h, s);
        }
    }
    /** GUI-pixel bounds; boxes within GROUP_GAP of each other (scaled by the larger one) belong to one group. */
    private record Box(double left, double top, double width, double height, double scale) {
        boolean near(Box other) {
            double gap = GROUP_GAP * Math.max(1, Math.max(scale, other.scale));
            return left < other.left + other.width + gap && other.left < left + width + gap
                    && top < other.top + other.height + gap && other.top < top + height + gap;
        }
    }
    private final Path file;
    private final EnumMap<Part, Transform> parts = new EnumMap<>(Part.class);
    private boolean enabled = true, initialize = true;
    private LyricStyle lyricStyle = LyricStyle.REGULAR;
    private FontStyle font = FontStyle.SMOOTH;
    private Theme theme = Theme.ADAPTIVE;
    private double legacyX = .02, legacyY = .05, legacyScale = 1;
    /** GUI size the stored fractions were laid out at; 0 until known (fresh layout, or a file saved before it was recorded). */
    private int frameWidth, frameHeight;
    public HudSettings(Path file) { this.file = file.toAbsolutePath().normalize(); reset(); load(); }
    public boolean enabled() { return enabled; }
    public void enabled(boolean value) { enabled = value; }
    public Transform component(Part part) { return parts.get(part); }
    public LyricStyle lyricStyle() { return lyricStyle; }
    public void lyricStyle(LyricStyle value) { lyricStyle = Objects.requireNonNull(value); }
    public FontStyle font() { return font; }
    public void font(FontStyle value) { font = Objects.requireNonNull(value); }
    public Theme theme() { return theme; }
    public void theme(Theme value) { theme = Objects.requireNonNull(value); }
    public void ensureLayout(int width, int height) {
        if (!initialize) { if (frameWidth <= 0) { frameWidth = width; frameHeight = height; } return; }
        initialize = false; frameWidth = width; frameHeight = height;
        double left = 4 + Math.max(0, width - 8 - 280 * legacyScale) * legacyX;
        double top = 4 + Math.max(0, height - 8 - 120 * legacyScale) * legacyY;
        for (var part : Part.values()) {
            var transform = component(part); transform.scale(legacyScale);
            transform.position(left + part.offsetX * legacyScale, top + part.offsetY * legacyScale, width, height);
        }
    }
    /**
     * Re-expresses the layout at this size without moving anything on screen, so edits apply to what is shown. Only a
     * component that had to be clamped at this size loses its original place.
     */
    public void rebase(int width, int height) {
        ensureLayout(width, height);
        if (width == frameWidth && height == frameHeight) return;
        var corners = layout(width, height);
        frameWidth = width; frameHeight = height;
        for (var part : Part.values()) component(part).place(corners[part.ordinal()][0], corners[part.ordinal()][1], width, height);
    }
    /** Top-left corner of every component at this GUI size, indexed by ordinal. */
    private double[][] layout(int width, int height) {
        ensureLayout(width, height);
        var all = Part.values(); var boxes = new Box[all.length]; var group = new int[all.length];
        for (int i = 0; i < all.length; i++) { boxes[i] = component(all[i]).frameBox(); group[i] = i; }
        for (int i = 0; i < all.length; i++) for (int j = i + 1; j < all.length; j++) if (boxes[i].near(boxes[j]))
            for (int k = 0, merged = group[j]; k < all.length; k++) if (group[k] == merged) group[k] = group[i];
        var corners = new double[all.length][];
        for (int g = 0; g < all.length; g++) {
            double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
            double endX = -Double.MAX_VALUE, endY = -Double.MAX_VALUE;
            for (int i = 0; i < all.length; i++) if (group[i] == g) {
                var box = boxes[i]; double s = component(all[i]).fitScale(width, height);
                left = Math.min(left, box.left()); right = Math.max(right, box.left() + box.width());
                top = Math.min(top, box.top()); bottom = Math.max(bottom, box.top() + box.height());
                endX = Math.max(endX, box.left() + all[i].width * s); endY = Math.max(endY, box.top() + all[i].height * s);
            }
            if (left > right) continue;
            double dx = shift(left, right, endX, frameWidth, width), dy = shift(top, bottom, endY, frameHeight, height);
            for (int i = 0; i < all.length; i++) if (group[i] == g) {
                double s = component(all[i]).fitScale(width, height);
                corners[i] = new double[]{dev.modernmusic.compat.Java17.clamp(boxes[i].left() + dx, 4, 4 + Math.max(0, width - 8 - all[i].width * s)),
                        dev.modernmusic.compat.Java17.clamp(boxes[i].top() + dy, 4, 4 + Math.max(0, height - 8 - all[i].height * s))};
            }
        }
        return corners;
    }
    /**
     * Shift along one axis that keeps a group's distance to whichever of start, center or end it was nearest in the
     * frame, then pulls the group back on-screen as a whole ({@code end} is its far edge at the new size).
     */
    private static double shift(double low, double high, double end, int frame, int extent) {
        double start = low - 4, finish = frame - 4 - high, middle = Math.abs(low + high - frame) / 2;
        double shift = start <= Math.min(finish, middle) ? 0 : finish <= middle ? extent - frame : (extent - frame) / 2.0;
        return Math.max(Math.min(shift, extent - 4 - end), 4 - low);
    }
    public void reset() {
        lyricStyle = LyricStyle.REGULAR;
        enabled = true; initialize = true; legacyX = .02; legacyY = .05; legacyScale = 1; frameWidth = frameHeight = 0;
        for (var part : Part.values()) parts.put(part, new Transform(part));
    }
    public void reset(Part part, int width, int height) {
        if (part == Part.LYRICS) lyricStyle = LyricStyle.REGULAR;
        ensureLayout(width, height);
        var transform = new Transform(part);
        transform.position(4 + Math.max(0, width - 288) * .02 + part.offsetX,
                4 + Math.max(0, height - 128) * .05 + part.offsetY, width, height);
        parts.put(part, transform);
    }
    public record PartState(double x, double y, double scale, double opacity, boolean enabled) {}
    public record Snapshot(boolean enabled, Map<Part, PartState> parts, LyricStyle lyricStyle, FontStyle font, Theme theme, int layoutWidth, int layoutHeight) {
        public Snapshot { parts = Map.copyOf(parts); }
    }
    public Snapshot snapshot(int width, int height) {
        ensureLayout(width, height);
        var values = new EnumMap<Part, PartState>(Part.class);
        parts.forEach((part, t) -> values.put(part, new PartState(t.x, t.y, t.scale, t.opacity, t.enabled)));
        return new Snapshot(enabled, values, lyricStyle, font, theme, frameWidth, frameHeight);
    }
    public void restore(Snapshot snapshot) {
        lyricStyle = snapshot.lyricStyle; font = snapshot.font; theme = snapshot.theme;
        enabled = snapshot.enabled; initialize = false; frameWidth = snapshot.layoutWidth; frameHeight = snapshot.layoutHeight;
        snapshot.parts.forEach((part, state) -> {
            var t = new Transform(part); t.x = state.x; t.y = state.y;
            t.scale(state.scale); t.opacity(state.opacity); t.enabled(state.enabled); parts.put(part, t);
        });
    }
    private static double finite(double value, double fallback, double low, double high) { return Double.isFinite(value) ? dev.modernmusic.compat.Java17.clamp(value, low, high) : fallback; }
    private void load() {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192) return;
            JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); enabled = json.get("enabled").getAsBoolean();
            if (!json.has("parts")) {
                legacyX = finite(json.get("x").getAsDouble(), .02, 0, 1); legacyY = finite(json.get("y").getAsDouble(), .05, 0, 1);
                legacyScale = finite(json.get("scale").getAsDouble(), 1, .25, 3); return;
            }
            if (json.get("version").getAsInt() != 2) throw new IllegalArgumentException();
            if (json.has("font")) {
                try { font = FontStyle.valueOf(json.get("font").getAsString()); }
                catch (IllegalArgumentException ignored) { font = FontStyle.SMOOTH; }
            }
            if (json.has("theme")) {
                try { theme = Theme.valueOf(json.get("theme").getAsString()); }
                catch (IllegalArgumentException ignored) { theme = Theme.ADAPTIVE; }
            }
            if (json.has("lyricStyle")) {
                try { lyricStyle = LyricStyle.valueOf(json.get("lyricStyle").getAsString()); }
                catch (IllegalArgumentException ignored) { lyricStyle = LyricStyle.REGULAR; }
            }
            for (var part : Part.values()) {
                JsonObject row = json.getAsJsonObject("parts").getAsJsonObject(part.name()); var transform = component(part);
                transform.x = finite(row.get("x").getAsDouble(), 0, 0, 1); transform.y = finite(row.get("y").getAsDouble(), 0, 0, 1);
                transform.scale(row.get("scale").getAsDouble()); transform.opacity(row.get("opacity").getAsDouble());
                transform.enabled(!row.has("enabled") || row.get("enabled").getAsBoolean());
            }
            initialize = false; frameWidth = frameSize(json, "layoutWidth"); frameHeight = frameSize(json, "layoutHeight");
            if (frameWidth == 0 || frameHeight == 0) inferFrame();
        } catch (Exception ignored) { reset(); }
    }
    private static int frameSize(JsonObject json, String key) {
        try { int value = json.get(key).getAsInt(); return value > 0 && value <= 16384 ? value : 0; }
        catch (RuntimeException ignored) { return 0; }
    }
    /**
     * Version-2 files from before the frame was saved: a group that still has the default shape reveals the GUI size it
     * was laid out at, because the cover and lyrics share a left edge and the lyrics sit a fixed distance below the
     * cover. Anything else keeps the first size it is shown at, which reproduces the old layout there.
     */
    private void inferFrame() {
        var cover = component(Part.COVER); var lyrics = component(Part.LYRICS); double s = cover.scale;
        double width = 8 + s * (Part.COVER.width * cover.x - Part.LYRICS.width * lyrics.x) / (cover.x - lyrics.x);
        double height = 8 + s * (Part.LYRICS.offsetY + Part.LYRICS.height * lyrics.y - Part.COVER.height * cover.y) / (lyrics.y - cover.y);
        frameWidth = frameHeight = 0;
        if (!(width >= 16 && width <= 16384 && height >= 16 && height <= 16384)) return;
        frameWidth = (int) Math.round(width); frameHeight = (int) Math.round(height);
        var origin = cover.frameBox();
        for (var part : Part.values()) {
            var transform = component(part); var box = transform.frameBox();
            if (transform.scale != s || Math.abs(box.left() - origin.left() - part.offsetX * s) > .5
                    || Math.abs(box.top() - origin.top() - part.offsetY * s) > .5) { frameWidth = frameHeight = 0; return; }
        }
    }
    public boolean save() {
        if (initialize) ensureLayout(854, 480);
        Path temporary = null;
        try {
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) return false;
            Files.createDirectories(file.getParent());
            JsonObject json = new JsonObject(); json.addProperty("version", 2); json.addProperty("enabled", enabled);
            json.addProperty("lyricStyle", lyricStyle.name()); json.addProperty("font", font.name()); json.addProperty("theme", theme.name());
            if (frameWidth > 0) { json.addProperty("layoutWidth", frameWidth); json.addProperty("layoutHeight", frameHeight); }
            JsonObject rows = new JsonObject(); json.add("parts", rows);
            for (var part : Part.values()) {
                var transform = component(part); var row = new JsonObject(); rows.add(part.name(), row);
                row.addProperty("x", transform.x); row.addProperty("y", transform.y); row.addProperty("scale", transform.scale); row.addProperty("opacity", transform.opacity);
                row.addProperty("enabled", transform.enabled);
            }
            temporary = Files.createTempFile(file.getParent(), "modernmusic-hud-", ".tmp"); Files.writeString(temporary, json.toString());
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception ignored) { return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }
}
