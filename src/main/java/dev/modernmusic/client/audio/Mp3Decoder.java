package dev.modernmusic.client.audio;

import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import javazoom.jl.decoder.*;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/** Decode the actual frames once; seeking uses PCM frame offsets and is independent of MP3 bitrate. */
public final class Mp3Decoder {
    public static final int MAX_PCM_BYTES = 96 * 1024 * 1024;

    public PcmAudio decode(byte[] mp3) {
        Decoded decoded = decodeFrames(mp3, true);
        return new PcmAudio(decoded.samples, decoded.info.sampleRate, decoded.info.channels);
    }

    /** Validate every frame while measuring actual samples, without retaining the expanded audio. */
    public Info inspect(byte[] mp3) { return decodeFrames(mp3, false).info; }

    public record Info(int sampleRate, int channels, long frameCount) {
        public long durationMs() { return frameCount * 1000 / sampleRate; }
    }
    private record Decoded(byte[] samples, Info info) { }

    private Decoded decodeFrames(byte[] mp3, boolean retainPcm) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        // JLayer allocates the ID3v2 size from the header before reading it. Bound it before construction.
        if (mp3.length >= 10 && mp3[0] == 'I' && mp3[1] == 'D' && mp3[2] == '3') {
            int size = 0;
            for (int i = 6; i < 10; i++) {
                if ((mp3[i] & 0x80) != 0) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
                size = (size << 7) | mp3[i];
            }
            if (size > 1024 * 1024) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
            if (size > mp3.length - 10) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
        }
        Bitstream stream = new Bitstream(new ByteArrayInputStream(mp3));
        try {
            Decoder decoder = new Decoder();
            PcmBuffer pcm = new PcmBuffer();
            int rate = 0;
            int channels = 0;
            long sampleCount = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                Header header = stream.readFrame();
                if (header == null) break;
                if (header.layer() != 3) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, stream);
                if (rate == 0) { rate = frame.getSampleFrequency(); channels = frame.getChannelCount(); }
                if (rate != frame.getSampleFrequency() || channels != frame.getChannelCount()) {
                    throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
                }
                int length = frame.getBufferLength();
                if (length > (MAX_PCM_BYTES / 2L) - sampleCount) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
                sampleCount += length;
                if (retainPcm) pcm.append(frame.getBuffer(), length);
                stream.closeFrame();
            }
            if (sampleCount == 0) throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
            return new Decoded(retainPcm ? pcm.toByteArray() : null, new Info(rate, channels, sampleCount / channels));
        } catch (JavaLayerException exception) {
            throw new MusicException(MusicError.UNSUPPORTED_FORMAT);
        } finally {
            try { stream.close(); } catch (BitstreamException ignored) { }
        }
    }

    /** Fixed-size chunks avoid geometric reallocations and a synchronized stream call per sample byte. */
    static final class PcmBuffer {
        private static final int CHUNK_BYTES = 64 * 1024;
        private final ArrayList<byte[]> chunks = new ArrayList<>();
        private int size, used;
        private byte[] current;

        void append(short[] samples, int length) {
            if (length > (MAX_PCM_BYTES - size) / 2) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
            for (int i = 0; i < length; i++) {
                if (current == null || used == current.length) {
                    current = new byte[CHUNK_BYTES]; chunks.add(current); used = 0;
                }
                current[used++] = (byte) samples[i];
                current[used++] = (byte) (samples[i] >>> 8);
            }
            size += length * 2;
        }

        byte[] toByteArray() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            byte[] result = new byte[size];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                int length = Math.min(chunk.length, size - offset);
                System.arraycopy(chunk, 0, result, offset, length); offset += length;
            }
            return result;
        }
    }
}
