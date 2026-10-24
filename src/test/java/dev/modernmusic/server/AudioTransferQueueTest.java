package dev.modernmusic.server;

import dev.modernmusic.provider.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioTransferQueueTest {
    private final AudioTransferQueue queue = new AudioTransferQueue();
    private List<UUID> occupySlots() {
        var requests = new ArrayList<UUID>();
        for (int i = 0; i < AudioTransferQueue.MAX_ACTIVE; i++) {
            UUID request = UUID.randomUUID(); requests.add(request);
            assertTrue(queue.download(UUID.randomUUID(), request));
        }
        return requests;
    }
    private void limited(Runnable action) {
        assertEquals(MusicError.RATE_LIMITED, assertThrows(MusicException.class, action::run).error());
    }

    @Test void departuresStartWaitingDownloadsInArrivalOrderWithoutOvertaking() {
        var active = occupySlots();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), newcomer = UUID.randomUUID();
        assertFalse(queue.download(UUID.randomUUID(), first));
        assertFalse(queue.download(UUID.randomUUID(), second));
        queue.remove(active.get(0));
        assertFalse(queue.download(UUID.randomUUID(), newcomer));
        limited(() -> queue.upload(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(List.of(first), queue.startWaiting());
        assertEquals(List.of(), queue.startWaiting());
        queue.remove(first); assertEquals(List.of(second), queue.startWaiting());
        queue.remove(second); assertEquals(List.of(newcomer), queue.startWaiting());
    }

    @Test void duplicateQueuedDownloadKeepsItsPositionAndDoesNotConsumeCapacity() {
        var active = occupySlots();
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        assertFalse(queue.download(owner, first)); assertFalse(queue.download(owner, second));
        for (int i = 0; i < 20; i++) assertFalse(queue.download(owner, first));
        limited(() -> queue.download(owner, UUID.randomUUID()));
        queue.remove(active.get(0)); assertEquals(List.of(first), queue.startWaiting());
        assertTrue(queue.download(owner, first));
        assertEquals(MusicError.INVALID_RESPONSE,
                assertThrows(MusicException.class, () -> queue.download(UUID.randomUUID(), first)).error());
    }

    @Test void waitingQueueAndPerPlayerReservationsAreBounded() {
        occupySlots();
        var waiting = new ArrayList<UUID>();
        for (int i = 0; i < AudioTransferQueue.MAX_WAITING; i++) {
            UUID request = UUID.randomUUID(); waiting.add(request);
            assertFalse(queue.download(UUID.randomUUID(), request));
        }
        limited(() -> queue.download(UUID.randomUUID(), UUID.randomUUID()));
        queue.remove(waiting.get(0));
        assertFalse(queue.download(UUID.randomUUID(), UUID.randomUUID()));
        queue.clear();
        UUID owner = UUID.randomUUID();
        assertTrue(queue.download(owner, UUID.randomUUID())); assertTrue(queue.download(owner, UUID.randomUUID()));
        limited(() -> queue.download(owner, UUID.randomUUID()));
    }

    @Test void cancellationOrDisconnectReleasesSlotsAndRemovesWaitingRequests() {
        var active = occupySlots();
        UUID cancelled = UUID.randomUUID(), survivor = UUID.randomUUID();
        assertFalse(queue.download(UUID.randomUUID(), cancelled));
        assertFalse(queue.download(UUID.randomUUID(), survivor));
        queue.remove(cancelled); queue.remove(cancelled); assertFalse(queue.waiting(cancelled));
        queue.remove(active.get(0)); queue.remove(active.get(0));
        assertEquals(List.of(survivor), queue.startWaiting());
        queue.clear(); assertEquals(List.of(), queue.startWaiting());
        assertTrue(queue.download(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test void uploadsKeepTheirGlobalAndPerPlayerLimitsAlongsideDownloads() {
        UUID owner = UUID.randomUUID(), upload = UUID.randomUUID();
        queue.upload(owner, upload);
        limited(() -> queue.upload(owner, UUID.randomUUID()));
        queue.upload(UUID.randomUUID(), UUID.randomUUID());
        limited(() -> queue.upload(UUID.randomUUID(), UUID.randomUUID()));
        assertTrue(queue.download(owner, UUID.randomUUID()));
        limited(() -> queue.download(owner, UUID.randomUUID()));
        assertTrue(queue.download(UUID.randomUUID(), UUID.randomUUID()));
        assertFalse(queue.download(UUID.randomUUID(), UUID.randomUUID()));
        queue.remove(upload); assertEquals(1, queue.startWaiting().size());
    }
}
