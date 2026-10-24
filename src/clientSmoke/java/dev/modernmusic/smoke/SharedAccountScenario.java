package dev.modernmusic.smoke;

import dev.modernmusic.client.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.auth.AccountController;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Two-client shared-account packets and PCM output using synthetic credentials and audio. */
public final class SharedAccountScenario {
    private final boolean host;
    private final Path directory, peer;
    private int step, frames;
    private boolean playRequested;
    private final SharedAdminScenario administration;
    private boolean adminDone;
    private final SharedDisplayScenario display = new SharedDisplayScenario();
    private boolean displayDone, deniedScreen;
    public SharedAccountScenario(boolean host, Path directory, Path peer) {
        this.host = host; this.directory = directory; this.peer = peer; administration = new SharedAdminScenario(host, directory, peer);
    }
    public String tick(Minecraft game, RoomClient rooms, PlaybackController player, AccountController account) throws Exception {
        var source = SharedAccountClient.source();
        if (account.session() != null) throw new AssertionError("M5 client must remain anonymous");
        if (!adminDone) { adminDone = administration.tick(game, account); return null; }
        var room = rooms.update().room();
        switch (step) {
            case 0 -> {
                if (!source.available()) return null;
                game.setScreen(new AccountScreen(null, account)); press(game, "modernmusic.shared.short_title"); step++;
            }
            case 1 -> {
                if (++frames < 15) return null;
                capture(game, "shared-source.png"); press(game, "modernmusic.shared.use_server");
                if (!source.selected()) throw new AssertionError("Source selection failed");
                player.add("42"); step++;
            }
            case 2 -> {
                if (!displayDone) {
                    if (player.state() != PlaybackController.State.PLAYING && frames == 0) return null;
                    frames = 1; displayDone = display.tick(game, player, account); return null;
                }
                if (player.state() != PlaybackController.State.PLAYING || player.audio().positionMs() < 500) return null;
                Files.writeString(directory.resolve("shared-personal.txt"), "PASS anonymous personal playback\n");
                game.getWindow().setWindowed(720, 520); game.options.guiScale().set(2); game.resizeDisplay(); frames = 0; step++;
            }
            case 3 -> {
                if (++frames < 10) return null;
                capture(game, "shared-source-compact.png");
                game.getWindow().setWindowed(1100, 720); game.resizeDisplay();
                if (host) { if (!Files.exists(peer.resolve("shared-personal.txt"))) return null; rooms.command(CREATE, "M5 shared", 0); }
                game.setScreen(new PlayerScreen(null, player, account)); step++;
            }
            case 4 -> {
                if (host) {
                    if (room == null) return null;
                    var other = game.level.players().stream().filter(p -> !p.getUUID().equals(game.player.getUUID())).findFirst();
                    if (other.isEmpty()) return null;
                    rooms.command(INVITE, other.get().getGameProfile().getName(), 0); rooms.command(ADD, "", 43); step++;
                } else {
                    if (rooms.update().invitations().isEmpty()) return null;
                    rooms.join(rooms.update().invitations().getFirst()); step++;
                }
            }
            case 5 -> {
                if (host && room != null && !room.songs().isEmpty() && !playRequested) { rooms.command(PLAY, "", 0); playRequested = true; }
                if (room == null || room.members().size() != 2 || player.state() != PlaybackController.State.PLAYING) return null;
                if (!player.shared() || !source.selected() || player.track().id().value() != 43) throw new AssertionError("Shared room routing failed");
                Files.writeString(directory.resolve("shared-group.txt"), "PASS anonymous group playback\n");
                if (host) {
                    if (!Files.exists(peer.resolve("shared-group.txt"))) return null;
                    game.setScreen(new SharedSourceScreen(null, source)); press(game, "modernmusic.admin.manage");
                }
                step++;
            }
            case 6 -> {
                if (host) {
                    if (!SharedAdminScenario.ready(SharedAccountClient.admin())) return null;
                    if (!SharedAccountClient.admin().snapshot().operatorsOnly()) { press(game, "modernmusic.admin.all"); return null; }
                    if (!Files.exists(peer.resolve("shared-denied.txt"))) return null;
                    if (!source.available()) throw new AssertionError("Host permission revoked unexpectedly");
                    press(game, "modernmusic.admin.ops");
                } else {
                    if (!source.statusKey().equals("modernmusic.error.shared_forbidden")) return null;
                    if (!source.profile().nickname().isEmpty() || source.profile().avatarUri() != null) throw new AssertionError("Revoked source retains public identity");
                    if (!deniedScreen) { game.setScreen(new PlayerScreen(null, player, account)); SharedDisplayScenario.focusAccount(game); deniedScreen = true; frames = 0; return null; }
                    if (++frames < 15) return null;
                    String tooltip = SharedDisplayScenario.accountTooltip(game);
                    if (tooltip.contains("Server fixture") || !tooltip.contains("没有使用服务器共享账号的权限")) throw new AssertionError("Revoked tooltip shows wrong identity/status");
                    capture(game, "shared-player-forbidden.png");
                    if (player.audio().state() == dev.modernmusic.client.audio.AudioOutput.State.PLAYING) throw new AssertionError("Denied client still playing");
                    Files.writeString(directory.resolve("shared-denied.txt"), "PASS permission revocation\n");
                }
                step++;
            }
            case 7 -> {
                if (!source.available() || player.state() != PlaybackController.State.PLAYING) return null;
                if (host) {
                    if (!Files.exists(peer.resolve("shared-restored.txt"))) return null;
                    if (!SharedAdminScenario.ready(SharedAccountClient.admin())) return null;
                    press(game, "modernmusic.admin.disable");
                } else Files.writeString(directory.resolve("shared-restored.txt"), "PASS room resync after permission restore\n");
                step++;
            }
            case 8 -> {
                if (!source.statusKey().equals("modernmusic.error.shared_disabled")) return null;
                if (player.audio().state() == dev.modernmusic.client.audio.AudioOutput.State.PLAYING) throw new AssertionError("Disabled source still playing");
                if (host) {
                    if (!Files.exists(peer.resolve("shared-disabled.txt"))) return null;
                    if (!SharedAdminScenario.ready(SharedAccountClient.admin())) return null;
                    press(game, "modernmusic.admin.enable");
                } else Files.writeString(directory.resolve("shared-disabled.txt"), "PASS shutdown stopped audio\n");
                step++;
            }
            case 9 -> {
                if (!source.available() || player.state() != PlaybackController.State.PLAYING) return null;
                if (host) {
                    if (!Files.exists(peer.resolve("shared-enabled.txt"))) return null;
                    game.getSingleplayerServer().execute(SharedAccountFixture::expire);
                } else Files.writeString(directory.resolve("shared-enabled.txt"), "PASS re-enable resync\n");
                step++;
            }
            case 10 -> {
                if (!source.statusKey().equals("modernmusic.error.shared_expired")) return null;
                if (player.audio().state() == dev.modernmusic.client.audio.AudioOutput.State.PLAYING) throw new AssertionError("Expired source still playing");
                Files.writeString(directory.resolve("shared-expired.txt"), "PASS session expiry\n"); step++;
            }
            case 11 -> {
                if (host) {
                    if (!Files.exists(peer.resolve("shared-expired.txt"))) return null;
                    if (SharedAccountFixture.resolves < 2) throw new AssertionError("Server was not used to resolve audio");
                }
                source.select(false); rooms.command(LEAVE, "", 0); step++;
            }
            case 12 -> {
                if (player.shared()) return null;
                if (source.selected() || player.queue().entries().size() != 1 || player.queue().entries().getFirst().value() != 42)
                    throw new AssertionError("Local source / queue restoration failed");
                Files.writeString(directory.resolve("shared-done.txt"), "PASS\n");
                if (host && !Files.exists(peer.resolve("shared-done.txt"))) return null;
                return "PASS: M5 shared profile/avatar, GUI/HUD/tooltip, local-source restore and denied display; admin binding/permission; anonymous personal/group audio and UI access/off/on";
            }
        }
        return null;
    }
    private void press(Minecraft game, String key) {
        SharedAdminScenario.press(game, key);
    }
    private void capture(Minecraft game, String name) {
        net.minecraft.client.Screenshot.grab(game.gameDirectory, name, game.getMainRenderTarget(), message -> {});
    }
}
