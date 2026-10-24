package dev.modernmusic.smoke;

import dev.modernmusic.auth.*;
import dev.modernmusic.client.ui.AccountScreen;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import java.time.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Real account widgets with deterministic upstream failures; no account or network access. */
final class AccountQrScenario implements AutoCloseable {
    private final TestClock clock = new TestClock();
    private final Fixture provider = new Fixture();
    private final AccountController account;
    private final AccountScreen screen;
    private int step;

    AccountQrScenario(Minecraft game) {
        account = new AccountController(provider,
                new RememberedSessionStore(game.gameDirectory.toPath().resolve("account-qr-fixture")),
                game::execute, () -> {}, clock);
        screen = new AccountScreen(null, account);
        game.setScreen(screen);
    }

    boolean tick(Minecraft game) throws Exception {
        if (account.busy()) return false;
        screen.tick();
        switch (step) {
            case 0 -> {
                press("modernmusic.auth.qr_login"); screen.tick();
                require(!hasInput(), "QR request did not leave the Cookie page");
                step++;
            }
            case 1 -> {
                require("modernmusic.error.network_error".equals(account.message()), "Generation failure was not delivered");
                requireQrPage(true);
                press("modernmusic.auth.qr_refresh"); screen.tick();
                requireQrPage(false); step++;
            }
            case 2 -> {
                require(account.qr() != null, "Retry did not obtain a new challenge");
                var matrix = AccountScreen.class.getDeclaredField("matrix"); matrix.setAccessible(true);
                require(matrix.get(screen) != null, "Retry did not render a QR matrix");
                requireQrPage(false);
                provider.pollFailure = true; account.tick(); step++;
            }
            case 3 -> {
                require("modernmusic.error.unavailable".equals(account.message()), "Polling failure was not delivered");
                requireQrPage(true);
                press("modernmusic.auth.qr_refresh"); step++;
            }
            case 4 -> {
                clock.advance(121); account.tick(); screen.tick();
                require("modernmusic.auth.qr_expired".equals(account.message()), "Challenge did not expire");
                requireQrPage(true);
                press("modernmusic.auth.qr_refresh"); step++;
            }
            case 5 -> {
                require(account.qrActive(), "New challenge was not active");
                press("gui.cancel"); screen.tick();
                require(!account.qrActive() && hasInput(), "Explicit cancel did not return to login methods");
                provider.pollFailure = false;
                press("modernmusic.auth.qr_login"); step++;
            }
            case 6 -> {
                game.setScreen(null);
                require(!account.qrActive(), "Closing screen left a QR request active");
                game.setScreen(screen); screen.tick();
                require(hasInput(), "Reopening screen retained the old QR page");
                press("modernmusic.auth.qr_login"); step++;
            }
            case 7 -> {
                provider.authorize = true; account.tick(); step++;
            }
            case 8 -> { account.tick(); step++; }
            case 9 -> {
                require(account.session() != null, "Authorized account was not installed");
                require(!hasInput() && button("modernmusic.auth.qr_refresh") == null,
                        "Successful sign-in did not leave the QR page");
                require(button("modernmusic.auth.logout") != null, "Signed-in account controls are missing");
                return true;
            }
            default -> throw new AssertionError("Unknown account QR step " + step);
        }
        return false;
    }

    private void requireQrPage(boolean retryEnabled) {
        require(!hasInput(), "QR failure unexpectedly returned to Cookie input");
        var retry = button("modernmusic.auth.qr_refresh");
        require(retry != null && retry.active == retryEnabled, "QR retry availability is incorrect");
    }
    private boolean hasInput() { return screen.children().stream().anyMatch(EditBox.class::isInstance); }
    private Button button(String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text && key.equals(text.getKey()))
                .findFirst().orElse(null);
    }
    private void press(String key) {
        var target = button(key);
        require(target != null && target.active, "Missing or inactive account action " + key);
        require(screen.mouseClicked(screen.toGuiCoordinate(target.getX() + target.getWidth() / 2.0),
                screen.toGuiCoordinate(target.getY() + target.getHeight() / 2.0), 0), "Account action was not clickable " + key);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @Override public void close() { account.close(); }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private final class Fixture implements AccountProvider {
        private int generations;
        private boolean pollFailure, authorize;
        @Override public MusicPlatform platform() { return MusicPlatform.NETEASE; }
        @Override public AccountSession validate(SessionCookie cookie) {
            return new AccountSession("42", "QR regression fixture", cookie, clock.instant());
        }
        @Override public QrChallenge beginQr() {
            if (++generations == 1) throw new MusicException(MusicError.NETWORK_ERROR);
            return new QrChallenge("qr-regression-fixture-" + generations, clock.instant().plusSeconds(120));
        }
        @Override public QrPoll pollQr(QrChallenge challenge) {
            if (pollFailure) throw new MusicException(MusicError.UNAVAILABLE);
            return authorize ? new QrPoll(QrState.AUTHORIZED, SessionCookie.parse("MUSIC_U=qr-regression-fixture"))
                    : new QrPoll(QrState.WAITING, null);
        }
    }
}
