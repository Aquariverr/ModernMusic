package dev.modernmusic.client.audio;

/** Signed little-endian 16-bit PCM. Owned by the player, never mutated after decoding. */
public record PcmAudio(byte[] samples, int sampleRate, int channels) {
    public PcmAudio {
        if (sampleRate <= 0 || channels < 1 || channels > 2 || samples.length == 0
                || samples.length % (channels * 2) != 0) throw new IllegalArgumentException("Invalid PCM");
    }

    public int frameSize() { return channels * 2; }
    public long frameCount() { return samples.length / frameSize(); }
    public long durationMs() { return frameCount() * 1000 / sampleRate; }
    public int byteOffset(long positionMs) {
        long frame = Math.min(frameCount(), Math.max(0, Math.min(positionMs, durationMs())) * sampleRate / 1000);
        return (int) (frame * frameSize());
    }
}
