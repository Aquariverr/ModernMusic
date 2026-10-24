package dev.modernmusic.client;

import dev.modernmusic.server.AudioTransfer;

/** Bounded slot waiting and file transfer have separate deadlines; busy replies cannot extend either stage. */
final class AudioDownloadRetry {
    static final long TIMEOUT_MS = AudioTransfer.TIMEOUT_MS, WAIT_TIMEOUT_MS = AudioTransfer.WAIT_TIMEOUT_MS;
    private long startedAt;
    private long nextAttempt;
    private int failures;
    private boolean waiting, cancelled, transferring;

    AudioDownloadRetry(long startedAt) { this.startedAt = startedAt; }
    boolean expired(long now) { return now - startedAt >= (transferring ? TIMEOUT_MS : WAIT_TIMEOUT_MS); }
    boolean timedOut(long now, long lastProgress) { return expired(now) || transferring && now - lastProgress > TIMEOUT_MS; }
    boolean transferring() { return transferring; }
    void begin(long now) {
        if (!transferring && !cancelled && !expired(now)) { transferring = true; startedAt = now; waiting = false; }
    }
    boolean defer(long now) {
        if (cancelled || transferring || expired(now)) return false;
        if (!waiting) {
            nextAttempt = now + (1_000L << Math.min(failures, 3));
            failures = Math.min(failures + 1, 3); waiting = true;
        }
        return true;
    }
    boolean poll(long now) {
        if (cancelled || transferring || expired(now) || !waiting || now < nextAttempt) return false;
        waiting = false; return true;
    }
    void cancel() { cancelled = true; waiting = false; }
}
