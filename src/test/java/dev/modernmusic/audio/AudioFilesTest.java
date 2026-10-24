package dev.modernmusic.audio;

import dev.modernmusic.client.audio.Mp3Decoder;
import dev.modernmusic.client.audio.Mp3DecoderTest;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AudioFilesTest {
    @TempDir Path temporary;

    @Test void localAndServerIdentifiersRoundTripWithoutAdmittingPaths() {
        var local = new SongId("local_0123456789abcdef0123456789abcdef", MusicPlatform.LOCAL);
        var server = new SongId("server_0123456789abcdef0123456789abcdef", MusicPlatform.LOCAL);
        assertEquals(local, SongId.stored(local.toString()));
        assertEquals(server, SongId.fromWire(server.toString()));
        assertTrue(local.isClientLocal()); assertFalse(local.isServerAudio());
        assertTrue(server.isServerAudio()); assertFalse(server.isClientLocal());
        assertThrows(IllegalStateException.class, local::wireValue);
        for (String value : new String[]{"1", "../audio.mp3", "server_ABCDEF0123456789ABCDEF0123456789", "local_"})
            assertThrows(IllegalArgumentException.class, () -> new SongId(value, MusicPlatform.LOCAL));
        assertEquals(MusicPlatform.LOCAL, MusicPlatform.KUGOU.next());
        assertEquals(MusicPlatform.NETEASE, MusicPlatform.LOCAL.next());
        assertFalse(MusicPlatform.onlineValues().contains(MusicPlatform.LOCAL));
    }

    @Test void mp3ProbeMeasuresActualVbrSamplesAndMatchesPlayback() {
        byte[] bytes = Mp3DecoderTest.silentMp3();
        var metadata = AudioFiles.inspect(bytes, "测试.MP3");
        var pcm = AudioFiles.decode(bytes, "测试.MP3");
        assertEquals("测试", metadata.name());
        assertEquals(40L * 1152 * 1000 / 44100, metadata.durationMs());
        assertEquals(pcm.durationMs(), metadata.durationMs());
        assertEquals(pcm.frameCount(), new Mp3Decoder().inspect(bytes).frameCount());
    }

    @Test void wavDecodesUnsignedEightBitAndMeasuresRealDuration() {
        byte[] samples = new byte[8000];
        Arrays.fill(samples, (byte) 128);
        samples[0] = 0; samples[1] = (byte) 255;
        byte[] wav = wave(1, 1, 8000, 8, samples);
        var metadata = AudioFiles.inspect(wav, "音频.wav");
        assertEquals(1000, metadata.durationMs());
        var audio = AudioFiles.decode(wav, "音频.wav");
        assertEquals(8000, audio.sampleRate()); assertEquals(1, audio.channels());
        assertArrayEquals(new byte[]{0, (byte) 128, 0, 127, 0, 0}, Arrays.copyOf(audio.samples(), 6));
    }

    @Test void wavDecodesHighBitDepthAndFloatWithoutExternalCodecs() {
        byte[] pcm24 = new byte[48000 * 3];
        pcm24[2] = (byte) 128; pcm24[3] = (byte) 255; pcm24[4] = (byte) 255; pcm24[5] = 127;
        assertArrayEquals(new byte[]{0, (byte) 128, (byte) 255, 127},
                Arrays.copyOf(AudioFiles.decode(wave(1, 1, 48000, 24, pcm24), "24.wav").samples(), 4));
        ByteBuffer floats = ByteBuffer.allocate(8000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        floats.putFloat(-1).putFloat(1).putFloat(Float.NaN).putFloat(0.5f);
        assertArrayEquals(new byte[]{1, (byte) 128, (byte) 255, 127, 0, 0, 0, 64},
                Arrays.copyOf(AudioFiles.decode(wave(3, 1, 8000, 32, floats.array()), "float.wav").samples(), 8));
    }

    @Test void wavRejectsTruncationWrongExtensionAndInvalidFrameAlignment() {
        byte[] valid = wave(1, 2, 8000, 16, new byte[32000]);
        assertEquals(1000, AudioFiles.inspect(valid, "stereo.wav").durationMs());
        assertThrows(MusicException.class, () -> AudioFiles.inspect(Arrays.copyOf(valid, valid.length - 1), "broken.wav"));
        assertThrows(MusicException.class, () -> AudioFiles.inspect(Mp3DecoderTest.silentMp3(), "wrong.wav"));
        byte[] malformed = valid.clone(); malformed[32] = 2;
        assertThrows(MusicException.class, () -> AudioFiles.inspect(malformed, "bad.wav"));
        byte[] oversize = new byte[AudioFiles.MAX_AUDIO_BYTES + 1];
        assertEquals(MusicError.RESOURCE_TOO_LARGE,
                assertThrows(MusicException.class, () -> AudioFiles.inspect(oversize, "large.wav")).error());
    }

    @Test void extensibleWavUsesOnlyRecognizedPcmSubtypeAndBoundedChunks() {
        byte[] ordinary = wave(1, 1, 8000, 16, new byte[16000]);
        ByteBuffer extended = ByteBuffer.allocate(ordinary.length + 24).order(ByteOrder.LITTLE_ENDIAN);
        extended.put(ordinary, 0, 12).put(new byte[]{'f','m','t',' '}).putInt(40);
        extended.putShort((short) 0xfffe).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        extended.putShort((short) 22).putShort((short) 16).putInt(4);
        extended.put(new byte[]{1, 0, 0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113});
        extended.put(ordinary, 36, ordinary.length - 36).putInt(4, extended.capacity() - 8);
        byte[] valid = extended.array();
        assertEquals(1000, AudioFiles.inspect(valid, "extensible.wav").durationMs());
        assertEquals(16000, AudioFiles.decode(valid, "extensible.wav").samples().length);
        byte[] unknownSubtype = valid.clone(); unknownSubtype[44] = 2;
        assertThrows(MusicException.class, () -> AudioFiles.inspect(unknownSubtype, "unknown.wav"));
        byte[] oversizedChunk = valid.clone(); Arrays.fill(oversizedChunk, 64, 68, (byte) 255);
        assertThrows(MusicException.class, () -> AudioFiles.inspect(oversizedChunk, "oversized-chunk.wav"));
    }

    @Test void unsafeNamesAndPathsNeverEscapeTheLibrary() throws IOException {
        for (String filename : new String[]{"../a.mp3", "C:\\a.wav", "/a.wav", "a.wav:stream", ".hidden.mp3", "NUL.mp3", "COM1.wav", "bad\n.wav"})
            assertThrows(MusicException.class, () -> AudioFiles.validateFileName(filename));
        Path library = Files.createDirectory(temporary.resolve("library"));
        Path outside = temporary.resolve("outside.wav");
        Files.write(outside, wave(1, 1, 8000, 16, new byte[16000]));
        assertThrows(MusicException.class, () -> AudioFiles.read(library, outside));
        assertThrows(MusicException.class, () -> AudioFiles.read(library, library.resolve("../outside.wav")));
        Path link = library.resolve("link.wav");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException exception) { return; } // Windows may deny symlink creation.
        assertThrows(MusicException.class, () -> AudioFiles.read(library, link));
        assertTrue(new AudioLibrary(library).list().isEmpty());
    }

    @Test void libraryFindsFilenameOrUniqueStemAndTracksChangedFiles() throws IOException {
        AudioLibrary library = new AudioLibrary(temporary.resolve("audio"));
        assertTrue(library.list().isEmpty());
        Files.write(library.directory().resolve("曲目.wav"), wave(1, 1, 8000, 16, new byte[16000]));
        Files.write(library.directory().resolve("broken.mp3"), new byte[]{1, 2, 3});
        var first = library.findByName("曲目").orElseThrow();
        assertEquals("曲目.wav", first.title()); assertEquals(1000, first.durationMs());
        assertEquals(first, library.findByName("曲目.WAV").orElseThrow());
        assertEquals(1, library.list().size());
        Files.write(library.directory().resolve("曲目.mp3"), Mp3DecoderTest.silentMp3());
        assertTrue(library.findByName("曲目").isEmpty());
        assertEquals(first, library.findByName("曲目.wav").orElseThrow());
        Path changed = library.directory().resolve("曲目.wav");
        Files.write(changed, wave(1, 1, 8000, 16, new byte[32000]));
        Files.setLastModifiedTime(changed, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals(2000, library.getTrack(first.id()).orElseThrow().durationMs());
        assertEquals(first.id(), AudioLibrary.idForFileName("曲目.wav"));
        assertEquals("曲目.wav", library.fileName(first.id()));
        assertEquals(32044, library.read(first.id()).length);
        Files.delete(changed);
        assertTrue(library.getTrack(first.id()).isEmpty());
        assertThrows(MusicException.class, () -> library.read(first.id()));
    }

    public static byte[] wave(int encoding, int channels, int rate, int bits, byte[] samples) {
        int padding = samples.length & 1;
        ByteBuffer wave = ByteBuffer.allocate(44 + samples.length + padding).order(ByteOrder.LITTLE_ENDIAN);
        wave.put(new byte[]{'R','I','F','F'}).putInt(wave.capacity() - 8).put(new byte[]{'W','A','V','E'});
        wave.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) encoding).putShort((short) channels);
        wave.putInt(rate).putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        wave.put(new byte[]{'d','a','t','a'}).putInt(samples.length).put(samples);
        return wave.array();
    }
}
