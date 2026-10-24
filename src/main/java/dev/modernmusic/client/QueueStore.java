package dev.modernmusic.client;

import com.google.gson.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.storage.AtomicStoreFile;
import dev.modernmusic.storage.SnapshotWriter;
import dev.modernmusic.provider.ProviderUris;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Persistent personal queues and optional track metadata. */
public final class QueueStore implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final AtomicStoreFile disk;
    private final SnapshotWriter<QueueLibrary.Snapshot> writer;
    private volatile boolean failed, closed;
    public QueueStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        disk = new AtomicStoreFile(this.file, "modernmusic-queues-", "queue_path");
        writer = new SnapshotWriter<>("modernmusic-queues", this::write, () -> failed = false, () -> failed = true);
    }
    public boolean failed() { return failed; }
    public CompletableFuture<QueueLibrary.Snapshot> load() {
        return writer.load(() -> {
            try {
                disk.checkPath();
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IllegalArgumentException();
                byte[] bytes;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
                JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
                var queues = new ArrayList<QueueLibrary.SavedQueue>();
                for (var value : root.getAsJsonArray("queues")) {
                    var row = value.getAsJsonObject(); var songs = new ArrayList<SongId>();
                    for (var id : row.getAsJsonArray("songs")) {
                        songs.add(SongId.stored(id.getAsString()));
                        if (songs.size() > PlaybackQueue.MAX_TRACKS) throw new IllegalArgumentException();
                    }
                    queues.add(new QueueLibrary.SavedQueue(row.get("id").getAsString(), row.get("name").getAsString(), songs,
                            row.get("selected").getAsInt(), PlaybackMode.valueOf(row.get("mode").getAsString())));
                    if (queues.size() > QueueLibrary.MAX_QUEUES) throw new IllegalArgumentException();
                }
                var metadata = new ArrayList<Track>();
                if (root.has("tracks")) for (var value : root.getAsJsonArray("tracks")) {
                    if (metadata.size() >= QueueLibrary.MAX_QUEUES * PlaybackQueue.MAX_TRACKS) break;
                    try {
                        var row = value.getAsJsonObject(); var artists = new ArrayList<String>();
                        for (var artist : row.getAsJsonArray("artists")) { if (artists.size() >= 32) break; artists.add(DisplayText.clean(artist.getAsString())); }
                        metadata.add(new Track(SongId.stored(row.get("id").getAsString()), DisplayText.clean(row.get("title").getAsString()), artists,
                                row.get("duration").getAsLong(), row.has("artwork") ? ProviderUris.image(row.get("artwork").getAsString()) : null));
                    } catch (RuntimeException ignored) { /* Optional metadata never discards an otherwise valid queue. */ }
                }
                var snapshot = new QueueLibrary.Snapshot(root.get("active").getAsString(), queues, metadata);
                new QueueLibrary().restore(snapshot); failed = false; return snapshot;
            } catch (Exception error) {
                failed = true; disk.backupOnNextWrite(); throw new CompletionException(new IllegalStateException("queue_storage"));
            }
        });
    }
    public void save(QueueLibrary.Snapshot snapshot) {
        if (!closed) writer.save(snapshot);
    }
    private void write(QueueLibrary.Snapshot snapshot) throws Exception {
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("active", snapshot.active());
        JsonArray queues = new JsonArray(); root.add("queues", queues);
        for (var saved : snapshot.queues()) {
            JsonObject row = new JsonObject(); row.addProperty("id", saved.id()); row.addProperty("name", saved.name());
            row.addProperty("selected", saved.selected()); row.addProperty("mode", saved.mode().name());
            JsonArray songs = new JsonArray(); saved.songs().forEach(id -> songs.add(id.toString())); row.add("songs", songs); queues.add(row);
        }
        JsonArray tracks = new JsonArray(); root.add("tracks", tracks);
        for (Track track : snapshot.tracks()) {
            JsonObject row = new JsonObject(); row.addProperty("id", track.id().toString()); row.addProperty("title", track.title());
            row.addProperty("duration", track.durationMs()); JsonArray artists = new JsonArray(); track.artists().forEach(artists::add); row.add("artists", artists);
            if (track.artwork() != null) row.addProperty("artwork", track.artwork().toString()); tracks.add(row);
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
        disk.write(bytes);
    }
    @Override public void close() { closed = true; writer.close(); }
}
