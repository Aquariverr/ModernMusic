package dev.modernmusic.client.audio;

import java.util.Arrays;

/** A short envelope of the PCM immediately behind the audible playhead, oldest samples on the left. */
public final class WaveformSampler {
    private static final int WINDOW_MS = 320;
    private static final int SAMPLES_PER_BAR = 96;

    private WaveformSampler() {}

    /** Writes normalized amplitudes into caller-owned storage; no PCM copies or retained audio references. */
    public static void sample(PcmAudio audio, long positionMs, float[] levels) {
        Arrays.fill(levels, 0);
        if (audio == null || levels.length == 0) return;
        long end = audio.byteOffset(positionMs) / audio.frameSize();
        long window = Math.max(levels.length, (long) audio.sampleRate() * WINDOW_MS / 1000);
        long start = end - window;
        byte[] pcm = audio.samples();
        for (int bar = 0; bar < levels.length; bar++) {
            long first = Math.max(0, start + window * bar / levels.length);
            long last = Math.max(0, start + window * (bar + 1) / levels.length);
            int count = (int) Math.min(SAMPLES_PER_BAR, last - first);
            if (count == 0) continue;
            double energy = 0;
            float peak = 0;
            for (int i = 0; i < count; i++) {
                long frame = first + (last - first) * i / count;
                int offset = (int) (frame * audio.frameSize());
                for (int channel = 0; channel < audio.channels(); channel++) {
                    int index = offset + channel * 2;
                    float value = (short) ((pcm[index] & 255) | pcm[index + 1] << 8) / 32768f;
                    energy += value * value;
                    peak = Math.max(peak, Math.abs(value));
                }
            }
            // Average channel energy instead of mixing channels: out-of-phase stereo stays visible.
            double rms = Math.sqrt(energy / (count * audio.channels()));
            levels[bar] = (float) Math.min(1, Math.sqrt(rms * .75 + peak * .25));
        }
    }
}
