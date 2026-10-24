package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.auth.*;
import dev.modernmusic.client.ui.AccountScreen;
import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Offline real-screen regression: a provider failure must not select Cookie sign-in. */
@Mod(value = "modernmusic_smoke", dist = Dist.CLIENT)
public final class AccountQrSmoke {
    private final TestClock clock = new TestClock();
    private final Fixture provider = new Fixture();
    private final long deadline = System.nanoTime() + 180_000_000_000L;
    private AccountController account;
    private AccountScreen screen;
    private int step, frames;
    private boolean done;

    public AccountQrSmoke() {
        if (Boolean.getBoolean("modernmusic.accountQrSmoke")) NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(ClientTickEvent.Post event) {
        if (done) return;
        var game = Minecraft.getInstance();
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("QR smoke timed out at step " + step);
            if (game.getOverlay() != null) return;
            if (account == null) {
                game.options.onboardingAccessibilityFinished(); game.options.pauseOnLostFocus = false;
                game.options.guiScale().set(2); game.getWindow().setWindowed(854, 480); game.resizeDisplay();
                account = new AccountController(provider, new RememberedSessionStore(Path.of("qr-smoke-account")),
                        game::execute, () -> {}, clock, false, false);
                screen = new AccountScreen(new TitleScreen(), account); game.setScreen(screen);
                press("modernmusic.auth.qr_login"); return;
            }
            account.tick(); screen.tick();
            if (frames-- > 0) return;
            switch (step) {
                case 0 -> {
                    if (account.busy()) return;
                    failure("modernmusic.error.network_error", 1);
                    game.getWindow().setWindowed(680, 480); game.resizeDisplay(); frames = 5; step++;
                }
                case 1 -> {
                    failure("modernmusic.error.network_error", 1);
                    capture(game, "qr-failed-creation.png", false);
                    provider.creationFailure = false; press("modernmusic.auth.qr_retry"); step++;
                }
                case 2 -> {
                    if (account.busy() || account.qr() == null) return;
                    waiting(); frames = 5; step++;
                }
                case 3 -> {
                    waiting(); capture(game, "qr-retried-waiting.png", true);
                    provider.expired = true; clock.advance(3); step++;
                }
                case 4 -> {
                    if (account.busy() || account.qrActive()) return;
                    failure("modernmusic.auth.qr_expired", 2);
                    provider.expired = false; press("modernmusic.auth.qr_retry"); step++;
                }
                case 5 -> {
                    if (account.busy() || account.qr() == null) return;
                    provider.pollFailure = true; clock.advance(3); step++;
                }
                case 6 -> {
                    if (account.busy() || account.qrActive()) return;
                    failure("modernmusic.error.unavailable", 3);
                    provider.pollFailure = false; press("modernmusic.auth.qr_retry"); step++;
                }
                case 7 -> {
                    if (account.busy() || account.qr() == null) return;
                    press("gui.cancel"); cookieLayout();
                    press("modernmusic.auth.qr_login"); step++;
                }
                case 8 -> {
                    if (account.busy() || account.qr() == null) return;
                    clock.advance(121); step++;
                }
                case 9 -> {
                    failure("modernmusic.auth.qr_expired", 5);
                    press("modernmusic.auth.qr_retry"); step++;
                }
                case 10 -> {
                    if (account.busy() || account.qr() == null) return;
                    provider.authorized = true; clock.advance(3); step++;
                }
                case 11 -> {
                    if (account.busy() || account.session() == null) return;
                    press("modernmusic.auth.logout"); step++;
                }
                case 12 -> {
                    if (account.busy()) return;
                    cookieLayout(); provider.authorized = false;
                    press("modernmusic.auth.qr_login"); step++;
                }
                case 13 -> {
                    if (account.busy() || account.qr() == null) return;
                    press("gui.back");
                    if (account.qrActive()) throw new AssertionError("Closing screen kept QR active");
                    finish("PASS: QR creation failure survives resize; manual retry renders decodable QR; upstream/local expiry and terminal poll failure retain QR view; cancel, successful login/logout and close restore correct navigation");
                }
                default -> throw new AssertionError("Unexpected smoke step");
            }
        } catch (Throwable failure) {
            ModernMusic.LOGGER.error("QR smoke failed at step {}", step, failure);
            finish("FAIL: step=" + step + " " + failure);
        }
    }
    private void failure(String message, int attempts) {
        if (account.qrActive() || account.session() != null || account.qr() != null
                || !account.message().equals(message) || provider.attempts != attempts)
            throw new AssertionError("Unexpected failed QR state: " + account.message());
        if (screen.children().stream().anyMatch(EditBox.class::isInstance))
            throw new AssertionError("Failed QR returned to Cookie sign-in");
        if (!button("modernmusic.auth.qr_retry").active) throw new AssertionError("No retry after QR failure");
    }
    private void waiting() {
        if (!account.qrActive() || account.qr() == null || account.session() != null
                || screen.children().stream().anyMatch(EditBox.class::isInstance) || button("modernmusic.auth.qr_retry").active)
            throw new AssertionError("Waiting QR state or retry guard is incorrect");
    }
    private void cookieLayout() {
        if (account.qrActive() || screen.children().stream().noneMatch(EditBox.class::isInstance))
            throw new AssertionError("Explicit exit did not return to Cookie sign-in");
    }
    private Button button(String key) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.visible && value.getMessage().getContents() instanceof TranslatableContents text
                        && text.getKey().equals(key)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + key));
    }
    private void press(String key) {
        screen.tick(); var target = button(key);
        if (!target.active) throw new AssertionError("Inactive button: " + key);
        double x = screen.toGuiCoordinate(target.getX() + target.getWidth() / 2.0);
        double y = screen.toGuiCoordinate(target.getY() + target.getHeight() / 2.0);
        if (!screen.mouseClicked(x, y, 0)) throw new AssertionError("QR button hit missed: " + key);
        screen.mouseReleased(x, y, 0); screen.tick();
    }
    private void capture(Minecraft game, String filename, boolean decode) throws Exception {
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            frame.writeToFile(Path.of(filename));
            if (!decode) return;
            int[] rgb = new int[frame.getWidth() * frame.getHeight()];
            for (int y = 0; y < frame.getHeight(); y++) for (int x = 0; x < frame.getWidth(); x++) {
                int abgr = frame.getPixelRGBA(x, y);
                rgb[y * frame.getWidth() + x] = 0xFF000000 | (abgr & 255) << 16 | abgr & 0xFF00 | (abgr >> 16 & 255);
            }
            var source = new com.google.zxing.RGBLuminanceSource(frame.getWidth(), frame.getHeight(), rgb);
            var bitmap = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));
            var decoded = new com.google.zxing.qrcode.QRCodeReader().decode(bitmap);
            if (!decoded.getText().equals("https://music.163.com/login?codekey=offline-qr-fixture"))
                throw new AssertionError("Retried QR did not render the fixture URL");
        }
    }
    private void finish(String result) {
        done = true; Minecraft.getInstance().setScreen(new TitleScreen());
        if (account != null) account.close();
        try { Files.writeString(Path.of("qr-result.txt"), result + System.lineSeparator()); }
        catch (Exception failure) { ModernMusic.LOGGER.error("Cannot save QR smoke result", failure); }
        ModernMusic.LOGGER.info("{}", result); Minecraft.getInstance().stop();
    }
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private final class Fixture implements AccountProvider {
        volatile boolean creationFailure = true, expired, pollFailure, authorized;
        volatile int attempts;
        public AccountSession validate(SessionCookie cookie) { return new AccountSession("42", "QR fixture", cookie, clock.instant()); }
        public QrChallenge beginQr() {
            attempts++;
            if (creationFailure) throw new MusicException(MusicError.NETWORK_ERROR);
            return new QrChallenge("offline-qr-fixture", clock.instant().plusSeconds(120));
        }
        public QrPoll pollQr(QrChallenge challenge) {
            if (pollFailure) throw new MusicException(MusicError.UNAVAILABLE);
            if (expired) return new QrPoll(QrState.EXPIRED, null);
            return authorized ? new QrPoll(QrState.AUTHORIZED, SessionCookie.parse("MUSIC_U=offline-fixture"))
                    : new QrPoll(QrState.WAITING, null);
        }
    }
}
