package dev.modernmusic.client.ui;

import java.util.Locale;

/**
 * A textured pixel-art theme in the visual language of another mod. Its parts are GUI sprites under
 * {@code textures/gui/sprites/theme/<id>/}, built by {@code scripts/build-theme-sprites.py} and nine-sliced by the game at
 * one texel per GUI pixel, as the reference mods draw their own screens. Code only picks the part for each state and
 * the inks that sit on it; colors for text on the panel itself come from the theme's {@link Palette}.
 */
enum Skin {
    // Brass name plate, dark-wood casing, sunken andesite wells, bevelled gray buttons that turn blue, brass plates.
    // White labels on the blue hover face would be 2.8:1, so blue faces take the dark navy of the pressed state.
    CREATE("create", Glyphs.SHADOW, false,
            new Header(0xFF592424, 0xFF6C3528, 0xFF6C3528), new Field(0, 0),
            new Labels(0xFFFFFFFF, 0xFF1F2235, 0xFF1F2235, 0xFF505050, 0xFF3A1414, 0xFF4F463C, true),
            new Geometry(0, 0, 1, 5, 2, 0),
            0x99120A04, 0xC00A0603, new Wave(0xFF9C6846, 0xFFF7CB6C, 0xFFFFEB8C), 0xFFF7CB6C),
    // Lavender-gray panels, slate buttons that half-sink sky blue, a dark terminal field, fluix purple where lit.
    // AE2 sets white labels on its slate faces (about 2:1); its slate ink keeps them legible instead.
    AE2("ae2", Glyphs.ENGRAVED, false,
            new Header(0, 0, 0), new Field(0xFFF2F2F2, 0xFFA9AEC2),
            new Labels(0xFF2E2C40, 0xFF2E4F70, 0xFF2E4F70, 0xFF413F54, 0xFFFFFFFF, 0xFF413F54, true),
            new Geometry(-1, 0, 1, 5, 2, 0),
            0x8C1E1D29, 0xB31E1D29, new Wave(0xFF5A479E, 0xFFB06FDD, 0xFFFF80D7), 0xFFB06FDD),
    // A gilded spell-book cover around parchment, tan frames ending in curls, purple tabs, a mana gauge.
    ARS_NOUVEAU("ars_nouveau", Glyphs.PLAIN, false,
            new Header(0, 0, 0), new Field(0, 0),
            new Labels(0xFF3D1F2F, 0xFF3D1F2F, 0xFF2A0F2A, 0xFFA8957F, 0xFFFBEAFB, 0xFFEDE4EA, false),
            new Geometry(0, 0, 1, 5, 3, -1),
            0x7A1A0A1E, 0xA61A0A1E, new Wave(0xFFD7C0EA, 0xFF7B2FBE, 0xFF4F1D8A), 0xFFC85FD5),
    // White marble around a quiet night sky, obsidian inlays with gold star studs, starlight rails, celestial blue.
    ASTRAL_SORCERY("astral_sorcery", Glyphs.GLOW, true,
            new Header(0, 0, 0), new Field(0, 0),
            new Labels(0xFF1E1E1E, 0xFF1E1E1E, 0xFF1E1E1E, 0xFF5E5E5E, 0xFFFFFFFF, 0xFF9AA0B0, true),
            new Geometry(0, 0, 1, 6, 3, -1),
            0xA605080F, 0xD005080F, new Wave(0xFF2C4A86, 0xFFB9C8FF, 0xFFFFFFFF), 0xFFE8C964);

    /** Every sprite a skin provides; the file name is the lower-case constant name. */
    enum Part {
        PANEL, HEADER, CREST, CARD, STAGE, WELL, FIELD, FIELD_FOCUSED,
        BUTTON, BUTTON_HOVERED, BUTTON_PRESSED, BUTTON_SELECTED, BUTTON_DISABLED,
        PRIMARY, PRIMARY_HOVERED, PRIMARY_PRESSED, PRIMARY_DISABLED,
        ROW_HOVERED, ROW_SELECTED, FOCUS, TOOLTIP, DIVIDER, BADGE, SLOT,
        SLIDER_TRACK, SLIDER_FILL, SLIDER_HANDLE, SLIDER_HANDLE_HOVERED, SLIDER_HANDLE_DISABLED,
        TOGGLE_OFF, TOGGLE_ON, TOGGLE_HANDLE, TOGGLE_HANDLE_HOVERED,
        CHECKBOX, CHECKBOX_HOVERED, CHECKBOX_CHECKED;
        final String file = name().toLowerCase(Locale.ROOT);
    }

    /** How pixel glyphs sit on the skin: plain, with a dark drop shadow, engraved into the panel, or softly lit. */
    enum Glyphs { PLAIN, SHADOW, ENGRAVED, GLOW }

    /** Ink on the header band; 0 keeps the palette's text, muted and accent colors. */
    record Header(int text, int muted, int accent) {}
    /** Ink inside input fields; 0 keeps the palette's text and quiet colors. */
    record Field(int text, int hint) {}
    /** Labels and glyphs on each button face. Light labels get a one-pixel drop shadow when {@code shadow}. */
    record Labels(int normal, int hovered, int selected, int disabled, int primary, int primaryDisabled, boolean shadow) {}
    /**
     * Content offsets in GUI pixels for faces at rest, hovered and pressed (faces with a bottom lip center their
     * label above it); how far tooltip frames reach past their text; the frame around artwork; and the crest's offset
     * from the panel's top edge.
     */
    record Geometry(int rest, int hovered, int pressed, int tooltip, int slot, int crest) {}
    /** Waveform bars: quiet level, loud level and the lit tip of a tall bar. */
    record Wave(int low, int high, int tip) {}

    final String id;
    final Glyphs glyphs;
    /** Headings use the Minecraft font; light headings on dark skins keep its drop shadow. */
    final boolean headingShadow;
    final Header header;
    final Field field;
    final Labels labels;
    final Geometry geometry;
    final int scrimTop, scrimBottom;
    final Wave wave;
    /** Accent for the HUD over the game world, where light skins' deep accents would not read. */
    final int worldAccent;

    Skin(String id, Glyphs glyphs, boolean headingShadow, Header header, Field field, Labels labels, Geometry geometry,
         int scrimTop, int scrimBottom, Wave wave, int worldAccent) {
        this.id = id; this.glyphs = glyphs; this.headingShadow = headingShadow; this.header = header; this.field = field;
        this.labels = labels; this.geometry = geometry; this.scrimTop = scrimTop; this.scrimBottom = scrimBottom;
        this.wave = wave; this.worldAccent = worldAccent;
    }

    /** Only some skins ornament the top of their panel. */
    boolean crest() { return this == ARS_NOUVEAU || this == ASTRAL_SORCERY; }

    /** The sprite path inside the GUI atlas, e.g. {@code theme/create/panel}. */
    String sprite(Part part) { return "theme/" + id + "/" + part.file; }
}
