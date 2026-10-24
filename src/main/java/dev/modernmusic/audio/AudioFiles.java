package dev.modernmusic.audio;

import dev.modernmusic.client.audio.Mp3Decoder;
import dev.modernmusic.client.audio.PcmAudio;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Shared, bounded audio validation. Contains no Minecraft client or audio-device dependency. */
public final class AudioFiles {
    public static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    public static final int MAX_PCM_BYTES = Mp3Decoder.MAX_PCM_BYTES;
    public static final int MAX_LIBRARY_FILES = 512;
    private AudioFiles() { }

    public record Metadata(String fileName, String name, long durationMs, String format, int sampleRate, int channels) { }

    /** A portable basename only: never a path, Windows device, alternate stream or hidden file. */
    public static String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 180 || !fileName.equals(fileName.strip())
                || fileName.startsWith(".") || fileName.endsWith(".") || fileName.endsWith(" ")
                || fileName.chars().anyMatch(c -> c < 32 || c == 127 || "\\/:*?\"<>|".indexOf(c) >= 0))
            throw unavailable();
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) throw unsupported();
        String stem = fileName.substring(0, dot).strip();
        if (stem.isEmpty() || stem.matches("(?i)(?:CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")) throw unavailable();
        String format = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!format.equals("mp3") && !format.equals("wav")) throw unsupported();
        return fileName;
    }

    public static Metadata inspect(byte[] data, String fileName) {
        validateData(data, fileName);
        int sampleRate, channels;
        long durationMs;
        String format = format(fileName);
        if (format.equals("mp3")) {
            Mp3Decoder.Info info = new Mp3Decoder().inspect(data);
            sampleRate = info.sampleRate(); channels = info.channels(); durationMs = info.durationMs();
        } else {
            Wav info = parseWav(data);
            sampleRate = info.sampleRate; channels = info.channels; durationMs = info.durationMs();
        }
        if (durationMs <= 0) throw unsupported();
        return new Metadata(fileName, fileName.substring(0, fileName.lastIndexOf('.')), durationMs, format, sampleRate, channels);
    }

    public static PcmAudio decode(byte[] data, String fileName) {
        validateData(data, fileName);
        if (format(fileName).equals("mp3")) return new Mp3Decoder().decode(data);
        Wav wav = parseWav(data);
        int bytesPerSample = wav.bits / 8;
        int sampleCount = wav.dataLength / bytesPerSample;
        byte[] pcm = new byte[Math.multiplyExact(sampleCount, 2)];
        int offset = wav.dataOffset;
        for (int sample = 0; sample < sampleCount; sample++, offset += bytesPerSample) {
            if ((sample & 16383) == 0) checkInterrupted();
            int value;
            if (wav.encoding == 3) {
                double number = wav.bits == 32 ? Float.intBitsToFloat((int) u32(data, offset))
                        : Double.longBitsToDouble(u32(data, offset) | (u32(data, offset + 4) << 32));
                value = Double.isNaN(number) ? 0 : (int) Math.round(Math.max(-1, Math.min(1, number)) * 32767);
            } else if (wav.bits == 8) value = ((data[offset] & 255) - 128) << 8;
            else {
                int high = offset + bytesPerSample - 2;
                value = (data[high] & 255) | data[high + 1] << 8;
            }
            pcm[sample * 2] = (byte) value;
            pcm[sample * 2 + 1] = (byte) (value >>> 8);
        }
        return new PcmAudio(pcm, wav.sampleRate, wav.channels);
    }

    /** Reads only regular files within root and rejects symlinks at every path component. */
    public static byte[] read(Path root, Path file) {
        try {
            Path checked = requireContainedFile(root, file);
            try (SeekableByteChannel channel = Files.newByteChannel(checked, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size <= 0) throw unsupported();
                if (size > MAX_AUDIO_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
                byte[] bytes = new byte[(int) size];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    checkInterrupted();
                    if (channel.read(buffer) < 0) throw unavailable();
                }
                if (channel.read(ByteBuffer.allocate(1)) != -1) throw unavailable();
                return bytes;
            }
        } catch (IOException | SecurityException exception) { throw unavailable(); }
    }

    public static Path requireContainedFile(Path root, Path file) throws IOException {
        Path directory = requireSafeDirectory(root);
        Path candidate = file.toAbsolutePath().normalize();
        if (candidate.equals(directory) || !candidate.startsWith(directory)) throw unavailable();
        rejectSymlinks(candidate);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        if (!candidate.toRealPath().startsWith(directory.toRealPath())) throw unavailable();
        return candidate;
    }

    /** The parent chain is checked as well, so a linked library directory cannot escape confinement. */
    public static Path requireSafeDirectory(Path root) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        rejectSymlinks(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        return directory;
    }

    public static Path createSafeDirectory(Path root) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        rejectSymlinks(directory);
        Files.createDirectories(directory);
        return requireSafeDirectory(directory);
    }

    private static void rejectSymlinks(Path path) throws IOException {
        for (Path component = path; component != null; component = component.getParent())
            if (Files.isSymbolicLink(component)
                    || Files.exists(component, LinkOption.NOFOLLOW_LINKS)
                    && !component.toRealPath().equals(component.toRealPath(LinkOption.NOFOLLOW_LINKS))) throw unavailable();
    }

    private static void validateData(byte[] data, String fileName) {
        checkInterrupted();
        validateFileName(fileName);
        if (data == null || data.length == 0) throw unsupported();
        if (data.length > MAX_AUDIO_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
    }
    private static String format(String name) { return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    private record Wav(int encoding, int channels, int sampleRate, int bits, int dataOffset, int dataLength) {
        long frameCount() { return dataLength / (channels * (bits / 8)); }
        long durationMs() { return frameCount() * 1000 / sampleRate; }
    }

    /** RIFF PCM and IEEE-float WAV, including their WAVE_FORMAT_EXTENSIBLE forms. */
    private static Wav parseWav(byte[] bytes) {
        if (bytes.length < 44 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE")) throw unsupported();
        long end = u32(bytes, 4) + 8;
        if (end != bytes.length) throw unsupported();
        int encoding = 0, channels = 0, rate = 0, bits = 0, align = 0, dataOffset = -1, dataLength = 0;
        boolean foundFormat = false;
        for (long offset = 12; offset < end;) {
            checkInterrupted();
            if (end - offset < 8) throw unsupported();
            int start = (int) offset;
            long length = u32(bytes, start + 4);
            long next = offset + 8 + length + (length & 1);
            if (next > end) throw unsupported();
            int chunk = start + 8;
            if (tag(bytes, start, "fmt ")) {
                if (foundFormat || length < 16) throw unsupported();
                foundFormat = true;
                encoding = u16(bytes, chunk); channels = u16(bytes, chunk + 2);
                long sampleRate = u32(bytes, chunk + 4);
                if (sampleRate < 1000 || sampleRate > 192000) throw unsupported();
                rate = (int) sampleRate; align = u16(bytes, chunk + 12); bits = u16(bytes, chunk + 14);
                if (encoding == 0xfffe) {
                    if (length < 40 || u16(bytes, chunk + 16) < 22 || u16(bytes, chunk + 18) != bits) throw unsupported();
                    // Remaining GUID bytes must be the standard PCM/IEEE-float subtype tail.
                    byte[] tail = {0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};
                    encoding = u16(bytes, chunk + 24);
                    for (int index = 0; index < tail.length; index++) if (bytes[chunk + 26 + index] != tail[index]) throw unsupported();
                }
                if (channels < 1 || channels > 2 || encoding != 1 && encoding != 3
                        || encoding == 1 && bits != 8 && bits != 16 && bits != 24 && bits != 32
                        || encoding == 3 && bits != 32 && bits != 64
                        || align != channels * (bits / 8) || u32(bytes, chunk + 8) != (long) rate * align) throw unsupported();
            } else if (tag(bytes, start, "data")) {
                if (dataOffset >= 0 || length == 0) throw unsupported();
                dataOffset = chunk; dataLength = (int) length;
            }
            offset = next;
        }
        if (!foundFormat || dataOffset < 0 || dataLength % align != 0) throw unsupported();
        Wav wav = new Wav(encoding, channels, rate, bits, dataOffset, dataLength);
        if (wav.frameCount() * channels * 2 > MAX_PCM_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
        if (wav.durationMs() <= 0) throw unsupported();
        return wav;
    }
    private static boolean tag(byte[] bytes, int offset, String tag) {
        for (int index = 0; index < 4; index++) if (bytes[offset + index] != tag.charAt(index)) return false;
        return true;
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8; }
    private static long u32(byte[] bytes, int offset) { return (bytes[offset] & 255L) | (bytes[offset + 1] & 255L) << 8 | (bytes[offset + 2] & 255L) << 16 | (bytes[offset + 3] & 255L) << 24; }
    private static void checkInterrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    private static MusicException unavailable() { return new MusicException(MusicError.RESOURCE_UNAVAILABLE); }
    private static MusicException unsupported() { return new MusicException(MusicError.UNSUPPORTED_FORMAT); }
}
