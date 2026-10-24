package dev.modernmusic.client.audio;

import javax.sound.sampled.*;

/** All device calls run on one daemon thread. The game thread only publishes short commands. */
public final class JavaSoundOutput implements AudioOutput {
    private final Object lock = new Object();
    private Thread worker;
    @FunctionalInterface interface LineFactory { SourceDataLine create(AudioFormat format) throws LineUnavailableException; }
    private final LineFactory lines;
    private PcmAudio requestedAudio;
    private long requestedPosition;
    private long revision;
    private boolean paused;
    private boolean closed;
    private volatile float gain = 0.7f;
    private volatile Snapshot snapshot = new Snapshot(State.STOPPED, 0, 0);

    public JavaSoundOutput() {
        this(AudioSystem::getSourceDataLine);
    }
    JavaSoundOutput(LineFactory lines) {
        this.lines = lines;
    }

    @Override public void load(PcmAudio audio) {
        load(audio, 0, false);
    }
    @Override public void load(PcmAudio audio, long positionMs, boolean initiallyPaused) {
        synchronized (lock) {
            if (closed) return;
            requestedAudio = audio; requestedPosition = Math.clamp(positionMs, 0, audio.durationMs()); paused = initiallyPaused; revision++;
            snapshot = new Snapshot(State.STARTING, requestedPosition, audio.durationMs()); lock.notifyAll();
            if (worker == null) worker = Thread.ofPlatform().daemon().name("modernmusic-audio").start(this::run);
        }
    }
    @Override public void pause(boolean value) {
        synchronized (lock) {
            if (paused == value) return;
            paused = value; lock.notifyAll();
        }
    }
    @Override public void seek(long positionMs) {
        synchronized (lock) {
            if (requestedAudio == null) return;
            requestedPosition = Math.max(0, Math.min(positionMs, requestedAudio.durationMs()));
            revision++; lock.notifyAll();
        }
    }
    @Override public void volume(float value) { gain = Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0; }
    @Override public void stop() {
        synchronized (lock) {
            requestedAudio = null; revision++;
            snapshot = new Snapshot(State.STOPPED, 0, 0); lock.notifyAll();
        }
    }
    @Override public Snapshot snapshot() { return snapshot; }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; requestedAudio = null; revision++;
            snapshot = new Snapshot(State.STOPPED, 0, 0); lock.notifyAll();
            if (worker != null) worker.interrupt();
        }
    }

    private void run() {
        SourceDataLine line = null;
        long activeRevision = -1;
        PcmAudio audio = null;
        int cursor = 0;
        int startOffset = 0;
        long startFrame = 0;
        byte[] chunk = new byte[4096];
        try {
            while (true) {
                PcmAudio desired;
                boolean isPaused;
                long targetRevision;
                long targetPosition;
                synchronized (lock) {
                    if (closed) return;
                    desired = requestedAudio; isPaused = paused;
                    targetRevision = revision; targetPosition = requestedPosition;
                }
                try {
                    if (activeRevision != targetRevision) {
                        if (line != null) {
                            line.stop(); line.flush();
                            // A room alignment/seek changes the cursor, not the device or PCM format.
                            if (desired != audio) { line.close(); line = null; }
                        }
                        audio = desired; activeRevision = targetRevision;
                        if (audio != null) {
                            if (line == null) {
                                AudioFormat format = new AudioFormat(audio.sampleRate(), 16, audio.channels(), true, false);
                                line = lines.create(format);
                                line.open(format, audio.sampleRate() / 10 * audio.frameSize());
                            }
                            cursor = audio.byteOffset(targetPosition); startOffset = cursor;
                            startFrame = line.getLongFramePosition();
                        }
                    }
                    if (audio == null || line == null) { awaitCommand(activeRevision, isPaused, 0); continue; }
                    long playedFrames = Math.max(0, line.getLongFramePosition() - startFrame);
                    long position = Math.min(audio.durationMs(),
                            (startOffset / audio.frameSize() + playedFrames) * 1000 / audio.sampleRate());
                    if (isPaused) {
                        line.stop(); publish(activeRevision, new Snapshot(State.PAUSED, position, audio.durationMs()));
                        awaitCommand(activeRevision, true, 0); continue;
                    }
                    line.start();
                    if (cursor >= audio.samples().length) {
                        // Avoid blocking drain(): commands must remain responsive at the end of a track.
                        if (playedFrames >= (audio.samples().length - startOffset) / audio.frameSize()) {
                            line.stop(); publish(activeRevision, new Snapshot(State.FINISHED, audio.durationMs(), audio.durationMs()));
                            awaitCommand(activeRevision, false, 0);
                        } else {
                            publish(activeRevision, new Snapshot(State.PLAYING, position, audio.durationMs()));
                            awaitCommand(activeRevision, false, 10);
                        }
                        continue;
                    }
                    int length = Math.min(chunk.length, audio.samples().length - cursor);
                    length = Math.min(length, line.available());
                    length -= length % audio.frameSize();
                    if (length == 0) { awaitCommand(activeRevision, false, 10); continue; }
                    scale(audio.samples(), cursor, chunk, length, gain);
                    int written = line.write(chunk, 0, length);
                    cursor += written;
                    publish(activeRevision, new Snapshot(State.PLAYING, position, audio.durationMs()));
                } catch (LineUnavailableException | IllegalArgumentException | IllegalStateException exception) {
                    if (line != null) { line.close(); line = null; }
                    audio = null;
                    synchronized (lock) { if (revision == activeRevision) requestedAudio = null; }
                    publish(activeRevision, new Snapshot(State.ERROR, 0, 0));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (line != null) { line.stop(); line.flush(); line.close(); }
        }
    }

    private void publish(long activeRevision, Snapshot value) {
        synchronized (lock) { if (!closed && revision == activeRevision) snapshot = value; }
    }

    private void awaitCommand(long activeRevision, boolean wasPaused, long timeoutMs) throws InterruptedException {
        synchronized (lock) {
            // Check under the same lock as wait: a load/resume/seek published just before this call
            // must not be lost when an idle device waits indefinitely instead of polling at 100 Hz.
            if (!closed && revision == activeRevision && paused == wasPaused) lock.wait(timeoutMs);
        }
    }

    static void scale(byte[] source, int offset, byte[] target, int length, float gain) {
        if (gain == 1) { System.arraycopy(source, offset, target, 0, length); return; }
        if (gain == 0) { java.util.Arrays.fill(target, 0, length, (byte) 0); return; }
        for (int i = 0; i < length; i += 2) {
            short sample = (short) ((source[offset + i] & 255) | (source[offset + i + 1] << 8));
            int value = Math.round(sample * gain);
            target[i] = (byte) value; target[i + 1] = (byte) (value >> 8);
        }
    }
}
