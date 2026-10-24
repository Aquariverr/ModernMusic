package dev.modernmusic.room;

/** Minimum RTT sample in a moving window limits jitter bias; no wall clock or game tick time. */
public final class ClockEstimator {
    private long offset, bestRtt = Long.MAX_VALUE, window;
    private boolean ready;
    public boolean sample(long sent, long received, long server) {
        long rtt = received - sent;
        if (rtt < 0 || rtt > 2_000) return false;
        if (!ready || received - window > 30_000) { bestRtt = Long.MAX_VALUE; window = received; }
        if (rtt <= bestRtt) { bestRtt = rtt; offset = server - sent - rtt / 2; ready = true; }
        return true;
    }
    public boolean ready() { return ready; }
    public long serverNow(long clientNow) { return clientNow + offset; }
    public long rtt() { return ready ? bestRtt : 0; }
}
