package dev.modernmusic.client;

import dev.modernmusic.domain.SongSearchResult;
import dev.modernmusic.provider.*;
import java.util.concurrent.*;

/** Client-thread search state. A newer query, leaving the screen or suspending invalidates old responses. */
public final class SongSearchController {
    private final MusicProvider provider;
    private final Executor mainThread;
    private CompletableFuture<SongSearchResult> pending;
    private long generation, resetRevision;
    private String query = "", message = "modernmusic.search.help";
    private int offset, limit = 10;
    private SongSearchResult result;

    public SongSearchController(MusicProvider provider, Executor mainThread) {
        this.provider = provider; this.mainThread = mainThread;
    }
    public void search(String input, int offset, int limit) {
        cancel(); result = null;
        try {
            query = SongSearchResult.query(input);
            if (offset < 0 || offset > SongSearchResult.MAX_OFFSET || limit < 1 || limit > SongSearchResult.MAX_PAGE_SIZE)
                throw new IllegalArgumentException("search_page");
        } catch (IllegalArgumentException invalid) {
            query = ""; this.offset = 0; message = "modernmusic.search.invalid_query"; return;
        }
        this.offset = offset; this.limit = limit; message = "modernmusic.search.loading";
        long token = generation;
        try { pending = provider.searchSongs(query, offset, limit); }
        catch (RuntimeException failure) { message = "modernmusic.error.invalid_response"; return; }
        pending.whenComplete((value, error) -> mainThread.execute(() -> {
            if (token != generation) return;
            pending = null;
            if (error != null || value == null) {
                Throwable failure = error;
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                message = failure instanceof MusicException music ? music.error().translationKey() : "modernmusic.error.invalid_response";
            } else {
                result = value; message = value.tracks().isEmpty() ? "modernmusic.search.empty" : "modernmusic.search.help";
            }
        }));
    }
    private void cancel() {
        generation++;
        if (pending != null) { pending.cancel(true); pending = null; }
    }
    public void reset() { cancel(); resetRevision++; query = ""; offset = 0; result = null; message = "modernmusic.search.help"; }
    public boolean loading() { return pending != null; }
    public String query() { return query; }
    public String message() { return message; }
    public int offset() { return offset; }
    public int limit() { return limit; }
    public long resetRevision() { return resetRevision; }
    public SongSearchResult result() { return result; }
}
