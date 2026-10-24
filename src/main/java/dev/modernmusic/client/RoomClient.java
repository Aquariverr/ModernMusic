package dev.modernmusic.client;

import dev.modernmusic.network.RoomNetwork;
import dev.modernmusic.room.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import dev.modernmusic.network.ForgeNetwork;

/** Client-thread room state and clock synchronization. */
public final class RoomClient {
    private static RoomClient instance;
    public static RoomClient instance() { return instance; }
    private final PlaybackController player;
    private ClockEstimator clock = new ClockEstimator();
    private RoomUpdate update = initialUpdate();
    private RoomReplica replica = new RoomReplica();
    private long nextPing, sent = -1, durationAttempt;
    private long lastReceived;
    private String message = "";
    private boolean listening = true;
    private boolean timedOut;
    private static RoomUpdate initialUpdate() {
        return new RoomUpdate(0, null, List.of(), "", new TeamStatus(TeamStatus.State.UNAVAILABLE, ""));
    }
    private record ImportWait(java.util.concurrent.CompletableFuture<RoomImportResult> future, long deadline, int total) {}
    private final Map<UUID, ImportWait> pendingImports = new HashMap<>();
    public RoomClient(PlaybackController player) {
        this.player = player; instance = this;
        player.roomCommands((action, value) -> command(action, "", value));
        player.roomImporter(this::importSongs);
        RoomNetwork.clientImport = result -> {
            var pending = pendingImports.remove(result.request());
            if (pending != null) pending.future().complete(result);
        };
        RoomNetwork.clientUpdate = this::receive;
        RoomNetwork.clientClock = data -> {
            if (available() && data.sent() == sent) { clock.sample(sent, RoomNetwork.now(), data.server()); sent = -1; }
        };
    }
    public boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeNetwork.available(connection.getConnection());
    }
    private java.util.concurrent.CompletableFuture<RoomImportResult> importSongs(RoomImportRequest request) {
        var future = new java.util.concurrent.CompletableFuture<RoomImportResult>();
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !ForgeNetwork.available(connection.getConnection())) {
            future.complete(new RoomImportResult(request.request(), 0, request.total(), "modernmusic.room.unavailable")); return future;
        }
        if (!isCurrentRoom(request.room(), request.epoch())) {
            future.complete(new RoomImportResult(request.request(), 0, request.total(), "modernmusic.search.destination_unavailable")); return future;
        }
        if (!pendingImports.isEmpty()) {
            future.complete(new RoomImportResult(request.request(), 0, request.total(), "modernmusic.room.error.rate")); return future;
        }
        if (!canRequest()) {
            future.complete(new RoomImportResult(request.request(), 0, request.total(), "modernmusic.room.error.request_permission")); return future;
        }
        message = "";
        pendingImports.put(request.request(), new ImportWait(future, RoomNetwork.now() + 10_000, request.total()));
        future.whenComplete((result, error) -> pendingImports.remove(request.request()));
        ForgeNetwork.sendToServer(new RoomNetwork.ImportPayload(request));
        return future;
    }
    public RoomUpdate update() { return update; }
    public PlaybackController player() { return player; }
    public boolean isCurrentRoom(UUID room, UUID epoch) {
        return available() && update.room() != null && update.room().id().equals(room) && update.room().epoch().equals(epoch);
    }
    /** Reuse the player import pipeline only with an explicitly pinned shared destination. */
    public boolean importReference(UUID room, UUID epoch, String input, boolean playlist) {
        if (!isCurrentRoom(room, epoch) || player.searchDestinations().stream().noneMatch(destination ->
                destination.shared() && destination.id().equals(room.toString()) && destination.roomEpoch().equals(epoch))) {
            message = "modernmusic.search.destination_unavailable"; return false;
        }
        if (!canRequest()) { message = "modernmusic.room.error.request_permission"; return false; }
        if (player.importing() || !pendingImports.isEmpty()) { message = "modernmusic.error.rate_limited"; return false; }
        message = "";
        if (!playlist && player.platform() == dev.modernmusic.domain.MusicPlatform.NETEASE) {
            var song = dev.modernmusic.domain.SongReferenceParser.parse(input);
            if (update.room().songs().contains(song.toString())) { message = "modernmusic.room.error.duplicate"; return false; }
            if (update.room().songs().size() >= dev.modernmusic.domain.PlaybackQueue.MAX_TRACKS) {
                message = "modernmusic.status.queue_full"; return false;
            }
            addSong(song); return true;
        }
        return playlist ? player.importPlaylist(input) : player.add(input);
    }
    /** The authoritative timeline also advances while this client has muted room audio. */
    public long positionMs() {
        var room = update.room();
        return room == null ? 0 : room.positionAt(clock.ready() ? clock.serverNow(RoomNetwork.now()) : room.anchorMs());
    }
    /** String transport preserves all provider IDs, including Kugou hashes. */
    public void addSong(dev.modernmusic.domain.SongId song) {
        if (song.isClientLocal()) { message = "modernmusic.error.local_audio_only"; return; }
        var room = update.room();
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        if (room == null) { message = "modernmusic.room.join_to_add"; return; }
        if (!canRequest()) { message = "modernmusic.room.error.request_permission"; return; }
        if (room.songs().contains(song.toString())) { message = "modernmusic.room.error.duplicate"; return; }
        var request = new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of(song.toString()), 1);
        importSongs(request).whenComplete((result, error) -> {
            var current = update.room();
            if (current == null || !current.id().equals(room.id()) || !current.epoch().equals(room.epoch())) return;
            message = error != null || result == null ? "modernmusic.error.invalid_response"
                    : result.imported() > 0 ? "modernmusic.room.song_added"
                    : result.message().isEmpty() ? "modernmusic.room.error.duplicate" : result.message();
        });
    }
    public String message() { return available() ? message : "modernmusic.room.unavailable"; }
    public boolean listening() { return listening; }
    public boolean canRequest() {
        var player = Minecraft.getInstance().player;
        return available() && player != null && update.room() != null && update.room().canRequest(player.getUUID());
    }
    public void toggleListening() {
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        listening = !listening;
        if (!listening) player.suspend();
        else { player.applyRoom(null); player.applyRoom(update.room()); }
    }
    private void receive(RoomUpdate value) {
        if (!available() || !replica.accept(value)) return;
        lastReceived = RoomNetwork.now();
        if (timedOut) { player.applyRoom(null); timedOut = false; }
        if (!value.message().isEmpty()) message = value.message();
        if (!value.invitations().equals(update.invitations()) && !value.invitations().isEmpty()) {
            var game = Minecraft.getInstance();
            if (game.player != null) game.player.displayClientMessage(Component.translatable("modernmusic.room.invited"), false);
        }
        update = value;
        player.applyRoom(value.room(), listening);
    }
    public void command(RoomCommand.Action action, String text, long value) {
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        var room = update.room();
        if (room != null && (Minecraft.getInstance().player == null || !room.allows(Minecraft.getInstance().player.getUUID(), action))) {
            message = "modernmusic.room.error." + action.permissionError(); return;
        }
        if (action == RoomCommand.Action.CREATE && room == null) listening = true;
        send(new RoomCommand(action, room == null ? null : room.id(), room == null ? null : room.epoch(),
                room == null ? -1 : room.revision(), text, value));
    }
    public void join(RoomUpdate.Invitation invitation) {
        join(invitation.room(), invitation.epoch());
    }
    public void join(RoomUpdate.SavedRoom room) {
        join(room.room(), room.epoch());
    }
    private void join(UUID room, UUID epoch) {
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        listening = true;
        send(new RoomCommand(RoomCommand.Action.JOIN, room, epoch, -1, "", 0));
    }
    public boolean canDissolve(UUID room, UUID epoch) {
        var local = Minecraft.getInstance().player;
        if (!available() || local == null) return false;
        var current = update.room();
        if (current != null && current.id().equals(room) && current.epoch().equals(epoch))
            return current.allows(local.getUUID(), RoomCommand.Action.DISSOLVE);
        return update.savedRooms().stream().anyMatch(saved -> saved.room().equals(room) && saved.epoch().equals(epoch)
                && !saved.isPublicRoom() && saved.owner().equals(local.getUUID()));
    }
    public void dissolve(UUID room, UUID epoch) {
        if (!canDissolve(room, epoch)) { message = "modernmusic.room.error.owner_permission"; return; }
        long revision = isCurrentRoom(room, epoch) ? update.room().revision() : -1;
        send(new RoomCommand(RoomCommand.Action.DISSOLVE, room, epoch, revision, "", 0));
    }
    public void joinTeam() {
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        listening = true;
        send(new RoomCommand(RoomCommand.Action.JOIN_TEAM, null, null, -1, "", 0));
    }
    private void send(RoomCommand command) {
        if (!available()) { message = "modernmusic.room.unavailable"; return; }
        message = ""; ForgeNetwork.sendToServer(new RoomNetwork.CommandPayload(RoomJson.encode(command)));
    }
    public void tick() {
        if (instance != this) return;
        if (!available()) {
            if (update.sequence() != 0 || sent != -1 || !pendingImports.isEmpty()) disconnect();
            return;
        }
        long time = RoomNetwork.now();
        for (var pending : List.copyOf(pendingImports.entrySet())) if (time >= pending.getValue().deadline()) {
            pending.getValue().future().complete(new RoomImportResult(pending.getKey(), 0, pending.getValue().total(), "modernmusic.room.import_timeout"));
        }
        if (time >= nextPing) { sent = time; nextPing = time + (clock.ready() ? 5_000 : 500); ForgeNetwork.sendToServer(new RoomNetwork.ClockPayload(sent, 0)); }
        if (update.room() == null || !listening) return;
        if (lastReceived > 0 && time - lastReceived > 10_000) {
            if (!timedOut) { player.suspend(); timedOut = true; }
            message = "modernmusic.room.timeout"; return;
        }
        if (clock.ready()) player.synchronizeRoom(clock.serverNow(time), time);
        var room = update.room();
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null) return;
        long duration = player.audio().durationMs();
        if (time - durationAttempt >= 1_000 && room.canReportDuration(localPlayer.getUUID())
                && duration > 0 && duration <= RoomDurationReport.MAX_DURATION_MS
                && player.bufferedBytes() > 0 && player.track() != null
                && player.track().id().toString().equals(room.songs().get(room.index()))) {
            durationAttempt = time;
            ForgeNetwork.sendToServer(new RoomNetwork.DurationPayload(new RoomDurationReport(room.id(), room.epoch(),
                    room.playbackRevision(), room.songs().get(room.index()), duration)));
        }
    }
    public void disconnect() {
        for (var pending : List.copyOf(pendingImports.values())) pending.future().cancel(false);
        pendingImports.clear();
        player.applyRoom(null); update = initialUpdate();
        replica = new RoomReplica(); sent = -1; nextPing = lastReceived = durationAttempt = 0; timedOut = false;
        clock = new ClockEstimator(); message = ""; listening = true;
    }
}
