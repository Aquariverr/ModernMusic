package dev.modernmusic.provider;

import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AsyncTasks {
    private AsyncTasks() {}

    public static ThreadPoolExecutor executor(String name, int threads, int queueSize) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), Thread.ofPlatform().daemon().name(name + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Unlike supplyAsync, cancellation also interrupts the underlying blocking HTTP/decode task. */
    public static <T> CompletableFuture<T> submit(ExecutorService executor, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
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
}
