package dev.modernmusic.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Main-thread-owned queue; only normalized IDs survive between playback attempts. */
public final class PlaybackQueue {
    public static final int MAX_TRACKS = 400;
    private final List<SongId> entries = new ArrayList<>();
    private List<SongId> snapshot = List.of();
    private int index = -1;
    private PlaybackMode mode = PlaybackMode.SEQUENTIAL;
    private final List<Integer> shuffled = new ArrayList<>();
    private final java.util.Random random = new java.util.Random();

    public boolean add(SongId id) {
        java.util.Objects.requireNonNull(id);
        if (contains(id) || entries.size() == MAX_TRACKS) return false;
        entries.add(id);
        snapshot = null;
        shuffled.clear();
        return true;
    }

    public boolean contains(SongId id) { return entries.contains(id); }
    /** Process a prefix in order, skipping duplicates without consuming capacity. */
    public record AppendResult(int added, int processed) {
        public int skipped() { return processed - added; }
    }
    public AppendResult append(List<SongId> songs) {
        int added = 0, processed = 0;
        for (SongId song : List.copyOf(songs)) {
            if (contains(song)) { processed++; continue; }
            if (!add(song)) break;
            added++; processed++;
        }
        return new AppendResult(added, processed);
    }
    /** Restore saved/authoritative order exactly, including legacy duplicates and their selected index. */
    public void restore(List<SongId> songs, int selected, PlaybackMode mode) {
        var restored = List.copyOf(songs);
        java.util.Objects.requireNonNull(mode);
        if (restored.size() > MAX_TRACKS || selected < -1 || selected >= restored.size()) throw new IllegalArgumentException("queue");
        entries.clear(); entries.addAll(restored); snapshot = restored; index = selected; this.mode = mode; shuffled.clear();
    }

    public Optional<SongId> select(int selected) {
        if (selected < 0 || selected >= entries.size()) return Optional.empty();
        index = selected;
        return current();
    }

    public Optional<SongId> next() {
        if (index + 1 >= entries.size()) return Optional.empty();
        return select(index + 1);
    }

    public PlaybackMode mode() { return mode; }
    public void mode(PlaybackMode value) { mode = java.util.Objects.requireNonNull(value); shuffled.clear(); }

    /** Manual next skips the current song even in repeat-one mode. Shuffle visits every other entry once. */
    public int following(boolean automatic) {
        if (entries.isEmpty()) return -1;
        if (index < 0) return 0;
        if (automatic && mode == PlaybackMode.REPEAT_ONE) return index;
        if (mode == PlaybackMode.SHUFFLE) {
            if (entries.size() == 1) return 0;
            shuffled.remove(Integer.valueOf(index));
            if (shuffled.isEmpty()) {
                for (int i = 0; i < entries.size(); i++) if (i != index) shuffled.add(i);
                java.util.Collections.shuffle(shuffled, random);
            }
            return shuffled.removeFirst();
        }
        if (index + 1 < entries.size()) return index + 1;
        return mode == PlaybackMode.SEQUENTIAL ? -1 : 0;
    }

    public Optional<SongId> current() {
        return index < 0 ? Optional.empty() : Optional.of(entries.get(index));
    }

    /** Removes exactly one occurrence, preserving the selected song when an earlier row is removed. */
    public boolean remove(int removed) {
        if (removed < 0 || removed >= entries.size()) return false;
        entries.remove(removed); snapshot = null; shuffled.clear();
        if (entries.isEmpty()) index = -1;
        else if (removed < index) index--;
        else if (removed == index) index = Math.min(index, entries.size() - 1);
        return true;
    }

    /** Reuse the immutable view between edits; rendering and membership readers never copy the queue. */
    public List<SongId> entries() {
        if (snapshot == null) snapshot = List.copyOf(entries);
        return snapshot;
    }
    public int index() { return index; }
    public void clear() { entries.clear(); snapshot = List.of(); shuffled.clear(); index = -1; }
}
