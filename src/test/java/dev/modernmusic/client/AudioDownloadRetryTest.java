package dev.modernmusic.client;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.server.AudioTransfer;
import dev.modernmusic.server.AudioTransferQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioDownloadRetryTest {
    @Test void retriesBusySlotsAtOneTwoFourThenEightSecondsWithoutPollingTwice() {
        var retry = new AudioDownloadRetry(100);
        long now = 100;
        assertFalse(retry.poll(now));
        for (long delay : new long[] {1_000, 2_000, 4_000, 8_000, 8_000, 8_000}) {
            assertTrue(retry.defer(now));
            assertFalse(retry.poll(now + delay - 1));
            assertTrue(retry.poll(now + delay));
            assertFalse(retry.poll(now + delay));
            now += delay;
        }
    }
    @Test void duplicateBusyResponsesDoNotMoveTheScheduledAttempt() {
        var retry = new AudioDownloadRetry(0);
        assertTrue(retry.defer(100)); assertTrue(retry.defer(900));
        assertFalse(retry.poll(1099)); assertTrue(retry.poll(1100));
    }
    @Test void continuousBusyResponsesCannotExtendTheTotalDeadline() {
        var retry = new AudioDownloadRetry(1_000);
        for (long now = 1_000; now < 1_000 + AudioDownloadRetry.WAIT_TIMEOUT_MS; now += 1_000) {
            assertTrue(retry.defer(now)); retry.poll(now);
        }
        long deadline = 1_000 + AudioDownloadRetry.WAIT_TIMEOUT_MS;
        assertFalse(retry.expired(deadline - 1)); assertTrue(retry.expired(deadline));
        assertFalse(retry.poll(deadline)); assertFalse(retry.defer(deadline));
    }
    @Test void cancelledDownloadNeverRetriesEvenAfterItsSlotWouldBecomeAvailable() {
        var retry = new AudioDownloadRetry(0); assertTrue(retry.defer(0));
        retry.cancel(); assertFalse(retry.poll(1_000)); assertFalse(retry.defer(2_000));
    }
    @Test void deadlineAlsoBoundsAServerThatNeverAnswers() {
        var retry = new AudioDownloadRetry(200);
        long deadline = 200 + AudioDownloadRetry.WAIT_TIMEOUT_MS;
        assertFalse(retry.expired(deadline - 1)); assertTrue(retry.expired(deadline)); assertFalse(retry.poll(deadline));
    }
    @Test void aLongSlotWaitDoesNotConsumeTheTransferDeadline() {
        var retry = new AudioDownloadRetry(0);
        assertFalse(retry.transferring()); assertFalse(retry.timedOut(300_000, 0));
        retry.begin(300_000);
        assertTrue(retry.transferring()); assertFalse(retry.defer(300_001)); assertFalse(retry.poll(310_000));
        retry.begin(350_000); // Duplicate notifications cannot keep a stalled transfer alive.
        assertFalse(retry.expired(419_999)); assertTrue(retry.expired(420_000));
    }
    @Test void twentyMaximumSizeDownloadsCanFinishAfterTheOldTwoMinuteDeadline() {
        var queue = new AudioTransferQueue();
        var requests = new LinkedHashMap<UUID, AudioDownloadRetry>();
        var active = new ArrayList<UUID>();
        var remaining = new HashMap<UUID, Integer>();
        var progress = new HashMap<UUID, Long>();
        for (int i = 0; i < 20; i++) {
            UUID request = UUID.randomUUID(); var retry = new AudioDownloadRetry(0); requests.put(request, retry);
            remaining.put(request, AudioFiles.MAX_AUDIO_BYTES); progress.put(request, 0L);
            if (queue.download(UUID.randomUUID(), request)) { retry.begin(0); active.add(request); }
        }
        int completed = 0; long now = 0;
        while (!active.isEmpty()) {
            now += 50; // A 20 TPS server; use the production pacing and file-size limits.
            for (var request : requests.entrySet())
                if (remaining.get(request.getKey()) != 0) assertFalse(request.getValue().timedOut(now, progress.get(request.getKey())));
            for (UUID request : List.copyOf(active)) {
                int bytes = Math.max(0, remaining.get(request) - AudioTransferQueue.CHUNKS_PER_TICK * AudioTransfer.CHUNK_BYTES);
                remaining.put(request, bytes); progress.put(request, now);
                if (bytes == 0) { queue.remove(request); active.remove(request); completed++; }
            }
            for (UUID request : queue.startWaiting()) {
                var retry = requests.get(request); assertFalse(retry.expired(now)); retry.begin(now);
                progress.put(request, now); active.add(request);
            }
        }
        assertEquals(20, completed); assertEquals(128_000, now);
    }
}
