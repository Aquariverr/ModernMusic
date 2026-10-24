package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.server.SharedResolver;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedPlaybackFanoutTest {
    @Test void thousandColdClientsRecoverThroughBoundedFanoutWithOneUpstreamRequest() {
        var clock = new SharedPlaybackSourceTest.Time();
        var upstream = new CompletableFuture<PlaybackResource>();
        var calls = new AtomicInteger(); var rateLimited = new AtomicInteger();
        MusicProvider provider = new MusicProvider() {
            public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError(); }
            public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { calls.incrementAndGet(); return upstream; }
            public void close() {}
        };
        var sources = new ArrayList<SharedPlaybackSource>();
        var results = new ArrayList<CompletableFuture<PlaybackResource>>();
        try (var resolver = new SharedResolver(clock, Runnable::run)) {
            resolver.use(provider, MusicError.SHARED_DISABLED);
            for (int i = 0; i < 1000; i++) {
                UUID player = new UUID(0, i + 1); var holder = new SharedPlaybackSource[1];
                holder[0] = new SharedPlaybackSource(provider, request ->
                    resolver.resolve(player, SongId.stored(request.song()), request.refresh()).whenComplete((value, error) -> {
                        MusicError failure = error == null ? null : SharedResolver.category(error);
                        if (failure == MusicError.RATE_LIMITED) rateLimited.incrementAndGet();
                        holder[0].receive(request.request(), request.generation(), request.song(),
                                value == null ? "" : value.uri().toString(), value == null ? 0 : value.expiresAt().toEpochMilli(), failure);
                    }), () -> {}, clock);
                holder[0].state(resolver.generation(), null); holder[0].select(true); sources.add(holder[0]);
                results.add(holder[0].resolvePlayback(new SongId(42)));
            }
            assertEquals(872, rateLimited.get()); assertEquals(1, calls.get());
            assertTrue(results.stream().noneMatch(CompletableFuture::isDone));
            for (int tick = 1; tick <= 200; tick++) {
                clock.value = clock.value.plusMillis(50);
                if (tick == 60) upstream.complete(new PlaybackResource(new SongId(42),
                        URI.create("https://m1.music.126.net/recovered"), clock.instant().plusSeconds(60)));
                for (var source : sources) { source.state(resolver.generation(), null); source.tick(); }
            }
            assertTrue(results.stream().allMatch(future -> future.isDone() && !future.isCompletedExceptionally()));
            assertEquals(1, calls.get()); assertEquals(1000, results.stream().map(CompletableFuture::join).count());
        }
    }
}
