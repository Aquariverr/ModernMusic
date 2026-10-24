package dev.modernmusic.provider;

import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.MusicPlatform;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderTasksTest {
    @Test void queuedRequestExpiresOnlyTheAccountCapturedAtSubmission() throws Exception {
        var original = SessionCookie.parse("uin=123; qm_keyst=first", MusicPlatform.QQ);
        var replacement = SessionCookie.parse("uin=456; qm_keyst=second", MusicPlatform.QQ);
        var credentials = new AtomicReference<>(original); var expired = new AtomicReference<SessionCookie>();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = AsyncTasks.executor("test-account-snapshot", 1, 2);
        try {
            try {
                worker.submit(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                var request = ProviderTasks.submit(worker, credentials::get, expired::set, cookie -> {
                    assertSame(original, cookie);
                    throw new MusicException(MusicError.AUTH_EXPIRED);
                });
                credentials.set(replacement); release.countDown();
                var failure = assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
                assertEquals(MusicError.AUTH_EXPIRED, assertInstanceOf(MusicException.class, failure.getCause()).error());
                assertSame(original, expired.get()); assertSame(replacement, credentials.get());
            } finally { release.countDown(); }
        } finally { worker.shutdownNow(); }
    }

    @Test void preservesMusicErrorsAndMapsUnexpectedResponsesWithoutExpiringGuests() throws Exception {
        var worker = AsyncTasks.executor("test-response-errors", 1, 2);
        try {
            for (var expected : new MusicError[]{MusicError.AUTH_EXPIRED, MusicError.UNAVAILABLE, MusicError.INVALID_RESPONSE}) {
                var request = ProviderTasks.submit(worker, () -> null, cookie -> fail("Guest has no account to expire"), cookie -> {
                    if (expected == MusicError.INVALID_RESPONSE) throw new IllegalArgumentException("fixture");
                    throw new MusicException(expected);
                });
                var failure = assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
                assertEquals(expected, assertInstanceOf(MusicException.class, failure.getCause()).error());
            }
        } finally { worker.shutdownNow(); }
    }
}
