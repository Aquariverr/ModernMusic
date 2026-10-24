package dev.modernmusic.server;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.provider.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Server-thread owner. Bounded fan-out, short-lived cache and no credential-bearing wire model. */
public final class SharedResolver implements AutoCloseable {
    private record Rate(long start, int count) {}
    private record Flight(CompletableFuture<PlaybackResource> upstream, List<CompletableFuture<PlaybackResource>> listeners) {}
    private final Clock clock;
    private final Executor main;
    private final Map<UUID, Rate> rates = new HashMap<>();
    private final Map<UUID, Set<CompletableFuture<PlaybackResource>>> waiting = new HashMap<>();
    private final Map<SongId, Flight> flights = new HashMap<>();
    private final LinkedHashMap<SongId, PlaybackResource> cache = new LinkedHashMap<>();
    private MusicProvider provider;
    private MusicError unavailable = MusicError.SHARED_DISABLED;
    private Rate global = new Rate(0, 0);
    private long generation;

    public SharedResolver(Clock clock, Executor main) { this.clock = clock; this.main = main; }
    public boolean ready() { return provider != null; }
    public long generation() { return generation; }
    public MusicError unavailable() { return unavailable; }
    public void use(MusicProvider next, MusicError reason) {
        generation++;
        MusicProvider previous = provider; provider = next; unavailable = reason;
        var abandoned = List.copyOf(flights.values()); flights.clear(); cache.clear(); waiting.clear();
        // Keep request rates across on/off and account changes; reconnect cannot reset the global limit.
        for (Flight flight : abandoned) {
            List.copyOf(flight.listeners).forEach(f -> f.completeExceptionally(new MusicException(reason)));
            flight.upstream.cancel(true);
        }
        if (previous != null) previous.close();
    }
    public CompletableFuture<PlaybackResource> resolve(UUID player, SongId id, boolean refresh) {
        if (!ready()) return failed(unavailable);
        long now = clock.millis();
        Rate rate = advance(rates.get(player), now);
        rates.put(player, rate);
        if (rate.count > 6 || waiting.getOrDefault(player, Set.of()).size() >= 2) return failed(MusicError.RATE_LIMITED);
        cache.values().removeIf(value -> !value.expiresAt().isAfter(clock.instant().plusSeconds(15)));
        if (refresh) cache.remove(id);
        PlaybackResource cached = cache.get(id);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        Flight flight = flights.get(id);
        boolean createdFlight = flight == null;
        if (flight == null) {
            if (flights.size() >= 4) return failed(MusicError.RATE_LIMITED);
            global = advance(global, now);
            if (global.count > 30) return failed(MusicError.RATE_LIMITED);
            CompletableFuture<PlaybackResource> upstream;
            try { upstream = provider.resolvePlayback(id); }
            catch (RuntimeException error) { return failed(MusicError.INTERNAL_ERROR); }
            flight = new Flight(upstream, new ArrayList<>());
            flights.put(id, flight);
        }
        if (flight.listeners.size() >= 128) return failed(MusicError.RATE_LIMITED);
        var result = new CompletableFuture<PlaybackResource>();
        var playerWaits = waiting.computeIfAbsent(player, ignored -> new HashSet<>());
        playerWaits.add(result); flight.listeners.add(result);
        Flight listening = flight;
        long token = generation;
        result.whenComplete((value, error) -> main.execute(() -> {
            playerWaits.remove(result);
            if (playerWaits.isEmpty()) waiting.remove(player, playerWaits);
            listening.listeners.remove(result);
        }));
        if (createdFlight) {
            Flight created = flight;
            flight.upstream.whenComplete((resource, error) -> main.execute(() -> finish(id, created, token, resource, error)));
        }
        return result;
    }
    private void finish(SongId id, Flight flight, long token, PlaybackResource resource, Throwable error) {
        if (token != generation || flights.get(id) != flight) return;
        flights.remove(id);
        if (error == null) {
            try {
                resource = checked(id, resource);
                if (resource.expiresAt().isAfter(clock.instant().plusSeconds(15))) {
                    cache.put(id, resource);
                    while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
                }
            } catch (RuntimeException invalid) { error = invalid; }
        }
        // Completion may synchronously remove listeners when the owner executor runs inline.
        for (var listener : List.copyOf(flight.listeners)) {
            if (error == null) listener.complete(resource);
            else listener.completeExceptionally(new MusicException(category(error)));
        }
    }
    private PlaybackResource checked(SongId id, PlaybackResource resource) {
        if (resource == null || !id.equals(resource.id()) || resource.uri().toString().length() > 4096
                || !resource.expiresAt().isAfter(clock.instant())
                || resource.expiresAt().isAfter(clock.instant().plusSeconds(86400))) throw new MusicException(MusicError.INVALID_RESPONSE);
        return new PlaybackResource(id, dev.modernmusic.provider.ProviderUris.audio(id.platform(), resource.uri().toString()), resource.expiresAt());
    }
    public void disconnect(UUID player) {
        rates.remove(player);
        var abandoned = waiting.remove(player);
        if (abandoned != null) for (var request : List.copyOf(abandoned)) request.completeExceptionally(new MusicException(MusicError.SHARED_DISABLED));
    }
    private static Rate advance(Rate rate, long now) {
        return rate == null || now - rate.start >= 10_000 || now < rate.start ? new Rate(now, 1) : new Rate(rate.start, rate.count + 1);
    }
    public static MusicError category(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error instanceof MusicException music ? music.error() : MusicError.INTERNAL_ERROR;
    }
    private static CompletableFuture<PlaybackResource> failed(MusicError error) { return CompletableFuture.failedFuture(new MusicException(error)); }
    @Override public void close() { use(null, MusicError.SHARED_DISABLED); rates.clear(); }
}
