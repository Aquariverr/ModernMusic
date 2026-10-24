package dev.modernmusic.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotWriterTest {
    @Test void closeFlushesNewestSnapshotWhilePendingEditsCoalesce() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var written = new ArrayList<Integer>(); var saved = new ArrayList<Integer>();
        var writer = new SnapshotWriter<Integer>("test-snapshots", snapshot -> {
            if (snapshot == 1) { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); }
            written.add(snapshot);
        }, () -> saved.add(dev.modernmusic.compat.Java17.last(written)), () -> fail("Unexpected write failure"));
        try {
            writer.save(1); assertTrue(started.await(2, TimeUnit.SECONDS));
            writer.save(2); writer.save(3);
        } finally { release.countDown(); writer.close(); }
        assertEquals(List.of(1, 3), written);
        assertEquals(written, saved);
    }

    @Test void failedWriteDoesNotBlockLaterSnapshots() throws Exception {
        var failure = new CountDownLatch(1); var written = new ArrayList<Integer>();
        var events = new ArrayList<String>();
        try (var writer = new SnapshotWriter<Integer>("test-recovery", snapshot -> {
            if (snapshot == 1) throw new IOException("fixture");
            written.add(snapshot);
        }, () -> events.add("saved"), () -> { events.add("failed"); failure.countDown(); })) {
            writer.save(1); assertTrue(failure.await(2, TimeUnit.SECONDS));
            writer.save(2);
        }
        assertEquals(List.of(2), written);
        assertEquals("saved", dev.modernmusic.compat.Java17.last(events));
        assertFalse(events.subList(0, events.size() - 1).isEmpty());
        assertTrue(events.subList(0, events.size() - 1).stream().allMatch("failed"::equals));
    }

    @Test void clientLoadCompletesBeforeQueuedEdits() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var events = new ArrayList<String>();
        var writer = new SnapshotWriter<Integer>("test-load-order", snapshot -> events.add("save"),
                () -> {}, () -> fail("Unexpected write failure"));
        try {
            var load = writer.load(() -> {
                started.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                events.add("load"); return 42;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS)); writer.save(1);
            release.countDown(); assertEquals(42, load.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); writer.close(); }
        assertEquals(List.of("load", "save"), events);
    }

    @Test void transientFailureRetriesWithoutAnotherEditOrClose() throws Exception {
        var attempts = new AtomicInteger(); var failures = new AtomicInteger();
        var saved = new CountDownLatch(1); var written = new ArrayList<Integer>();
        try (var writer = new SnapshotWriter<Integer>("test-auto-retry", snapshot -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("temporary");
            written.add(snapshot);
        }, saved::countDown, failures::incrementAndGet)) {
            writer.save(42);
            assertTrue(saved.await(2, TimeUnit.SECONDS), "The pending edit must recover without another save");
            assertEquals(List.of(42), written); assertEquals(2, attempts.get()); assertEquals(1, failures.get());
        }
    }

    @Test void newerSnapshotSupersedesAnOlderFailedWriteDuringClose() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var written = new ArrayList<Integer>(); var failures = new AtomicInteger();
        var writer = new SnapshotWriter<Integer>("test-latest-retry", snapshot -> {
            if (snapshot == 1) {
                started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
                throw new IOException("old write failed");
            }
            written.add(snapshot);
        }, () -> {}, failures::incrementAndGet);
        try {
            writer.save(1); assertTrue(started.await(2, TimeUnit.SECONDS));
            writer.save(2); writer.save(3);
        } finally { release.countDown(); writer.close(); }
        assertEquals(List.of(3), written); assertEquals(1, failures.get());
    }

    @Test void closeRetriesLatestSnapshotAndPermanentFailureHasBoundedAttempts() {
        var attempts = new AtomicInteger(); var saved = new AtomicInteger();
        var writer = new SnapshotWriter<Integer>("test-close-retry", snapshot -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("temporary");
        }, saved::incrementAndGet, () -> {});
        writer.save(42); writer.close(); assertEquals(2, attempts.get()); assertEquals(1, saved.get());

        var failures = new AtomicInteger();
        var broken = new SnapshotWriter<Integer>("test-close-broken", snapshot -> {
            throw new IOException("permanent");
        }, () -> fail("Nothing was saved"), failures::incrementAndGet);
        broken.save(42); broken.close();
        assertTrue(failures.get() >= 3 && failures.get() <= 4, "Close must make bounded final attempts");
        int afterClose = failures.get(); broken.save(43); broken.close(); assertEquals(afterClose, failures.get());
    }
}
