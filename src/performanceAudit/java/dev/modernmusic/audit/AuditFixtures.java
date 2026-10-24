package dev.modernmusic.audit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Deterministic, quiet PCM files for isolated client/server runs; no account or downloaded media. */
public final class AuditFixtures {
    private AuditFixtures() {}

    public static byte[] wav(int durationSeconds, int sampleRate, int channels) {
        if (durationSeconds <= 0 || sampleRate < 1000 || sampleRate > 192000 || channels < 1 || channels > 2)
            throw new IllegalArgumentException("Invalid audit WAV dimensions");
        int frames = Math.multiplyExact(durationSeconds, sampleRate);
        int pcmBytes = Math.multiplyExact(Math.multiplyExact(frames, channels), 2);
        byte[] data = new byte[Math.addExact(44, pcmBytes)];
        var out = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcmBytes);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels);
        out.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcmBytes);
        int random = 0x13579bdf;
        var wave = new short[1024];
        for (int i = 0; i < wave.length; i++) wave[i] = (short) (Math.sin(2 * Math.PI * i / wave.length) * 192);
        for (int frame = 0; frame < frames; frame++) {
            int tone = wave[(int) ((long) frame * 220 * wave.length / sampleRate % wave.length)];
            for (int channel = 0; channel < channels; channel++) {
                random ^= random << 13; random ^= random >>> 17; random ^= random << 5;
                out.putShort((short) (tone + (random & 255) - 128));
            }
        }
        return data;
    }
}
