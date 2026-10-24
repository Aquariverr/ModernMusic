package dev.modernmusic.client.audio;

import dev.modernmusic.provider.MusicException;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

public class Mp3DecoderTest {
    /** Original silent MPEG-1 Layer III frames; alternate bitrates to exercise VBR without third-party media. */
    public static byte[] silentMp3() {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 0; i < 40; i++) {
            int bitrate = i % 2 == 0 ? 128 : 160;
            byte[] frame = new byte[144 * bitrate * 1000 / 44100];
            frame[0] = (byte) 0xff; frame[1] = (byte) 0xfb;
            frame[2] = (byte) (i % 2 == 0 ? 0x90 : 0xa0); frame[3] = 0;
            result.writeBytes(frame);
        }
        return result.toByteArray();
    }
    @Test void decodesVbrFramesAndSeeksBySampleCount() {
        var audio = new Mp3Decoder().decode(silentMp3());
        assertEquals(44100, audio.sampleRate()); assertEquals(2, audio.channels());
        assertEquals(40 * 1152, audio.frameCount());
        assertEquals(44100 * 4 / 2, audio.byteOffset(500));
        assertEquals(0, audio.byteOffset(-1));
        assertTrue(audio.byteOffset(Long.MAX_VALUE) <= audio.samples().length);
    }
    @Test void rejectsHtmlAndEmptyData() {
        assertThrows(MusicException.class, () -> new Mp3Decoder().decode(new byte[0]));
        assertThrows(MusicException.class, () -> new Mp3Decoder().decode("<html>not audio</html>".getBytes()));
    }
    @Test void rejectsOversizedAndTruncatedId3BeforeDecoderAllocation() {
        assertThrows(MusicException.class, () -> new Mp3Decoder().decode(new byte[]{'I', 'D', '3', 4, 0, 0, 127, 127, 127, 127}));
        assertThrows(MusicException.class, () -> new Mp3Decoder().decode(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 100}));
    }
    @Test void softwareVolumePreservesSignedPcmAndSource() {
        byte[] original = {0, (byte) 0x80, (byte) 0xfe, 0x7f};
        byte[] scaled = new byte[4];
        JavaSoundOutput.scale(original, 0, scaled, 4, 0.5f);
        assertArrayEquals(new byte[]{0, (byte) 0xc0, (byte) 0xff, 0x3f}, scaled);
        JavaSoundOutput.scale(original, 0, scaled, 4, 0);
        assertArrayEquals(new byte[4], scaled);
        assertEquals((byte) 0x80, original[1]);
        JavaSoundOutput.scale(original, 0, scaled, 4, 1);
        assertArrayEquals(original, scaled);
    }
    @Test void pcmAccumulationPreservesSamplesAcrossChunkBoundaries() {
        var buffer = new Mp3Decoder.PcmBuffer();
        short[] samples = new short[40_003];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (i * 197);
        buffer.append(samples, samples.length);
        buffer.append(new short[]{Short.MIN_VALUE, Short.MAX_VALUE}, 2);
        byte[] bytes = buffer.toByteArray();
        assertEquals((samples.length + 2) * 2, bytes.length);
        for (int i = 0; i < samples.length; i++)
            assertEquals(samples[i], (short) ((bytes[i * 2] & 255) | bytes[i * 2 + 1] << 8));
        assertArrayEquals(new byte[]{0, (byte) 0x80, (byte) 0xff, 0x7f}, java.util.Arrays.copyOfRange(bytes, bytes.length - 4, bytes.length));
    }
    @Test void cancellationPreventsStartingAnotherDecode() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class, () -> new Mp3Decoder().decode(silentMp3()));
        } finally { Thread.interrupted(); }
    }
}
