package dev.modernmusic.provider;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncTasksTest {
    @Test void repeatedQueuedCancellationDoesNotExhaustCapacity() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = AsyncTasks.executor("test-capacity", 1, 2);
        try {
            var running = AsyncTasks.submit(executor, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                var queued = AsyncTasks.submit(executor, () -> 2);
                assertFalse(queued.isCompletedExceptionally());
                assertTrue(queued.cancel(true));
                assertEquals(0, executor.getQueue().size());
            }
            var last = AsyncTasks.submit(executor, () -> 3);
            release.countDown(); assertEquals(1, running.get(1, TimeUnit.SECONDS));
            assertEquals(3, last.get(1, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void linkageErrorsCompleteTheFutureInsteadOfHanging() {
        try (var executor = AsyncTasks.executor("test-error", 1, 1)) {
            var future = AsyncTasks.submit(executor, () -> { throw new NoClassDefFoundError("test"); });
            var failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
        }
    }
    @Test void cancellationInterruptsBlockingWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var executor = AsyncTasks.executor("test-cancel", 1, 1)) {
            var future = AsyncTasks.submit(executor, () -> {
                started.countDown();
                try { Thread.sleep(5000); }
                catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return null;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            future.cancel(true);
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }
}
