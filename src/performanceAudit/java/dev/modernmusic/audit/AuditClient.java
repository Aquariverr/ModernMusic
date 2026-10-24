package dev.modernmusic.audit;

import dev.modernmusic.client.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.network.RoomNetwork;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Uses the production controller, HTTP provider, HUD and audio device in isolated run directories. */
@Mod(value="modernmusic_audit", dist=Dist.CLIENT)
public final class AuditClient {
    private AuditMetrics metrics;
    private PlaybackController player;
    private RoomClient rooms;
    private String phase = "startup", applied = "";
    private long phaseStart, nextPoll, deadline = System.currentTimeMillis() + 1_100_000;
    private boolean connecting, initialized, finished, invited, secondAdded;
    private final boolean leader = "leader".equals(System.getProperty("modernmusic.auditRole"));
    private final AtomicLong audioBytes = new AtomicLong(), audioRequests = new AtomicLong(), audioDownloadNanos = new AtomicLong();
    private int importAttempt;
    private long manualReplayRequestsBefore, manualReplayBytesBefore;
    private boolean manualReplayRecorded;
    private AuditRegressions regressions;
    private CompletableFuture<Map<String, Object>> regressionCheck;
    private boolean regressionRecorded, serverAdded, serverSecondAdded, serverInvited;
    private String serverSong, serverNextSong;
    private long commandRevision;
    public AuditClient() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> { if (metrics != null && !finished) metrics.framePre(); });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> { if (metrics != null && !finished) metrics.framePost(); });
    }
    private void tick(ClientTickEvent.Post event) {
        if (finished) return;
        var game = Minecraft.getInstance();
        try {
            if (metrics == null) metrics = new AuditMetrics();
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("Timeout in " + phase);
            if (game.getOverlay() != null) return;
            if (!connecting) {
                if (game.screen instanceof AccessibilityOnboardingScreen) { game.options.onboardingAccessibilityFinished(); game.setScreen(new TitleScreen()); }
                if (!(game.screen instanceof TitleScreen) || !Files.exists(metrics.control.resolve("ready.txt"))) return;
                game.options.pauseOnLostFocus = false; game.options.renderDistance().set(2); game.options.simulationDistance().set(5);
                game.options.framerateLimit().set(60); game.options.enableVsync().set(false); game.options.guiScale().set(2);
                game.getWindow().setWindowed(1100, 720); game.resizeDisplay();
                ConnectScreen.startConnecting(new TitleScreen(), game, new ServerAddress("127.0.0.1", 25579),
                        new ServerData("Runtime audit", "127.0.0.1:25579", ServerData.Type.OTHER), false, null);
                connecting = true; return;
            }
            if (game.screen instanceof DisconnectedScreen) throw new IllegalStateException("Disconnected in " + phase);
            if (game.level == null || game.player == null || RoomClient.instance() == null) return;
            if (!initialized) {
                rooms = RoomClient.instance(); player = (PlaybackController) field(RoomClient.class, "player").get(rooms);
                if (!player.queuesReady()) return;
                if (AuditMetrics.SHARED) {
                    var shared = SharedAccountClient.source();
                    if (shared == null || !shared.available()) return;
                    shared.select(true);
                    if (!shared.selected()) throw new IllegalStateException("Shared source was not selected");
                }
                player.volume(0); game.setScreen(null);
                regressions = new AuditRegressions(game, metrics.directory);
                HttpTransport real = (HttpTransport) field(PlaybackController.class, "transport").get(player);
                field(PlaybackController.class, "transport").set(player, (HttpTransport) (uri, max) -> {
                    long start = System.nanoTime(); byte[] body = real.get(uri, max);
                    audioBytes.addAndGet(body.length); audioRequests.incrementAndGet(); audioDownloadNanos.addAndGet(System.nanoTime() - start); return body;
                });
                metrics.attach(game.getConnection().getConnection());
                Files.writeString(metrics.directory.resolve("connected.txt"), "READY"); initialized = true;
            }
            long now = System.currentTimeMillis();
            regressions.tick();
            if (now >= nextPoll) {
                nextPoll = now + 1000;
                if (leader && phase.equals("startup") && Files.exists(metrics.control.resolveSibling("audit-guest").resolve("connected.txt"))) setPhase("baseline");
                if (Files.exists(metrics.control.resolve("phase.txt"))) {
                    String[] value = Files.readString(metrics.control.resolve("phase.txt")).strip().split(",");
                    if (value.length == 2) { phase = value[0]; phaseStart = Long.parseLong(value[1]); metrics.phase(phase, phaseStart); }
                }
            }
            if (!applied.equals(phase)) {
                applied = phase; apply(game);
                System.out.println("AUDIT phase=" + phase + " role=" + metrics.role);
            }
            if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException("Playback error in " + phase + ": " + player.message());
            var room = rooms.update().room();
            if (phase.equals("room_setup")) {
                if (!leader && room == null && !rooms.update().invitations().isEmpty()) rooms.join(rooms.update().invitations().getFirst());
                if (leader && room != null) {
                    if (!invited) { rooms.command(INVITE, "AuditGuest", 0); invited = true; }
                    else if (room.songs().isEmpty()) rooms.command(ADD, "", 2058263032L);
                    else if (room.songs().size() == 1 && !secondAdded) { rooms.command(ADD, "", 1973665667L); secondAdded = true; }
                    else if (room.songs().size() == 2 && room.members().size() == 2 && room.state() == RoomSnapshot.State.STOPPED) rooms.command(PLAY, "", 0);
                }
                if (room != null && room.members().size() == 2 && player.state() == PlaybackController.State.PLAYING) ready();
                if (leader && bothReady()) setPhase("room_two");
            }
            if (phase.equals("personal_loading")) {
                if (player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() > 1000) ready();
                if (leader && bothReady()) setPhase("personal");
            }
            if (phase.equals("manual_replay")) {
                if (!manualReplayRecorded && player.state() == PlaybackController.State.PLAYING && now - phaseStart > 5000) {
                    long requestDelta = audioRequests.get() - manualReplayRequestsBefore;
                    long expectedDelta = Boolean.getBoolean("modernmusic.auditBaseline") ? 1 : 0;
                    if (requestDelta != expectedDelta) throw new IllegalStateException("Manual replay HTTP count: expected=" + expectedDelta + " actual=" + requestDelta);
                    if (player.audio().positionMs() > 20000) throw new IllegalStateException("Manual replay did not reset the playhead");
                    metrics.event("manual_replay_complete", Map.of("requestsBefore", manualReplayRequestsBefore,
                            "requestsAfter", audioRequests.get(), "requestDelta", requestDelta,
                            "downloadedBytesDelta", audioBytes.get() - manualReplayBytesBefore,
                            "positionMs", player.audio().positionMs(), "pcmBytes", player.bufferedBytes()));
                    manualReplayRecorded = true; ready();
                }
                if (leader && bothReady() && now - phaseStart >= 15000) setPhase("room_setup");
            }
            if (phase.equals("import400")) {
                if (leader && !player.importing() && room != null && room.songs().size() < 400) {
                    String[] lists = {"3778678", "19723756", "3779629", "2884035", "60198", "180106"};
                    if (importAttempt >= lists.length) throw new IllegalStateException("Public playlists did not provide 400 distinct tracks; actual=" + room.songs().size());
                    player.importPlaylist(lists[importAttempt++]);
                }
                if (room != null && room.songs().size() == 400) ready();
                if (leader && bothReady()) { rooms.command(PLAY, "", 0); setPhase("room400"); }
            }
            if (phase.equals("replay")) {
                if (player.state() == PlaybackController.State.PLAYING && now - phaseStart > 5000) ready();
                if (leader && bothReady() && now - phaseStart >= 15000) setPhase("provider_checks");
            }
            if (phase.equals("provider_checks") || phase.equals("local_checks")) {
                if (phase.equals("local_checks") && regressionCheck == null && !player.shared())
                    regressionCheck = regressions.startLocal(player);
                if (regressionCheck != null && regressionCheck.isDone() && !regressionRecorded) {
                    var result = regressionCheck.join();
                    Files.writeString(metrics.directory.resolve(phase + ".json"), AuditMetrics.JSON.toJson(result));
                    metrics.event(phase + "_complete", result); regressionRecorded = true; ready();
                }
                if (leader && bothReady()) setPhase(phase.equals("provider_checks") ? "local_checks" : "qr_ui");
            }
            if (phase.equals("qr_ui") && now - phaseStart >= 45000) {
                if (!regressionRecorded) {
                    regressions.captureQr(metrics.directory.resolve("audit-qr.png"));
                    var result = regressions.qrStats();
                    Files.writeString(metrics.directory.resolve("qr_checks.json"), AuditMetrics.JSON.toJson(result));
                    metrics.event("qr_complete", result); regressionRecorded = true; ready();
                }
                if (leader && bothReady()) setPhase("server_audio_setup");
            }
            if (phase.equals("server_audio_setup")) {
                if (Files.exists(metrics.control.resolve("fixture-error.txt")))
                    throw new IllegalStateException(Files.readString(metrics.control.resolve("fixture-error.txt")));
                if (serverSong == null && Files.exists(metrics.control.resolve("fixture-ready.txt"))) {
                    var fixtures = com.google.gson.JsonParser.parseString(Files.readString(metrics.control.resolve("audio-fixtures.json"))).getAsJsonObject().getAsJsonArray("tracks");
                    serverSong = fixtures.get(0).getAsJsonObject().get("id").getAsString();
                    serverNextSong = fixtures.get(1).getAsJsonObject().get("id").getAsString();
                    ServerAudioClient.refresh();
                }
                if (!leader && room == null && !rooms.update().invitations().isEmpty()) rooms.join(rooms.update().invitations().getFirst());
                if (leader && room != null && serverSong != null) {
                    if (!serverInvited) { rooms.command(INVITE, "AuditGuest", 0); serverInvited = true; }
                    else if (room.songs().isEmpty() && !serverAdded) { rooms.addSong(SongId.stored(serverSong)); serverAdded = true; }
                    else if (room.songs().size() == 1 && !serverSecondAdded) { rooms.addSong(SongId.stored(serverNextSong)); serverSecondAdded = true; }
                    else if (room.songs().size() == 2 && room.members().size() == 2 && room.state() == RoomSnapshot.State.STOPPED) rooms.command(PLAY, "", 0);
                }
                if (room != null && room.members().size() == 2 && room.songs().size() == 2
                        && room.index() == 0 && player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() > 1000) ready();
                if (leader && bothReady()) setPhase("server_audio");
            }
            if (phase.startsWith("sync_")) {
                boolean matches = room != null && room.members().size() == 2 && room.revision() > commandRevision;
                matches &= switch (phase) {
                    case "sync_pause" -> room != null && room.state() == RoomSnapshot.State.PAUSED && player.state() == PlaybackController.State.PAUSED;
                    case "sync_seek" -> room != null && room.state() == RoomSnapshot.State.PAUSED && Math.abs(room.positionMs() - 30000) < 100 && Math.abs(player.audio().positionMs() - 30000) < 250;
                    case "sync_resume" -> room != null && room.state() == RoomSnapshot.State.PLAYING && player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() > 30000;
                    case "sync_next" -> room != null && room.index() == 1 && player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() > 1000;
                    case "sync_stop" -> room != null && room.state() == RoomSnapshot.State.STOPPED && player.state() == PlaybackController.State.STOPPED;
                    default -> false;
                };
                if (matches && now - phaseStart > 3000 && !regressionRecorded) {
                    metrics.event("sync_control_ack", Map.of("control", phase, "audioPositionMs", player.audio().positionMs(),
                            "roomPositionMs", rooms.positionMs(), "playbackRevision", room.playbackRevision(), "index", room.index()));
                    regressionRecorded = true; ready();
                }
                if (leader && bothReady()) setPhase(switch (phase) {
                    case "sync_pause" -> "sync_seek"; case "sync_seek" -> "sync_resume";
                    case "sync_resume" -> "sync_next"; case "sync_next" -> "sync_stop"; default -> "cleanup";
                });
            }
            if (phase.equals("done")) { finish(game, "PASS: FPS/TPS, two-client public/server audio synchronization and six-fix runtime regressions"); return; }
            long elapsed = now - phaseStart;
            if (leader && elapsed >= 75000) {
                switch (phase) {
                    case "baseline" -> setPhase("personal_loading");
                    case "personal" -> setPhase("manual_replay");
                    case "room_two" -> setPhase("import400");
                    case "room400" -> { rooms.command(PLAY, "", 0); setPhase("ui400"); }
                    case "ui400" -> { rooms.command(PLAY, "", 0); setPhase("replay"); }
                    case "server_audio" -> setPhase("sync_pause");
                    case "cleanup" -> setPhase("done");
                    default -> { if (elapsed > 120000 && !phase.equals("startup")) throw new IllegalStateException("Phase did not become ready: " + phase); }
                }
            }
            var extra = new LinkedHashMap<String, Object>();
            var audio = player.audio();
            extra.put("audioSampleEpochMs", System.currentTimeMillis());
            long clientNow = RoomNetwork.now(); var clock = (ClockEstimator) field(RoomClient.class, "clock").get(rooms);
            extra.put("clientNowMs", clientNow); extra.put("estimatedServerNowMs", clock.serverNow(clientNow));
            extra.put("clockReady", clock.ready()); extra.put("clockRttMs", clock.rtt());
            extra.put("audioState", audio.state().name());
            extra.put("playerState", player.state().name()); extra.put("audioPositionMs", audio.positionMs());
            extra.put("audioDurationMs", audio.durationMs()); extra.put("bufferedPcmBytes", player.bufferedBytes());
            if (room != null) {
                extra.put("roomId", room.id().toString()); extra.put("roomEpoch", room.epoch().toString());
                extra.put("roomRevision", room.revision()); extra.put("roomPlaybackRevision", room.playbackRevision()); extra.put("roomQueueRevision", room.queueRevision());
                extra.put("roomState", room.state().name()); extra.put("roomPositionMs", room.positionMs()); extra.put("roomAnchorMs", room.anchorMs());
                extra.put("roomDurationMs", room.durationMs()); extra.put("roomIndex", room.index());
                extra.put("roomSongId", room.index() < 0 ? "" : room.songs().get(room.index()));
            }
            extra.put("queueSize", player.queue().entries().size()); extra.put("roomMembers", room == null ? 0 : room.members().size());
            extra.put("artworkTextures", ArtworkCache.textureCount());
            extra.put("audioDownloadedBytesTotal", audioBytes.get()); extra.put("audioRequestsTotal", audioRequests.get());
            extra.put("audioDownloadMsTotal", audioDownloadNanos.get() / 1e6); extra.put("screen", game.screen == null ? "HUD" : game.screen.getClass().getSimpleName());
            extra.put("gameFps", game.getFps());
            extra.put("frameLimit", game.options.framerateLimit().get()); extra.put("vsync", game.options.enableVsync().get());
            extra.put("windowFocused", org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(game.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0);
            var render = UiRenderMetrics.snapshot();
            extra.put("renderShapeSubmissionsTotal", render.shapeSubmissions());
            extra.put("renderWaveformShapeSubmissionsTotal", render.waveformShapeSubmissions());
            extra.put("renderWaveformFramesTotal", render.waveformFrames());
            extra.put("renderHudTextFlushesTotal", render.hudTextFlushes());
            extra.put("renderHudTextCallsTotal", render.hudTextCalls());
            extra.put("sharedSelected", SharedAccountClient.source() != null && SharedAccountClient.source().selected());
            extra.put("sharedAvailable", SharedAccountClient.source() != null && SharedAccountClient.source().available());
            metrics.sample(extra);
        } catch (Throwable e) { e.printStackTrace(); finish(game, "FAIL: " + phase + " " + e); }
    }
    private void apply(Minecraft game) throws Exception {
        regressionRecorded = false;
        // The guest may see the command before polling the phase file. Pin the previous
        // revision on the leader only; state/index assertions still apply on both clients.
        commandRevision = leader && rooms.update().room() != null ? rooms.update().room().revision() : -1;
        switch (phase) {
            case "personal_loading" -> { player.clear(); player.add("2058263032"); }
            case "manual_replay" -> {
                manualReplayRequestsBefore = audioRequests.get(); manualReplayBytesBefore = audioBytes.get();
                metrics.event("manual_replay_requested", Map.of("requestsBefore", manualReplayRequestsBefore,
                        "positionMs", player.audio().positionMs(), "pcmBytes", player.bufferedBytes()));
                player.play(player.queue().index());
            }
            case "room_setup" -> { capture(game, "audit-hud.png"); player.stop(); if (leader) rooms.command(CREATE, "Runtime Audit", 0); }
            case "ui400" -> {
                long openedAt = System.currentTimeMillis(), started = System.nanoTime();
                game.setScreen(new PlayerScreen(null, player));
                double initMs = (System.nanoTime() - started) / 1e6;
                metrics.event("player_screen_opened", Map.of("openedAtEpochMs", openedAt, "initMs", initMs));
            }
            case "replay" -> { capture(game, "audit-player.png"); game.setScreen(null); }
            case "provider_checks" -> { rooms.command(LEAVE, "", 0); regressionCheck = regressions.startProviders(); }
            case "local_checks" -> { regressionCheck = null; }
            case "qr_ui" -> { player.stop(); game.setScreen(regressions.qrScreen()); }
            case "server_audio_setup" -> { game.setScreen(null); player.clearTemporaryCache(); if (leader) rooms.command(CREATE, "Server Audio Audit", 0); }
            case "sync_pause", "sync_resume" -> { if (leader) rooms.command(PAUSE, "", 0); }
            case "sync_seek" -> { if (leader) rooms.command(SEEK, "", 30000); }
            case "sync_next" -> { if (leader) rooms.command(NEXT, "", 0); }
            case "sync_stop" -> { if (leader) rooms.command(STOP, "", 0); }
            case "cleanup" -> { game.setScreen(null); rooms.command(LEAVE, "", 0); player.clearTemporaryCache(); ArtworkCache.clearIfCreated(); }
        }
    }
    /** Capture only after measured stable windows; synchronous readback never enters them. */
    private void capture(Minecraft game, String name) throws Exception {
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(metrics.directory.resolve(name));
        }
    }
    private void ready() throws Exception {
        Path path = metrics.directory.resolve("ready-" + phase + ".txt");
        if (!Files.exists(path)) Files.writeString(path, "READY");
    }
    private boolean bothReady() {
        return Files.exists(metrics.directory.resolve("ready-" + phase + ".txt")) && Files.exists(metrics.control.resolveSibling("audit-guest").resolve("ready-" + phase + ".txt"));
    }
    private void setPhase(String name) throws Exception {
        phase = name; phaseStart = System.currentTimeMillis();
        Path temp = metrics.control.resolve("phase.next"); Files.writeString(temp, phase + "," + phaseStart);
        Files.move(temp, metrics.control.resolve("phase.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        metrics.phase(phase, phaseStart);
    }
    private void finish(Minecraft game, String result) {
        finished = true;
        try { if (regressions != null) regressions.close(); if (metrics != null) { metrics.close(); Files.writeString(metrics.directory.resolve("result.txt"), result); } }
        catch (Exception e) { e.printStackTrace(); }
        game.stop();
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
