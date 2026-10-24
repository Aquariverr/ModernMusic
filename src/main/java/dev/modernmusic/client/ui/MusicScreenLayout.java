package dev.modernmusic.client.ui;

/** Fits the full player (740 by 460 plus margins) independently of Minecraft's GUI setting. */
record MusicScreenLayout(int width, int height, double pixelsPerUnit) {
    static final int REFERENCE_WIDTH = 760, REFERENCE_HEIGHT = 476;

    static MusicScreenLayout fit(int pixelWidth, int pixelHeight) {
        // Below one physical pixel per unit, retain responsive layouts instead of shrinking text further.
        double scale = Math.max(1, Math.min(pixelWidth / (double) REFERENCE_WIDTH,
                pixelHeight / (double) REFERENCE_HEIGHT));
        return new MusicScreenLayout((int) Math.floor(pixelWidth / scale + 1e-6),
                (int) Math.floor(pixelHeight / scale + 1e-6), scale);
    }
}
