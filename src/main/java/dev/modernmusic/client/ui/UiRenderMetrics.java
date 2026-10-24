package dev.modernmusic.client.ui;

/** Opt-in render-thread counters for repeatable UI audits; disabled in normal clients. */
public final class UiRenderMetrics {
    private static final boolean ENABLED = Boolean.getBoolean("modernmusic.auditRenderCounters");
    private static long shapeSubmissions, waveformShapeSubmissions, waveformFrames, hudTextFlushes, hudTextCalls;
    private static boolean waveform;

    private UiRenderMetrics() {}

    /**
     * Shape submissions count actual custom mesh draws. HUD text flushes count all GUI flush boundaries inside
     * worldText, including its entry flush and GuiGraphics.drawString's implicit flush. They are not GPU draw
     * calls: empty flushes submit nothing, and switching font atlases can submit additional batches.
     * Read on the render thread, like the rendering that increments these cumulative counters.
     */
    public record Snapshot(long shapeSubmissions, long waveformShapeSubmissions, long waveformFrames,
                           long hudTextFlushes, long hudTextCalls) {}

    public static Snapshot snapshot() {
        return new Snapshot(shapeSubmissions, waveformShapeSubmissions, waveformFrames, hudTextFlushes, hudTextCalls);
    }

    static void shapeSubmitted() {
        if (!ENABLED) return;
        shapeSubmissions++;
        if (waveform) waveformShapeSubmissions++;
    }

    static void beginWaveform() { if (ENABLED) { waveform = true; waveformFrames++; } }
    static void endWaveform() { if (ENABLED) waveform = false; }
    static void hudText(int flushes) { if (ENABLED) { hudTextCalls++; hudTextFlushes += flushes; } }
}
