package dev.modernmusic.smoke;

import dev.modernmusic.client.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.auth.AccountController;
import dev.modernmusic.network.SharedAdminNetwork;
import dev.modernmusic.shared.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.network.PacketDistributor;

/** Exercises real admin packets and widgets, with a synthetic upstream QR/cookie only. */
final class SharedAdminScenario {
    private final boolean host;
    private final Path directory, peer;
    private int step, frames, loggedStep = -1;
    private SharedAdminSnapshot denied;
    SharedAdminScenario(boolean host, Path directory, Path peer) { this.host = host; this.directory = directory; this.peer = peer; }
    boolean tick(Minecraft game, AccountController account) throws Exception {
        var admin = SharedAccountClient.admin(); var state = admin.snapshot();
        if (loggedStep != step) { loggedStep = step; dev.modernmusic.ModernMusic.LOGGER.info("Shared admin smoke {} step {}", host ? "host" : "guest", step); }
        if (!host) return guest(game, account, admin, state);
        switch (step) {
            case 0 -> {
                if (!admin.allowed()) return false;
                game.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                open(game, account); step++;
            }
            case 1 -> {
                if (!ready(admin) || state.bound()) return false;
                if (!state.cookieUpload()) throw new AssertionError("Integrated owner cannot bind a cookie");
                capture(game, "shared-admin-empty.png"); press(game, "modernmusic.admin.cookie"); step++;
            }
            case 2 -> {
                game.screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow().setValue(SharedAccountFixture.cookie());
                press(game, "modernmusic.admin.bind_cookie"); step++;
            }
            case 3 -> {
                if (!ready(admin) || !state.bound() || !state.saved()) return false;
                capture(game, "shared-admin-bound.png"); forget(game); step++;
            }
            case 4 -> {
                if (!ready(admin) || state.bound()) return false;
                press(game, "modernmusic.admin.qr"); step++;
            }
            case 5 -> {
                if (state == null || state.qrKey().isEmpty()) return false;
                if (++frames < 15) return false;
                capture(game, "shared-admin-qr.png");
                game.getWindow().setWindowed(720, 520); game.resizeDisplay(); frames = 0; step++;
            }
            case 6 -> {
                if (++frames < 15) return false;
                capture(game, "shared-admin-qr-compact.png");
                game.getSingleplayerServer().execute(SharedAccountFixture::authorize); step++;
            }
            case 7 -> {
                if (!ready(admin) || !state.bound() || !state.saved()) return false;
                if (!Files.exists(peer.resolve("admin-denied.txt"))) return false;
                game.getWindow().setWindowed(1100, 720); game.resizeDisplay(); forget(game); step++;
            }
            case 8 -> {
                if (!ready(admin) || state.bound()) return false;
                game.screen.onClose();
                game.getSingleplayerServer().execute(() -> {
                    var guest = game.getSingleplayerServer().getPlayerList().getPlayers().stream().filter(p -> !p.getGameProfile().getName().equals("RoomHost")).findFirst().orElseThrow();
                    var players = game.getSingleplayerServer().getPlayerList();
                    players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(guest.getGameProfile(), 4, false));
                    players.sendPlayerPermissionLevel(guest);
                }); step++;
            }
            case 9 -> {
                if (!Files.exists(peer.resolve("admin-transport.txt"))) return false;
                game.getSingleplayerServer().execute(() -> {
                    var guest = game.getSingleplayerServer().getPlayerList().getPlayers().stream().filter(p -> !p.getGameProfile().getName().equals("RoomHost")).findFirst().orElseThrow();
                    game.getSingleplayerServer().getPlayerList().deop(guest.getGameProfile());
                }); step++;
            }
            case 10 -> {
                if (!Files.exists(peer.resolve("admin-revoked.txt"))) return false;
                press(game, "modernmusic.admin.manage"); step++;
            }
            case 11 -> {
                if (!ready(admin)) return false;
                if (state.bound() || state.qrActive() || !state.qrKey().isEmpty()) throw new AssertionError("Revoked QR remained active on server");
                press(game, "modernmusic.admin.cookie"); step++;
            }
            case 12 -> {
                game.screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow().setValue(SharedAccountFixture.cookie());
                press(game, "modernmusic.admin.bind_cookie"); step++;
            }
            case 13 -> {
                if (!ready(admin) || !state.bound() || !state.saved()) return false;
                press(game, "modernmusic.admin.enable"); frames = 0; step++;
            }
            case 14 -> {
                if (!ready(admin) || !state.enabled()) return false;
                if (++frames < 15) return false;
                capture(game, "shared-admin-enabled.png"); game.screen.onClose();
                Files.writeString(directory.resolve("admin-done.txt"), "PASS local UI cookie/QR binding, private persistence, shared switch, unencrypted OP4 management allowed with warning\n"); step++;
            }
            default -> { return Files.exists(peer.resolve("admin-done.txt")); }
        }
        return false;
    }
    private boolean guest(Minecraft game, AccountController account, SharedAdminController admin, SharedAdminSnapshot state) throws Exception {
        switch (step) {
            case 0 -> {
                if (++frames < 40) return false;
                game.setScreen(new AccountScreen(null, account)); press(game, "modernmusic.shared.short_title");
                if (admin.allowed() || button(game, "modernmusic.admin.manage").visible) throw new AssertionError("Guest has management UI");
                var receiver = SharedAdminNetwork.clientSnapshot;
                UUID forged = UUID.randomUUID();
                SharedAdminNetwork.clientSnapshot = value -> {
                    if (value.view().equals(forged)) denied = value; else receiver.accept(value);
                };
                PacketDistributor.sendToServer(new SharedAdminNetwork.CommandPayload(forged, 1, SharedAdminAction.COOKIE_LOGIN, SharedAccountFixture.cookie()));
                step++;
            }
            case 1 -> {
                if (denied == null) return false;
                if (denied.allowed() || denied.editing() || !denied.qrKey().isEmpty() || !denied.nickname().isEmpty()) throw new AssertionError("Unauthorized payload exposed private state");
                Files.writeString(directory.resolve("admin-denied.txt"), "PASS hidden entry and forged cookie packet rejected\n"); step++;
            }
            case 2 -> {
                if (game.player == null || !game.player.hasPermissions(4) || !admin.allowed()) return false;
                open(game, account); step++;
            }
            case 3 -> {
                if (!ready(admin)) return false;
                if (!state.cookieUpload()) throw new AssertionError("Unencrypted administrator cannot bind cookie");
                admin.request(SharedAdminAction.COOKIE_LOGIN, SharedAccountFixture.cookie()); step++;
            }
            case 4 -> {
                if (!ready(admin) || !state.bound() || !state.saved()) return false;
                admin.request(SharedAdminAction.FORGET, ""); step++;
            }
            case 5 -> {
                if (!ready(admin) || state.bound()) return false;
                admin.request(SharedAdminAction.QR_START, ""); step++;
            }
            case 6 -> {
                if (state == null || state.qrKey().isEmpty()) return false;
                capture(game, "shared-admin-unencrypted-warning.png");
                Files.writeString(directory.resolve("admin-transport.txt"), "PASS OP4 cookie and QR management allowed on unencrypted transport\n"); step++;
            }
            case 7 -> {
                if (admin.allowed() || game.player != null && game.player.hasPermissions(4)) return false;
                if (state != null && (!state.qrKey().isEmpty() || state.editing())) throw new AssertionError("Revoked editor retains QR");
                Files.writeString(directory.resolve("admin-revoked.txt"), "PASS permission revocation cleared private UI\n"); game.screen.onClose(); step++;
            }
            default -> {
                if (!Files.exists(peer.resolve("admin-done.txt"))) return false;
                Files.writeString(directory.resolve("admin-done.txt"), "PASS\n"); return true;
            }
        }
        return false;
    }
    static boolean ready(SharedAdminController admin) { return admin.snapshot() != null && admin.snapshot().editing() && !admin.waiting() && !admin.snapshot().busy(); }
    private static void open(Minecraft game, AccountController account) {
        game.setScreen(new AccountScreen(null, account)); press(game, "modernmusic.shared.short_title"); press(game, "modernmusic.admin.manage");
    }
    private static void forget(Minecraft game) { press(game, "modernmusic.admin.forget"); press(game, "modernmusic.admin.confirm_forget"); }
    private static Button button(Minecraft game, String key) {
        return game.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key)).findFirst().orElseThrow();
    }
    static void press(Minecraft game, String key) {
        game.screen.tick(); Button button = button(game, key);
        if (!button.visible || !button.active) throw new AssertionError("Inactive button " + key);
        button.onPress();
    }
    private static void capture(Minecraft game, String name) { net.minecraft.client.Screenshot.grab(game.gameDirectory, name, game.getMainRenderTarget(), message -> {}); }
}
