package dev.modernmusic.client.audio;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaveformSamplerTest {
    @Test void silenceAndMissingAudioClearPreviouslyVisibleBars() {
        float[] levels = new float[48];
        Arrays.fill(levels, 1);
        WaveformSampler.sample(new PcmAudio(new byte[8000 * 2], 8000, 1), 500, levels);
        assertArrayEquals(new float[48], levels);
        Arrays.fill(levels, 1);
        WaveformSampler.sample(null, 500, levels);
        assertArrayEquals(new float[48], levels);
    }

    @Test void stereoEnergyDoesNotCancelOppositeChannels() {
        byte[] pcm = new byte[8000 * 4];
        for (int frame = 0; frame < 8000; frame++) {
            short value = (short) Math.round(Math.sin(frame * Math.PI / 10) * 24000);
            write(pcm, frame * 4, value); write(pcm, frame * 4 + 2, (short) -value);
        }
        float[] levels = new float[48];
        WaveformSampler.sample(new PcmAudio(pcm, 8000, 2), 750, levels);
        for (float value : levels) assertTrue(value > .65 && value <= 1, "Stereo waveform disappeared");
    }

    @Test void followsSeeksWithoutReadingFutureAudioOrRetainingOldBars() {
        byte[] pcm = new byte[8000 * 2];
        for (int frame = 4000; frame < 8000; frame++) write(pcm, frame * 2, Short.MIN_VALUE);
        var audio = new PcmAudio(pcm, 8000, 1);
        float[] levels = new float[32];
        WaveformSampler.sample(audio, 500, levels);
        assertArrayEquals(new float[32], levels, "Future loud passage must not appear before it plays");
        WaveformSampler.sample(audio, 660, levels);
        for (int i = 0; i < levels.length; i++) assertEquals(i < 16 ? 0 : 1, levels[i]);
        WaveformSampler.sample(audio, 900, levels);
        for (float level : levels) assertEquals(1, level);
        WaveformSampler.sample(audio, 200, levels);
        assertArrayEquals(new float[32], levels);
    }

    @Test void handlesStartEndShortClipsAndUnusualSampleRates() {
        for (int rate : new int[]{1, 8000, 44100, 48000, 192000}) {
            var audio = new PcmAudio(new byte[]{0, -128, -1, 127}, rate, 1);
            for (int count : new int[]{0, 1, 64}) {
                float[] levels = new float[count];
                for (long position : new long[]{Long.MIN_VALUE, 0, 1, Long.MAX_VALUE}) {
                    assertDoesNotThrow(() -> WaveformSampler.sample(audio, position, levels));
                    for (float level : levels) assertTrue(Float.isFinite(level) && level >= 0 && level <= 1);
                }
                WaveformSampler.sample(audio, 0, levels);
                assertArrayEquals(new float[count], levels);
            }
        }
    }

    private static void write(byte[] pcm, int offset, short value) {
        pcm[offset] = (byte) value; pcm[offset + 1] = (byte) (value >> 8);
    }
}
