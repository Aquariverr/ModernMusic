package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.*;
import dev.modernmusic.client.audio.*;
import dev.modernmusic.client.ui.RoomScreen;
import dev.modernmusic.client.ui.PlayerScreen;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import dev.modernmusic.network.RoomNetwork;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Two actual game processes and loopback networking; generated silent MP3, no upstream/account access. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class RoomNetworkSmoke {
    private final String role = System.getProperty("modernmusic.roomSmoke");
    private final boolean host = "host".equals(role);
    private final boolean uiOnly = Boolean.getBoolean("modernmusic.roomUiSmoke");
    private FtbRoomScenario ftbScenario;
    private SharedAccountScenario sharedScenario;
    private AudioNetworkScenario audioScenario;
    private PublicRoomScenario publicScenario;
    private LocalAudioProvider fixtureLocalAudio;
    private String fixtureAudioName;
    private final boolean audioSmoke = Boolean.getBoolean("modernmusic.audioSmoke");
    private final boolean sharedSmoke = Boolean.getBoolean("modernmusic.sharedSmoke");
    private final boolean publicSmoke = Boolean.getBoolean("modernmusic.publicRoomSmoke");
    private PlaybackController player;
    private RoomClient rooms;
    private int step;
    private long deadline, since, measuredAt, roomStarted;
    private Path directory, peer;
    private final List<Long> errors = new ArrayList<>();
    private boolean sawPaused, sawSeek, sawNext, sawStopped, lateAligned, permissionRejected;
    private String guestName;
    private int loggedStep = -2;
    private long nextProgress;
    private long nextConnect;
    private AccountController fixtureAccount;
    private HudSettings fixtureHud;
    private boolean headerVerified, playlistSubmitted, playlistVerified, requesterGranted;
    private int headerFrames, importFrames;
    private int uiStage, uiFrames, uiReadyFrames;
    private int fontStage;
    private String uiAction = "";
    public RoomNetworkSmoke(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener(this::registerHud);
        if (role != null) { deadline = RoomNetwork.now() + 420_000; NeoForge.EVENT_BUS.addListener(this::tick); }
    }
    private void registerHud(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        // Same production HUD renderer with this isolated fixture's actual room controller.
        if (!sharedSmoke && (!uiOnly || host)) return;
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("modernmusic_smoke", "room_hud"),
                (graphics, delta) -> { if (fixtureHud != null) dev.modernmusic.client.ui.MusicHud.render(graphics, player, fixtureHud,
                        graphics.guiWidth(), graphics.guiHeight(), false); });
    }
    private void tick(ClientTickEvent.Post event) {
        if (step == -1) return;
        var game = Minecraft.getInstance(); long time = RoomNetwork.now();
        try {
            if (time > deadline) throw new IllegalStateException("Timeout at " + role + " step " + step);
            if (peer != null && Files.exists(peer.resolve("room-result.txt"))
                    && Files.readString(peer.resolve("room-result.txt")).startsWith("FAIL:"))
                throw new IllegalStateException("Peer smoke failed; see its room-result.txt");
            if (directory == null) {
                directory = game.gameDirectory.toPath().toAbsolutePath().normalize(); peer = directory.resolveSibling(host ? "room-guest-smoke" : "room-host-smoke");
                Files.writeString(directory.resolve("room-result.txt"), "RUNNING\n");
                Files.deleteIfExists(directory.resolve("room-ready.txt"));
                Files.deleteIfExists(directory.resolve("room-measured.txt"));
                Files.deleteIfExists(directory.resolve("room-late.txt"));
                if (audioSmoke) for (String marker : List.of("audio-id.txt", "audio-personal.txt", "audio-group.txt")) Files.deleteIfExists(directory.resolve(marker));
            }
            if (step == 0) {
                if (game.getOverlay() != null) return;
                if (game.screen instanceof AccessibilityOnboardingScreen) { game.options.onboardingAccessibilityFinished(); game.setScreen(new TitleScreen()); }
                if (!(game.screen instanceof TitleScreen)) return;
                if (!game.options.languageCode.equals("zh_cn")) {
                    game.options.languageCode = "zh_cn"; game.getLanguageManager().setSelected("zh_cn"); game.reloadResourcePacks(); return;
                }
                game.getWindow().setWindowed(1100, 720); game.options.guiScale().set(2); game.resizeDisplay();
                game.options.renderDistance().set(2); game.options.simulationDistance().set(5); game.options.framerateLimit().set(30);
                game.options.pauseOnLostFocus = false;
                if (host) {
                    game.createWorldOpenFlows().createFreshLevel("room-smoke-" + System.currentTimeMillis(),
                            new net.minecraft.world.level.LevelSettings("Room Smoke", net.minecraft.world.level.GameType.CREATIVE, false,
                                    net.minecraft.world.Difficulty.PEACEFUL, true, new net.minecraft.world.level.GameRules(), net.minecraft.world.level.WorldDataConfiguration.DEFAULT),
                            new net.minecraft.world.level.levelgen.WorldOptions(0L, false, false),
                            registry -> registry.registryOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                                    .getHolderOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.FLAT).value().createWorldDimensions(), new TitleScreen());
                } else {
                    if (time < nextConnect) return;
                    if (!Files.exists(peer.resolve("room-ready.txt"))) return;
                    nextConnect = time + 2_000;
                    ConnectScreen.startConnecting(new TitleScreen(), game, new ServerAddress("127.0.0.1", 25575),
                            new ServerData("Room Smoke", "127.0.0.1:25575", ServerData.Type.OTHER), false, null);
                }
                step = 1; return;
            }
            if (!host && step > 1 && game.screen instanceof DisconnectedScreen)
                throw new IllegalStateException("Unexpected disconnection during room test");
            if (!host && step == 1 && game.screen instanceof DisconnectedScreen) {
                game.setScreen(new TitleScreen()); step = 0; return;
            }
            if (game.level == null || game.player == null || game.getOverlay() != null) return;
            if (step == 1) {
                if (host) {
                    var server = game.getSingleplayerServer();
                    var ready = new CompletableFuture<Void>();
                    server.execute(() -> {
                        try {
                            if (sharedSmoke) SharedAccountFixture.install(server);
                            server.setUsesAuthentication(false);
                            server.getConnection().startTcpServerListener(java.net.InetAddress.getByName("127.0.0.1"), 25575);
                            ready.complete(null);
                        } catch (Exception failure) { ready.completeExceptionally(failure); }
                    });
                    ready.get(5, TimeUnit.SECONDS); Files.writeString(directory.resolve("room-ready.txt"), "READY\n");
                } else {
                    var field = net.minecraft.network.Connection.class.getDeclaredField("channel"); field.setAccessible(true);
                    var channel = (io.netty.channel.Channel) field.get(game.getConnection().getConnection());
                    channel.pipeline().addFirst("room-smoke-latency", new TestLatency());
                }
                MusicProvider provider = new MusicProvider() {
                    public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Room test " + id, List.of("Fixture"), 230_000)); }
                    public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { return CompletableFuture.completedFuture(new PlaybackResource(id,
                            java.net.URI.create("https://m801.music.126.net/fixture.mp3"), Instant.now().plusSeconds(600))); }
                    public void close() {}
                    public CompletableFuture<Lyrics> getLyrics(SongId id) {
                        return CompletableFuture.completedFuture(new Lyrics(java.util.stream.IntStream.range(0, 100)
                                .mapToObj(i -> new Lyrics.Line(i * 2_000L, "歌词测试 · 第 " + (i + 1) + " 行", "翻译测试 · 第 " + (i + 1) + " 行")).toList()));
                    }
                    public CompletableFuture<Playlist> getPlaylist(SongId id) {
                        return CompletableFuture.completedFuture(new Playlist("Fixture playlist", java.util.stream.LongStream.range(50, 450)
                                .mapToObj(SongId::new).toList(), List.of(), 500));
                    }
                };
                byte[] mp3 = silentMp3();
                if (sharedSmoke) {
                    SharedDisplayScenario.installAvatar(game);
                    provider = SharedAccountClient.connect(provider, () -> { if (player != null) player.playbackSourceChanged(); });
                }
                if (audioSmoke) {
                    ServerAudioClient.initialize();
                    Path audioDirectory = directory.resolve("fixture-audio"); Files.createDirectories(audioDirectory);
                    fixtureAudioName = "audio-smoke-" + role + "-" + System.currentTimeMillis() + ".wav";
                    Files.write(audioDirectory.resolve(fixtureAudioName), AudioNetworkScenario.wav());
                    fixtureLocalAudio = new LocalAudioProvider(audioDirectory, game::execute);
                    provider = new MultiPlatformProvider(provider, provider, provider, fixtureLocalAudio);
                }
                player = new PlaybackController(provider, (uri, max) -> mp3, new JavaSoundOutput(), game::execute);
                player.volume(0); rooms = new RoomClient(player);
                fixtureHud = new HudSettings(directory.resolve("fixture-hud.json"));
                fixtureAccount = new AccountController(new AccountProvider() {
                    public AccountSession validate(SessionCookie cookie) { return new AccountSession("42", "测试账号", cookie, Instant.now()); }
                    public QrChallenge beginQr() { throw new UnsupportedOperationException(); }
                    public QrPoll pollQr(QrChallenge challenge) { throw new UnsupportedOperationException(); }
                }, new RememberedSessionStore(directory.resolve("fixture-account")), game::execute, () -> {}, java.time.Clock.systemUTC());
                if (!sharedSmoke) fixtureAccount.loginCookie("MUSIC_U=room-fixture-not-a-real-cookie");
                game.setScreen(new PlayerScreen(null, player, fixtureAccount, fixtureHud));
                step = 2;
                return;
            }
            fixtureAccount.tick();
            if (publicSmoke) {
                if (publicScenario == null) publicScenario = new PublicRoomScenario(host, peer);
                player.tick(1); rooms.tick();
                String result = publicScenario.tick(game, rooms, player);
                if (result != null) finish(game, result);
                return;
            }
            if (audioSmoke) {
                if (audioScenario == null) audioScenario = new AudioNetworkScenario(host, directory, peer, fixtureLocalAudio, fixtureAudioName);
                player.tick(1); rooms.tick(); ServerAudioClient.tick();
                String result = audioScenario.tick(game, rooms, player);
                if (result != null) finish(game, result);
                return;
            }
            if (sharedSmoke) {
                if (sharedScenario == null) sharedScenario = new SharedAccountScenario(host, directory, peer);
                player.tick(1); rooms.tick();
                String result = sharedScenario.tick(game, rooms, player, fixtureAccount);
                if (result != null) finish(game, result);
                return;
            }
            if (Boolean.getBoolean("modernmusic.ftbSmoke")) {
                if (ftbScenario == null) ftbScenario = new FtbRoomScenario(host, directory, peer);
                player.tick(1); rooms.tick();
                String result = ftbScenario.tick(game, rooms, player);
                if (result != null) finish(game, result);
                return;
            }
            if (host && !headerVerified) {
                if (!(game.screen instanceof PlayerScreen)) {
                    game.setScreen(new PlayerScreen(null, player, fixtureAccount, fixtureHud)); headerFrames = 0; return;
                }
                if (fixtureAccount.session() == null || headerFrames++ < 100) return;
                capture(game, "player-account-header.png"); headerVerified = true;
                rooms.command(CREATE, "M3 Smoke", 0); game.setScreen(new RoomScreen(null, rooms)); return;
            }
            player.tick(1); rooms.tick();
            if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException(player.message());
            var room = rooms.update().room();
            if (loggedStep != step || time >= nextProgress) {
                loggedStep = step; nextProgress = time + 10_000;
                ModernMusic.LOGGER.info("Room smoke {} step={} available={} state={} position={} room={} message={}", role, step,
                        rooms.available(), player.state(), player.audio().positionMs(), room == null ? "none" : room.revision() + "/" + room.state(), rooms.message());
            }
            if (host) host(game, room, time); else guest(game, room, time);
        } catch (Throwable failure) { finish(game, "FAIL: " + role + " step=" + step + " " + failure); }
    }
    private void host(Minecraft game, RoomSnapshot room, long time) throws Exception {
        if (room == null) return;
        switch (step) {
            case 2 -> { rooms.command(ADD, "", 42); step++; }
            case 3 -> { if (room.songs().size() == 1) { rooms.command(ADD, "", 43); step++; } }
            case 4 -> { if (room.songs().size() == 2) { rooms.command(PLAY, "", 0); step++; } }
            case 5 -> {
                if (player.state() != PlaybackController.State.PLAYING || player.audio().positionMs() < 2_000) return;
                var other = game.level.players().stream().filter(p -> !p.getUUID().equals(game.player.getUUID())).findFirst();
                if (other.isEmpty()) return;
                guestName = other.get().getGameProfile().getName(); rooms.command(INVITE, guestName, 0); roomStarted = time; step++;
            }
            case 6 -> {
                if (!requesterGranted && room.members().size() == 2) {
                    rooms.command(REQUESTER, guestName, 1); requesterGranted = true;
                }
                if (uiOnly) {
                    var actionFile = peer.resolve("room-hud-action.txt");
                    if (Files.exists(actionFile)) {
                        String action = Files.readString(actionFile).strip();
                        if (!action.equals(uiAction)) {
                            uiAction = action;
                            switch (action) {
                                case "pause", "resume" -> rooms.command(PAUSE, "", 0);
                                case "seek" -> rooms.command(SEEK, "", 30_000);
                                case "next" -> rooms.command(NEXT, "", 0);
                                default -> throw new IllegalStateException("Unknown HUD action " + action);
                            }
                        }
                    }
                    if (Files.exists(peer.resolve("room-result.txt")) && Files.readString(peer.resolve("room-result.txt")).startsWith("PASS"))
                        finish(game, "PASS: two-client room UI / personal header / playlist import / group status and tooltip / responsive lyrics");
                    return;
                }
                if (room.members().size() != 2 || !Files.exists(peer.resolve("room-import.txt"))) return;
                if (room.songs().size() != 400 || !room.songs().subList(2, 400).equals(java.util.stream.LongStream.range(50, 448).mapToObj(Long::toString).toList()))
                    throw new IllegalStateException("Playlist order or server truncation failed");
                rooms.command(PLAY, "", 0); since = time; step++;
            }
            case 7 -> {
                measure(time);
                if (time - since < 180_000 || !Files.exists(peer.resolve("room-measured.txt"))) return;
                verifyMeasurement(); Files.writeString(directory.resolve("room-measured.txt"), "P95=" + p95());
                rooms.command(PAUSE, "", 0); step++;
            }
            case 8 -> {
                if (room.state() != RoomSnapshot.State.PAUSED || player.state() != PlaybackController.State.PAUSED) return;
                since = time; step++;
            }
            case 9 -> { if (time - since > 2_000) { rooms.command(SEEK, "", 30_000); since = time; step++; } }
            case 10 -> {
                if (Math.abs(player.audio().positionMs() - 30_000) > 100 || time - since < 2_000) return;
                rooms.command(PAUSE, "", 0); since = time; step++;
            }
            case 11 -> { if (time - since > 5_000) { rooms.command(NEXT, "", 0); since = time; step++; } }
            case 12 -> {
                if (room.index() != 1 || player.state() != PlaybackController.State.PLAYING || time - since < 5_000) return;
                rooms.command(STOP, "", 0); since = time; step++;
            }
            case 13 -> {
                if (room.state() != RoomSnapshot.State.STOPPED || time - since < 3_000) return;
                rooms.command(DJ, guestName, 1); since = time; step++;
            }
            case 14 -> {
                if (room.index() != 0 || room.state() != RoomSnapshot.State.PLAYING || time - since < 3_000) return;
                rooms.command(DJ, guestName, 0); since = time; step++;
            }
            case 15 -> {
                if (time - since < 3_000) return;
                rooms.command(KICK, guestName, 0); step++;
            }
            case 16 -> {
                if (room.members().size() != 1 || !Files.exists(peer.resolve("room-result.txt"))
                        || !Files.readString(peer.resolve("room-result.txt")).startsWith("PASS")) return;
                finish(game, "PASS: account header / requester playlist import 398 of 500 / actual two-client room / controls / permissions; 3min P95=" + p95() + "ms");
            }
            default -> { }
        }
    }
    private void guest(Minecraft game, RoomSnapshot room, long time) throws Exception {
        if (step == 2 && !rooms.update().invitations().isEmpty()) {
            rooms.join(rooms.update().invitations().getFirst()); since = time; step = 3; return;
        }
        if (room == null) {
            if (uiOnly && uiStage == 11) {
                if (uiFrames++ < 30) return;
                if (player.shared() || player.track() != null || player.state() == PlaybackController.State.PLAYING)
                    throw new IllegalStateException("Group HUD state survived leave");
                capture(game, "hud-after-leave.png");
                finish(game, "PASS: real group HUD / song and lyrics / pause / seek / resume / next / leave; group UI and tooltip"); return;
            }
            if (step >= 4 && sawPaused && sawSeek && sawNext && sawStopped && permissionRejected && lateAligned && playlistVerified) {
                if (player.shared() || player.state() == PlaybackController.State.PLAYING) throw new IllegalStateException("Kick did not stop playback");
                finish(game, "PASS: guest room playlist import / ordered batch / server count 398 of 500 / playback preserved / permissions / controls; 3min P95=" + p95() + "ms");
            }
            return;
        }
        if (step == 3) {
            if (player.state() != PlaybackController.State.PLAYING) { if (time - since > 3_000) throw new IllegalStateException("Late join exceeded 3s"); return; }
            lateAligned = player.audio().positionMs() > 1_000;
            if (!lateAligned) throw new IllegalStateException("Late join did not seek to room position");
            Files.writeString(directory.resolve("room-late.txt"), "PASS\n");
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new RoomNetwork.CommandPayload(RoomJson.encode(
                    new RoomCommand(STOP, room.id(), room.epoch(), room.revision(), "", 0))));
            since = time; step = 4;
        }
        if (rooms.message().equals("modernmusic.room.error.permission")) permissionRejected = true;
        if (step == 4 && !playlistSubmitted && fixtureAccount.session() != null && room.canRequest(game.player.getUUID())) {
            game.setScreen(new PlayerScreen(null, player, fixtureAccount, fixtureHud));
            // PlayerScreen deliberately targets the personal queue now; exercise the room batch protocol explicitly.
            if (!player.importPlaylist("https://music.163.com/playlist?id=99"))
                throw new IllegalStateException("Room playlist import was not submitted");
            playlistSubmitted = true;
        }
        if (playlistSubmitted && !playlistVerified && !player.importing() && player.importedCount() == 398 && permissionRejected) {
            if (room.songs().size() != 400 || room.index() != 0 || player.state() != PlaybackController.State.PLAYING || player.importTotal() != 500)
                throw new IllegalStateException("Playlist import changed playback or counts");
            if (importFrames++ == 0) rooms.command(SNAPSHOT, "", 0);
            if (importFrames < 120) return;
            capture(game, "player-group-playlist.png"); playlistVerified = true;
            Files.writeString(directory.resolve("room-import.txt"), "PASS: imported 398/500, ordered, playing unchanged\n");
        }
        if (uiOnly && playlistVerified) { verifyUi(game); return; }
        if (step == 4 && room.state() == RoomSnapshot.State.PLAYING && room.index() == 0) {
            measure(time);
            if (time - since >= 180_000 && !Files.exists(directory.resolve("room-measured.txt"))) {
                verifyMeasurement(); Files.writeString(directory.resolve("room-measured.txt"), "P95=" + p95());
            }
        }
        if (room.state() == RoomSnapshot.State.PAUSED && player.state() == PlaybackController.State.PAUSED) {
            sawPaused = true; if (Math.abs(player.audio().positionMs() - 30_000) < 100) sawSeek = true;
        }
        if (room.index() == 1 && player.state() == PlaybackController.State.PLAYING) sawNext = true;
        if (room.state() == RoomSnapshot.State.STOPPED && player.state() == PlaybackController.State.STOPPED) sawStopped = true;
        if (sawStopped && room.canControl(game.player.getUUID()) && room.state() == RoomSnapshot.State.STOPPED) {
            rooms.command(PLAY, "", 0); step = 5;
        }
    }
    private void measure(long time) {
        if (player.state() != PlaybackController.State.PLAYING || time - measuredAt < 100) return;
        measuredAt = time;
        // The client estimator has already aligned audio to this timeline; compare to the true server clock
        // (both JVMs on this host use the same monotonic origin) rather than the estimated clock.
        errors.add(Math.abs(player.audio().positionMs() - rooms.update().room().positionAt(time)));
    }
    private void verifyUi(Minecraft game) throws Exception {
        if (uiFrames++ < 30) return;
        if (uiStage <= 5 && !player.message().equals("modernmusic.room.playing")) throw new IllegalStateException("Incorrect group playback status");
        if (uiStage == 0) {
            player.dismissImportMessage();
            game.getWindow().setWindowed(1600, 1000); game.resizeDisplay();
        } else if (uiStage == 1) {
            capture(game, "player-lyrics-large.png");
            var manager = game.screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
                    .filter(widget -> widget.getClass().getSimpleName().equals("QueueHeading")).findFirst().orElseThrow();
            StringBuilder tooltip = new StringBuilder();
            for (var line : manager.getTooltip().toCharSequence(game)) line.accept((index, style, code) -> { tooltip.appendCodePoint(code); return true; });
            if (!tooltip.toString().contains(player.library().active().name()) || tooltip.toString().contains("打开房间管理"))
                throw new IllegalStateException("Main queue stopped identifying the personal library: " + tooltip);
            var screen = (dev.modernmusic.client.ui.MusicScreen) game.screen;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(game.getWindow().getWindow(),
                    screen.toGuiCoordinate(manager.getX() + 10.0) * game.getWindow().getWidth() / game.getWindow().getGuiScaledWidth(),
                    screen.toGuiCoordinate(manager.getY() + 10.0) * game.getWindow().getHeight() / game.getWindow().getGuiScaledHeight());
        } else if (uiStage == 2) {
            capture(game, "player-personal-queue-tooltip.png");
            game.getWindow().setWindowed(1100, 720); game.resizeDisplay();
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(game.getWindow().getWindow(), 0, 0);
        } else if (uiStage == 3) {
            capture(game, "player-lyrics-normal.png");
            game.getWindow().setWindowed(720, 600); game.resizeDisplay();
        } else if (uiStage == 4) {
            if (!verifyLyricFont(game)) { uiFrames = 0; return; }
            game.getWindow().setWindowed(1100, 720); game.resizeDisplay();
            fixtureHud.enabled(true); game.setScreen(null);
        } else if (uiStage == 5) {
            if (game.screen != null || player.lyrics().at(player.audio().positionMs()) == null) throw new IllegalStateException("HUD not reached");
            capture(game, "hud-group-playing.png"); hudAction("pause");
        } else if (uiStage == 6) {
            if (player.state() != PlaybackController.State.PAUSED) return;
            capture(game, "hud-group-paused.png"); hudAction("seek");
        } else if (uiStage == 7) {
            if (player.state() != PlaybackController.State.PAUSED || Math.abs(player.audio().positionMs() - 30_000) > 100) return;
            capture(game, "hud-group-seek.png"); hudAction("resume");
        } else if (uiStage == 8) {
            if (player.state() != PlaybackController.State.PLAYING) return;
            if (uiReadyFrames++ < 10) return;
            capture(game, "hud-group-resumed.png"); hudAction("next");
        } else if (uiStage == 9) {
            if (player.state() != PlaybackController.State.PLAYING || player.track().id().value() != 43) return;
            if (uiReadyFrames++ < 10) return;
            capture(game, "hud-group-next.png"); rooms.command(LEAVE, "", 0); uiStage = 11; uiFrames = 0; return;
        }
        uiStage++; uiFrames = 0; uiReadyFrames = 0;
    }
    private void hudAction(String action) throws java.io.IOException { Files.writeString(directory.resolve("room-hud-action.txt"), action); }
    private boolean verifyLyricFont(Minecraft game) throws Exception {
        if (fontStage == 0) {
            capture(game, "player-lyrics-compact.png");
            game.getWindow().setWindowed(1100, 800); game.resizeDisplay(); fixtureHud.reset();
            game.setScreen(new dev.modernmusic.client.ui.HudEditorScreen(null, player, fixtureHud));
            press(game, "modernmusic.hud.part.lyrics");
            for (int i = 0; i < 3; i++) press(game, "modernmusic.hud.lyric_style");
            var slider = game.screen.children().stream().filter(net.minecraft.client.gui.components.AbstractSliderButton.class::isInstance)
                    .map(net.minecraft.client.gui.components.AbstractSliderButton.class::cast)
                    .filter(widget -> widget.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                            && text.getKey().equals("modernmusic.hud.lyric_size")).findFirst().orElseThrow();
            double x = slider.getX() + 4 + (slider.getWidth() - 8) * ((1.5 - .25) / 2.75);
            slider.mouseClicked(x, slider.getY() + 20, 0); slider.mouseReleased(x, slider.getY() + 20, 0);
            if (fixtureHud.lyricStyle() != HudSettings.LyricStyle.BOLD_ITALIC || Math.abs(fixtureHud.component(HudSettings.Part.LYRICS).scale() - 1.5) > .01)
                throw new IllegalStateException("Font controls did not update lyrics");
        } else if (fontStage == 1) {
            capture(game, "hud-font-editor.png");
            game.getWindow().setWindowed(720, 600); game.resizeDisplay();
        } else if (fontStage == 2) {
            capture(game, "hud-font-editor-compact.png"); press(game, "gui.done");
            var reloaded = new HudSettings(directory.resolve("fixture-hud.json"));
            if (reloaded.lyricStyle() != HudSettings.LyricStyle.BOLD_ITALIC || Math.abs(reloaded.component(HudSettings.Part.LYRICS).scale() - 1.5) > .01)
                throw new IllegalStateException("Font save/reload failed");
            game.setScreen(new dev.modernmusic.client.ui.HudEditorScreen(null, player, fixtureHud));
            press(game, "modernmusic.hud.expand"); press(game, "modernmusic.hud.part.lyrics");
            press(game, "modernmusic.hud.lyric_style"); press(game, "gui.cancel");
            if (fixtureHud.lyricStyle() != HudSettings.LyricStyle.BOLD_ITALIC) throw new IllegalStateException("Cancel lost lyric font");
            game.setScreen(new PlayerScreen(null, player, fixtureAccount, fixtureHud)); return true;
        }
        fontStage++; return false;
    }
    private long p95() { var sorted = errors.stream().sorted().toList(); return sorted.isEmpty() ? Long.MAX_VALUE : sorted.get((int) ((sorted.size() - 1) * .95)); }
    private void verifyMeasurement() { if (errors.size() < 500 || p95() > 100) throw new IllegalStateException("Audio error P95=" + p95() + " samples=" + errors.size()); }
    private void finish(Minecraft game, String result) {
        step = -1; ModernMusic.LOGGER.info(result);
        try { Files.writeString(directory.resolve("room-result.txt"), result + "\n"); }
        catch (Exception failure) { ModernMusic.LOGGER.error("Cannot write room smoke result"); }
        if (fixtureAccount != null) fixtureAccount.close();
        if (rooms != null) rooms.disconnect(); if (player != null) player.close(); game.stop();
    }
    private void press(Minecraft game, String key) {
        var widget = game.screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
                .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).filter(button ->
                        button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().equals(key))
                .findFirst().orElseThrow();
        if (!widget.active) throw new IllegalStateException("Disabled GUI action: " + key);
        widget.mouseClicked(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0);
    }
    private void capture(Minecraft game, String name) throws java.io.IOException {
        try (var frame = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) { frame.writeToFile(directory.resolve(name)); }
    }
    private static byte[] silentMp3() {
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < (Boolean.getBoolean("modernmusic.publicRoomSmoke") ? 180 : 12_000); i++) {
            byte[] frame = new byte[144 * 128_000 / 44_100];
            frame[0] = (byte) 0xff; frame[1] = (byte) 0xfb; frame[2] = (byte) 0x90;
            bytes.writeBytes(frame);
        }
        return bytes.toByteArray();
    }
    /** Delay the guest's real socket stream by 50 ± 30 ms each way, preserving TCP ordering. */
    private static final class TestLatency extends io.netty.channel.ChannelDuplexHandler {
        private final Random random = new Random(163);
        private long incoming, outgoing;
        private long due(boolean inbound) {
            long now = System.nanoTime(); long target = now + (20 + random.nextInt(61)) * 1_000_000L;
            // Strictly increasing deadlines: equal deadlines could be reordered by scheduling overhead.
            if (inbound) { incoming = Math.max(incoming + 1_000_000, target); return Math.max(0, incoming - now); }
            outgoing = Math.max(outgoing + 1_000_000, target); return Math.max(0, outgoing - now);
        }
        @Override public void channelRead(io.netty.channel.ChannelHandlerContext context, Object message) {
            context.executor().schedule(() -> {
                if (context.channel().isActive()) context.fireChannelRead(message);
                else io.netty.util.ReferenceCountUtil.release(message);
            }, due(true), TimeUnit.NANOSECONDS);
        }
        @Override public void write(io.netty.channel.ChannelHandlerContext context, Object message, io.netty.channel.ChannelPromise promise) {
            context.executor().schedule(() -> {
                if (context.channel().isActive()) context.writeAndFlush(message, promise);
                else { io.netty.util.ReferenceCountUtil.release(message); promise.tryFailure(new java.nio.channels.ClosedChannelException()); }
            }, due(false), TimeUnit.NANOSECONDS);
        }
    }
}
