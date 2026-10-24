package dev.modernmusic.smoke;

import dev.modernmusic.client.SharedAdminController;
import dev.modernmusic.client.ui.SharedAdminScreen;
import dev.modernmusic.shared.SharedAdminSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Exercises stale admin widgets without sending packets or opening a browser. */
final class IdeInspectionSmoke {
    private IdeInspectionSmoke() {}

    static void verify(Minecraft game) {
        var requests = new ArrayList<SharedAdminController.Request>();
        var admin = new SharedAdminController(requests::add, Clock.systemUTC());
        admin.capability(true);
        var screen = new SharedAdminScreen(null, admin);
        game.setScreen(screen);
        screen.tick();
        if (admin.snapshot() != null || requests.size() != 1)
            throw new AssertionError("Admin must support the initial loading state");

        var request = requests.getFirst();
        admin.receive(verification(request, "https://music.163.com/st/verify?token=inspection-fixture"));
        screen.tick();
        var openVerification = button(screen, "modernmusic.auth.qr_open_verification");
        var sharing = button(screen, "modernmusic.admin.disable");
        var access = button(screen, "modernmusic.admin.all");

        // Same layout, but a newer snapshot has removed its verification URL.
        admin.receive(verification(request, ""));
        openVerification.onPress();
        if (requests.size() != 1) throw new AssertionError("Stale verification action sent a request");

        // Input can retain widgets from the previous layout while the controller disconnects.
        admin.disconnect();
        openVerification.onPress();
        sharing.onPress();
        access.onPress();
        screen.tick();
        if (button(screen, "modernmusic.admin.retry").active || requests.size() != 1)
            throw new AssertionError("Disconnected admin actions must remain inactive");
        SharedProtocolSmoke.verify();
    }

    private static SharedAdminSnapshot verification(SharedAdminController.Request request, String url) {
        return new SharedAdminSnapshot(request.view(), request.sequence(), true, true, true, true, false,
                false, false, false, true, true, "", "modernmusic.auth.qr_security_verification",
                "inspection-fixture", System.currentTimeMillis() + 60_000, url);
    }

    private static Button button(SharedAdminScreen screen, String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getContents() instanceof TranslatableContents contents
                        && key.equals(contents.getKey()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing admin widget: " + key));
    }
}
