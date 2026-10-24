package dev.modernmusic.domain;

import java.util.*;

/** Client-owned named queues. Snapshots contain no credentials, temporary media URLs or audio. */
public final class QueueLibrary {
    public static final int MAX_QUEUES = 64, MAX_NAME = 48;
    public record SavedQueue(String id, String name, List<SongId> songs, int selected, PlaybackMode mode) {
        public SavedQueue { songs = List.copyOf(songs); }
    }
    public record Snapshot(String active, List<SavedQueue> queues, List<Track> tracks) {
        public Snapshot { queues = List.copyOf(queues); tracks = List.copyOf(tracks); }
    }
    public static final class NamedQueue {
        private final String id;
        private String name;
        private final PlaybackQueue queue = new PlaybackQueue();
        private NamedQueue(String id, String name) { this.id = id; this.name = cleanName(name); }
        public String id() { return id; }
        public String name() { return name; }
        public PlaybackQueue queue() { return queue; }
    }
    private final List<NamedQueue> queues = new ArrayList<>();
    private NamedQueue active;
    public QueueLibrary() { create("默认队列"); }
    public NamedQueue active() { return active; }
    public List<NamedQueue> queues() { return List.copyOf(queues); }
    public NamedQueue create(String name) {
        if (queues.size() >= MAX_QUEUES) throw new IllegalStateException("queue_limit");
        var value = new NamedQueue(UUID.randomUUID().toString(), name); queues.add(value); active = value; return value;
    }
    public boolean select(String id) {
        for (var queue : queues) if (queue.id.equals(id)) { active = queue; return true; }
        return false;
    }
    public void rename(String name) { active.name = cleanName(name); }
    public static String cleanName(String name) {
        if (name == null) throw new IllegalArgumentException("queue_name");
        String cleaned = name.replaceAll("[\\p{Cntrl}§]", "").strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_NAME) throw new IllegalArgumentException("queue_name");
        return cleaned;
    }
    public Set<SongId> songIds() {
        var ids = new HashSet<SongId>(); for (var queue : queues) ids.addAll(queue.queue.entries()); return ids;
    }
    public Snapshot snapshot(Collection<Track> tracks) {
        var ids = songIds();
        return new Snapshot(active.id, queues.stream().map(q -> new SavedQueue(q.id, q.name, q.queue.entries(), q.queue.index(), q.queue.mode())).toList(),
                tracks.stream().filter(t -> ids.contains(t.id())).toList());
    }
    public void restore(Snapshot snapshot) {
        if (snapshot.queues().isEmpty() || snapshot.queues().size() > MAX_QUEUES) throw new IllegalArgumentException("queues");
        var restored = new ArrayList<NamedQueue>(); var ids = new HashSet<String>();
        for (var saved : snapshot.queues()) {
            if (!ids.add(saved.id()) || saved.id() == null || saved.id().length() > 64 || saved.songs().size() > PlaybackQueue.MAX_TRACKS
                    || saved.selected() < -1 || saved.selected() >= saved.songs().size()) throw new IllegalArgumentException("queue");
            var entry = new NamedQueue(saved.id(), saved.name());
            entry.queue.restore(saved.songs(), saved.selected(), saved.mode()); restored.add(entry);
        }
        if (!ids.contains(snapshot.active())) throw new IllegalArgumentException("active_queue");
        queues.clear(); queues.addAll(restored); select(snapshot.active());
    }
}
