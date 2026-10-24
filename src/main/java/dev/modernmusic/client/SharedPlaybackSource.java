package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Client-thread provider decorator. A remembered opt-in restores once the connection's shared account is ready. */
public final class SharedPlaybackSource implements MusicProvider {
    public record Request(UUID request, long generation, String song, boolean refresh) {}
    private record Pending(SongId song, long generation, long deadline, CompletableFuture<PlaybackResource> future) {}
    private final MusicProvider personal;
    private final Consumer<Request> send;
    private final Runnable changed;
    private final Clock clock;
    private final MusicPlatform platform;
    private final BooleanSupplier keepLogin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private long generation = -1, lastState;
    private MusicError error = MusicError.SHARED_DISABLED;
    private boolean selected, connected;
    private boolean restorePending = true;
    private dev.modernmusic.shared.SharedAccountProfile profile = dev.modernmusic.shared.SharedAccountProfile.EMPTY;
    public SharedPlaybackSource(MusicProvider personal, Consumer<Request> send, Runnable changed, Clock clock) {
        this(personal, send, changed, clock, MusicPlatform.NETEASE);
    }
    public SharedPlaybackSource(MusicProvider personal, Consumer<Request> send, Runnable changed, Clock clock, MusicPlatform platform) {
        this(personal, send, changed, clock, platform, () -> false);
    }
    public SharedPlaybackSource(MusicProvider personal, Consumer<Request> send, Runnable changed, Clock clock, MusicPlatform platform,
                                BooleanSupplier keepLogin) {
        this.personal = personal; this.send = send; this.changed = changed; this.clock = clock;
        this.platform = platform; this.keepLogin = keepLogin;
    }
    public MusicPlatform platform() { return platform; }
    public boolean selected() { return selected; }
    public boolean available() { return connected && error == null; }
    public dev.modernmusic.shared.SharedAccountProfile profile() { return profile; }
    public void profile(long revision, dev.modernmusic.shared.SharedAccountProfile value) {
        if (available() && revision == generation) profile = Objects.requireNonNull(value);
    }
    public String statusKey() { return !connected ? "modernmusic.shared.unavailable" : error == null ? "modernmusic.shared.ready" : error.translationKey(); }
    public void select(boolean value) {
        if (value && !available()) return;
        restorePending = false;
        if (value == selected) return;
        selected = value; cancel(MusicError.SHARED_DISABLED); changed.run();
    }
    public void state(long revision, MusicError failure) {
        if (connected && revision < generation) return;
        boolean different = !connected || revision != generation || failure != error;
        if (different || failure != null) profile = dev.modernmusic.shared.SharedAccountProfile.EMPTY;
        connected = true; generation = revision; error = failure; lastState = clock.millis();
        if (restorePending && available()) {
            restorePending = false;
            if (keepLogin.getAsBoolean()) { selected = true; different = true; }
        }
        if (different) { cancel(failure == null ? MusicError.SHARED_EXPIRED : failure); if (selected) changed.run(); }
    }
    public void receive(UUID request, long revision, String song, String url, long expiresAt, MusicError failure) {
        Pending wait = pending.get(request);
        if (wait == null || wait.generation != revision || revision != generation || !wait.song.toString().equals(song)) return;
        pending.remove(request);
        if (!selected || !available()) { wait.future.completeExceptionally(new MusicException(error == null ? MusicError.SHARED_DISABLED : error)); return; }
        if (failure != null) { wait.future.completeExceptionally(new MusicException(failure)); return; }
        try {
            Instant expires = Instant.ofEpochMilli(expiresAt);
            if (url == null || url.length() > 4096 || !expires.isAfter(clock.instant()) || expires.isAfter(clock.instant().plusSeconds(86400)))
                throw new MusicException(MusicError.INVALID_RESPONSE);
            wait.future.complete(new PlaybackResource(wait.song, dev.modernmusic.provider.ProviderUris.audio(wait.song.platform(), url), expires));
        } catch (RuntimeException invalid) { wait.future.completeExceptionally(new MusicException(MusicError.INVALID_RESPONSE)); }
    }
    public void tick() {
        if (connected && clock.millis() - lastState > 10_000) {
            profile = dev.modernmusic.shared.SharedAccountProfile.EMPTY;
            connected = false; error = MusicError.NETWORK_ERROR; cancel(error); if (selected) changed.run();
        }
        var expired = pending.entrySet().stream().filter(entry -> entry.getValue().future.isDone() || clock.millis() >= entry.getValue().deadline)
                .map(Map.Entry::getKey).toList();
        for (UUID id : expired) {
            Pending wait = pending.remove(id); if (wait != null) wait.future.completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
        }
    }
    public void disconnect() {
        boolean wasSelected = selected;
        connected = false; selected = false; generation = -1; error = MusicError.SHARED_DISABLED;
        restorePending = true;
        profile = dev.modernmusic.shared.SharedAccountProfile.EMPTY;
        cancel(error); if (wasSelected) changed.run();
    }
    private void cancel(MusicError failure) {
        var cancelled = List.copyOf(pending.values()); pending.clear();
        cancelled.forEach(wait -> wait.future.completeExceptionally(new MusicException(failure)));
    }
    private CompletableFuture<PlaybackResource> resolve(SongId song, boolean refresh) {
        if (!selected) return refresh ? personal.refreshPlayback(song) : personal.resolvePlayback(song);
        if (!available()) return CompletableFuture.failedFuture(new MusicException(error));
        if (pending.size() >= 2) return CompletableFuture.failedFuture(new MusicException(MusicError.RATE_LIMITED));
        UUID request = UUID.randomUUID();
        var future = new CompletableFuture<PlaybackResource>();
        pending.put(request, new Pending(song, generation, clock.millis() + 25_000, future));
        try { send.accept(new Request(request, generation, song.toString(), refresh)); }
        catch (RuntimeException failure) { pending.remove(request); future.completeExceptionally(new MusicException(MusicError.NETWORK_ERROR)); }
        return future;
    }
    @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId song) { return resolve(song, false); }
    @Override public CompletableFuture<PlaybackResource> refreshPlayback(SongId song) { return resolve(song, true); }
    @Override public CompletableFuture<Track> getReference(String reference) { return personal.getReference(reference); }
    @Override public CompletableFuture<Track> getTrack(SongId song) { return personal.getTrack(song); }
    @Override public CompletableFuture<java.util.List<Track>> getTracks(java.util.List<SongId> songs) { return personal.getTracks(songs); }
    @Override public CompletableFuture<SongSearchResult> searchSongs(String query, int offset, int limit) {
        return personal.searchSongs(query, offset, limit);
    }
    @Override public CompletableFuture<Lyrics> getLyrics(SongId song) { return personal.getLyrics(song); }
    @Override public CompletableFuture<Playlist> getPlaylist(SongId song) { return personal.getPlaylist(song); }
    @Override public void close() { disconnect(); personal.close(); }
}
