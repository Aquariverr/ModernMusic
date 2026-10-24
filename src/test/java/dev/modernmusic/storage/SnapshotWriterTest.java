package dev.modernmusic.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotWriterTest {
    @Test void closeFlushesNewestSnapshotWhilePendingEditsCoalesce() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var written = new ArrayList<Integer>(); var saved = new ArrayList<Integer>();
        var writer = new SnapshotWriter<Integer>("test-snapshots", snapshot -> {
            if (snapshot == 1) { started.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); }
            written.add(snapshot);
        }, () -> saved.add(written.getLast()), () -> fail("Unexpected write failure"));
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
        assertEquals(List.of("failed", "saved"), events);
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
}
