package dev.modernmusic.server;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedResolverTest {
    static final class Time extends Clock {
        Instant value = Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
        void add(long seconds) { value = value.plusSeconds(seconds); }
    }
    static final class Provider implements MusicProvider {
        final Map<SongId, CompletableFuture<PlaybackResource>> calls = new HashMap<>();
        int count, closed;
        public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError("No server metadata forwarding"); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            count++;
            var future = new CompletableFuture<PlaybackResource>() { @Override public boolean cancel(boolean interrupt) { return false; } };
            calls.put(id, future); return future;
        }
        void finish(long id, Instant deadline) {
            calls.get(new SongId(id)).complete(new PlaybackResource(new SongId(id), URI.create("https://m801.music.126.net/signed.mp3?token=short"), deadline));
        }
        public void close() { closed++; }
    }
    final Time time = new Time();
    final Provider provider = new Provider();
    final SharedResolver resolver = new SharedResolver(time, Runnable::run);
    final UUID one = UUID.randomUUID(), two = UUID.randomUUID();
    SharedResolverTest() { resolver.use(provider, MusicError.SHARED_DISABLED); }
    static void error(MusicError expected, CompletableFuture<?> result) {
        assertEquals(expected, ((MusicException) assertThrows(CompletionException.class, result::join).getCause()).error());
    }
    @Test void deduplicatesConcurrentPlayersAndCachesOnlyShortLivedResource() {
        var first = resolver.resolve(one, new SongId(1), false);
        var second = resolver.resolve(two, new SongId(1), false);
        assertEquals(1, provider.count); provider.finish(1, time.instant().plusSeconds(60));
        assertEquals(first.join(), second.join());
        assertEquals(first.join(), resolver.resolve(one, new SongId(1), false).join()); assertEquals(1, provider.count);
        time.add(46); var after = resolver.resolve(two, new SongId(1), false); assertFalse(after.isDone()); assertEquals(2, provider.count);
    }
    @Test void rejectedCdnRefreshBypassesCachedAddressAndCoalescesRefreshes() {
        var first = resolver.resolve(one, new SongId(1), false); provider.finish(1, time.instant().plusSeconds(60)); first.join();
        var refresh = resolver.resolve(one, new SongId(1), true);
        var other = resolver.resolve(two, new SongId(1), true);
        assertEquals(2, provider.count); provider.finish(1, time.instant().plusSeconds(90)); assertEquals(refresh.join(), other.join());
    }
    @Test void disablingAndReplacingAccountRevokesInFlightAndCachedResults() {
        var first = resolver.resolve(one, new SongId(1), false); var old = provider.calls.get(new SongId(1));
        long previous = resolver.generation(); resolver.use(null, MusicError.SHARED_DISABLED);
        error(MusicError.SHARED_DISABLED, first); error(MusicError.SHARED_DISABLED, resolver.resolve(one, new SongId(1), false));
        assertEquals(1, provider.closed); assertTrue(resolver.generation() > previous);
        Provider replacement = new Provider(); resolver.use(replacement, MusicError.SHARED_EXPIRED);
        var request = resolver.resolve(one, new SongId(1), false);
        old.complete(new PlaybackResource(new SongId(1), URI.create("https://m1.music.126.net/old"), time.instant().plusSeconds(60)));
        assertFalse(request.isDone()); replacement.finish(1, time.instant().plusSeconds(60)); request.join();
    }
    @Test void limitsPerPlayerOutstandingRequestsAndDistinctGlobalConcurrency() {
        resolver.resolve(one, new SongId(1), false); resolver.resolve(one, new SongId(2), false);
        error(MusicError.RATE_LIMITED, resolver.resolve(one, new SongId(3), false));
        resolver.resolve(two, new SongId(3), false); resolver.resolve(two, new SongId(4), false);
        error(MusicError.RATE_LIMITED, resolver.resolve(UUID.randomUUID(), new SongId(5), false)); assertEquals(4, provider.count);
    }
    @Test void cacheHitsAlsoConsumePlayerRateAndGlobalRateSurvivesDisconnects() {
        var first = resolver.resolve(one, new SongId(1), false); provider.finish(1, time.instant().plusSeconds(60)); first.join();
        for (int i = 0; i < 5; i++) resolver.resolve(one, new SongId(1), false).join();
        error(MusicError.RATE_LIMITED, resolver.resolve(one, new SongId(1), false));
        for (int i = 2; i <= 30; i++) {
            UUID player = UUID.randomUUID(); var request = resolver.resolve(player, new SongId(i), false);
            provider.finish(i, time.instant().plusSeconds(60)); request.join(); resolver.disconnect(player);
        }
        error(MusicError.RATE_LIMITED, resolver.resolve(UUID.randomUUID(), new SongId(31), false));
        time.add(11); assertFalse(resolver.resolve(one, new SongId(31), false).isDone());
    }
    @Test void cancelledListenerDoesNotCancelOtherPlayers() {
        var first = resolver.resolve(one, new SongId(1), false); var second = resolver.resolve(two, new SongId(1), false);
        first.cancel(true); provider.finish(1, time.instant().plusSeconds(60)); second.join(); assertTrue(first.isCancelled());
    }
    @Test void disconnectedListenersCannotExhaustTheSharedSongFanout() {
        SongId song = new SongId(1);
        // All requests share one slow upstream; departed players must relinquish listener slots.
        for (int i = 0; i < 256; i++) {
            UUID player = UUID.randomUUID();
            var request = resolver.resolve(player, song, false);
            assertFalse(request.isDone());
            resolver.disconnect(player);
            error(MusicError.SHARED_DISABLED, request);
        }
        var remaining = resolver.resolve(one, song, false);
        assertFalse(remaining.isDone()); assertEquals(1, provider.count);
        provider.finish(1, time.instant().plusSeconds(60));
        assertEquals(song, remaining.join().id());
    }
    @Test void allListenersAreRevokedWhenOwnerExecutorRunsInline() {
        var first = resolver.resolve(one, new SongId(1), false);
        var second = resolver.resolve(two, new SongId(1), false);
        resolver.use(null, MusicError.SHARED_DISABLED);
        error(MusicError.SHARED_DISABLED, first); error(MusicError.SHARED_DISABLED, second);
    }
    @Test void reconnectCannotReceiveOldWaitsOrCorruptNewOutstandingLimit() {
        var old = resolver.resolve(one, new SongId(1), false); resolver.disconnect(one);
        error(MusicError.SHARED_DISABLED, old);
        var first = resolver.resolve(one, new SongId(2), false); var second = resolver.resolve(one, new SongId(3), false);
        provider.finish(1, time.instant().plusSeconds(60));
        error(MusicError.RATE_LIMITED, resolver.resolve(one, new SongId(4), false));
        assertFalse(first.isDone()); assertFalse(second.isDone());
    }
    @Test void rejectsForeignHostWrongSongExpiredAndOversizedResources() {
        for (PlaybackResource invalid : List.of(
                new PlaybackResource(new SongId(1), URI.create("https://127.0.0.1/song"), time.instant().plusSeconds(60)),
                new PlaybackResource(new SongId(2), URI.create("https://m1.music.126.net/song"), time.instant().plusSeconds(60)),
                new PlaybackResource(new SongId(1), URI.create("https://m1.music.126.net/song"), time.instant()),
                new PlaybackResource(new SongId(1), URI.create("https://m1.music.126.net/" + "x".repeat(4096)), time.instant().plusSeconds(60)))) {
            var request = resolver.resolve(UUID.randomUUID(), new SongId(1), true);
            provider.calls.get(new SongId(1)).complete(invalid); error(MusicError.INVALID_RESPONSE, request);
        }
    }
    @Test void upstreamErrorsStayCategorizedWithoutLeakingMessage() {
        var request = resolver.resolve(one, new SongId(1), false);
        provider.calls.get(new SongId(1)).completeExceptionally(new IllegalStateException("MUSIC_U=should-never-leak"));
        error(MusicError.INTERNAL_ERROR, request);
        assertFalse(assertThrows(CompletionException.class, request::join).toString().contains("should-never-leak"));
    }
}
