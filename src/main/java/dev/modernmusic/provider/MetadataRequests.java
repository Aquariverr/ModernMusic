package dev.modernmusic.provider;

import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Metadata aggregations retain cancellation ownership of the requests they start. */
final class MetadataRequests {
    private MetadataRequests() {}

    static CompletableFuture<List<Track>> sequential(List<SongId> songs,
                                                     Function<SongId, CompletableFuture<Track>> lookup) {
        return new Batch(songs, lookup).start();
    }

    static CompletableFuture<List<Track>> combine(List<CompletableFuture<List<Track>>> requests) {
        var children = List.copyOf(requests);
        var result = CompletableFuture.allOf(children.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> children.stream().flatMap(request -> request.join().stream()).toList());
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) children.forEach(request -> request.cancel(true));
        });
        return result;
    }

    private static final class Batch {
        private final List<SongId> songs;
        private final Function<SongId, CompletableFuture<Track>> lookup;
        private final List<Track> found = new ArrayList<>();
        private final CompletableFuture<List<Track>> result = new CompletableFuture<>();
        private CompletableFuture<Track> active;
        private int index;

        Batch(List<SongId> songs, Function<SongId, CompletableFuture<Track>> lookup) {
            this.songs = songs; this.lookup = lookup;
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) cancelActive();
            });
        }

        CompletableFuture<List<Track>> start() { next(null, null); return result; }

        private synchronized void cancelActive() {
            if (active != null) { active.cancel(true); active = null; }
        }

        // lookup only submits asynchronous work. Serialize publication with cancellation so a
        // request created during cancel is cancelled too, including synchronously completed futures.
        private synchronized void next(Track track, Throwable error) {
            if (result.isDone()) return;
            active = null;
            if (error == null && track != null) found.add(track);
            if (index == songs.size()) { result.complete(List.copyOf(found)); return; }
            try { active = java.util.Objects.requireNonNull(lookup.apply(songs.get(index++))); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); return; }
            if (result.isDone()) { cancelActive(); return; }
            active.whenComplete(this::next);
        }
    }
}
