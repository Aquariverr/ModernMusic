package dev.modernmusic.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Owner-thread state delivery: changes immediately, unchanged state at most once per heartbeat. */
final class StateHeartbeat<K, V> {
    static final long INTERVAL_MS = 5_000;
    private record Sent<V>(V value, long time) {}
    private final Map<K, Sent<V>> sent = new HashMap<>();

    boolean update(K key, V value, long now) {
        Sent<V> previous = sent.get(key);
        if (previous != null && Objects.equals(previous.value, value)
                && now >= previous.time && now - previous.time < INTERVAL_MS) return false;
        sent.put(key, new Sent<>(value, now));
        return true;
    }
    void remove(K key) { sent.remove(key); }
    void clear() { sent.clear(); }
}
