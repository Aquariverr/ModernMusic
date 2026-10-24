package dev.modernmusic.server;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioTransferTest {
    @Test void rejectsInvalidAndOversizedTotalsBeforeAllocation() {
        assertEquals(MusicError.RESOURCE_TOO_LARGE, assertThrows(MusicException.class, () -> new AudioTransfer(0, true, 0)).error());
        assertThrows(MusicException.class, () -> new AudioTransfer(-1, true, 0));
        assertThrows(MusicException.class, () -> new AudioTransfer(AudioFiles.MAX_AUDIO_BYTES + 1, true, 0));
    }
    @Test void onlyCompleteSequentialTransferCanFinish() {
        var transfer = new AudioTransfer(4, true, 0);
        transfer.append(0, new byte[] {1, 2}, true, 1);
        assertThrows(MusicException.class, () -> transfer.append(0, new byte[] {3, 4}, true, 2));
        assertThrows(MusicException.class, () -> transfer.append(3, new byte[] {3}, true, 2));
        assertThrows(MusicException.class, () -> transfer.finish(true, 2));
        assertThrows(MusicException.class, () -> transfer.append(2, new byte[] {3, 4, 5}, true, 2));
        transfer.append(2, new byte[] {3, 4}, true, 3);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, transfer.finish(true, 4));
    }
    @Test void checksPermissionAtStartEveryChunkAndCommit() {
        assertEquals(MusicError.SHARED_FORBIDDEN, assertThrows(MusicException.class, () -> new AudioTransfer(2, false, 0)).error());
        var transfer = new AudioTransfer(2, true, 0);
        assertEquals(MusicError.SHARED_FORBIDDEN, assertThrows(MusicException.class, () -> transfer.append(0, new byte[] {1, 2}, false, 0)).error());
        assertEquals(0, transfer.received());
        transfer.append(0, new byte[] {1, 2}, true, 1);
        assertEquals(MusicError.SHARED_FORBIDDEN, assertThrows(MusicException.class, () -> transfer.finish(false, 2)).error());
    }
    @Test void timesOutAndBoundsEveryChunk() {
        var transfer = new AudioTransfer(AudioTransfer.CHUNK_BYTES + 1, true, 0);
        assertThrows(MusicException.class, () -> transfer.append(0, new byte[AudioTransfer.CHUNK_BYTES + 1], true, 1));
        assertThrows(MusicException.class, () -> transfer.append(0, new byte[0], true, 1));
        assertTrue(transfer.expired(AudioTransfer.TIMEOUT_MS + 1));
        assertEquals(MusicError.NETWORK_ERROR, assertThrows(MusicException.class,
                () -> transfer.append(0, new byte[] {1}, true, AudioTransfer.TIMEOUT_MS + 1)).error());
    }
    @Test void cancelledDataCanBeCleared() {
        var transfer = new AudioTransfer(2, true, 0); transfer.append(0, new byte[] {4, 5}, true, 1);
        byte[] bytes = transfer.finish(true, 2); transfer.clear(); assertArrayEquals(new byte[2], bytes);
    }
}
