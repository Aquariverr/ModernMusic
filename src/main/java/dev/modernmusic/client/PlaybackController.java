package dev.modernmusic.client;

import dev.modernmusic.client.audio.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;

/** Public methods and callbacks belong to the client thread; generation rejects stale completions. */
public final class PlaybackController implements AutoCloseable {
    public enum State { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR }
    private static final int MAX_MP3_BYTES = 32 * 1024 * 1024;
    private final MusicProvider provider;
    private final HttpTransport transport;
    private final AudioOutput output;
    private final Executor mainThread;
    private final SongSearchController search;
    private CompletableFuture<SelectionResult> pendingSelection;
    private CompletableFuture<?> pendingSelectionAck;
    private long selectionGeneration;
    private final ThreadPoolExecutor loader = AsyncTasks.executor("modernmusic-loader", 1, 2);
    private final QueueLibrary library = new QueueLibrary();
    private PlaybackQueue queue = library.active().queue();
    private final QueueStore queueStore;
    private boolean queuesReady = true;
    private final Map<SongId, Track> tracks = new HashMap<>();
    private CompletableFuture<?> pending;
    private CompletableFuture<?> pendingLyrics, pendingImport;
    private long importGeneration;
    private String importMessage;
    private int importedCount, importTotal, importSkipped;
    private Lyrics lyrics = Lyrics.EMPTY;
    private String lyricMessage = "modernmusic.lyrics.empty";
    private long generation;
    private boolean closed;
    private State state = State.IDLE;
    private String message = "modernmusic.status.idle";
    private float volume = 0.7f;
    private Track track;
    private PcmAudio bufferedAudio;
    private dev.modernmusic.room.RoomSnapshot shared;
    private java.util.function.BiConsumer<dev.modernmusic.room.RoomCommand.Action, Long> sharedCommands;
    private java.util.function.Function<dev.modernmusic.room.RoomImportRequest, CompletableFuture<dev.modernmusic.room.RoomImportResult>> sharedImporter;
    private boolean applyingShared;
    private long sharedPlaybackRevision = -1, lastCorrection;
    private boolean needsAlignment;
    private boolean synchronizedPaused = true;
    private CompletableFuture<?> pendingMetadata;
    private long metadataGeneration;
    private final Map<SongId, Long> metadataRetry = new HashMap<>();
    private final Set<SongId> localMetadataNeeded = new LinkedHashSet<>();

    public boolean shared() { return shared != null; }
    public MusicPlatform platform() { return provider instanceof MultiPlatformProvider multi ? multi.selected() : MusicPlatform.NETEASE; }
    public void switchPlatform() {
        if (provider instanceof MultiPlatformProvider multi) { multi.select(multi.next()); search.reset(); }
    }
    public LocalAudioProvider localAudio() {
        var source = provider instanceof MultiPlatformProvider multi ? multi.provider(MusicPlatform.LOCAL) : provider;
        return source instanceof LocalAudioProvider local ? local : null;
    }
    public void accountChanged(MusicPlatform platform) {
        if (queue.current().map(id -> id.platform() == platform).orElse(false)) playbackSourceChanged();
    }
    public SharedPlaybackSource sharedSource() {
        var selected = provider instanceof MultiPlatformProvider multi ? multi.provider(platform()) : provider;
        return selected instanceof SharedPlaybackSource source ? source : null;
    }
    public boolean teamRoom() { return shared != null && shared.isTeamRoom(); }
    public String sharedModeKey() { return teamRoom() ? "modernmusic.team.mode" : "modernmusic.room.mode"; }
    public void roomCommands(java.util.function.BiConsumer<dev.modernmusic.room.RoomCommand.Action, Long> commands) { sharedCommands = commands; }
    public void roomImporter(java.util.function.Function<dev.modernmusic.room.RoomImportRequest, CompletableFuture<dev.modernmusic.room.RoomImportResult>> importer) { sharedImporter = importer; }
    private boolean route(dev.modernmusic.room.RoomCommand.Action action, long value) {
        if (!shared() || applyingShared) return false;
        if (sharedCommands != null) sharedCommands.accept(action, value);
        return true;
    }
    /** Shared queue is transient; local libraries and their selected queue never change. */
    public void applyRoom(dev.modernmusic.room.RoomSnapshot snapshot) {
        applyRoom(snapshot, true);
    }
    /** Muting audio must not freeze shared queue membership, import capacity or metadata. */
    public void applyRoom(dev.modernmusic.room.RoomSnapshot snapshot, boolean listening) {
        if (closed) return;
        if (snapshot == null) {
            if (!shared()) return;
            suspend(); shared = null; sharedPlaybackRevision = -1;
            queue = library.active().queue(); pruneTracks(); selectStoppedTrack(); return;
        }
        boolean newRoom = shared == null || !shared.id().equals(snapshot.id()) || !shared.epoch().equals(snapshot.epoch());
        boolean playbackChanged = newRoom || sharedPlaybackRevision != snapshot.playbackRevision();
        boolean controlChanged = newRoom || shared.state() != snapshot.state() || shared.anchorMs() != snapshot.anchorMs()
                || shared.positionMs() != snapshot.positionMs();
        boolean queueChanged = newRoom || shared.queueRevision() != snapshot.queueRevision() || queue.index() != snapshot.index();
        if (newRoom) { suspend(); queue = new PlaybackQueue(); }
        if (queueChanged) {
            queue.restore(snapshot.songs().stream().map(SongId::fromWire).toList(), snapshot.index(), snapshot.mode());
        }
        shared = snapshot; sharedPlaybackRevision = listening ? snapshot.playbackRevision() : -1;
        if (queueChanged) pruneTracks();
        if (!listening) {
            if (pending != null || bufferedAudio != null || state == State.LOADING || state == State.PLAYING || state == State.PAUSED)
                stopLocal();
            state = queue.entries().isEmpty() ? State.IDLE : State.STOPPED;
            track = queue.current().map(tracks::get).orElse(null);
            lyrics = Lyrics.EMPTY; lyricMessage = "modernmusic.lyrics.empty";
            if (queueChanged) loadQueueMetadata();
            return;
        }
        applyingShared = true;
        try {
            if (snapshot.state() == dev.modernmusic.room.RoomSnapshot.State.STOPPED) {
                if (playbackChanged || controlChanged) stop();
                track = queue.current().map(tracks::get).orElse(null);
            }
            else if (playbackChanged) {
                if (!newRoom && bufferedAudio != null && track != null && queue.current().filter(track.id()::equals).isPresent()) {
                    // Replaying the same room song needs a new timeline, not another CDN download/decode.
                    // Account/source changes and leaving the room clear bufferedAudio through suspend().
                    output.load(bufferedAudio, 0, true); synchronizedPaused = true;
                    state = State.LOADING; message = "modernmusic.status.audio_starting";
                } else play(snapshot.index());
                needsAlignment = true;
            }
            if (controlChanged) { needsAlignment = true; if (bufferedAudio != null) output.pause(true); }
        } finally { applyingShared = false; }
        if (queueChanged) loadQueueMetadata();
    }
    private void loadQueueMetadata() {
        if (pendingMetadata != null || closed) return;
        if (localMetadataNeeded.isEmpty() && !shared()) return;
        if (!localMetadataNeeded.isEmpty()) {
            var retained = library.songIds();
            retained.addAll(queue.entries());
            localMetadataNeeded.removeIf(id -> !retained.contains(id) || tracks.containsKey(id));
        }
        long now = System.nanoTime() / 1_000_000;
        var needed = new LinkedHashSet<>(localMetadataNeeded);
        if (shared()) needed.addAll(queue.entries());
        var songs = needed.stream().filter(id -> !tracks.containsKey(id))
                .filter(id -> state != State.LOADING || queue.current().filter(id::equals).isEmpty())
                .filter(id -> now >= metadataRetry.getOrDefault(id, 0L)).limit(50).toList();
        if (songs.isEmpty()) return;
        songs.forEach(id -> metadataRetry.put(id, now + 30_000));
        long token = metadataGeneration;
        var request = provider.getTracks(songs); pendingMetadata = request;
        request.whenComplete((found, error) -> mainThread.execute(() -> {
            if (closed || token != metadataGeneration) return;
            pendingMetadata = null;
            if (error == null && found != null) {
                var currentIds = library.songIds(); currentIds.addAll(queue.entries());
                for (var value : found) if (songs.contains(value.id()) && currentIds.contains(value.id())) tracks.put(value.id(), value);
                if (state == State.STOPPED || state == State.IDLE) track = queue.current().map(tracks::get).orElse(null);
                if (!found.isEmpty()) saveQueues();
            }
        }));
    }
    private void loadAddedMetadata(SongId id) {
        if (tracks.containsKey(id)) return;
        localMetadataNeeded.add(id);
        loadQueueMetadata();
    }
    /** Room controls describe the server timeline even while this client buffers or is muted. */
    public boolean playbackPlaying() { return shared() ? shared.state() == dev.modernmusic.room.RoomSnapshot.State.PLAYING : state == State.PLAYING; }
    public boolean canTogglePlayback() { return !queue.entries().isEmpty() && (shared() || state != State.LOADING); }
    public void synchronizeRoom(long serverNow, long clientNow) {
        if (shared == null || shared.state() == dev.modernmusic.room.RoomSnapshot.State.STOPPED || bufferedAudio == null
                || state == State.ERROR) return;
        boolean pause = shared.state() == dev.modernmusic.room.RoomSnapshot.State.PAUSED || serverNow < shared.anchorMs();
        if (synchronizedPaused && !pause) needsAlignment = true;
        synchronizedPaused = pause;
        long target = Math.min(shared.positionAt(serverNow), bufferedAudio.durationMs());
        long error = Math.abs(output.snapshot().positionMs() - target);
        if (needsAlignment || error > 750 || error > 150 && clientNow - lastCorrection >= 2_000) {
            if (needsAlignment || clientNow - lastCorrection >= 500) {
                output.seek(target); lastCorrection = clientNow; needsAlignment = false;
            }
        }
        output.pause(pause);
    }

    public PlaybackController(MusicProvider provider, HttpTransport transport, AudioOutput output, Executor mainThread) {
        this(provider, transport, output, mainThread, null);
    }
    public PlaybackController(MusicProvider provider, HttpTransport transport, AudioOutput output, Executor mainThread, QueueStore queueStore) {
        this.provider = provider; this.transport = transport; this.output = output; this.mainThread = mainThread;
        this.search = new SongSearchController(provider, mainThread);
        this.queueStore = queueStore;
        if (queueStore != null) {
            queuesReady = false;
            queueStore.load().whenComplete((snapshot, error) -> mainThread.execute(() -> {
                if (closed) return;
                if (error == null && snapshot != null) {
                    library.restore(snapshot); if (!shared()) queue = library.active().queue();
                    var ids = library.songIds();
                    for (var metadata : snapshot.tracks()) if (ids.contains(metadata.id())) tracks.put(metadata.id(), metadata);
                    if (!shared()) selectStoppedTrack();
                }
                queuesReady = true;
            }));
        }
    }
    public boolean queuesReady() { return queuesReady; }
    public QueueLibrary library() { return library; }
    /** The user's selected queue remains available while the room owns active playback. */
    public PlaybackQueue personalQueue() { return library.active().queue(); }
    public SongSearchController search() { return search; }
    public record SearchDestination(String id, String name, java.util.UUID roomEpoch) {
        public boolean shared() { return roomEpoch != null; }
    }
    public record SelectionResult(int added, int total, String message, int processed) {
        public SelectionResult(int added, int total, String message) { this(added, total, message, added); }
        public int skipped() { return processed - added; }
    }
    public java.util.List<SearchDestination> searchDestinations() {
        var destinations = new java.util.ArrayList<SearchDestination>();
        if (shared()) destinations.add(new SearchDestination(shared.id().toString(), shared.name(), shared.epoch()));
        for (var entry : library.queues()) destinations.add(new SearchDestination(entry.id(), entry.name(), null));
        return java.util.List.copyOf(destinations);
    }
    /** Append metadata to an explicitly selected destination without switching queues or starting playback. */
    public CompletableFuture<SelectionResult> addSearchTracks(SearchDestination destination, java.util.List<Track> selection) {
        var songs = java.util.List.copyOf(selection);
        if (closed || !queuesReady || destination == null || songs.isEmpty() || songs.size() > PlaybackQueue.MAX_TRACKS)
            return CompletableFuture.completedFuture(new SelectionResult(0, songs.size(), "modernmusic.search.destination_unavailable"));
        if (!destination.shared()) {
            var target = library.queues().stream().filter(entry -> entry.id().equals(destination.id())).findFirst().orElse(null);
            if (target == null) return CompletableFuture.completedFuture(new SelectionResult(0, songs.size(), "modernmusic.search.destination_unavailable"));
            var appended = target.queue().append(songs.stream().map(Track::id).toList());
            if (appended.processed() > 0) {
                // A duplicate may be an older ID-only entry; keep the freshly resolved metadata too.
                for (Track song : songs.subList(0, appended.processed())) tracks.put(song.id(), song);
                if (target.queue() == queue && queue.index() < 0) selectStoppedTrack();
                pruneTracks();
                if (queueStore != null) queueStore.save(library.snapshot(tracks.values()));
            }
            return CompletableFuture.completedFuture(selectionResult(appended.added(), songs.size(), appended.processed(), ""));
        }
        if (songs.stream().anyMatch(song -> song.id().isClientLocal()))
            return CompletableFuture.completedFuture(new SelectionResult(0, songs.size(), "modernmusic.error.local_audio_only"));
        if (!shared() || !shared.id().toString().equals(destination.id()) || !shared.epoch().equals(destination.roomEpoch()) || sharedImporter == null)
            return CompletableFuture.completedFuture(new SelectionResult(0, songs.size(), "modernmusic.search.destination_unavailable"));
        if (pendingSelection != null || importing())
            return CompletableFuture.completedFuture(new SelectionResult(0, songs.size(), "modernmusic.error.rate_limited"));
        var completion = new CompletableFuture<SelectionResult>(); pendingSelection = completion;
        long token = ++selectionGeneration;
        var request = new dev.modernmusic.room.RoomImportRequest(java.util.UUID.randomUUID(), shared.id(), shared.epoch(),
                songs.stream().map(song -> song.id().toString()).toList(), songs.size());
        try {
            var acknowledgement = sharedImporter.apply(request); pendingSelectionAck = acknowledgement;
            acknowledgement.whenComplete((result, failure) -> mainThread.execute(() -> {
                if (closed || token != selectionGeneration) return;
                pendingSelection = null; pendingSelectionAck = null;
                if (failure != null || result == null || !request.request().equals(result.request()) || result.processedCount() > songs.size()) {
                    completion.complete(new SelectionResult(0, songs.size(), "modernmusic.error.invalid_response")); return;
                }
                for (Track song : songs.subList(0, result.processedCount())) if (queue.contains(song.id())) tracks.put(song.id(), song);
                pruneTracks();
                completion.complete(selectionResult(result.imported(), songs.size(), result.processedCount(), result.message()));
            }));
        } catch (RuntimeException failure) {
            pendingSelection = null; pendingSelectionAck = null;
            completion.complete(new SelectionResult(0, songs.size(), "modernmusic.error.invalid_response"));
        }
        return completion;
    }
    private static SelectionResult selectionResult(int added, int total, int processed, String message) {
        boolean normal = message.isEmpty() || message.equals("modernmusic.status.duplicate") || message.equals("modernmusic.status.queue_full");
        String key = !normal ? message : processed > added
                ? processed < total ? "modernmusic.search.partial_deduplicated" : "modernmusic.search.deduplicated"
                : added == 0 ? "modernmusic.status.queue_full" : added < total ? "modernmusic.search.partial" : "modernmusic.search.added";
        return new SelectionResult(added, total, key, processed);
    }
    public String queueStorageMessage() { return !queuesReady ? "modernmusic.queues.loading" : queueStore != null && queueStore.failed() ? "modernmusic.queues.storage_error" : null; }
    private void saveQueues() {
        pruneTracks();
        if (queueStore != null && queuesReady) queueStore.save(library.snapshot(tracks.values()));
    }
    private void pruneTracks() {
        var retained = library.songIds();
        if (shared()) retained.addAll(queue.entries());
        tracks.keySet().retainAll(retained);
        metadataRetry.keySet().retainAll(retained);
        localMetadataNeeded.retainAll(retained);
    }
    public boolean createQueue(String name) {
        if (closed || !queuesReady || library.queues().size() >= QueueLibrary.MAX_QUEUES) return false;
        QueueLibrary.cleanName(name);
        if (shared()) cancelImport(); else suspend();
        library.create(name);
        if (!shared()) { queue = personalQueue(); selectStoppedTrack(); }
        saveQueues(); return true;
    }
    public void renameQueue(String name) { if (!closed && queuesReady) { library.rename(name); saveQueues(); } }
    public void switchQueue(String id) {
        if (closed || !queuesReady || library.active().id().equals(id) || library.queues().stream().noneMatch(q -> q.id().equals(id))) return;
        if (shared()) cancelImport(); else suspend();
        library.select(id);
        if (!shared()) { queue = personalQueue(); selectStoppedTrack(); }
        saveQueues();
    }
    public boolean removePersonalTrack(int index) {
        if (!shared()) return removeTrack(index);
        if (closed || !queuesReady || index < 0 || index >= personalQueue().entries().size()) return false;
        personalQueue().remove(index); saveQueues(); return true;
    }
    public void clearPersonalQueue() {
        if (!shared()) { clear(); return; }
        if (closed || !queuesReady) return;
        cancelImport(); personalQueue().clear(); saveQueues();
    }
    public void playPersonal(int index) {
        if (!shared()) play(index);
    }
    public boolean removeTrack(int index) {
        if (route(dev.modernmusic.room.RoomCommand.Action.REMOVE, index)) return true;
        if (closed || !queuesReady || index < 0 || index >= queue.entries().size()) return false;
        boolean currentTrack = index == queue.index();
        boolean resume = state == State.PLAYING || state == State.LOADING;
        if (currentTrack) stop();
        queue.remove(index);
        if (currentTrack) {
            if (resume && !queue.entries().isEmpty()) play(queue.index());
            else selectStoppedTrack();
        }
        saveQueues(); return true;
    }
    private void selectStoppedTrack() {
        lyrics = Lyrics.EMPTY; lyricMessage = "modernmusic.lyrics.empty";
        if (queue.index() < 0 && !queue.entries().isEmpty()) queue.select(0);
        track = queue.current().map(tracks::get).orElse(null);
        state = queue.entries().isEmpty() ? State.IDLE : State.STOPPED;
        message = state == State.IDLE ? "modernmusic.status.idle" : "modernmusic.status.stopped";
    }

    public boolean add(String input) { return add(input, false); }
    public boolean addPersonal(String input) { return add(input, true); }
    private boolean add(String input, boolean personal) {
        if (closed || !queuesReady) return false;
        if (platform() == MusicPlatform.LOCAL) return addReference(input, personal);
        if (platform() == MusicPlatform.KUGOU) {
            KugouReferenceParser.song(input);
            return addReference(input, personal);
        }
        if (platform() == MusicPlatform.QQ) return addReference(QqReferenceParser.song(input), personal);
        SongId id = SongReferenceParser.parse(input);
        if (!personal && route(dev.modernmusic.room.RoomCommand.Action.ADD, id.wireValue())) return true;
        var target = personal ? personalQueue() : queue;
        if (target.contains(id)) { loadAddedMetadata(id); message = "modernmusic.status.duplicate"; return false; }
        if (!target.add(id)) { message = "modernmusic.status.queue_full"; return false; }
        saveQueues();
        if (!shared() && target == queue && queue.index() < 0) play(0);
        else loadAddedMetadata(id);
        return true;
    }

    private boolean addReference(String reference, boolean personal) {
        if (pendingImport != null || pendingSelection != null) return false;
        var destination = shared() && !personal ? new SearchDestination(shared.id().toString(), shared.name(), shared.epoch())
                : new SearchDestination(library.active().id(), library.active().name(), null);
        long token = ++importGeneration;
        importMessage = "modernmusic.playlist.loading";
        var request = platform() == MusicPlatform.LOCAL && destination.shared() && localAudio() != null
                ? localAudio().getServerReference(reference) : provider.getReference(reference);
        pendingImport = request;
        request.whenComplete((song, error) -> mainThread.execute(() -> {
            if (closed || token != importGeneration) return;
            pendingImport = null;
            if (error != null) { var failure = unwrap(error); importMessage = failure instanceof MusicException music ? music.error().translationKey() : "modernmusic.error.invalid_response"; return; }
            addSearchTracks(destination, java.util.List.of(song)).thenAccept(result -> mainThread.execute(() -> {
                if (closed || token != importGeneration) return;
                importedCount = result.added(); importTotal = result.total(); importSkipped = result.skipped();
                // PlayerScreen supplies the three import counts; search messages also require a destination name.
                importMessage = switch (result.message()) {
                    case "modernmusic.search.added", "modernmusic.search.partial", "modernmusic.search.deduplicated",
                            "modernmusic.search.partial_deduplicated" -> playlistResultMessage();
                    default -> result.message();
                };
            }));
        }));
        return true;
    }

    public boolean importPlaylist(String input) { return importPlaylist(input, false); }
    public boolean importPersonalPlaylist(String input) { return importPlaylist(input, true); }
    private boolean importPlaylist(String input, boolean personal) {
        if (platform() == MusicPlatform.LOCAL) { importMessage = "modernmusic.error.unsupported_format"; return false; }
        SongId id = switch (platform()) {
            case NETEASE -> SongReferenceParser.parsePlaylist(input);
            case QQ -> QqReferenceParser.playlist(input);
            case KUGOU -> KugouReferenceParser.playlist(input);
            case LOCAL -> throw new IllegalStateException("Local audio has no playlists");
        };
        if (closed || !queuesReady || pendingImport != null || pendingSelection != null) return false;
        var target = personal ? personalQueue() : queue;
        if (target.entries().size() >= PlaybackQueue.MAX_TRACKS) { importMessage = "modernmusic.status.queue_full"; return false; }
        long token = ++importGeneration;
        var importRoom = personal ? null : shared;
        importMessage = "modernmusic.playlist.loading";
        var request = provider.getPlaylist(id); pendingImport = request;
        request.whenComplete((list, error) -> mainThread.execute(() -> {
            if (closed || token != importGeneration) return;
            pendingImport = null;
            if (error != null) {
                Throwable failure = unwrap(error);
                importMessage = failure instanceof MusicException music ? music.error().translationKey() : "modernmusic.error.invalid_response";
                return;
            }
            importedCount = 0; importTotal = list.total(); importSkipped = 0;
            if (importRoom != null && !list.ids().isEmpty()) {
                if (sharedImporter == null) { importMessage = "modernmusic.room.unavailable"; return; }
                var batch = new dev.modernmusic.room.RoomImportRequest(java.util.UUID.randomUUID(), importRoom.id(), importRoom.epoch(),
                        list.ids().stream().limit(PlaybackQueue.MAX_TRACKS).map(SongId::toString).toList(), Math.max(list.total(), list.ids().size()));
                importMessage = "modernmusic.room.import_submitting";
                var acknowledgement = sharedImporter.apply(batch); pendingImport = acknowledgement;
                acknowledgement.whenComplete((result, failure) -> mainThread.execute(() -> {
                    if (closed || token != importGeneration) return;
                    pendingImport = null;
                    if (failure != null) { importMessage = "modernmusic.error.invalid_response"; return; }
                    importedCount = result.imported(); importTotal = result.total(); importSkipped = result.skipped();
                    for (Track value : list.tracks()) if (queue.contains(value.id())) tracks.put(value.id(), value);
                    pruneTracks();
                    importMessage = !result.message().isEmpty() && importSkipped == 0 ? result.message() : playlistResultMessage();
                }));
                return;
            }
            if (importRoom != null) { importMessage = "modernmusic.playlist.empty"; return; }
            var appended = target.append(list.ids()); importedCount = appended.added(); importSkipped = appended.skipped();
            for (Track value : list.tracks()) if (target.contains(value.id())) tracks.put(value.id(), value);
            localMetadataNeeded.addAll(list.ids().stream().filter(target::contains).toList());
            saveQueues();
            importMessage = list.ids().isEmpty() ? "modernmusic.playlist.empty" : playlistResultMessage();
            if (importedCount > 0 && !shared() && target == queue && queue.index() < 0) play(0);
        }));
        return true;
    }
    public boolean importing() { return pendingImport != null; }
    public String importMessage() { return importMessage; }
    public int importedCount() { return importedCount; }
    public int importTotal() { return importTotal; }
    public int importSkipped() { return importSkipped; }
    private String playlistResultMessage() {
        if (importSkipped > 0) return importedCount + importSkipped < importTotal
                ? "modernmusic.playlist.partial_deduplicated" : "modernmusic.playlist.deduplicated";
        return importedCount == 0 ? "modernmusic.status.queue_full" : importedCount < importTotal
                ? "modernmusic.playlist.partial" : "modernmusic.playlist.imported";
    }
    public void dismissImportMessage() { if (!importing()) importMessage = null; }
    public Lyrics lyrics() { return lyrics; }
    public String lyricMessage() { return lyricMessage; }
    public void cycleMode() { if (route(dev.modernmusic.room.RoomCommand.Action.MODE, 0)) return; if (!closed && queuesReady) { queue.mode(queue.mode().next()); saveQueues(); } }

    public void play(int index) {
        if (route(dev.modernmusic.room.RoomCommand.Action.PLAY, index)) return;
        if (closed || !queuesReady) return;
        var selected = queue.select(index);
        if (selected.isEmpty() || closed) return;
        if (!shared() && bufferedAudio != null && track != null && track.id().equals(selected.get())) {
            // A manual replay keeps the current source's PCM and in-flight/completed lyrics.
            // Source changes, cleanup, failures and queue switches invalidate PCM via cancelPending().
            output.load(bufferedAudio, 0, false);
            state = State.LOADING; message = "modernmusic.status.audio_starting";
            saveQueues(); return;
        }
        cancelPending(); output.stop(); track = tracks.get(selected.get());
        saveQueues();
        lyrics = Lyrics.EMPTY; lyricMessage = "modernmusic.lyrics.loading";
        state = State.LOADING; message = "modernmusic.status.details";
        long token = generation;
        pending = provider.getTrack(selected.get());
        @SuppressWarnings("unchecked") CompletableFuture<Track> details = (CompletableFuture<Track>) pending;
        details.whenComplete((value, error) -> mainThread.execute(() -> {
            if (!current(token)) return;
            if (error != null) { fail(error); return; }
            track = value; tracks.put(value.id(), value); message = "modernmusic.status.resolving";
            saveQueues();
            resolveAndLoad(value.id(), token, true);
            if (!current(token)) return;
            var request = provider.getLyrics(value.id()); pendingLyrics = request;
            request.whenComplete((text, failure) -> mainThread.execute(() -> {
                if (!current(token)) return;
                pendingLyrics = null; lyrics = failure == null ? text : Lyrics.EMPTY;
                lyricMessage = failure == null ? "modernmusic.lyrics.empty" : "modernmusic.lyrics.failed";
            }));
        }));
    }

    private void resolveAndLoad(SongId id, long token, boolean canRefresh) {
        if (id.platform() == MusicPlatform.LOCAL) {
            var local = localAudio();
            if (local == null || shared() && id.isClientLocal()) { fail(new MusicException(MusicError.RESOURCE_UNAVAILABLE)); return; }
            message = "modernmusic.status.buffering";
            var request = local.loadAudio(id); pending = request;
            request.whenComplete((audio, error) -> mainThread.execute(() -> {
                if (!current(token)) return;
                pending = null;
                if (error != null) { fail(error); return; }
                bufferedAudio = audio; output.load(audio, 0, shared()); needsAlignment = shared(); synchronizedPaused = shared();
                state = State.LOADING; message = "modernmusic.status.audio_starting";
            }));
            return;
        }
        CompletableFuture<PlaybackResource> resource = canRefresh ? provider.resolvePlayback(id) : provider.refreshPlayback(id);
        pending = resource;
        resource.whenComplete((resolved, resolveError) -> mainThread.execute(() -> {
            if (!current(token)) return;
            if (resolveError != null) { fail(resolveError); return; }
            message = "modernmusic.status.buffering";
            CompletableFuture<PcmAudio> decoded = AsyncTasks.submit(loader, () -> {
                if (!resolved.expiresAt().isAfter(Instant.now())) throw new MusicException(MusicError.RESOURCE_UNAVAILABLE);
                byte[] bytes = transport.get(ProviderUris.audio(resolved.id().platform(), resolved.uri().toString()), MAX_MP3_BYTES);
                return new Mp3Decoder().decode(bytes);
            });
            pending = decoded;
            decoded.whenComplete((audio, decodeError) -> mainThread.execute(() -> {
                if (!current(token)) return;
                pending = null;
                if (decodeError != null) {
                    if (canRefresh && unwrap(decodeError) instanceof MusicException failure
                            && failure.error() == MusicError.RESOURCE_UNAVAILABLE) {
                        message = "modernmusic.status.refreshing_resource";
                        org.slf4j.LoggerFactory.getLogger(PlaybackController.class).info("Refreshing rejected or expired playback resource once");
                        resolveAndLoad(id, token, false);
                    } else fail(decodeError);
                    return;
                }
                bufferedAudio = audio; output.load(audio, 0, shared()); needsAlignment = shared(); synchronizedPaused = shared(); state = State.LOADING; message = "modernmusic.status.audio_starting";
            }));
        }));
    }

    public void tick(float gameVolume) {
        if (closed) return;
        loadQueueMetadata();
        output.volume(volume * Math.clamp(gameVolume, 0, 1));
        if (state == State.IDLE || state == State.STOPPED || state == State.ERROR) return;
        switch (output.snapshot().state()) {
            case PLAYING -> { state = State.PLAYING; message = "modernmusic.status.playing"; }
            case PAUSED -> { state = State.PAUSED; message = "modernmusic.status.paused"; }
            case ERROR -> fail(new MusicException(MusicError.AUDIO_DEVICE));
            case FINISHED -> { if (!shared()) advance(true); }
            default -> { }
        }
    }

    public void togglePause() {
        if (closed) return;
        if (shared()) { route(shared.state() == dev.modernmusic.room.RoomSnapshot.State.STOPPED
                ? dev.modernmusic.room.RoomCommand.Action.PLAY : dev.modernmusic.room.RoomCommand.Action.PAUSE, Math.max(0, queue.index())); return; }
        if (state == State.PLAYING || state == State.PAUSED) {
            boolean pause = state == State.PLAYING;
            output.pause(pause); state = pause ? State.PAUSED : State.PLAYING;
        } else if (state != State.LOADING && !queue.entries().isEmpty()) play(Math.max(0, queue.index()));
    }

    public void next() {
        if (closed) return;
        if (route(dev.modernmusic.room.RoomCommand.Action.NEXT, 0)) return;
        advance(false);
    }
    private void advance(boolean automatic) {
        int next = queue.following(automatic);
        if (automatic && next >= 0 && next == queue.index() && bufferedAudio != null) {
            output.load(bufferedAudio); state = State.LOADING; message = "modernmusic.status.audio_starting";
        } else if (next >= 0) play(next); else stop();
    }

    public void stop() { if (route(dev.modernmusic.room.RoomCommand.Action.STOP, 0)) return; stopLocal(); }
    private void stopLocal() { cancelPending(); output.stop(); state = State.STOPPED; message = "modernmusic.status.stopped"; }
    public void clear() {
        if (shared() && !applyingShared) { cancelImport(); route(dev.modernmusic.room.RoomCommand.Action.CLEAR, 0); return; }
        if (closed || !queuesReady) return;
        suspend(); queue.clear(); track = null; state = State.IDLE; message = "modernmusic.status.idle"; saveQueues();
    }
    /** Exit world / account changes cancel playback and imports, but never erase the user's saved queues. */
    public void suspend() {
        metadataGeneration++;
        if (pendingMetadata != null) { pendingMetadata.cancel(true); pendingMetadata = null; }
        metadataRetry.clear();
        localMetadataNeeded.clear();
        search.reset();
        cancelImport();
        lyrics = Lyrics.EMPTY; lyricMessage = "modernmusic.lyrics.empty";
        stopLocal(); track = null;
    }
    private void cancelImport() {
        selectionGeneration++;
        if (pendingSelectionAck != null) { pendingSelectionAck.cancel(true); pendingSelectionAck = null; }
        if (pendingSelection != null) { pendingSelection.cancel(false); pendingSelection = null; }
        // Import callbacks belong to the source and connection that initiated them.
        importGeneration++;
        if (pendingImport != null) { pendingImport.cancel(true); pendingImport = null; }
        importMessage = null;
    }
    public void audioReloaded() {
        if (closed) return;
        search.reset();
        cancelImport(); lyrics = Lyrics.EMPTY; lyricMessage = "modernmusic.lyrics.empty";
        stopLocal(); message = "modernmusic.status.audio_reset";
    }
    /** Releases transient audio/lyrics. The caller mutes local room listening before clearing. */
    public void clearTemporaryCache() {
        if (closed) return;
        suspend(); pruneTracks(); message = "modernmusic.cache.cleared";
    }
    public long bufferedBytes() { return bufferedAudio == null ? 0 : bufferedAudio.samples().length; }
    /** Rejoin the existing timeline after changing credential source, without issuing room controls. */
    public void playbackSourceChanged() { suspend(); sharedPlaybackRevision = -1; }
    public void seek(long positionMs) { if (route(dev.modernmusic.room.RoomCommand.Action.SEEK, positionMs)) return; if (state == State.PLAYING || state == State.PAUSED) output.seek(positionMs); }
    public void volume(float value) { volume = Math.clamp(value, 0, 1); }
    public float volume() { return volume; }
    public State state() { return state; }
    public boolean suppressesGameMusic() {
        AudioOutput.State audio = output.snapshot().state();
        return state == State.PLAYING || state == State.LOADING
                && (audio == AudioOutput.State.STARTING || audio == AudioOutput.State.PLAYING);
    }
    public String message() { return shared() && message.equals("modernmusic.status.playing")
            ? (teamRoom() ? "modernmusic.team.playing" : "modernmusic.room.playing") : message; }
    public Track track() { return track; }
    public Track track(SongId id) { return tracks.get(id); }
    public PlaybackQueue queue() { return queue; }
    public AudioOutput.Snapshot audio() { return output.snapshot(); }

    /** Decoded timing for this room playback, independent of delayed audio-device snapshots. */
    public long roomDurationMs() {
        return shared != null && shared.state() != dev.modernmusic.room.RoomSnapshot.State.STOPPED
                && sharedPlaybackRevision == shared.playbackRevision() && bufferedAudio != null && track != null
                && queue.current().filter(track.id()::equals).isPresent() ? bufferedAudio.durationMs() : 0;
    }

    /** Samples existing decoded audio on the UI thread; paused/loading/stopped players settle to silence. */
    public void sampleWaveform(float[] levels) {
        var audio = output.snapshot();
        WaveformSampler.sample(state == State.PLAYING && audio.state() == AudioOutput.State.PLAYING ? bufferedAudio : null,
                audio.positionMs(), levels);
    }

    private void cancelPending() {
        generation++;
        bufferedAudio = null;
        if (pending != null) { pending.cancel(true); pending = null; }
        if (pendingLyrics != null) { pendingLyrics.cancel(true); pendingLyrics = null; }
        loader.purge();
    }
    private boolean current(long token) { return !closed && token == generation; }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
    private void fail(Throwable error) {
        error = unwrap(error);
        MusicError category = error instanceof MusicException failure ? failure.error() : MusicError.INTERNAL_ERROR;
        // Log only stage, category, class and code locations; exception messages may contain signed URLs.
        LoggerFactory.getLogger(PlaybackController.class).warn(
                "Playback failed at {}: {} ({}), locations={}", message, category,
                error.getClass().getName(), java.util.Arrays.stream(error.getStackTrace()).limit(6).toList());
        cancelPending(); output.stop(); state = State.ERROR;
        message = category.translationKey();
    }
    @Override public void close() {
        if (closed) return;
        suspend(); closed = true; loader.shutdownNow(); tracks.clear();
        try { provider.close(); }
        finally {
            try { output.close(); }
            finally {
                try { if (queueStore != null) queueStore.close(); }
                finally { if (transport instanceof BoundedHttpTransport http) http.close(); }
            }
        }
    }
}
