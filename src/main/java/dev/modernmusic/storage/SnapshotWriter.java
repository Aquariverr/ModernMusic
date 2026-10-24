package dev.modernmusic.storage;

import dev.modernmusic.provider.AsyncTasks;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Serial, coalesced writes retain the latest failed snapshot and retry with capped backoff. */
public final class SnapshotWriter<T> implements AutoCloseable {
    @FunctionalInterface public interface Write<T> { void accept(T snapshot) throws Exception; }

    private static final int CLOSE_ATTEMPTS = 3;
    private final ExecutorService worker;
    private final Object lock = new Object();
    private T waiting;
    private boolean scheduled, closed;
    private final Write<T> write;
    private final Runnable saved, failed;

    public SnapshotWriter(String name, Write<T> write, Runnable saved, Runnable failed) {
        worker = AsyncTasks.executor(name, 1, 2);
        this.write = write; this.saved = saved; this.failed = failed;
    }

    /** Client loading shares the writer's executor so reads and later edits remain ordered. */
    public <R> CompletableFuture<R> load(Supplier<R> read) {
        synchronized (lock) { return AsyncTasks.submit(worker, read); }
    }

    public void save(T snapshot) {
        Objects.requireNonNull(snapshot);
        synchronized (lock) {
            if (closed) return;
            waiting = snapshot;
            if (!scheduled) { scheduled = true; worker.execute(this::drain); }
            lock.notifyAll();
        }
    }

    private void drain() {
        int failures = 0, closingFailures = 0;
        while (true) {
            T snapshot;
            synchronized (lock) {
                snapshot = waiting; waiting = null;
                if (snapshot == null) { scheduled = false; return; }
            }
            try { write.accept(snapshot); saved.run(); failures = closingFailures = 0; }
            catch (Exception failure) {
                failed.run();
                synchronized (lock) {
                    // Newer edits supersede failed writes; recovery does not depend on another edit.
                    if (waiting == null) waiting = snapshot;
                    if (closed && ++closingFailures >= CLOSE_ATTEMPTS) { scheduled = false; return; }
                    long delay = Math.min(5_000L, 100L << Math.min(failures++, 6));
                    if (!closed && waiting == snapshot) {
                        try { lock.wait(delay); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt(); scheduled = false; return;
                        }
                    }
                }
            }
        }
    }

    @Override public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll(); // Do not spend the shutdown budget waiting for a retry deadline.
            worker.shutdown();
        }
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) { failed.run(); worker.shutdownNow(); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failed.run(); worker.shutdownNow(); }
    }
}
