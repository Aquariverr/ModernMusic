package dev.modernmusic.smoke;

import dev.modernmusic.client.*;
import dev.modernmusic.room.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Real socket/decoder regression: an ordinary listener supplies timing while the last OP mutes, then leaves. */
final class PublicRoomScenario {
    private final boolean host;
    private final Path peer;
    private boolean joined, controllerLeft;
    private int stage;
    private final Set<Integer> timedTracks = new HashSet<>();
    PublicRoomScenario(boolean host, Path peer) { this.host = host; this.peer = peer; }

    String tick(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        if (!joined) {
            var entry = rooms.update().savedRooms().stream().filter(RoomUpdate.SavedRoom::isPublicRoom).findFirst();
            if (entry.isPresent()) { rooms.join(entry.get()); joined = true; }
            return null;
        }
        var room = rooms.update().room();
        if (host && stage == 6) {
            var result = peer.resolve("room-result.txt");
            return Files.exists(result) && Files.readString(result).startsWith("PASS:")
                    ? "PASS: public room advances with last OP muted and after OP leaves; ordinary listener reports actual decoded duration" : null;
        }
        if (room == null) return null;
        if (!room.isPublicRoom()) throw new AssertionError("Joined the wrong room");
        if (player.state() == PlaybackController.State.ERROR) throw new AssertionError(player.message());
        if (host) {
            switch (stage) {
                case 0 -> {
                    if (room.members().size() != 2) return null;
                    if (!room.canManage(game.player.getUUID())) throw new AssertionError("Host must be OP");
                    rooms.command(CLEAR, "", 0); stage++;
                }
                case 1 -> { if (room.songs().isEmpty()) { rooms.command(ADD, "", 42); stage++; } }
                case 2 -> { if (room.songs().size() == 1) { rooms.command(ADD, "", 43); stage++; } }
                case 3 -> { if (room.songs().size() == 2) { rooms.command(ADD, "", 44); stage++; } }
                case 4 -> {
                    if (room.songs().size() != 3) return null;
                    rooms.toggleListening(); rooms.command(PLAY, "", 0); stage++;
                }
                case 5 -> {
                    if (rooms.listening() || player.roomDurationMs() != 0) throw new AssertionError("Muted host retained decoded timing");
                    if (room.index() == 1 && room.durationMs() > 0) { rooms.command(LEAVE, "", 0); stage++; }
                }
                default -> { }
            }
        } else {
            if (room.canControl(game.player.getUUID()) || room.canRequest(game.player.getUUID()))
                throw new AssertionError("Listener unexpectedly received control/request permissions");
            if (room.state() == RoomSnapshot.State.PLAYING && room.durationMs() > 0 && player.roomDurationMs() > 0) {
                if (room.durationMs() != player.roomDurationMs()) throw new AssertionError("Server did not use decoded duration");
                timedTracks.add(room.index());
                if (room.index() == 2 && room.members().size() == 1) controllerLeft = true;
            }
            if (controllerLeft && timedTracks.size() == 3 && room.state() == RoomSnapshot.State.STOPPED)
                return "PASS: listener decoded/reported all three tracks, automatic next and stop after last OP muted/left, control permissions unchanged";
        }
        return null;
    }
}
