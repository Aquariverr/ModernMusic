package dev.modernmusic.server;

import com.google.gson.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.storage.AtomicStoreFile;
import dev.modernmusic.storage.SnapshotWriter;
import dev.modernmusic.room.RoomService;
import dev.modernmusic.room.RoomService.PersistentRoom;
import dev.modernmusic.room.RoomSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Durable room identity, access and queues; transient playback clocks are never restored. */
public final class RoomStore implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final AtomicStoreFile disk;
    private final SnapshotWriter<List<PersistentRoom>> writer;
    private volatile boolean failed;

    public RoomStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        disk = new AtomicStoreFile(this.file, "modernmusic-rooms-", "room_path");
        writer = new SnapshotWriter<>("modernmusic-rooms", this::write, () -> failed = false, this::reportFailure);
    }
    public boolean failed() { return failed; }
    public List<PersistentRoom> load() {
        try {
            disk.checkPath();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
                throw new IllegalArgumentException("room_file_size");
            byte[] bytes;
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("room_file_size");
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("room_file_version");
            var saved = new ArrayList<PersistentRoom>(); var ids = new HashSet<UUID>();
            for (var element : root.getAsJsonArray("rooms")) {
                var row = element.getAsJsonObject();
                UUID id = UUID.fromString(row.get("id").getAsString());
                if (!ids.add(id) || ids.size() - (ids.contains(RoomService.PUBLIC_ROOM_ID) ? 1 : 0) > RoomService.MAX_ROOMS)
                    throw new IllegalArgumentException("room_ids");
                var members = new ArrayList<RoomSnapshot.Member>();
                for (var value : row.getAsJsonArray("members")) {
                    var member = value.getAsJsonObject();
                    members.add(new RoomSnapshot.Member(UUID.fromString(member.get("id").getAsString()),
                            member.get("name").getAsString(), member.get("dj").getAsBoolean(), member.get("requester").getAsBoolean()));
                }
                var songs = new ArrayList<SongId>();
                for (var song : row.getAsJsonArray("songs")) {
                    songs.add(SongId.stored(song.getAsString()));
                    if (songs.size() > PlaybackQueue.MAX_TRACKS) throw new IllegalArgumentException("room_songs");
                }
                saved.add(new PersistentRoom(id, row.get("name").getAsString(), UUID.fromString(row.get("owner").getAsString()),
                        members, songs, row.get("selected").getAsInt(), PlaybackMode.valueOf(row.get("mode").getAsString())));
            }
            failed = false; return List.copyOf(saved);
        } catch (Exception failure) {
            disk.backupOnNextWrite(); reportFailure(); return List.of();
        }
    }
    public void save(List<PersistentRoom> rooms) {
        writer.save(List.copyOf(rooms));
    }
    private void write(List<PersistentRoom> rooms) throws Exception {
        if (rooms.size() > RoomService.MAX_ROOMS + 1
                || rooms.stream().filter(room -> !RoomService.PUBLIC_ROOM_ID.equals(room.id())).count() > RoomService.MAX_ROOMS)
            throw new IllegalArgumentException("room_count");
        var root = new JsonObject(); root.addProperty("version", 1);
        var entries = new JsonArray(); root.add("rooms", entries);
        for (var room : rooms) {
            var row = new JsonObject(); entries.add(row);
            row.addProperty("id", room.id().toString()); row.addProperty("name", room.name());
            row.addProperty("owner", room.owner().toString()); row.addProperty("selected", room.selected());
            row.addProperty("mode", room.mode().name());
            var members = new JsonArray(); row.add("members", members);
            for (var member : room.members()) {
                var item = new JsonObject(); members.add(item);
                item.addProperty("id", member.id().toString()); item.addProperty("name", member.name());
                item.addProperty("dj", member.dj()); item.addProperty("requester", member.requester());
            }
            var songs = new JsonArray(); row.add("songs", songs);
            room.songs().forEach(song -> songs.add(song.toString()));
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("room_file_size");
        disk.write(bytes);
    }
    private void reportFailure() {
        failed = true; LoggerFactory.getLogger(RoomStore.class).warn("Cannot read or save music rooms: {}", file);
    }
    @Override public void close() { writer.close(); }
}
