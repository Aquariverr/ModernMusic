package dev.modernmusic.audio;

import dev.modernmusic.domain.Track;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** File-drop operations only read the explicitly supplied, regular source file. */
public final class AudioImports {
    private AudioImports() { }
    public record Source(String fileName, byte[] bytes, AudioFiles.Metadata metadata) { }

    public static Source read(Path source) {
        if (source == null || source.getFileName() == null) throw unavailable();
        Path absolute = source.toAbsolutePath().normalize();
        if (absolute.getParent() == null) throw unavailable();
        String fileName = AudioFiles.validateFileName(absolute.getFileName().toString());
        byte[] bytes = AudioFiles.read(absolute.getParent(), absolute);
        return new Source(fileName, bytes, AudioFiles.inspect(bytes, fileName));
    }

    public static Track copyInto(AudioLibrary library, Path source, BooleanSupplier active) {
        checkActive(active);
        Source audio = read(source);
        Path temporary = null;
        try {
            Path directory = AudioFiles.createSafeDirectory(library.directory());
            Path absoluteSource = source.toAbsolutePath().normalize();
            if (directory.equals(absoluteSource.getParent())) {
                // The validated file is already present; retaining its ID avoids a duplicate copy.
                String existingName = absoluteSource.toRealPath().getFileName().toString();
                return track(existingName, audio.metadata());
            }
            Set<String> occupied = new HashSet<>();
            int count = 0;
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    checkActive(active);
                    if (++count >= AudioFiles.MAX_LIBRARY_FILES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
                    occupied.add(entry.getFileName().toString().toLowerCase(Locale.ROOT));
                }
            }
            checkActive(active);
            temporary = Files.createTempFile(directory, ".import-", ".tmp");
            Files.write(temporary, audio.bytes(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            for (int suffix = 0; suffix <= AudioFiles.MAX_LIBRARY_FILES; suffix++) {
                checkActive(active);
                String name = withSuffix(audio.fileName(), suffix);
                if (occupied.contains(name.toLowerCase(Locale.ROOT))) continue;
                AudioFiles.requireSafeDirectory(directory);
                Path destination = directory.resolve(name);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    // Staging in the destination directory avoids partially visible audio. Never replace a file.
                    Files.move(temporary, destination);
                    temporary = null;
                    return track(name, audio.metadata());
                } catch (FileAlreadyExistsException raced) { occupied.add(name.toLowerCase(Locale.ROOT)); }
            }
            throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
        } catch (IOException | SecurityException exception) { throw unavailable(); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static Track track(String fileName, AudioFiles.Metadata metadata) {
        return new Track(AudioLibrary.idForFileName(fileName), fileName, List.of(), metadata.durationMs());
    }
    private static String withSuffix(String fileName, int suffix) {
        if (suffix == 0) return fileName;
        int dot = fileName.lastIndexOf('.');
        String extra = " (" + suffix + ")", extension = fileName.substring(dot);
        int end = Math.min(dot, 180 - extra.length() - extension.length());
        if (end > 0 && Character.isHighSurrogate(fileName.charAt(end - 1))) end--;
        return AudioFiles.validateFileName(fileName.substring(0, end) + extra + extension);
    }
    private static void checkActive(BooleanSupplier active) {
        if (!active.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private static MusicException unavailable() { return new MusicException(MusicError.RESOURCE_UNAVAILABLE); }
}
