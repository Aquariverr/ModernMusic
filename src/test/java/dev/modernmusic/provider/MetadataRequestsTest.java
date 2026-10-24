package dev.modernmusic.provider;

import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MetadataRequestsTest {
    private static Track track(SongId id) { return new Track(id, "Fixture", List.of(), 1000, null); }
    private static class ControlledProvider implements MusicProvider {
        final List<SongId> started = new ArrayList<>();
        final List<CompletableFuture<Track>> requests = new ArrayList<>();
        @Override public CompletableFuture<Track> getTrack(SongId id) {
            started.add(id);
            var request = new CompletableFuture<Track>(); requests.add(request); return request;
        }
        @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { throw new AssertionError(); }
        @Override public void close() { }
    }

    @Test void cancellingBatchCancelsCurrentLookupAndNeverStartsRemainingSongs() {
        var provider = new ControlledProvider();
        var songs = java.util.stream.LongStream.rangeClosed(1, 50).mapToObj(SongId::new).toList();
        var result = provider.getTracks(songs);
        assertEquals(1, provider.started.size());
        assertTrue(result.cancel(true));
        assertTrue(provider.requests.getFirst().isCancelled());
        assertFalse(provider.requests.getFirst().complete(track(songs.getFirst())));
        assertEquals(1, provider.started.size());
    }

    @Test void completedAndFailedLookupsKeepOrderAndReturnImmutableResults() {
        var provider = new ControlledProvider() {
            @Override public CompletableFuture<Track> getTrack(SongId id) {
                started.add(id);
                return id.value() == 2 ? CompletableFuture.failedFuture(new MusicException(MusicError.UNAVAILABLE))
                        : CompletableFuture.completedFuture(track(id));
            }
        };
        var songs = java.util.stream.LongStream.rangeClosed(1, 50).mapToObj(SongId::new).toList();
        var result = provider.getTracks(songs).join();
        assertEquals(songs, provider.started);
        assertEquals(songs.stream().filter(id -> id.value() != 2).toList(), result.stream().map(Track::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.add(track(new SongId(51))));
        assertEquals(List.of(), provider.getTracks(List.of()).join());
    }

    @Test void asynchronousFailureContinuesButSynchronousSubmissionFailureTerminatesBatch() {
        var provider = new ControlledProvider();
        var songs = List.of(new SongId(1), new SongId(2));
        var result = provider.getTracks(songs);
        provider.requests.getFirst().completeExceptionally(new MusicException(MusicError.UNAVAILABLE));
        assertEquals(2, provider.started.size());
        provider.requests.get(1).complete(track(songs.get(1)));
        assertEquals(List.of(track(songs.get(1))), result.join());

        var rejection = new ControlledProvider() {
            @Override public CompletableFuture<Track> getTrack(SongId id) { throw new IllegalStateException("closed"); }
        };
        assertInstanceOf(IllegalStateException.class,
                assertThrows(CompletionException.class, () -> rejection.getTracks(songs).join()).getCause());
    }

    @Test void cancellingDuringChildPublicationAlsoCancelsThatChild() throws Exception {
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var second = new CompletableFuture<Track>();
        var provider = new ControlledProvider() {
            @Override public CompletableFuture<Track> getTrack(SongId id) {
                if (id.value() != 2) return super.getTrack(id);
                started.add(id); publishing.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return second;
            }
        };
        var result = provider.getTracks(List.of(new SongId(1), new SongId(2), new SongId(3)));
        result.whenComplete((value, error) -> { if (result.isCancelled()) cancelled.countDown(); });
        try (var threads = Executors.newFixedThreadPool(2)) {
            var completing = threads.submit(() -> provider.requests.getFirst().complete(track(new SongId(1))));
            assertTrue(publishing.await(2, TimeUnit.SECONDS));
            var cancellation = threads.submit(() -> result.cancel(true));
            try { assertTrue(cancelled.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            completing.get(2, TimeUnit.SECONDS); cancellation.get(2, TimeUnit.SECONDS);
            assertTrue(second.isCancelled());
            assertEquals(List.of(new SongId(1), new SongId(2)), provider.started);
        } finally { release.countDown(); }
    }

    @Test void multiPlatformCancellationReachesEveryActivePlatformAndItsCurrentLookup() {
        var netease = new ControlledProvider(); var qq = new ControlledProvider();
        var provider = new MultiPlatformProvider(netease, qq);
        var result = provider.getTracks(List.of(new SongId(1), new SongId(2),
                new SongId(3, MusicPlatform.QQ), new SongId(4, MusicPlatform.QQ)));
        assertTrue(result.cancel(true));
        assertTrue(netease.requests.getFirst().isCancelled());
        assertTrue(qq.requests.getFirst().isCancelled());
        assertEquals(1, netease.started.size()); assertEquals(1, qq.started.size());
    }

    @Test void multiPlatformPreservesPlatformOrderAndCancelsStartedBatchesOnSubmissionFailure() {
        var netease = new ControlledProvider(); var qq = new ControlledProvider();
        var provider = new MultiPlatformProvider(netease, qq);
        var first = new SongId(1); var second = new SongId(2, MusicPlatform.QQ);
        var result = provider.getTracks(List.of(second, first));
        qq.requests.getFirst().complete(track(second));
        assertFalse(result.isDone());
        netease.requests.getFirst().complete(track(first));
        assertEquals(List.of(track(first), track(second)), result.join());

        var rejecting = new ControlledProvider() {
            @Override public CompletableFuture<List<Track>> getTracks(List<SongId> ids) { throw new IllegalStateException("closed"); }
        };
        var failed = new MultiPlatformProvider(netease, rejecting).getTracks(List.of(first, second));
        assertThrows(CompletionException.class, failed::join);
        assertTrue(netease.requests.get(1).isCancelled());
    }
}
