package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.RoomSnapshot;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MetadataRetrySchedulingTest {
    private final UUID roomId = UUID.randomUUID(), epoch = UUID.randomUUID(), owner = UUID.randomUUID();
    private PlaybackController player(NeteaseImportMetadataTest.Provider provider, AtomicLong clock) {
        return new PlaybackController(provider, (uri, max) -> { throw new AssertionError("No audio download for metadata"); },
                new SharedPlaybackTest.Output(), Runnable::run, null, clock::get);
    }
    private RoomSnapshot room(UUID id, UUID roomEpoch, long revision, long song, RoomSnapshot.State state) {
        return new RoomSnapshot(id, roomEpoch, "Shared", revision, revision, revision, owner, List.of(),
                List.of(Long.toString(song)), 0, PlaybackMode.SEQUENTIAL, state, 0, 10_000, 1000);
    }
    private static void fail(NeteaseImportMetadataTest.Provider provider, int batch) {
        provider.results.get(batch).completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
    }

    @Test void retryDeadlineDoesNotDelayNewImportsAndCompletedRowsDoNotRetry() {
        var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            player.add("1"); player.add("42"); fail(provider, 0);
            clock.set(30_999); player.tick(1); assertEquals(1, provider.batches.size());
            player.add("43"); assertEquals(List.of(new SongId(43)), provider.batches.get(1));
            provider.complete(1); player.tick(1); assertEquals(2, provider.batches.size());
            clock.set(31_000); player.tick(1);
            assertEquals(List.of(new SongId(42)), provider.batches.get(2));
            provider.complete(2);
            clock.set(100_000); player.tick(1);
            assertEquals(3, provider.batches.size());
            assertEquals(NeteaseImportMetadataTest.song(42), player.track(new SongId(42)));
            assertEquals(NeteaseImportMetadataTest.song(43), player.track(new SongId(43)));
            assertEquals(1, provider.resolves);
        }
    }

    @Test void removingARetryingRowCancelsItsWakeupAndNewRowsStillLoad() {
        var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            player.add("1"); player.add("42"); fail(provider, 0);
            assertTrue(player.removeTrack(1));
            clock.set(31_000); player.tick(1); assertEquals(1, provider.batches.size());
            player.add("43"); assertEquals(List.of(new SongId(43)), provider.batches.get(1));
            provider.complete(1); assertNull(player.track(new SongId(42)));
            assertNotNull(player.track(new SongId(43)));
        }
    }

    @Test void sharedQueueChangesBypassOldBackoffAndPruneRemovedRows() {
        var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            player.applyRoom(room(roomId, epoch, 1, 42, RoomSnapshot.State.STOPPED)); fail(provider, 0);
            clock.set(2000);
            player.applyRoom(room(roomId, epoch, 2, 43, RoomSnapshot.State.STOPPED));
            assertEquals(List.of(new SongId(43)), provider.batches.get(1));
            provider.complete(1); clock.set(31_000); player.tick(1);
            assertEquals(2, provider.batches.size()); assertNull(player.track(new SongId(42)));
            assertEquals(NeteaseImportMetadataTest.song(43), player.track());
        }
    }

    @Test void roomQueueEpochAndLeaveChangesRejectLateMetadataWithoutLosingTheNextBatch() {
        for (String action : List.of("queue", "room", "epoch", "leave")) {
            var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
            try (var player = player(provider, clock)) {
                player.applyRoom(room(roomId, epoch, 1, 42, RoomSnapshot.State.STOPPED));
                switch (action) {
                    case "queue" -> player.applyRoom(room(roomId, epoch, 2, 43, RoomSnapshot.State.STOPPED));
                    case "room" -> player.applyRoom(room(UUID.randomUUID(), epoch, 2, 43, RoomSnapshot.State.STOPPED));
                    case "epoch" -> player.applyRoom(room(roomId, UUID.randomUUID(), 2, 43, RoomSnapshot.State.STOPPED));
                    case "leave" -> player.applyRoom(null);
                }
                provider.complete(0); player.tick(1);
                assertNull(player.track(new SongId(42)), action);
                if (action.equals("leave")) assertEquals(1, provider.batches.size());
                else {
                    assertEquals(2, provider.batches.size(), action);
                    assertEquals(List.of(new SongId(43)), provider.batches.get(1), action);
                    provider.complete(1); assertEquals(NeteaseImportMetadataTest.song(43), player.track(), action);
                }
            }
        }
    }

    @Test void leavingAndRejoiningDoesNotInheritThePreviousRoomsRetryDeadline() {
        var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            var snapshot = room(roomId, epoch, 1, 42, RoomSnapshot.State.STOPPED);
            player.applyRoom(snapshot); fail(provider, 0);
            player.applyRoom(null); player.applyRoom(snapshot);
            assertEquals(List.of(List.of(new SongId(42)), List.of(new SongId(42))), provider.batches);
        }
    }

    @Test void mutingAnUnresolvedPlayingSongMakesItEligibleForMetadata() {
        var provider = new NeteaseImportMetadataTest.Provider() {
            @Override public CompletableFuture<Track> getTrack(SongId id) { return new CompletableFuture<>(); }
        };
        var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            var snapshot = room(roomId, epoch, 1, 42, RoomSnapshot.State.PLAYING);
            player.applyRoom(snapshot); player.tick(1); assertTrue(provider.batches.isEmpty());
            player.applyRoom(snapshot, false); player.tick(1);
            assertEquals(List.of(List.of(new SongId(42))), provider.batches);
            provider.complete(0); assertEquals(NeteaseImportMetadataTest.song(42), player.track());
            assertEquals(0, provider.resolves);
        }
    }

    @Test void retryWaitAllocationDoesNotGrowWithThePersonalLibrary() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assumeTrue(platformBean instanceof com.sun.management.ThreadMXBean);
        var bean = (com.sun.management.ThreadMXBean) platformBean;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long small = waitingAllocations(bean, 1), maximum = waitingAllocations(bean, QueueLibrary.MAX_QUEUES);
        // Counts thread allocation, not elapsed time. The old full-library HashSet cost ~1.35 MB/tick.
        assertTrue(maximum < 128 * 1024, "128 waiting ticks allocated " + maximum + " bytes for 25,600 unique songs");
        assertTrue(maximum <= small + 64 * 1024, "Allocation grew with the library: small=" + small + ", maximum=" + maximum);
    }

    private long waitingAllocations(com.sun.management.ThreadMXBean bean, int count) {
        var provider = new NeteaseImportMetadataTest.Provider(); var clock = new AtomicLong(1000);
        try (var player = player(provider, clock)) {
            String first = player.library().active().id();
            for (int q = 0; q < count; q++) {
                var target = q == 0 ? player.library().active() : player.library().create("Queue " + q);
                for (int i = 0; i < PlaybackQueue.MAX_TRACKS; i++) target.queue().add(new SongId(q * 400L + i + 1));
            }
            player.switchQueue(first);
            assertEquals(count * PlaybackQueue.MAX_TRACKS, player.library().songIds().size());
            assertFalse(player.add("1")); fail(provider, 0);
            for (int i = 0; i < 32; i++) player.tick(1);
            long thread = Thread.currentThread().getId(); bean.getThreadAllocatedBytes(thread);
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 128; i++) player.tick(1);
            long bytes = bean.getThreadAllocatedBytes(thread) - before;
            assertEquals(1, provider.batches.size());
            return bytes;
        }
    }
}
