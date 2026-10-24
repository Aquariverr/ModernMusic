package dev.modernmusic.provider;

import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AsyncTasks {
    private AsyncTasks() {}

    public static ThreadPoolExecutor executor(String name, int threads, int queueSize) {
        return new TaskExecutor(name, threads, queueSize);
    }

    /** Unlike supplyAsync, cancellation also interrupts the underlying blocking HTTP/decode task. */
    public static <T> CompletableFuture<T> submit(ExecutorService executor, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            if (executor instanceof TaskExecutor owner) owner.track(result);
            Future<?> task = executor.submit(() -> {
                try { result.complete(action.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) {
                    task.cancel(true);
                    if (executor instanceof ThreadPoolExecutor pool && task instanceof Runnable queued) pool.remove(queued);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new MusicException(MusicError.RATE_LIMITED));
        }
        return result;
    }

    /** Graceful shutdown still drains writes; immediate shutdown also ends callers' futures. */
    private static final class TaskExecutor extends ThreadPoolExecutor {
        private final java.util.Set<CompletableFuture<?>> pending = new java.util.HashSet<>();
        private boolean stopping;

        TaskExecutor(String name, int threads, int queueSize) {
            super(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                    dev.modernmusic.compat.Java17.daemonFactory(name), new ThreadPoolExecutor.AbortPolicy());
        }

        void track(CompletableFuture<?> result) {
            synchronized (pending) {
                if (stopping || isShutdown()) throw new RejectedExecutionException();
                pending.add(result);
            }
            result.whenComplete((value, error) -> {
                synchronized (pending) { pending.remove(result); }
            });
        }

        @Override public java.util.List<Runnable> shutdownNow() {
            java.util.List<CompletableFuture<?>> unfinished;
            synchronized (pending) {
                stopping = true;
                unfinished = java.util.List.copyOf(pending);
            }
            // Never run completion callbacks under the registry lock. A concurrent submission
            // is either in this snapshot or rejected; late publication still observes cancellation.
            try { return super.shutdownNow(); }
            finally { unfinished.forEach(result -> result.cancel(true)); }
        }
    }
}
