package dev.modernmusic.audit;

import dev.modernmusic.domain.Track;
import dev.modernmusic.network.*;
import dev.modernmusic.room.*;
import dev.modernmusic.server.ServerAudioStore;
import io.netty.channel.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;

@Mod(value="modernmusic_audit", dist=Dist.DEDICATED_SERVER)
public final class AuditServer {
    private AuditMetrics metrics;
    private SharedAuditTransport shared;
    private long tickStart, nextPoll, deadline;
    private boolean finished;
    private volatile String phase = "startup";
    private final Queue<ObservedEvent> events = new ConcurrentLinkedQueue<>();
    private record ObservedEvent(String name, Map<String, Object> values) {}
    private record TransferKey(UUID player, UUID request) {}
    private static final class Download {
        final String song;
        final long requested = System.nanoTime();
        volatile long begun;
        volatile int bytes;
        Download(String song) { this.song = song; }
    }
    private final Map<TransferKey, Download> downloads = new ConcurrentHashMap<>();
    private final Map<UUID, RoomSnapshot> playerRooms = new ConcurrentHashMap<>();
    private final Map<UUID, RoomSnapshot> lastTimeline = new ConcurrentHashMap<>();
    private final Map<String, RoomUpdate> decoded = new IdentityHashMap<>();
    private final Deque<String> payloadOrder = new ArrayDeque<>();
    private final AtomicLong audioStarted = new AtomicLong(), audioCompleted = new AtomicLong(), audioBytes = new AtomicLong();
    private final AtomicLong audioErrors = new AtomicLong(), audioCancelled = new AtomicLong(), roomDistinct = new AtomicLong(), roomReuse = new AtomicLong();
    public AuditServer() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            try {
                metrics = new AuditMetrics(); deadline = System.currentTimeMillis() + 1_200_000;
                if (AuditMetrics.SHARED) shared = SharedAuditTransport.install(event.getServer(), metrics.directory);
                prepareAudioFixtures();
                event.getServer().overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, event.getServer());
                event.getServer().overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, event.getServer());
                event.getServer().overworld().setDayTime(6000);
                Files.writeString(metrics.control.resolve("ready.txt"), "READY");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ServerTickEvent.Pre event) -> tickStart = System.nanoTime());
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerTickEvent.Post event) -> {
            if (metrics == null || finished) return;
            metrics.tickWork(tickStart);
            // Minecraft writes this ring-buffer slot before firing ServerTickEvent.Post.
            metrics.completedServerTick(event.getServer().getTickTimesNanos()[event.getServer().getTickCount() % 100]);
            try {
                long now = System.currentTimeMillis();
                if (now >= nextPoll) {
                    nextPoll = now + 1000;
                    for (var player : event.getServer().getPlayerList().getPlayers()) {
                        metrics.attach(player.connection.getConnection()); attachPlaybackObserver(player);
                    }
                    Path phaseFile = metrics.control.resolve("phase.txt");
                    if (Files.exists(phaseFile)) {
                        String[] parts = Files.readString(phaseFile).strip().split(",");
                        if (parts.length == 2) { phase = parts[0]; metrics.phase(phase, Long.parseLong(parts[1])); }
                    }
                    boolean done = Files.exists(metrics.control.resolveSibling("audit-leader").resolve("result.txt"));
                    boolean guestDone = Files.exists(metrics.control.resolveSibling("audit-guest").resolve("result.txt"));
                    if ((done && guestDone) || now > deadline) {
                        finished = true; metrics.close();
                        Files.writeString(metrics.control.resolve("result.txt"), now > deadline ? "FAIL: timeout" : "PASS: server runtime collection complete");
                        event.getServer().halt(false); return;
                    }
                }
                var extra = new LinkedHashMap<String, Object>();
                extra.put("players", event.getServer().getPlayerCount());
                extra.put("serverAverageTickMs", event.getServer().getAverageTickTimeNanos() / 1e6);
                extra.put("serverAudioStartedTotal", audioStarted.get()); extra.put("serverAudioCompletedTotal", audioCompleted.get());
                extra.put("serverAudioBytesTotal", audioBytes.get()); extra.put("serverAudioErrorsTotal", audioErrors.get());
                extra.put("serverAudioCancelledTotal", audioCancelled.get()); extra.put("serverAudioPending", downloads.size());
                extra.put("serverAudioActive", downloads.values().stream().filter(download -> download.begun != 0).count());
                extra.put("serverRoomPayloadDistinctTotal", roomDistinct.get()); extra.put("serverRoomPayloadReuseTotal", roomReuse.get());
                for (ObservedEvent observed; (observed = events.poll()) != null;) metrics.event(observed.name(), observed.values());
                if (shared != null) extra.putAll(shared.snapshot());
                long serverNow = RoomNetwork.now();
                extra.put("epochMs", System.currentTimeMillis()); extra.put("serverNowMs", serverNow);
                extra.put("roomTimelines", timelines(serverNow));
                metrics.sample(extra);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private void prepareAudioFixtures() throws Exception {
        if (!metrics.directory.getFileName().toString().equals("audit-server")) throw new IllegalStateException("Audio fixtures require the isolated audit-server directory");
        for (String marker : List.of("fixture-id.txt", "fixture-ready.txt", "fixture-error.txt", "audio-fixtures.json"))
            Files.deleteIfExists(metrics.control.resolve(marker));
        Field active = AudioNetwork.class.getDeclaredField("activeStore"); active.setAccessible(true);
        var store = (ServerAudioStore) active.get(null);
        if (store == null) throw new IllegalStateException("Production server audio store was not started");
        Thread.ofPlatform().daemon().name("modernmusic-audit-fixtures").start(() -> {
            try {
                var tracks = List.of(fixture(store, "primary", 360, 44100), fixture(store, "secondary", 180, 22050));
                Files.writeString(metrics.control.resolve("audio-fixtures.json"), AuditMetrics.JSON.toJson(Map.of("version", 1, "tracks", tracks)));
                Files.writeString(metrics.control.resolve("fixture-id.txt"), tracks.getFirst().get("id").toString());
                Files.writeString(metrics.control.resolve("fixture-ready.txt"), "READY");
                observed("server_audio_fixtures_ready", Map.of("tracks", tracks));
            } catch (Throwable failure) {
                try { Files.writeString(metrics.control.resolve("fixture-error.txt"), failure.toString()); }
                catch (Exception suppressed) { failure.addSuppressed(suppressed); }
                failure.printStackTrace();
            }
        });
    }
    private Map<String, Object> fixture(ServerAudioStore store, String role, int seconds, int rate) throws Exception {
        byte[] bytes = AuditFixtures.wav(seconds, rate, 1);
        String filename = "audit-runtime-" + role + ".wav";
        Track track = null;
        for (Track candidate : store.catalog()) if (candidate.title().equals(filename) && candidate.durationMs() == seconds * 1000L
                && Arrays.equals(store.read(candidate.id()), bytes)) { track = candidate; break; }
        if (track == null) track = store.add(filename, bytes);
        return Map.of("role", role, "id", track.id().toString(), "title", track.title(), "bytes", bytes.length,
                "durationMs", track.durationMs(), "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    private void observed(String name, Map<String, Object> values) {
        var captured = new LinkedHashMap<String, Object>();
        captured.put("epochMs", System.currentTimeMillis()); captured.put("serverNowMs", RoomNetwork.now()); captured.put("phase", phase);
        captured.putAll(values); events.add(new ObservedEvent(name, captured));
    }
    private void attachPlaybackObserver(ServerPlayer player) throws Exception {
        Field field = Connection.class.getDeclaredField("channel"); field.setAccessible(true);
        Channel channel = (Channel) field.get(player.connection.getConnection());
        if (channel == null) return;
        UUID playerId = player.getUUID();
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get("audit-playback") != null) return;
            channel.pipeline().addBefore("packet_handler", "audit-playback", new ChannelDuplexHandler() {
                @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                    if (message instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof AudioNetwork.CommandPayload command) {
                        var key = new TransferKey(playerId, command.request());
                        if (command.command() == AudioNetwork.Command.DOWNLOAD) downloads.putIfAbsent(key, new Download(command.value()));
                        if (command.command() == AudioNetwork.Command.CANCEL && downloads.remove(key) != null) {
                            audioCancelled.incrementAndGet(); observed("server_audio_download_cancelled", Map.of("playerId", playerId, "requestId", command.request()));
                        }
                    }
                    super.channelRead(context, message);
                }
                @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                    if (message instanceof ClientboundCustomPayloadPacket packet) {
                        if (packet.payload() instanceof AudioNetwork.ReplyPayload reply) observeAudio(playerId, reply);
                        if (packet.payload() instanceof RoomNetwork.UpdatePayload update) observeRoom(playerId, update.json());
                    }
                    super.write(context, message, promise);
                }
                @Override public void channelInactive(ChannelHandlerContext context) throws Exception {
                    playerRooms.remove(playerId);
                    downloads.keySet().removeIf(key -> key.player().equals(playerId));
                    super.channelInactive(context);
                }
            });
        }).sync();
    }
    private void observeAudio(UUID player, AudioNetwork.ReplyPayload reply) {
        var key = new TransferKey(player, reply.request());
        Download download = downloads.get(key);
        if (reply.reply() == AudioNetwork.Reply.ERROR) {
            audioErrors.incrementAndGet(); downloads.remove(key);
            observed("server_audio_error", Map.of("playerId", player, "requestId", reply.request(), "error", String.valueOf(reply.error())));
        } else if (reply.reply() == AudioNetwork.Reply.DOWNLOAD_BEGIN) {
            if (download == null) { download = new Download(reply.tracks().getFirst().id().toString()); downloads.put(key, download); }
            download.begun = System.nanoTime(); audioStarted.incrementAndGet();
            observed("server_audio_download_begin", Map.of("playerId", player, "requestId", reply.request(), "songId", download.song,
                    "totalBytes", reply.total(), "queueWaitMs", (download.begun - download.requested) / 1e6));
        } else if (reply.reply() == AudioNetwork.Reply.DOWNLOAD_CHUNK) {
            audioBytes.addAndGet(reply.bytes().length);
            if (download != null) {
                download.bytes += reply.bytes().length;
                if (reply.offset() + reply.bytes().length == reply.total()) {
                    downloads.remove(key); audioCompleted.incrementAndGet();
                    observed("server_audio_download_complete", Map.of("playerId", player, "requestId", reply.request(), "songId", download.song,
                            "totalBytes", reply.total(), "observedBytes", download.bytes, "transferMs", (System.nanoTime() - download.begun) / 1e6));
                }
            }
        }
    }
    private synchronized void observeRoom(UUID player, String json) {
        RoomUpdate update = decoded.get(json);
        if (update == null) {
            update = RoomJson.decode(json, RoomUpdate.class); decoded.put(json, update); payloadOrder.addLast(json); roomDistinct.incrementAndGet();
            while (payloadOrder.size() > 16) decoded.remove(payloadOrder.removeFirst());
        } else roomReuse.incrementAndGet();
        RoomSnapshot room = update.room();
        if (room == null) { playerRooms.remove(player); return; }
        playerRooms.put(player, room);
        RoomSnapshot previous = lastTimeline.get(room.id());
        if (previous == null || !previous.epoch().equals(room.epoch()) || previous.revision() < room.revision()) {
            lastTimeline.put(room.id(), room); observed("server_room_timeline", timeline(room, RoomNetwork.now()));
        }
    }
    private List<Map<String, Object>> timelines(long now) {
        var rooms = new LinkedHashMap<UUID, RoomSnapshot>();
        for (RoomSnapshot room : playerRooms.values()) rooms.merge(room.id(), room, (a, b) -> a.revision() >= b.revision() ? a : b);
        return rooms.values().stream().map(room -> timeline(room, now)).toList();
    }
    private Map<String, Object> timeline(RoomSnapshot room, long now) {
        var value = new LinkedHashMap<String, Object>();
        value.put("roomId", room.id()); value.put("roomEpoch", room.epoch()); value.put("roomRevision", room.revision());
        value.put("roomPlaybackRevision", room.playbackRevision()); value.put("roomQueueRevision", room.queueRevision());
        value.put("roomState", room.state().name()); value.put("roomPositionMs", room.positionAt(now)); value.put("roomBasePositionMs", room.positionMs());
        value.put("roomAnchorMs", room.anchorMs()); value.put("roomDurationMs", room.durationMs()); value.put("roomIndex", room.index());
        value.put("roomSongId", room.index() < 0 ? "" : room.songs().get(room.index()));
        return value;
    }
}
