package dev.modernmusic.provider;

import dev.modernmusic.auth.SessionCookie;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** QQ and Kugou requests keep the submitting caller's account snapshot. */
public final class ProviderTasks {
    private ProviderTasks() {}

    public static <T> CompletableFuture<T> submit(ExecutorService worker, Supplier<SessionCookie> credentials,
                                                Consumer<SessionCookie> expired, Function<SessionCookie, T> action) {
        SessionCookie cookie = credentials.get();
        return AsyncTasks.submit(worker, () -> {
            try { return action.apply(cookie); }
            catch (MusicException failure) {
                if (failure.error() == MusicError.AUTH_EXPIRED && cookie != null) expired.accept(cookie);
                throw failure;
            }
            catch (RuntimeException invalid) { throw new MusicException(MusicError.INVALID_RESPONSE); }
        });
    }
}
