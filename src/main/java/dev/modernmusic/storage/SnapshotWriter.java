package dev.modernmusic.storage;

import dev.modernmusic.provider.AsyncTasks;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Serial writes coalesce pending edits; close waits for the newest submitted snapshot. */
public final class SnapshotWriter<T> implements AutoCloseable {
    @FunctionalInterface public interface Write<T> { void accept(T snapshot) throws Exception; }

    private final ExecutorService worker;
    private final AtomicReference<T> waiting = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Write<T> write;
    private final Runnable saved, failed;

    public SnapshotWriter(String name, Write<T> write, Runnable saved, Runnable failed) {
        worker = AsyncTasks.executor(name, 1, 2);
        this.write = write; this.saved = saved; this.failed = failed;
    }

    /** Client loading shares the writer's executor so reads and later edits remain ordered. */
    public <R> CompletableFuture<R> load(Supplier<R> read) {
        return CompletableFuture.supplyAsync(read, worker);
    }

    public void save(T snapshot) {
        waiting.set(snapshot);
        if (scheduled.compareAndSet(false, true)) worker.execute(this::drain);
    }

    private void drain() {
        try {
            T snapshot;
            while ((snapshot = waiting.getAndSet(null)) != null) {
                try { write.accept(snapshot); saved.run(); }
                catch (Exception failure) { failed.run(); }
            }
        } finally {
            scheduled.set(false);
            if (waiting.get() != null && scheduled.compareAndSet(false, true)) drain();
        }
    }

    @Override public void close() {
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) { failed.run(); worker.shutdownNow(); } }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failed.run(); worker.shutdownNow(); }
    }
}
