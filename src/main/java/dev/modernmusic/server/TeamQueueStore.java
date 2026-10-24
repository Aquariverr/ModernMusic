package dev.modernmusic.server;

import com.google.gson.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.storage.AtomicStoreFile;
import dev.modernmusic.storage.SnapshotWriter;
import dev.modernmusic.room.RoomService.TeamQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** World-local FTB team playlists. */
public final class TeamQueueStore implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final AtomicStoreFile disk;
    private final SnapshotWriter<List<TeamQueue>> writer;
    private volatile boolean failed;
    public TeamQueueStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        disk = new AtomicStoreFile(this.file, "modernmusic-team-queues-", "team_queue_path");
        writer = new SnapshotWriter<>("modernmusic-team-queues", this::write, () -> failed = false, this::reportFailure);
    }
    public boolean failed() { return failed; }
    public List<TeamQueue> load() {
        try {
            disk.checkPath();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            var saved = new ArrayList<TeamQueue>(); var ids = new HashSet<UUID>();
            for (var element : root.getAsJsonArray("teams")) {
                var row = element.getAsJsonObject(); var songs = new ArrayList<SongId>();
                for (var song : row.getAsJsonArray("songs")) {
                    songs.add(SongId.stored(song.getAsString()));
                    if (songs.size() > PlaybackQueue.MAX_TRACKS) throw new IllegalArgumentException();
                }
                UUID team = UUID.fromString(row.get("team").getAsString());
                if (!ids.add(team)) throw new IllegalArgumentException();
                saved.add(new TeamQueue(team, songs, row.get("selected").getAsInt(), PlaybackMode.valueOf(row.get("mode").getAsString())));
            }
            failed = false; return List.copyOf(saved);
        } catch (Exception failure) {
            disk.backupOnNextWrite(); reportFailure(); return List.of();
        }
    }
    public void save(List<TeamQueue> queues) {
        writer.save(List.copyOf(queues));
    }
    private void write(List<TeamQueue> queues) throws Exception {
        var root = new JsonObject(); root.addProperty("version", 1);
        var teams = new JsonArray(); root.add("teams", teams);
        for (var queue : queues) {
            var row = new JsonObject(); row.addProperty("team", queue.team().toString());
            row.addProperty("selected", queue.selected()); row.addProperty("mode", queue.mode().name());
            var songs = new JsonArray(); queue.songs().forEach(song -> songs.add(song.toString()));
            row.add("songs", songs); teams.add(row);
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
        disk.write(bytes);
    }
    private void reportFailure() {
        failed = true;
        LoggerFactory.getLogger(TeamQueueStore.class).warn("Cannot read or save FTB team playlists: {}", file);
    }
    @Override public void close() { writer.close(); }
}
