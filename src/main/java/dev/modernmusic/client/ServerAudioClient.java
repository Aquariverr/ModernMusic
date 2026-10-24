package dev.modernmusic.client;

import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.domain.*;
import dev.modernmusic.network.AudioNetwork;
import dev.modernmusic.network.AudioNetwork.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.server.AudioTransfer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Current-connection audio catalogue and bounded transfers; discarded completely on disconnect. */
public final class ServerAudioClient {
    private static final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private static List<Track> tracks = List.of();
    private static UUID session;
    private static UUID connectionToken = UUID.randomUUID();
    private static boolean uploadAllowed;
    private static CompletableFuture<List<Track>> refreshing;
    private ServerAudioClient() {}

    public static synchronized void initialize() {
        AudioNetwork.clientState = ServerAudioClient::state;
        AudioNetwork.clientReply = ServerAudioClient::receive;
    }
    private static boolean transportAvailable() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(CommandPayload.TYPE) && connection.hasChannel(StatePayload.TYPE) && connection.hasChannel(ReplyPayload.TYPE);
    }
    public static synchronized boolean available() { return session != null && transportAvailable(); }
    public static synchronized UUID connectionToken() { return available() ? connectionToken : null; }
    public static synchronized boolean canUpload() { return available() && uploadAllowed; }
    public static synchronized List<Track> catalog() { return tracks; }
    public static synchronized CompletableFuture<List<Track>> refresh() {
        if (!available()) return failed(MusicError.RESOURCE_UNAVAILABLE);
        if (refreshing != null && !refreshing.isDone()) return refreshing;
        Pending request = create(Command.CATALOG, "");
        if (request == null) return failed(MusicError.RATE_LIMITED);
        refreshing = request.future.thenApply(value -> {
            @SuppressWarnings("unchecked") List<Track> result = (List<Track>) value;
            return result;
        });
        send(request, Command.CATALOG, 0, 0, new byte[0]);
        return refreshing;
    }
    public static CompletableFuture<Track> getTrack(SongId song) {
        synchronized (ServerAudioClient.class) {
            if (!song.isServerAudio() || !available()) return failed(MusicError.RESOURCE_UNAVAILABLE);
            for (Track track : tracks) if (track.id().equals(song)) return CompletableFuture.completedFuture(track);
        }
        return refresh().thenApply(list -> list.stream().filter(track -> track.id().equals(song)).findFirst()
                .orElseThrow(() -> new MusicException(MusicError.RESOURCE_UNAVAILABLE)));
    }
    public static synchronized CompletableFuture<byte[]> download(SongId song) {
        if (!available() || !song.isServerAudio()) return failed(MusicError.RESOURCE_UNAVAILABLE);
        Pending request = create(Command.DOWNLOAD, song.toString());
        if (request == null) return failed(MusicError.RATE_LIMITED);
        send(request, Command.DOWNLOAD, 0, 0, new byte[0]);
        return cancellable(request, byte[].class);
    }
    public static CompletableFuture<Track> upload(Track local, byte[] bytes) {
        if (!local.id().isClientLocal()) return failed(MusicError.RESOURCE_UNAVAILABLE);
        return upload(local.title(), bytes);
    }
    public static synchronized CompletableFuture<Track> upload(String filename, byte[] bytes) {
        if (!canUpload()) return failed(MusicError.SHARED_FORBIDDEN);
        try { AudioFiles.validateFileName(filename); } catch (MusicException error) { return CompletableFuture.failedFuture(error); }
        if (bytes == null || bytes.length == 0 || bytes.length > AudioFiles.MAX_AUDIO_BYTES) return failed(MusicError.RESOURCE_TOO_LARGE);
        if (pending.values().stream().anyMatch(request -> request.command == Command.UPLOAD_BEGIN)) return failed(MusicError.RATE_LIMITED);
        Pending request = create(Command.UPLOAD_BEGIN, filename);
        if (request == null) return failed(MusicError.RATE_LIMITED);
        request.upload = bytes.clone();
        send(request, Command.UPLOAD_BEGIN, bytes.length, 0, new byte[0]);
        return cancellable(request, Track.class);
    }
    public static synchronized void disconnect() {
        session = null; connectionToken = UUID.randomUUID(); uploadAllowed = false; tracks = List.of(); refreshing = null;
        var abandoned = List.copyOf(pending.values()); pending.clear();
        for (Pending request : abandoned) { request.clear(); request.future.completeExceptionally(new MusicException(MusicError.RESOURCE_UNAVAILABLE)); }
    }
    public static synchronized void tick() {
        if (session != null && !transportAvailable()) { disconnect(); return; }
        long now = System.nanoTime() / 1_000_000;
        for (Pending request : List.copyOf(pending.values())) {
            if (request.retry == null ? now - request.touched > AudioTransfer.TIMEOUT_MS : request.retry.timedOut(now, request.touched)) {
                cancel(request, MusicError.NETWORK_ERROR); continue;
            }
            if (request.retry != null && request.retry.poll(now)) {
                send(request, Command.DOWNLOAD, 0, 0, new byte[0]); request.touched = now;
            }
            if (request.command == Command.UPLOAD_BEGIN && request.ready && !request.sentEnd) {
                if (!canUpload()) { cancel(request, MusicError.SHARED_FORBIDDEN); continue; }
                for (int count = 0; count < 4 && request.offset < request.upload.length; count++) {
                    int end = Math.min(request.upload.length, request.offset + AudioTransfer.CHUNK_BYTES);
                    send(request, Command.UPLOAD_CHUNK, request.upload.length, request.offset, Arrays.copyOfRange(request.upload, request.offset, end));
                    request.offset = end; request.touched = now;
                }
                if (request.offset == request.upload.length) {
                    request.sentEnd = true; send(request, Command.UPLOAD_END, request.upload.length, request.offset, new byte[0]); request.upload = null;
                }
            }
        }
    }
    private static synchronized void state(StatePayload value) {
        if (!transportAvailable()) return;
        if (!value.session().equals(session)) { disconnect(); session = value.session(); }
        uploadAllowed = value.canUpload();
        if (!uploadAllowed) for (Pending request : List.copyOf(pending.values()))
            if (request.command == Command.UPLOAD_BEGIN) cancel(request, MusicError.SHARED_FORBIDDEN);
    }
    private static synchronized void receive(ReplyPayload value) {
        if (!available() || !session.equals(value.session())) return;
        Pending request = pending.get(value.request()); if (request == null) return;
        request.touched = System.nanoTime() / 1_000_000;
        if (request.retry != null && request.retry.expired(request.touched)) { cancel(request, MusicError.NETWORK_ERROR); return; }
        try {
            switch (value.reply()) {
                case ERROR -> {
                    MusicError error = value.error() == null ? MusicError.INVALID_RESPONSE : value.error();
                    if (error == MusicError.RATE_LIMITED && request.retry != null && request.download == null) {
                        if (!request.retry.defer(request.touched)) fail(request, MusicError.NETWORK_ERROR);
                    } else fail(request, error);
                }
                case CATALOG -> {
                    require(request.command == Command.CATALOG); tracks = List.copyOf(value.tracks()); complete(request, tracks);
                }
                case DOWNLOAD_BEGIN -> {
                    require(request.command == Command.DOWNLOAD && request.download == null && value.tracks().size() == 1
                            && value.tracks().getFirst().id().toString().equals(request.value));
                    request.download = new AudioTransfer(value.total(), true, request.touched);
                    request.retry.begin(request.touched);
                }
                case DOWNLOAD_CHUNK -> {
                    require(request.command == Command.DOWNLOAD && request.download != null && value.total() == request.download.total());
                    request.download.append(value.offset(), value.bytes(), true, request.touched);
                    if (request.download.received() == request.download.total()) {
                        byte[] bytes = request.download.finish(true, request.touched); request.download = null; complete(request, bytes);
                    }
                }
                case UPLOAD_READY -> {
                    require(request.command == Command.UPLOAD_BEGIN && !request.ready && request.upload != null && value.total() == request.upload.length);
                    request.ready = true;
                }
                case UPLOADED -> {
                    require(request.command == Command.UPLOAD_BEGIN && request.sentEnd && value.tracks().size() == 1);
                    var track = value.tracks().getFirst(); var updated = new ArrayList<>(tracks); updated.removeIf(item -> item.id().equals(track.id())); updated.add(track);
                    tracks = List.copyOf(updated); complete(request, track);
                }
            }
        } catch (RuntimeException error) { cancel(request, error instanceof MusicException music ? music.error() : MusicError.INVALID_RESPONSE); }
    }
    private static Pending create(Command command, String value) {
        if (pending.size() >= 4) return null;
        Pending request = new Pending(command, value); pending.put(request.id, request); return request;
    }
    private static <T> CompletableFuture<T> cancellable(Pending request, Class<T> type) {
        var result = request.future.thenApply(type::cast);
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) synchronized (ServerAudioClient.class) {
                if (pending.get(request.id) == request) cancel(request, MusicError.RESOURCE_UNAVAILABLE);
            }
        });
        return result;
    }
    private static void send(Pending request, Command command, int total, int offset, byte[] bytes) {
        UUID current = session;
        Minecraft.getInstance().execute(() -> {
            synchronized (ServerAudioClient.class) {
                if (current != null && current.equals(session) && transportAvailable())
                    PacketDistributor.sendToServer(new CommandPayload(current, request.id, command, request.value, total, offset, bytes));
            }
        });
    }
    private static void cancel(Pending request, MusicError error) { send(request, Command.CANCEL, 0, 0, new byte[0]); fail(request, error); }
    private static void fail(Pending request, MusicError error) { pending.remove(request.id); request.clear(); request.future.completeExceptionally(new MusicException(error)); }
    private static void complete(Pending request, Object value) { pending.remove(request.id); request.clear(); request.future.complete(value); }
    private static void require(boolean valid) { if (!valid) throw new MusicException(MusicError.INVALID_RESPONSE); }
    private static <T> CompletableFuture<T> failed(MusicError error) { return CompletableFuture.failedFuture(new MusicException(error)); }
    private static final class Pending {
        final UUID id = UUID.randomUUID(); final Command command; final String value;
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final AudioDownloadRetry retry;
        long touched = System.nanoTime() / 1_000_000; boolean ready, sentEnd; int offset; byte[] upload; AudioTransfer download;
        Pending(Command command, String value) {
            this.command = command; this.value = value; retry = command == Command.DOWNLOAD ? new AudioDownloadRetry(touched) : null;
        }
        void clear() { if (retry != null) retry.cancel(); upload = null; if (download != null) download.clear(); download = null; }
    }
}
