package dev.modernmusic.smoke;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.RoomClient;
import dev.modernmusic.client.ui.RoomScreen;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.network.RoomNetwork;
import dev.modernmusic.room.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.modernmusic.network.ForgeNetwork;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Real FTB lifecycle with two actual players. Internal FTB mutations are fixtures only, never shipped. */
final class FtbRoomScenario {
    private final boolean host;
    private final Path directory, peer;
    private int stage, frames, loggedStage = -1;
    private long since, nextLog;
    private UUID hostId, guestId;
    private RoomSnapshot oldRoom;
    private PartyTeam party; // touched exclusively on the integrated server thread

    FtbRoomScenario(boolean host, Path directory, Path peer) {
        this.host = host; this.directory = directory; this.peer = peer;
    }
    String tick(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        if (stage != loggedStage || RoomNetwork.now() >= nextLog) {
            loggedStage = stage;
            nextLog = RoomNetwork.now() + 10_000;
            ModernMusic.LOGGER.info("FTB smoke {} stage={} room={} team={} message={}", host ? "host" : "guest", stage,
                    rooms.update().room() == null ? "none" : rooms.update().room().revision(), rooms.update().team(), rooms.message());
        }
        try {
            if (player.state() == PlaybackController.State.ERROR) throw new IllegalStateException(player.message());
            return host ? host(game, rooms, player) : guest(game, rooms, player);
        } catch (Exception failure) {
            String context = "FTB " + (host ? "host" : "guest") + " stage=" + stage
                    + " room=" + rooms.update().room() + " " + screenState(game);
            ModernMusic.LOGGER.error(context, failure);
            throw new IllegalStateException(context + ": " + failure, failure);
        }
    }
    private String host(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        var room = rooms.update().room();
        switch (stage) {
            case 0 -> {
                if (!peerSays("ready") || rooms.update().team().state() != TeamStatus.State.NO_PARTY) return null;
                hostId = game.player.getUUID();
                server(game, server -> {
                    var owner = server.getPlayerList().getPlayer(hostId);
                    var guest = server.getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(hostId)).findFirst().orElseThrow();
                    guestId = guest.getUUID();
                    var directory = new dev.modernmusic.compat.FtbTeamsDirectory();
                    check(directory.party(hostId).isEmpty() && directory.party(guestId).isEmpty(), "Personal team exposed as party");
                    party = (PartyTeam) FTBTeamsAPI.api().getManager().createPartyTeam(owner, "M4 FTB 队伍", null, null);
                    party.invite(owner, List.of(guest.getGameProfile())); party.join(guest);
                    check(directory.party(guestId).isPresent(), "Actual party membership not found");
                });
                stage++;
            }
            case 1 -> { if (rooms.update().team().state() == TeamStatus.State.READY) { rooms.joinTeam(); stage++; } }
            case 2 -> { if (room != null) { check(room.isTeamRoom(), "Not a team room"); rooms.command(ADD, "", 42); stage++; } }
            case 3 -> { if (room.songs().size() == 1) { rooms.command(ADD, "", 43); stage++; } }
            case 4 -> { if (room.songs().size() == 2) { rooms.command(PLAY, "", 0); stage++; } }
            case 5 -> {
                if (player.audio().positionMs() < 2_000 || player.state() != PlaybackController.State.PLAYING) return null;
                signal("listen"); stage++;
            }
            case 6 -> { if (peerSays("listening")) { rooms.command(PAUSE, "", 0); signal("pause"); stage++; } }
            case 7 -> {
                if (!peerSays("paused")) return null;
                server(game, server -> party.promote(server.getPlayerList().getPlayer(hostId), List.of(server.getPlayerList().getPlayer(guestId).getGameProfile())));
                game.setScreen(new RoomScreen(null, rooms)); game.screen.tick();
                var member = game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                        .filter(w -> w.getClass().getSimpleName().equals("MemberRow") && w.getMessage().getString().contains("RoomGuest"))
                        .findFirst().orElseThrow(() -> new IllegalStateException("RoomGuest member row missing: " + screenState(game)));
                var screen = (dev.modernmusic.client.ui.MusicScreen) game.screen;
                ModernMusic.LOGGER.info("FTB owner context menu before right-click: {}", screenState(game));
                check(screen.mouseClicked(screen.toGuiCoordinate(member.getX() + 8), screen.toGuiCoordinate(member.getY() + 8), 1),
                        "Owner right-click did not open the member menu");
                press(game, "modernmusic.room.dj");
                signal("officer"); stage++;
            }
            case 8 -> {
                if (!peerSays("officer") || room.state() != RoomSnapshot.State.PLAYING || player.audio().positionMs() < 30_000) return null;
                server(game, server -> party.demote(server.getPlayerList().getPlayer(hostId), List.of(server.getPlayerList().getPlayer(guestId).getGameProfile())));
                rooms.command(DJ, "RoomGuest", 0);
                signal("demoted"); stage++;
            }
            case 9 -> {
                if (!peerSays("demoted")) return null;
                server(game, server -> party.leave(guestId)); since = RoomNetwork.now(); signal("left"); stage++;
            }
            case 10 -> {
                if (!peerSays("left")) { check(RoomNetwork.now() - since < 3_000, "Leave revocation exceeded 3s"); return null; }
                server(game, server -> {
                    var guest = server.getPlayerList().getPlayer(guestId);
                    party.invite(server.getPlayerList().getPlayer(hostId), List.of(guest.getGameProfile())); party.join(guest);
                });
                signal("rejoin"); stage++;
            }
            case 11 -> {
                if (!peerSays("rejoined") || room.members().size() != 2) return null;
                server(game, server -> party.transferOwnership(server.getPlayerList().getPlayer(hostId).createCommandSourceStack(), server.getPlayerList().getPlayer(guestId).getGameProfile()));
                signal("transfer"); stage++;
            }
            case 12 -> {
                if (!peerSays("owner") || !room.owner().equals(guestId)) return null;
                server(game, server -> party.forceDisband(server.createCommandSourceStack()));
                since = RoomNetwork.now(); signal("disband"); stage++;
            }
            case 13 -> {
                if (!peerSays("disbanded") || room != null) { check(RoomNetwork.now() - since < 3_000, "Disband revocation exceeded 3s"); return null; }
                rooms.command(CREATE, "M4 普通群组回归", 0); stage++;
            }
            case 14 -> {
                if (room == null) return null;
                check(!room.isTeamRoom(), "Group retained team identity");
                server(game, server -> {
                    ServerPlayer guest = server.getPlayerList().getPlayer(guestId);
                    Files.writeString(directory.resolve("ftb-guest-name.txt"), guest.getGameProfile().getName());
                });
                rooms.command(INVITE, Files.readString(directory.resolve("ftb-guest-name.txt")), 0); signal("group"); stage++;
            }
            case 15 -> {
                if (peerSays("group") && room != null && room.members().size() == 2) {
                    signal("done"); stage++;
                }
            }
            case 16 -> { if (Files.exists(peer.resolve("room-result.txt")) && Files.readString(peer.resolve("room-result.txt")).startsWith("PASS:")) {
                Path saved = game.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/modernmusic-team-queues.json");
                check(Files.readString(saved).contains(party.getId().toString()), "Team playlist was not saved to this world");
                return "PASS: real FTB / owner member context menu / owner-appointed DJ resumes through player UI / playlist saved / leave and disband / ownership transfer / ordinary group recovery";
            } }
        }
        return null;
    }
    private String guest(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        var room = rooms.update().room();
        switch (stage) {
            case 0 -> {
                if (rooms.update().team().state() != TeamStatus.State.NO_PARTY) return null;
                player.queue().add(new SongId(7)); game.setScreen(new RoomScreen(null, rooms)); signal("ready"); stage++;
            }
            case 1 -> {
                if (!peerSays("listen") || rooms.update().team().state() != TeamStatus.State.READY) return null;
                check(room == null, "FTB party force-joined listening");
                if (++frames < 30) return null;
                capture(game, "ftb-join.png"); press(game, "modernmusic.team.join"); since = RoomNetwork.now(); frames = 0; stage++;
            }
            case 2 -> {
                if (room == null || player.state() != PlaybackController.State.PLAYING) { check(RoomNetwork.now() - since < 3_000, "Late join exceeded 3s"); return null; }
                check(room.isTeamRoom() && !room.canControl(game.player.getUUID()) && player.audio().positionMs() > 1_000, "Late join or listener permissions wrong");
                check(player.sharedModeKey().equals("modernmusic.team.mode"), "HUD does not identify team");
                sendRaw(room, STOP); stage++;
            }
            case 3 -> {
                if (!rooms.message().equals("modernmusic.room.error.permission")) return null;
                check(room.state() == RoomSnapshot.State.PLAYING, "Forged control succeeded"); signal("listening"); stage++;
            }
            case 4 -> {
                if (!peerSays("pause") || player.state() != PlaybackController.State.PAUSED) return null;
                if (++frames < 30) return null;
                capture(game, "ftb-room.png"); game.getWindow().setWindowed(720, 600); game.resizeDisplay(); frames = 0; stage++;
            }
            case 5 -> { if (++frames >= 30) { capture(game, "ftb-room-compact.png"); signal("paused"); stage++; } }
            case 6 -> {
                if (!peerSays("officer") || !room.canControl(game.player.getUUID())) return null;
                rooms.command(SEEK, "", 30_000); stage++;
            }
            case 7 -> { if (Math.abs(player.audio().positionMs() - 30_000) < 100) {
                game.setScreen(new dev.modernmusic.client.ui.PlayerScreen(null, player)); game.screen.tick();
                since = RoomNetwork.now(); press(game, "modernmusic.play"); stage++;
            } }
            case 8 -> {
                long elapsed = RoomNetwork.now() - since;
                check(elapsed < 1_500, "Buffered FTB resume exceeded 1.5s");
                if (player.state() == PlaybackController.State.PLAYING && player.audio().positionMs() >= 30_000) {
                    ModernMusic.LOGGER.info("FTB buffered resume completed in {} ms", elapsed);
                    signal("officer"); stage++;
                }
            }
            case 9 -> {
                if (!peerSays("demoted") || room.canControl(game.player.getUUID())) return null;
                rooms.command(SNAPSHOT, "", 0); sendRaw(room, STOP); stage++;
            }
            case 10 -> {
                if (!rooms.message().equals("modernmusic.room.error.permission")) return null;
                check(room.state() == RoomSnapshot.State.PLAYING, "Demoted controller stopped room"); oldRoom = room; signal("demoted"); stage++;
            }
            case 11 -> {
                if (!peerSays("left") || room != null) return null;
                stopped(player); sendRaw(oldRoom, ADD); stage++;
            }
            case 12 -> { if (rooms.message().equals("modernmusic.room.error.member")) { signal("left"); stage++; } }
            case 13 -> {
                if (!peerSays("rejoin") || rooms.update().team().state() != TeamStatus.State.READY) return null;
                check(room == null && !player.shared(), "Party rejoin auto-started listening"); rooms.joinTeam(); stage++;
            }
            case 14 -> { if (room != null && player.state() == PlaybackController.State.PLAYING) { signal("rejoined"); stage++; } }
            case 15 -> { if (peerSays("transfer") && room.owner().equals(game.player.getUUID()) && room.canControl(game.player.getUUID())) { signal("owner"); stage++; } }
            case 16 -> {
                if (!peerSays("disband") || room != null || rooms.update().team().state() != TeamStatus.State.NO_PARTY) return null;
                stopped(player); signal("disbanded"); stage++;
            }
            case 17 -> {
                if (!peerSays("group") || rooms.update().invitations().isEmpty()) return null;
                rooms.join(rooms.update().invitations().get(0)); stage++;
            }
            case 18 -> { if (room != null) { check(!room.isTeamRoom(), "Ordinary group unavailable"); signal("group"); stage++; } }
            case 19 -> { if (peerSays("done")) return "PASS: explicit GUI join / late join / DJ seek and UI resume / forged control denied / DJ revoked / leave restores personal queue / rejoin / transfer / disband / group invitation"; }
        }
        return null;
    }
    private void stopped(PlaybackController player) {
        check(!player.shared() && player.state() == PlaybackController.State.STOPPED && player.queue().entries().equals(List.of(new SongId(7))), "Revocation did not stop and restore local queue");
    }
    private void sendRaw(RoomSnapshot room, RoomCommand.Action action) {
        ForgeNetwork.sendToServer(new RoomNetwork.CommandPayload(RoomJson.encode(new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", action == ADD ? 99 : 0))));
    }
    private void signal(String value) throws Exception { Files.writeString(directory.resolve("ftb-stage.txt"), value); }
    private boolean peerSays(String value) throws Exception { var file = peer.resolve("ftb-stage.txt"); return Files.exists(file) && Files.readString(file).equals(value); }
    private static void check(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
    private interface ServerAction { void run(MinecraftServer server) throws Exception; }
    private void server(Minecraft game, ServerAction action) throws Exception {
        var server = game.getSingleplayerServer(); var done = new CompletableFuture<Void>();
        server.execute(() -> { try { action.run(server); done.complete(null); } catch (Throwable failure) { done.completeExceptionally(failure); } });
        done.get(5, TimeUnit.SECONDS);
    }
    private void capture(Minecraft game, String name) throws Exception {
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(directory.resolve(name)); }
    }
    private void press(Minecraft game, String key) {
        AbstractWidget button = game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(w -> w.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().equals(key))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key + ": " + screenState(game)));
        check(button.active, "Disabled button " + key);
        check(button.mouseClicked(button.getX() + 8, button.getY() + 8, 0), "Button did not accept click: " + key);
    }
    private static String screenState(Minecraft game) {
        var screen = game.screen;
        return "window=" + game.getWindow().getWidth() + "x" + game.getWindow().getHeight()
                + " guiScale=" + game.getWindow().getGuiScale()
                + " screen=" + (screen == null ? "none" : screen.getClass().getSimpleName()
                + " viewport=" + screen.width + "x" + screen.height + " widgets=" + screen.children().stream()
                .filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .map(widget -> widget.getClass().getSimpleName() + "[" + widget.getMessage().getString() + "]@"
                        + widget.getX() + "," + widget.getY() + " active=" + widget.active + " visible=" + widget.visible)
                .toList());
    }
}
