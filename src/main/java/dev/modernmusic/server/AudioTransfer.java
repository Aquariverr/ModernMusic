package dev.modernmusic.server;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import java.util.Arrays;

/** Strict sequential, bounded transfer assembly. Authorization is checked at every upload stage. */
public final class AudioTransfer {
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final long TIMEOUT_MS = 120_000;
    /** Waiting requests retain metadata only; active file buffers keep their shorter transfer timeout. */
    public static final long WAIT_TIMEOUT_MS = 600_000;
    private final byte[] bytes;
    private int received;
    private long touched;

    public AudioTransfer(int total, boolean authorized, long now) {
        authorize(authorized);
        if (total <= 0 || total > AudioFiles.MAX_AUDIO_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
        bytes = new byte[total]; touched = now;
    }
    public void append(int offset, byte[] chunk, boolean authorized, long now) {
        authorize(authorized);
        if (expired(now)) throw new MusicException(MusicError.NETWORK_ERROR);
        if (offset != received || chunk.length == 0 || chunk.length > CHUNK_BYTES || chunk.length > bytes.length - received)
            throw new MusicException(MusicError.INVALID_RESPONSE);
        System.arraycopy(chunk, 0, bytes, received, chunk.length); received += chunk.length; touched = now;
    }
    public byte[] finish(boolean authorized, long now) {
        authorize(authorized);
        if (expired(now)) throw new MusicException(MusicError.NETWORK_ERROR);
        if (received != bytes.length) throw new MusicException(MusicError.INVALID_RESPONSE);
        return bytes;
    }
    public boolean expired(long now) { return now - touched > TIMEOUT_MS; }
    public int received() { return received; }
    public int total() { return bytes.length; }
    public void clear() { Arrays.fill(bytes, (byte) 0); }
    private static void authorize(boolean authorized) { if (!authorized) throw new MusicException(MusicError.SHARED_FORBIDDEN); }
}
