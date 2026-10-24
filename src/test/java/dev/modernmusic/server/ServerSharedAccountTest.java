package dev.modernmusic.server;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static dev.modernmusic.shared.SharedAdminAction.*;

class ServerSharedAccountTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = MusicPlatform.class, names = {"QQ", "KUGOU"})
    void otherPlatformsHideSharedIdentityAndKeepCredentialStoresIndependent(MusicPlatform platform) throws Exception {
        var qqCookie = SessionCookie.parse(platform == MusicPlatform.QQ ? "uin=456; qqmusic_key=qq-fixture" : "userid=456; token=kugou-fixture", platform);
        AccountProvider qqAuth = new AccountProvider() {
            public MusicPlatform platform() { return platform; }
            public AccountSession validate(SessionCookie value) { assertEquals(platform, value.platform()); return new AccountSession("456", "Private server account", value, clock.instant(), java.net.URI.create("https://p1.music.126.net/private.png")); }
            public QrChallenge beginQr() { throw new AssertionError(); }
            public QrPoll pollQr(QrChallenge challenge) { throw new AssertionError(); }
        };
        try (var netease = create(); var qq = new ServerSharedAccount(directory.resolve("qq"), main::add, clock, qqAuth,
                (value, invalidator) -> new SharedResolverTest.Provider(), () -> {})) {
            bind(netease); qq.enabled(true); qq.manage(COOKIE_LOGIN, qqCookie.header(), () -> true);
            pump(qq, () -> qq.resolver().ready());
            pump(qq, () -> qq.status().equals("modernmusic.auth.saved"));
            assertEquals(dev.modernmusic.shared.SharedAccountProfile.EMPTY, qq.profile());
            assertEquals("", qq.snapshot(UUID.randomUUID(), 1, false, true).nickname());
            assertEquals(cookie.header(), new RememberedSessionStore(directory).load().cookie().header());
            assertEquals(qqCookie.header(), new RememberedSessionStore(directory.resolve("qq")).load().cookie().header());
            qq.manage(FORGET, "", () -> true); assertFalse(qq.resolver().ready()); assertTrue(netease.resolver().ready());
        }
    }
    @TempDir Path directory;
    final SharedResolverTest.Time clock = new SharedResolverTest.Time();
    final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    final SessionCookie cookie = SessionCookie.parse("MUSIC_U=fake-server-secret");
    final Auth auth = new Auth();
    final List<Consumer<SessionCookie>> invalidators = new ArrayList<>();
    int providers;
    final class Auth implements AccountProvider {
        volatile boolean reject;
        final java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        volatile QrState state = QrState.WAITING;
        public AccountSession validate(SessionCookie value) {
            validations.incrementAndGet();
            if (reject) throw new MusicException(MusicError.AUTH_EXPIRED);
            return new AccountSession("123", "Server fixture", SessionCookie.parse(value.header()), clock.instant());
        }
        public QrChallenge beginQr() { return new QrChallenge("fake-challenge", clock.instant().plusSeconds(120)); }
        public QrPoll pollQr(QrChallenge challenge) {
            polls.incrementAndGet();
            return new QrPoll(state, state == QrState.AUTHORIZED ? cookie : null,
                    state == QrState.VERIFY_REQUIRED ? java.net.URI.create("https://music.163.com/st/verify?token=fake") : null);
        }
    }
    ServerSharedAccount create() {
        return new ServerSharedAccount(directory, main::add, clock, auth, (value, invalidator) -> {
            assertEquals(cookie.header(), value.header()); invalidators.add(invalidator); providers++;
            return new SharedResolverTest.Provider();
        }, () -> {});
    }
    void pump(ServerSharedAccount server, BooleanSupplier done) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < until) {
            Runnable task; while ((task = main.poll()) != null) task.run(); server.tick(); Thread.sleep(5);
        }
        assertTrue(done.getAsBoolean(), server.status());
    }
    void bind(ServerSharedAccount server) throws Exception {
        server.manage(COOKIE_LOGIN, cookie.header(), () -> true); server.enabled(true); pump(server, () -> server.resolver().ready());
        pump(server, () -> new RememberedSessionStore(directory).exists());
        pump(server, () -> server.status().equals("modernmusic.auth.saved"));
    }
    @Test void cookieIsValidatedSavedAndCanBeDisabledWithoutDeletingSession() throws Exception {
        try (var server = create()) {
            assertFalse(server.resolver().ready()); bind(server);
            assertEquals(dev.modernmusic.shared.SharedAccountProfile.EMPTY, server.profile());
            assertFalse(Files.exists(directory.resolve("import-cookie.txt"))); assertEquals(1, auth.validations.get());
            assertEquals(cookie.header(), new RememberedSessionStore(directory).load().cookie().header());
            server.enabled(false); assertFalse(server.resolver().ready()); assertEquals("", server.profile().nickname());
            assertTrue(new RememberedSessionStore(directory).exists()); server.enabled(true); assertTrue(server.resolver().ready());
        }
    }
    @Test void restoredSessionMustPassValidationBeforeSharingAndExpiredRestoreFailsClosed() throws Exception {
        new RememberedSessionStore(directory).save(auth.validate(cookie)); auth.reject = true;
        try (var server = create()) {
            server.enabled(true); assertFalse(server.resolver().ready());
            pump(server, () -> server.status().equals(MusicError.AUTH_EXPIRED.translationKey()));
            assertFalse(server.resolver().ready()); assertEquals(0, providers);
            pump(server, () -> !new RememberedSessionStore(directory).exists());
        }
        auth.reject = false;
        new RememberedSessionStore(directory).save(auth.validate(cookie));
        try (var restarted = create()) {
            restarted.enabled(true); assertFalse(restarted.resolver().ready());
            pump(restarted, () -> restarted.resolver().ready());
        }
    }
    @Test void disabledSharingDefersSavedSessionValidationUntilEnabled() throws Exception {
        new RememberedSessionStore(directory).save(auth.validate(cookie));
        int validations = auth.validations.get();
        try (var server = create()) {
            server.enabled(false); server.tick();
            var snapshot = server.snapshot(UUID.randomUUID(), 0, false, true);
            assertTrue(snapshot.saved()); assertFalse(snapshot.busy());
            assertEquals(validations, auth.validations.get()); assertFalse(server.resolver().ready());
            server.enabled(true);
            pump(server, () -> server.resolver().ready());
            assertEquals(validations + 1, auth.validations.get());
            server.enabled(false); server.enabled(true); server.tick();
            assertEquals(validations + 1, auth.validations.get());
        }
    }
    @Test void editorCanRestoreWhileSharingIsDisabled() throws Exception {
        new RememberedSessionStore(directory).save(auth.validate(cookie));
        try (var server = create()) {
            server.manage(RESTORE, "", () -> true);
            pump(server, () -> server.status().equals("modernmusic.auth.restored"));
            assertFalse(server.resolver().ready());
            int validations = auth.validations.get();
            server.enabled(true); assertTrue(server.resolver().ready());
            assertEquals(validations, auth.validations.get());
        }
    }
    @Test void expiredProviderRevokesSessionAndForgetDeletesSavedCopy() throws Exception {
        try (var server = create()) {
            bind(server); assertEquals(dev.modernmusic.shared.SharedAccountProfile.EMPTY, server.profile());
            dev.modernmusic.compat.Java17.last(invalidators).accept(cookie);
            pump(server, () -> !server.resolver().ready());
            pump(server, () -> !new RememberedSessionStore(directory).exists());
            assertEquals(MusicError.SHARED_EXPIRED, server.resolver().unavailable());
            // Credential-bearing cache keys must clear eagerly on expiration.
            for (String name : List.of("saveAttempt", "installed")) {
                var field = ServerSharedAccount.class.getDeclaredField(name); field.setAccessible(true);
                assertNull(field.get(server), name + " retained expired credentials");
            }
        }
    }
    @Test void retiredProviderCannotExpireReenabledSession() throws Exception {
        try (var server = create()) {
            bind(server); var old = dev.modernmusic.compat.Java17.last(invalidators); server.enabled(false); server.enabled(true);
            old.accept(cookie);
            Runnable task; while ((task = main.poll()) != null) task.run();
            assertTrue(server.resolver().ready()); assertTrue(new RememberedSessionStore(directory).exists());
        }
    }
    @Test void periodicValidationWithSameCookieKeepsCurrentPlaybackGeneration() throws Exception {
        try (var server = create()) {
            bind(server); long generation = server.resolver().generation(); clock.add(601);
            pump(server, () -> auth.validations.get() >= 2);
            // Allow the completed validation and save to reach the main thread.
            for (int i = 0; i < 30; i++) { Runnable task; while ((task = main.poll()) != null) task.run(); server.tick(); Thread.sleep(5); }
            assertEquals(generation, server.resolver().generation()); assertEquals(1, providers);
        }
    }
    dev.modernmusic.shared.SharedAdminSnapshot view(ServerSharedAccount server) {
        return server.snapshot(new UUID(1, 1), 1, false, true);
    }
    @Test void qrIsTransientAndClearedOnCancelExpiryAndAuthorization() throws Exception {
        try (var server = create()) {
            server.manage(QR_START, "", () -> true); pump(server, () -> !view(server).qrKey().isEmpty());
            assertFalse(Files.exists(directory.resolve("login-qr.png")));
            assertFalse(view(server).toString().contains("fake-challenge"));
            server.manage(CANCEL_LOGIN, "", () -> true); assertEquals("", view(server).qrKey());
            server.manage(QR_START, "", () -> true); pump(server, () -> !view(server).qrKey().isEmpty());
            clock.add(121); server.tick(); assertEquals("", view(server).qrKey()); assertFalse(server.resolver().ready());
            auth.state = AccountProvider.QrState.AUTHORIZED; server.enabled(true); server.manage(QR_START, "", () -> true);
            pump(server, () -> server.resolver().ready()); assertFalse(Files.exists(directory.resolve("login-qr.png")));
            assertEquals(1, auth.validations.get()); assertEquals("", view(server).qrKey());
        }
    }
    @Test void verificationRequiresEditorResumeAndNeverWritesLoginArtifacts() throws Exception {
        try (var server = create()) {
            auth.state = AccountProvider.QrState.VERIFY_REQUIRED; server.manage(QR_START, "", () -> true);
            pump(server, () -> view(server).verificationRequired());
            int polls = auth.polls.get(); clock.add(10); server.tick(); assertEquals(polls, auth.polls.get()); assertFalse(server.resolver().ready());
            auth.state = AccountProvider.QrState.WAITING; server.manage(QR_RESUME, "", () -> true);
            assertFalse(view(server).verificationRequired()); assertEquals("", view(server).verificationUrl());
            assertFalse(Files.exists(directory.resolve("login-qr.png")));
        }
        assertFalse(Files.exists(directory.resolve("login-qr.png"))); assertFalse(Files.exists(directory.resolve("verification.html")));
    }
    @Test void authorizedEditorCanReceiveQrAndAccountDetails() throws Exception {
        try (var server = create()) {
            auth.state = AccountProvider.QrState.VERIFY_REQUIRED; server.manage(QR_START, "", () -> true);
            pump(server, () -> view(server).verificationRequired());
            var snapshot = server.snapshot(new UUID(1, 1), 1, false, true);
            assertTrue(snapshot.allowed()); assertTrue(snapshot.editing()); assertTrue(snapshot.cookieUpload());
            assertFalse(snapshot.qrKey().isEmpty()); assertFalse(snapshot.verificationUrl().isEmpty());
            server.cancelBinding(); bind(server);
            snapshot = server.snapshot(new UUID(1, 1), 2, false, true);
            assertTrue(snapshot.bound()); assertTrue(snapshot.nickname().isEmpty());
        }
    }
    @Test void forgetDuringLoginPreventsLateCredentialInstallOrPersistence() throws Exception {
        try (var server = create()) {
            server.manage(COOKIE_LOGIN, cookie.header(), () -> true); server.manage(FORGET, "", () -> true); server.enabled(true);
            pump(server, () -> server.status().equals("modernmusic.auth.logged_out"));
            assertFalse(server.resolver().ready()); assertFalse(new RememberedSessionStore(directory).exists());
        }
    }
    @Test void permissionIsRequiredAndRevocationRejectsCompletedCookieValidation() throws Exception {
        try (var server = create()) {
            assertThrows(IllegalStateException.class, () -> server.manage(QR_START, "", () -> false));
            var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
            server.manage(COOKIE_LOGIN, cookie.header(), allowed::get); server.enabled(true);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (main.isEmpty() && System.nanoTime() < until) Thread.sleep(5);
            assertFalse(main.isEmpty()); allowed.set(false);
            while (!main.isEmpty()) main.remove().run(); server.tick();
            assertFalse(server.resolver().ready()); assertFalse(view(server).bound());
            assertFalse(new RememberedSessionStore(directory).exists());
        }
    }
    @Test void closingEditorCancelsQrButKeepsAnAlreadyBoundAccount() throws Exception {
        try (var server = create()) {
            server.manage(QR_START, "", () -> true); pump(server, () -> !view(server).qrKey().isEmpty());
            server.cancelBinding(); assertFalse(view(server).qrActive());
            assertEquals("", view(server).qrKey());
            bind(server); server.cancelBinding(); assertTrue(server.resolver().ready());
        }
    }
}
