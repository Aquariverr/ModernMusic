package dev.modernmusic.provider;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AsyncTasksTest {
    @Test void immediateShutdownCompletesQueuedAndUncooperativeRunningRequests() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = AsyncTasks.executor("test-shutdown", 1, 2);
        try {
            var running = AsyncTasks.submit(executor, () -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = AsyncTasks.submit(executor, () -> fail("Dropped task must not execute"));
            var dependent = queued.thenApply(value -> value);
            executor.shutdownNow();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(running.isCancelled()); assertTrue(queued.isCancelled());
            assertTrue(dependent.isCompletedExceptionally());
            assertFalse(executor.isTerminated(), "Fixture must still ignore interruption");
            executor.shutdownNow();
            var rejected = AsyncTasks.submit(executor, () -> 3);
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertEquals(MusicError.RATE_LIMITED, assertInstanceOf(MusicException.class, failure.getCause()).error());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test void gracefulShutdownStillDrainsRequestsAndPlainExecutorWork() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var plainRan = new CountDownLatch(1);
        var executor = AsyncTasks.executor("test-graceful", 1, 3);
        try {
            var running = AsyncTasks.submit(executor, () -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = AsyncTasks.submit(executor, () -> 2);
            executor.execute(plainRan::countDown);
            executor.shutdown();
            assertFalse(running.isDone()); assertFalse(queued.isDone());
            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
            assertEquals(2, queued.get(2, TimeUnit.SECONDS));
            assertTrue(plainRan.await(2, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void shutdownDuringSubmissionCancelsBeforeUnderlyingTaskIsPublished() throws Exception {
        var executor = AsyncTasks.executor("test-publication", 1, 1);
        var factory = executor.getThreadFactory();
        var creating = new CountDownLatch(1); var release = new CountDownLatch(1);
        executor.setThreadFactory(command -> {
            creating.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return factory.newThread(command);
        });
        var submitting = Executors.newSingleThreadExecutor();
        try {
            var call = submitting.submit(() -> AsyncTasks.submit(executor, () -> fail("Closed submission must not run")));
            assertTrue(creating.await(2, TimeUnit.SECONDS));
            executor.shutdownNow();
            release.countDown();
            assertTrue(call.get(2, TimeUnit.SECONDS).isCancelled());
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); submitting.shutdownNow(); }
    }

    @Test void queueRejectionRemainsRateLimitedAndDoesNotPoisonLaterSubmissions() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = AsyncTasks.executor("test-reject", 1, 1);
        try {
            AsyncTasks.submit(executor, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = AsyncTasks.submit(executor, () -> 2);
            var rejected = AsyncTasks.submit(executor, () -> 3);
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertEquals(MusicError.RATE_LIMITED, assertInstanceOf(MusicException.class, failure.getCause()).error());
            assertTrue(queued.cancel(true));
            var accepted = AsyncTasks.submit(executor, () -> 4);
            assertFalse(accepted.isDone());
            executor.shutdownNow();
            assertTrue(accepted.isCancelled());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void concurrentSubmissionCancellationAndShutdownLeaveNoPendingRequests() throws Exception {
        var racers = Executors.newFixedThreadPool(3);
        try {
            for (int round = 0; round < 50; round++) {
                var executor = AsyncTasks.executor("test-race", 1, 2);
                var started = new CountDownLatch(1); var release = new CountDownLatch(1);
                var startRace = new CountDownLatch(1);
                try {
                    var running = AsyncTasks.submit(executor, () -> {
                        started.countDown();
                        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return 1;
                    });
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    var queued = AsyncTasks.submit(executor, () -> 2);
                    var submitting = racers.submit(() -> { startRace.await(); return AsyncTasks.submit(executor, () -> 3); });
                    var closing = racers.submit(() -> { startRace.await(); executor.shutdownNow(); return true; });
                    var cancelling = racers.submit(() -> { startRace.await(); return queued.cancel(true); });
                    startRace.countDown();
                    var concurrent = submitting.get(2, TimeUnit.SECONDS);
                    closing.get(2, TimeUnit.SECONDS); cancelling.get(2, TimeUnit.SECONDS);
                    assertTrue(running.isDone()); assertTrue(queued.isDone()); assertTrue(concurrent.isDone());
                    assertEquals(0, executor.getQueue().size());
                } finally {
                    startRace.countDown(); release.countDown(); executor.shutdownNow();
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
                }
            }
        } finally { racers.shutdownNow(); }
    }

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
        var executor = AsyncTasks.executor("test-error", 1, 1);
        try {
            var future = AsyncTasks.submit(executor, () -> { throw new NoClassDefFoundError("test"); });
            var failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
        } finally { executor.shutdownNow(); }
    }
    @Test void cancellationInterruptsBlockingWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var executor = AsyncTasks.executor("test-cancel", 1, 1);
        try {
            var future = AsyncTasks.submit(executor, () -> {
                started.countDown();
                try { Thread.sleep(5000); }
                catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return null;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            future.cancel(true);
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
