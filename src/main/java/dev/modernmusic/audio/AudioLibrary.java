package dev.modernmusic.audio;

import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/** The client's own audio folder. No paths or arbitrary filesystem locations become song IDs. */
public final class AudioLibrary {
    private final Path directory;
    private final DirectoryReader directoryReader;
    private Map<String, Cached> cache = new HashMap<>();
    private Map<SongId, Entry> entries = Map.of();
    private Map<SongId, String> names = Map.of();

    @FunctionalInterface interface DirectoryReader { DirectoryStream<Path> open(Path path) throws IOException; }

    public AudioLibrary(Path directory) { this(directory, Files::newDirectoryStream); }
    AudioLibrary(Path directory, DirectoryReader directoryReader) {
        this.directory = directory.toAbsolutePath().normalize(); this.directoryReader = directoryReader;
    }
    public static AudioLibrary atGameDirectory(Path gameDirectory) { return new AudioLibrary(gameDirectory.resolve("modernmusic/audio")); }
    public Path directory() { return directory; }

    /** Metadata is revalidated only when a file's identity, size or modification timestamp changes. */
    public synchronized List<Track> list() {
        refresh();
        return entries.values().stream().map(Entry::track).toList();
    }

    /** Prefer a full filename; a stem resolves only when it identifies exactly one audio file. */
    public synchronized Optional<Track> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        refresh();
        String query = name.strip();
        List<Entry> fileMatches = entries.values().stream().filter(entry -> entry.fileName.equalsIgnoreCase(query)).toList();
        if (!fileMatches.isEmpty()) return fileMatches.size() == 1 ? Optional.of(fileMatches.getFirst().track) : Optional.empty();
        List<Entry> titleMatches = entries.values().stream().filter(entry ->
                entry.fileName.substring(0, entry.fileName.lastIndexOf('.')).equalsIgnoreCase(query)).toList();
        return titleMatches.size() == 1 ? Optional.of(titleMatches.getFirst().track) : Optional.empty();
    }

    public synchronized Optional<Track> getTrack(SongId id) {
        if (id == null || !id.isClientLocal()) return Optional.empty();
        Entry entry = lookup(id);
        return Optional.ofNullable(entry == null ? null : entry.track);
    }

    public synchronized byte[] read(SongId id) {
        return readAudio(id).bytes();
    }

    public record Audio(String fileName, byte[] bytes) { }

    /** Resolve and validate one file once, keeping its bytes and decoder format together. */
    public synchronized Audio readAudio(SongId id) {
        Entry entry = requireEntry(id);
        return new Audio(entry.fileName, AudioFiles.read(directory, directory.resolve(entry.fileName)));
    }

    public synchronized String fileName(SongId id) { return requireEntry(id).fileName; }

    public static SongId idForFileName(String fileName) {
        AudioFiles.validateFileName(fileName);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(fileName.getBytes(StandardCharsets.UTF_8));
            return new SongId("local_" + HexFormat.of().formatHex(digest, 0, 16), MusicPlatform.LOCAL);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private Entry requireEntry(SongId id) {
        if (id == null || !id.isClientLocal()) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        Entry entry = lookup(id);
        if (entry == null) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
        return entry;
    }

    /** Discovery is needed for unknown IDs; playback of a known file never scans unrelated files. */
    private Entry lookup(SongId id) {
        String name = names.get(id);
        if (name == null) {
            refresh();
            name = names.get(id);
            // refresh already inspected this file.
            return name == null ? null : entries.get(id);
        }
        try { return inspect(name).entry; }
        catch (IOException | MusicException | SecurityException ignored) {
            cache.remove(name);
            return null;
        }
    }

    /** The same safety and change checks apply to discovery and every targeted lookup. */
    private Cached inspect(String fileName) throws IOException {
        Path path = directory.resolve(fileName);
        AudioFiles.requireContainedFile(directory, path);
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        Cached cached = cache.get(fileName);
        if (cached == null || !cached.stamp.equals(stamp)) {
            Entry entry = null;
            try {
                AudioFiles.Metadata metadata = AudioFiles.inspect(AudioFiles.read(directory, path), fileName);
                entry = new Entry(fileName, new Track(idForFileName(fileName), fileName, List.of(), metadata.durationMs()));
            } catch (MusicException ignored) { /* Invalid files stay hidden until they change. */ }
            cached = new Cached(stamp, entry);
            cache.put(fileName, cached);
        }
        return cached;
    }

    private void refresh() {
        Map<String, Cached> nextCache = new HashMap<>();
        Map<SongId, String> discovered = new HashMap<>();
        List<Entry> found = new ArrayList<>();
        try {
            AudioFiles.createSafeDirectory(directory);
            try (DirectoryStream<Path> files = directoryReader.open(directory)) {
                int examined = 0;
                for (Path path : files) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    if (++examined > AudioFiles.MAX_LIBRARY_FILES) break;
                    String fileName = path.getFileName().toString();
                    String lower = fileName.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".mp3") && !lower.endsWith(".wav")) continue;
                    try {
                        AudioFiles.validateFileName(fileName);
                        discovered.put(idForFileName(fileName), fileName);
                        Cached cached = inspect(fileName);
                        nextCache.put(fileName, cached);
                        if (cached.entry != null) found.add(cached.entry);
                    } catch (IOException | MusicException | SecurityException ignored) { /* One bad file must not hide the library. */ }
                }
            }
        } catch (IOException | SecurityException exception) { throw new MusicException(MusicError.RESOURCE_UNAVAILABLE); }
        found.sort(Comparator.comparing((Entry entry) -> entry.track.title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::fileName));
        Map<SongId, Entry> indexed = new LinkedHashMap<>();
        for (Entry entry : found) indexed.put(entry.track.id(), entry);
        cache = nextCache;
        entries = indexed;
        names = discovered;
    }

    private record Stamp(long size, FileTime modified, Object fileKey) { }
    private record Cached(Stamp stamp, Entry entry) { }
    private record Entry(String fileName, Track track) { }
}
