package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.RoomClient;
import dev.modernmusic.client.ui.MusicScreen;
import dev.modernmusic.client.ui.RoomScreen;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.network.ForgeNetwork;
import dev.modernmusic.network.RoomNetwork;
import dev.modernmusic.room.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Public-room UI and authority over the real host/guest socket; synthetic music only. */
final class PublicRoomScenario {
    private final boolean host;
    private final Path directory, peer;
    private int stage, frames, loggedStage = -1;
    private long stageStarted, nextLog, responseAfter;
    private UUID guestId;
    private String guestName;

    PublicRoomScenario(boolean host, Path directory, Path peer) {
        this.host = host; this.directory = directory; this.peer = peer;
    }

    String tick(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        long now = RoomNetwork.now();
        if (loggedStage != stage || now >= nextLog) {
            if (loggedStage != stage) stageStarted = now;
            loggedStage = stage; nextLog = now + 10_000;
            ModernMusic.LOGGER.info("Public room smoke {} stage={} room={} message={}", host ? "host" : "guest", stage,
                    rooms.update().room() == null ? "none" : rooms.update().room().revision(), rooms.message());
        }
        check(now - stageStarted < 60_000, "Public room " + (host ? "host" : "guest") + " timed out at stage " + stage);
        check(player.state() != PlaybackController.State.ERROR, "Fixture playback failed: " + player.message());
        return host ? host(game, rooms, player) : guest(game, rooms, player);
    }

    private String host(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        var room = rooms.update().room();
        UUID local = game.player.getUUID();
        switch (stage) {
            case 0 -> {
                if (!publicAvailable(rooms)) return null;
                check(room == null && rooms.update().invitations().isEmpty(), "Public room automatically joined or invited host");
                game.setScreen(new RoomScreen(null, rooms)); stage++;
            }
            case 1 -> {
                if (++frames < 20) return null;
                capture(game, "public-lobby-host.png"); joinPublic(game); frames = 0; stage++;
            }
            case 2 -> {
                if (room == null) return null;
                check(room.isPublicRoom() && room.canManage(local), "Integrated server owner lacks public administrator authority");
                check(member(room, local).administrator(), "Administrator assertion missing from actual network snapshot");
                game.screen.tick(); checkNoPrivateActions(game); stage++;
            }
            case 3 -> {
                if (++frames < 20) return null;
                capture(game, "public-administrator.png"); signal("host-ready"); frames = 0; stage++;
            }
            case 4 -> {
                if (!peerSays("listener-denied") || room.members().size() != 2) return null;
                var guest = room.members().stream().filter(m -> !m.id().equals(local)).findFirst().orElseThrow();
                guestId = guest.id(); guestName = guest.name();
                check(!guest.administrator() && !room.canRequest(guestId), "Guest received implicit public permissions");
                openMemberMenu(game, guestName); stage++;
            }
            case 5 -> {
                if (++frames < 15) return null;
                capture(game, "public-administrator-menu.png"); press(game, "modernmusic.room.requester"); frames = 0; stage++;
            }
            case 6 -> {
                if (!room.canRequest(guestId) || room.canControl(guestId)) return null;
                signal("requester"); stage++;
            }
            case 7 -> {
                if (!peerSays("requested") || !room.songs().equals(List.of("42"))) return null;
                openMemberMenu(game, guestName); press(game, "modernmusic.room.dj"); stage++;
            }
            case 8 -> {
                if (!member(room, guestId).dj()) return null;
                signal("dj"); stage++;
            }
            case 9 -> {
                if (!peerSays("dj-played") || room.state() != RoomSnapshot.State.PLAYING
                        || room.durationMs() <= 0 || player.state() != PlaybackController.State.PLAYING) return null;
                openMemberMenu(game, guestName); press(game, "modernmusic.room.listener"); stage++;
            }
            case 10 -> {
                if (room.canRequest(guestId) || room.canControl(guestId)) return null;
                signal("revoked"); stage++;
            }
            case 11 -> {
                if (!peerSays("revoked-denied")) return null;
                check(room.state() == RoomSnapshot.State.PLAYING && room.songs().equals(List.of("42")), "Revoked guest modified shared playback");
                openMemberMenu(game, guestName); press(game, "modernmusic.room.requester"); stage++;
            }
            case 12 -> {
                if (!member(room, guestId).requester()) return null;
                signal("retained"); stage++;
            }
            case 13 -> {
                if (!peerSays("left") || room.members().size() != 1) return null;
                check(room.songs().equals(List.of("42")), "Public queue lost when guest left"); signal("rejoin"); stage++;
            }
            case 14 -> {
                if (!peerSays("rejoined") || room.members().size() != 2) return null;
                check(room.isPublicRoom() && member(room, guestId).requester() && room.songs().equals(List.of("42")),
                        "Rejoin lost public identity, request grant or queue");
                rooms.command(ADD, "", 43); signal("continuation"); stage++;
            }
            case 15 -> {
                if (!peerSays("continuation-ready") || !room.songs().equals(List.of("42", "43"))) return null;
                check(room.durationMs() > 1_000, "Missing current duration before unattended continuation");
                rooms.command(SEEK, "", room.durationMs() - 250); stage++;
            }
            case 16 -> {
                if (room.positionMs() < room.durationMs() - 250) return null;
                rooms.command(LEAVE, "", 0); stage++;
            }
            case 17 -> {
                if (room != null) return null;
                signal("unattended"); stage++;
            }
            case 18 -> {
                if (!peerSays("continued")) return null;
                signal("done"); stage++;
            }
            case 19 -> {
                var result = peer.resolve("room-result.txt");
                if (Files.exists(result) && Files.readString(result).startsWith("PASS:"))
                    return "PASS: public lobby / administrator UI / requester and DJ grants / revoke / leave-rejoin / unattended continuation with playback-bound listener duration";
            }
        }
        return null;
    }

    private String guest(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        var room = rooms.update().room();
        UUID local = game.player.getUUID();
        switch (stage) {
            case 0 -> {
                if (!publicAvailable(rooms)) return null;
                check(room == null && rooms.update().invitations().isEmpty(), "Public room automatically joined or invited guest");
                player.queue().add(new SongId(7)); game.setScreen(new RoomScreen(null, rooms)); stage++;
            }
            case 1 -> {
                if (++frames < 20 || !peerSays("host-ready")) return null;
                capture(game, "public-lobby-guest.png"); joinPublic(game); frames = 0; stage++;
            }
            case 2 -> {
                if (room == null) return null;
                check(room.isPublicRoom() && !room.canManage(local) && !room.canRequest(local) && !room.canControl(local),
                        "Guest was not a public listener by default");
                check(room.members().stream().anyMatch(RoomSnapshot.Member::administrator), "Guest cannot see the administrator role");
                check(rooms.update().invitations().isEmpty(), "Public join unexpectedly required an invitation");
                game.screen.tick(); checkNoPrivateActions(game);
                check(!widget(game, "modernmusic.search.open").active, "Listener search UI allows requests"); stage++;
            }
            case 3 -> {
                if (++frames < 20) return null;
                capture(game, "public-listener.png"); responseAfter = rooms.update().sequence(); sendRaw(room, ADD, 99); frames = 0; stage++;
            }
            case 4 -> {
                if (!response(rooms, "modernmusic.room.error.request_permission")) return null;
                check(room.songs().isEmpty(), "Server accepted a listener's raw ADD packet");
                responseAfter = rooms.update().sequence(); sendRaw(room, STOP, 0); stage++;
            }
            case 5 -> {
                if (!response(rooms, "modernmusic.room.error.permission")) return null;
                signal("listener-denied"); stage++;
            }
            case 6 -> {
                if (!peerSays("requester") || !room.canRequest(local)) return null;
                check(!room.canControl(local) && member(room, local).requester(), "Requester gained DJ controls");
                game.screen.tick(); check(widget(game, "modernmusic.search.open").active, "Granted requester search UI stayed disabled");
                rooms.addSong(new SongId(42)); stage++;
            }
            case 7 -> {
                if (!room.songs().equals(List.of("42"))) return null;
                signal("requested"); stage++;
            }
            case 8 -> {
                if (!peerSays("dj") || !room.canControl(local)) return null;
                check(member(room, local).dj() && !member(room, local).administrator(), "DJ promotion incorrectly granted administrator");
                game.screen.tick(); press(game, "modernmusic.play"); stage++;
            }
            case 9 -> {
                if (room.state() != RoomSnapshot.State.PLAYING || player.state() != PlaybackController.State.PLAYING
                        || room.durationMs() <= 0 || player.audio().positionMs() < 300) return null;
                capture(game, "public-dj.png"); signal("dj-played"); stage++;
            }
            case 10 -> {
                if (!peerSays("revoked") || room.canRequest(local) || room.canControl(local)) return null;
                game.screen.tick(); check(!widget(game, "modernmusic.pause").active, "Revoked DJ retains active playback UI");
                responseAfter = rooms.update().sequence(); sendRaw(room, ADD, 99); stage++;
            }
            case 11 -> {
                if (!response(rooms, "modernmusic.room.error.request_permission")) return null;
                check(room.songs().equals(List.of("42")), "Server accepted revoked guest's raw ADD");
                responseAfter = rooms.update().sequence(); sendRaw(room, STOP, 0); stage++;
            }
            case 12 -> {
                if (!response(rooms, "modernmusic.room.error.permission")) return null;
                check(room.state() == RoomSnapshot.State.PLAYING, "Server accepted revoked DJ's raw STOP");
                signal("revoked-denied"); stage++;
            }
            case 13 -> {
                if (!peerSays("retained") || !member(room, local).requester()) return null;
                game.screen.tick(); press(game, "modernmusic.room.leave"); stage++;
            }
            case 14 -> {
                if (room != null) return null;
                check(!player.shared() && player.state() == PlaybackController.State.STOPPED
                        && player.queue().entries().equals(List.of(new SongId(7))), "Leave did not restore personal queue");
                check(publicAvailable(rooms), "Public lobby entry disappeared after leave");
                game.screen.tick(); signal("left"); stage++;
            }
            case 15 -> {
                if (!peerSays("rejoin")) return null;
                check(rooms.update().invitations().isEmpty(), "Public rejoin unexpectedly required an invitation");
                joinPublic(game); stage++;
            }
            case 16 -> {
                if (room == null) return null;
                check(room.isPublicRoom() && member(room, local).requester() && !room.canControl(local)
                        && room.songs().equals(List.of("42")), "Rejoin lost requester grant or public queue");
                if (player.state() != PlaybackController.State.PLAYING) return null;
                game.screen.tick(); signal("rejoined"); stage++;
            }
            case 17 -> {
                if (!peerSays("continuation")) return null;
                if (++frames < 15) return null;
                capture(game, "public-rejoined-requester.png");
                signal("continuation-ready"); stage++;
            }
            case 18 -> {
                if (!peerSays("unattended") || room.index() != 1 || room.members().size() != 1
                        || room.durationMs() <= 0 || player.state() != PlaybackController.State.PLAYING
                        || player.track() == null || !player.track().id().equals(new SongId(43))) return null;
                check(!room.canControl(local) && !room.allows(local, DURATION), "Duration report granted playback controls");
                check(room.state() == RoomSnapshot.State.PLAYING, "Unattended room did not continue");
                signal("continued"); stage++;
            }
            case 19 -> {
                if (!peerSays("done")) return null;
                return "PASS: public GUI / raw permission rejection / requester and DJ / leave-rejoin / ordinary listener continues and reports the next song after administrator leaves";
            }
        }
        return null;
    }

    private static boolean publicAvailable(RoomClient rooms) {
        return rooms.available() && rooms.update().savedRooms().stream().anyMatch(RoomUpdate.SavedRoom::isPublicRoom);
    }
    private boolean response(RoomClient rooms, String error) {
        return rooms.update().sequence() > responseAfter && rooms.message().equals(error);
    }
    private static RoomSnapshot.Member member(RoomSnapshot room, UUID id) {
        return room.members().stream().filter(member -> member.id().equals(id)).findFirst().orElseThrow();
    }
    private static List<AbstractWidget> widgets(Minecraft game) {
        return game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
    }
    private static boolean key(AbstractWidget widget, String key) {
        return widget.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key);
    }
    private static AbstractWidget widget(Minecraft game, String key) {
        return widgets(game).stream().filter(widget -> key(widget, key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing public-room UI action: " + key));
    }
    private static void checkNoPrivateActions(Minecraft game) {
        check(widgets(game).stream().noneMatch(widget -> key(widget, "modernmusic.room.invite") || key(widget, "modernmusic.room.dissolve")),
                "Public room exposes invitation or dissolution action");
    }
    private static void joinPublic(Minecraft game) {
        game.screen.tick();
        String name = Component.translatable("modernmusic.room.public_name").getString();
        var entry = widgets(game).stream().filter(widget -> key(widget, "modernmusic.room.join_named")
                && widget.getMessage().getString().contains(name)).findFirst().orElseThrow();
        click(game, entry, 0);
    }
    private static void openMemberMenu(Minecraft game, String name) {
        game.screen.tick();
        var row = widgets(game).stream().filter(widget -> widget.getClass().getSimpleName().equals("MemberRow")
                && widget.getMessage().getString().equals(name)).findFirst().orElseThrow();
        click(game, row, 1); widget(game, "modernmusic.room.requester");
    }
    private static void press(Minecraft game, String key) { click(game, widget(game, key), 0); }
    private static void click(Minecraft game, AbstractWidget widget, int button) {
        check(widget.active, "Disabled public-room GUI action: " + widget.getMessage().getString());
        var screen = (MusicScreen) game.screen;
        check(screen.mouseClicked(screen.toGuiCoordinate(widget.getX() + 8), screen.toGuiCoordinate(widget.getY() + 8), button),
                "Public-room GUI click was not handled");
    }
    private static void sendRaw(RoomSnapshot room, RoomCommand.Action action, long value) {
        ForgeNetwork.sendToServer(new RoomNetwork.CommandPayload(RoomJson.encode(
                new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value))));
    }
    private void signal(String value) throws Exception { Files.writeString(directory.resolve("public-stage.txt"), value); }
    private boolean peerSays(String value) throws Exception {
        var marker = peer.resolve("public-stage.txt"); return Files.exists(marker) && Files.readString(marker).equals(value);
    }
    private void capture(Minecraft game, String name) throws Exception {
        try (var image = net.minecraft.client.Screenshot.takeScreenshot(game.getMainRenderTarget())) { image.writeToFile(directory.resolve(name)); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
