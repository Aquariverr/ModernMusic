package dev.modernmusic.server;

import com.google.gson.*;
import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.storage.AtomicStoreFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Persistent server-owned audio. User filenames never become storage paths. */
public final class ServerAudioStore {
    public static final int MAX_FILES = 512;
    public static final long MAX_LIBRARY_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private record Entry(Track track, String format, int bytes) {}
    private final Path directory, manifest;
    private final AtomicStoreFile manifestFile;
    private final Map<SongId, Entry> entries = new LinkedHashMap<>();
    private volatile Map<SongId, Entry> published = Map.of();
    private long usedBytes;
    private boolean failed;

    public ServerAudioStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize(); manifest = this.directory.resolve("catalog.json");
        manifestFile = new AtomicStoreFile(manifest, "audio-catalog-", "audio_path"); load(); publish();
    }
    public synchronized boolean failed() { return failed; }
    public boolean contains(SongId id) { return id.isServerAudio() && published.containsKey(id); }
    public List<Track> catalog() { return published.values().stream().map(Entry::track).toList(); }
    public Track track(SongId id) { return entry(id).track; }
    public byte[] read(SongId id) {
        Entry entry = entry(id);
        byte[] bytes = AudioFiles.read(directory, path(entry));
        if (bytes.length != entry.bytes) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        return bytes;
    }
    public Track add(String filename, byte[] bytes) { return add(filename, bytes, () -> true); }
    public Track add(String filename, byte[] bytes, BooleanSupplier authorized) {
        permit(authorized);
        var metadata = AudioFiles.inspect(bytes, filename);
        synchronized (this) {
            permit(authorized);
            if (failed) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
            if (entries.size() >= MAX_FILES || bytes.length > MAX_LIBRARY_BYTES - usedBytes)
                throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
            var id = new SongId("server_" + UUID.randomUUID().toString().replace("-", ""), MusicPlatform.LOCAL);
            var track = new Track(id, metadata.fileName(), List.of(), metadata.durationMs());
            var entry = new Entry(track, metadata.format(), bytes.length);
            Path file = path(entry), temporary = null;
            boolean moved = false;
            try {
                manifestFile.checkPath(); AudioFiles.createSafeDirectory(directory);
                temporary = Files.createTempFile(directory, "upload-", ".tmp");
                Files.write(temporary, bytes); manifestFile.checkPath(); AudioFiles.requireSafeDirectory(directory); permit(authorized);
                // No REPLACE_EXISTING: uploads cannot overwrite another library entry.
                Files.move(temporary, file); moved = true; permit(authorized);
                entries.put(id, entry); usedBytes += bytes.length;
                try { manifestFile.write(serialize()); }
                catch (Exception error) { entries.remove(id); usedBytes -= bytes.length; throw error; }
                if (!authorized.getAsBoolean()) {
                    entries.remove(id); usedBytes -= bytes.length; manifestFile.write(serialize());
                    throw new MusicException(MusicError.SHARED_FORBIDDEN);
                }
                publish(); return track;
            } catch (Exception error) {
                if (moved && !entries.containsKey(id)) try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                if (error instanceof MusicException music) throw music;
                throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
            } finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) {} }
        }
    }
    private Entry entry(SongId id) {
        Entry entry = published.get(id);
        if (!id.isServerAudio() || entry == null) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        return entry;
    }
    private void publish() { published = Collections.unmodifiableMap(new LinkedHashMap<>(entries)); }
    private Path path(Entry entry) { return directory.resolve(entry.track.id().key() + "." + entry.format); }
    private static void permit(BooleanSupplier authorized) { if (!authorized.getAsBoolean()) throw new MusicException(MusicError.SHARED_FORBIDDEN); }
    private void load() {
        try {
            manifestFile.checkPath();
            if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return;
            AudioFiles.requireContainedFile(directory, manifest);
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > MAX_MANIFEST_BYTES) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(manifest, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1); }
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException();
            var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            for (var item : root.getAsJsonArray("audio")) {
                var row = item.getAsJsonObject(); var id = SongId.stored(row.get("id").getAsString());
                String title = row.get("title").getAsString(), format = row.get("format").getAsString();
                int size = row.get("bytes").getAsInt(); long duration = row.get("durationMs").getAsLong();
                if (!id.isServerAudio() || entries.containsKey(id) || entries.size() >= MAX_FILES || title.isBlank() || title.length() > 180
                        || !(format.equals("mp3") || format.equals("wav")) || size <= 0 || size > AudioFiles.MAX_AUDIO_BYTES
                        || size > MAX_LIBRARY_BYTES - usedBytes || duration <= 0 || duration > 24L * 60 * 60 * 1000) throw new IllegalArgumentException();
                var entry = new Entry(new Track(id, title, List.of(), duration), format, size);
                if (!Files.isRegularFile(path(entry), LinkOption.NOFOLLOW_LINKS) || Files.size(path(entry)) != size) continue;
                entries.put(id, entry); usedBytes += size;
            }
        } catch (Exception error) { entries.clear(); usedBytes = 0; failed = true; }
    }
    private byte[] serialize() {
        var root = new JsonObject(); root.addProperty("version", 1); var array = new JsonArray(); root.add("audio", array);
        for (Entry entry : entries.values()) {
            var row = new JsonObject(); array.add(row); row.addProperty("id", entry.track.id().toString());
            row.addProperty("title", entry.track.title()); row.addProperty("durationMs", entry.track.durationMs());
            row.addProperty("format", entry.format); row.addProperty("bytes", entry.bytes);
        }
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MANIFEST_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
        return bytes;
    }
}
