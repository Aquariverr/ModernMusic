package dev.modernmusic.client;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.audio.AudioLibrary;
import dev.modernmusic.audio.AudioImports;
import dev.modernmusic.client.audio.PcmAudio;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Local files and server files share one browsing platform, but never share file-system authority. */
public final class LocalAudioProvider implements MusicProvider {
    public static final int MAX_DROPPED_FILES = 32;
    public record DropFailure(String fileName, MusicError error) { }
    public record DropResult(List<Track> tracks, List<DropFailure> failures, int total) {
        public DropResult { tracks = List.copyOf(tracks); failures = List.copyOf(failures); }
    }
    public interface Remote {
        boolean available();
        boolean canUpload();
        default Object connectionToken() { return this; }
        CompletableFuture<List<Track>> refresh();
        CompletableFuture<Track> getTrack(SongId id);
        CompletableFuture<byte[]> download(SongId id);
        CompletableFuture<Track> upload(String filename, byte[] bytes);
    }
    private final AudioLibrary files;
    private final Remote remote;
    private final Executor main;
    private final ExecutorService worker = AsyncTasks.executor("modernmusic-local-audio", 1, 8);
    private final Set<CompletableFuture<DropResult>> imports = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public LocalAudioProvider(Path directory, Executor main) {
        this(directory, main, new Remote() {
            public boolean available() { return ServerAudioClient.available(); }
            public boolean canUpload() { return ServerAudioClient.canUpload(); }
            public Object connectionToken() { return ServerAudioClient.connectionToken(); }
            public CompletableFuture<List<Track>> refresh() { return ServerAudioClient.refresh(); }
            public CompletableFuture<Track> getTrack(SongId id) { return ServerAudioClient.getTrack(id); }
            public CompletableFuture<byte[]> download(SongId id) { return ServerAudioClient.download(id); }
            public CompletableFuture<Track> upload(String name, byte[] bytes) { return ServerAudioClient.upload(name, bytes); }
        });
    }
    public LocalAudioProvider(Path directory, Executor main, Remote remote) {
        this.files = new AudioLibrary(directory); this.main = main; this.remote = remote;
    }
    public Path directory() { return files.directory(); }
    public boolean serverAvailable() { return !closed && remote.available(); }
    public boolean canUpload() { return serverAvailable() && remote.canUpload(); }
    public CompletableFuture<List<Track>> browseLocal() { return AsyncTasks.submit(worker, files::list); }
    public CompletableFuture<List<Track>> browseServer() { return remote.refresh(); }

    /** Each file is validated and completed before the next is read, bounding memory for multi-file drops. */
    public CompletableFuture<DropResult> importDropped(List<Path> paths, boolean toServer) {
        if (paths == null || paths.isEmpty() || paths.size() > MAX_DROPPED_FILES)
            return CompletableFuture.failedFuture(new MusicException(MusicError.RESOURCE_TOO_LARGE));
        if (closed) return CompletableFuture.failedFuture(new MusicException(MusicError.RESOURCE_UNAVAILABLE));
        var batch = new DropBatch(new ArrayList<>(paths), toServer);
        // The screen invokes this method on the client thread: bind the actual drop gesture before any dispatch.
        if (toServer) batch.connection = remote.connectionToken();
        imports.add(batch.result);
        batch.result.whenComplete((value, failure) -> {
            imports.remove(batch.result);
            if (batch.result.isCancelled()) {
                var current = batch.current.get(); if (current != null) current.cancel(true);
            }
        });
        if (closed) { batch.result.cancel(true); return batch.result; }
        batch.next();
        return batch.result;
    }

    private final class DropBatch {
        private final List<Path> paths;
        private final boolean toServer;
        private final List<Track> tracks = new ArrayList<>();
        private final List<DropFailure> failures = new ArrayList<>();
        private final CompletableFuture<DropResult> result = new CompletableFuture<>();
        private final java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>> current = new java.util.concurrent.atomic.AtomicReference<>();
        private Object connection;
        private int index;
        private DropBatch(List<Path> paths, boolean toServer) { this.paths = paths; this.toServer = toServer; }
        private boolean active() { return !closed && !result.isDone(); }
        private void requireActive() { if (!active()) throw new CancellationException(); }
        private void requireRemote() {
            requireActive();
            if (connection == null || !Objects.equals(connection, remote.connectionToken()) || !remote.available())
                throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
            if (!remote.canUpload()) throw new MusicException(MusicError.SHARED_FORBIDDEN);
        }
        private <T> CompletableFuture<T> watch(CompletableFuture<T> future) {
            current.set(future);
            if (!active()) future.cancel(true);
            return future;
        }
        private <T> CompletableFuture<T> onMain(java.util.function.Supplier<CompletableFuture<T>> action) {
            var next = new CompletableFuture<T>();
            try {
                main.execute(() -> {
                    try {
                        requireActive();
                        action.get().whenComplete((value, failure) -> {
                            if (failure != null) next.completeExceptionally(failure); else next.complete(value);
                        });
                    } catch (Throwable failure) { next.completeExceptionally(failure); }
                });
            } catch (RuntimeException failure) { next.completeExceptionally(failure); }
            return next;
        }
        private void next() {
            if (!active()) return;
            if (index == paths.size()) { result.complete(new DropResult(tracks, failures, paths.size())); return; }
            Path path = paths.get(index++);
            CompletableFuture<Track> operation;
            if (toServer) {
                operation = onMain(() -> {
                    requireRemote();
                    return watch(AsyncTasks.submit(worker, () -> { requireActive(); return AudioImports.read(path); }));
                }).thenCompose(audio -> onMain(() -> {
                    requireRemote();
                    return watch(remote.upload(audio.fileName(), audio.bytes()));
                }));
            } else operation = watch(AsyncTasks.submit(worker, () -> AudioImports.copyInto(files, path, this::active)));
            operation.whenComplete((track, failure) -> {
                if (!active()) return;
                if (failure == null) tracks.add(track);
                else failures.add(new DropFailure(path == null || path.getFileName() == null ? "" : path.getFileName().toString(), category(failure)));
                next();
            });
        }
    }

    private static MusicError category(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof MusicException music ? music.error() : MusicError.RESOURCE_UNAVAILABLE;
    }

    @Override public CompletableFuture<Track> getReference(String reference) {
        if (reference == null || reference.isBlank()) return CompletableFuture.failedFuture(new MusicException(MusicError.AUDIO_NOT_FOUND));
        String name = reference.strip();
        // Capture server availability on the client thread. Do not hide a failed server catalogue behind a local match.
        var server = serverAvailable() ? browseServer() : CompletableFuture.<List<Track>>completedFuture(List.of());
        return browseLocal().thenCombine(server, (local, shared) -> {
            var all = new ArrayList<>(local); all.addAll(shared);
            return findReference(name, all);
        });
    }
    /** Group input can only refer to the server catalogue, including immediately after an upload. */
    public CompletableFuture<Track> getServerReference(String reference) {
        if (reference == null || reference.isBlank()) return CompletableFuture.failedFuture(new MusicException(MusicError.AUDIO_NOT_FOUND));
        return browseServer().thenApply(tracks -> findReference(reference.strip(), tracks));
    }
    private static Track findReference(String name, List<Track> tracks) {
        var matches = tracks.stream().filter(track -> name.equalsIgnoreCase(track.title())).toList();
        if (matches.isEmpty()) matches = tracks.stream().filter(track -> matchesName(name, track.title())).toList();
        if (matches.isEmpty()) throw new MusicException(MusicError.AUDIO_NOT_FOUND);
        if (matches.size() != 1) throw new MusicException(MusicError.AUDIO_AMBIGUOUS);
        return matches.getFirst();
    }
    private static boolean matchesName(String query, String filename) {
        return query.equalsIgnoreCase(filename) || stem(query).equalsIgnoreCase(stem(filename));
    }
    private static String stem(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ? name.substring(0, name.length() - 4) : name;
    }
    @Override public CompletableFuture<Track> getTrack(SongId id) {
        if (id.isServerAudio()) return remote.getTrack(id);
        if (!id.isClientLocal()) return CompletableFuture.failedFuture(new MusicException(MusicError.AUDIO_NOT_FOUND));
        return AsyncTasks.submit(worker, () -> files.getTrack(id).orElseThrow(() -> new MusicException(MusicError.AUDIO_NOT_FOUND)));
    }
    /** No file URI or caller-supplied download URL ever reaches the HTTP transport. */
    @Override public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
        return CompletableFuture.failedFuture(new MusicException(MusicError.RESOURCE_UNAVAILABLE));
    }
    public CompletableFuture<PcmAudio> loadAudio(SongId id) {
        if (id.isClientLocal()) return AsyncTasks.submit(worker, () -> {
            var audio = files.readAudio(id);
            return AudioFiles.decode(audio.bytes(), audio.fileName());
        });
        if (!id.isServerAudio()) return CompletableFuture.failedFuture(new MusicException(MusicError.AUDIO_NOT_FOUND));
        var download = remote.download(id);
        var result = new CompletableFuture<PcmAudio>();
        var decoding = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<PcmAudio>>();
        download.whenComplete((bytes, error) -> {
            if (result.isDone()) return;
            if (error != null) { result.completeExceptionally(error); return; }
            var task = AsyncTasks.submit(worker, () -> AudioFiles.decode(bytes,
                    bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' ? "audio.wav" : "audio.mp3"));
            decoding.set(task);
            if (result.isCancelled()) task.cancel(true);
            task.whenComplete((audio, failure) -> {
                if (failure != null) result.completeExceptionally(failure); else result.complete(audio);
            });
        });
        result.whenComplete((value, error) -> {
            if (!result.isCancelled()) return;
            download.cancel(true);
            var task = decoding.get(); if (task != null) task.cancel(true);
        });
        return result;
    }
    public CompletableFuture<Track> upload(Track track) {
        if (!canUpload()) return CompletableFuture.failedFuture(new MusicException(MusicError.SHARED_FORBIDDEN));
        if (!track.id().isClientLocal()) return CompletableFuture.failedFuture(new MusicException(MusicError.AUDIO_NOT_FOUND));
        Object connection = remote.connectionToken();
        return AsyncTasks.submit(worker, () -> files.readAudio(track.id()))
                .thenComposeAsync(data -> {
                    if (closed || connection == null || !Objects.equals(connection, remote.connectionToken()) || !remote.available())
                        throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
                    if (!remote.canUpload()) throw new MusicException(MusicError.SHARED_FORBIDDEN);
                    return remote.upload(data.fileName(), data.bytes());
                }, main);
    }
    @Override public void close() {
        closed = true;
        for (var batch : List.copyOf(imports)) batch.cancel(true);
        worker.shutdownNow();
    }
}
