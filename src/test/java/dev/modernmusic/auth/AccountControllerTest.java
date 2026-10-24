package dev.modernmusic.auth;

import dev.modernmusic.provider.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AccountControllerTest {
    @TempDir Path directory;
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final TestClock clock = new TestClock();
    private final AtomicInteger changed = new AtomicInteger();
    private static final String COOKIE = "MUSIC_U=fixture-secret";
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    private class Provider implements AccountProvider {
        int polls;
        MusicError failure;
        public AccountSession validate(SessionCookie cookie) {
            if (failure != null) throw new MusicException(failure);
            return new AccountSession("42", "Fixture User", cookie, clock.instant());
        }
        public QrChallenge beginQr() { return new QrChallenge("fixture-qr-key", clock.instant().plusSeconds(120)); }
        public QrPoll pollQr(QrChallenge challenge) {
            return switch (++polls) {
                case 1 -> new QrPoll(QrState.WAITING, null);
                case 2 -> new QrPoll(QrState.CONFIRM, null);
                default -> new QrPoll(QrState.AUTHORIZED, SessionCookie.parse(COOKIE));
            };
        }
    }
    private AccountController controller(Provider provider) {
        return new AccountController(provider, new RememberedSessionStore(directory), main::add, changed::incrementAndGet, clock);
    }
    private void drain() { Runnable task; while ((task = main.poll()) != null) task.run(); }
    private void settle(AccountController account) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do { drain(); if (!account.busy()) return; Thread.sleep(5); } while (System.nanoTime() < deadline);
        fail("Account operation timed out");
    }
    @Test void sessionOnlyByDefaultAndRememberedRestartDoesNotNeedNetworkValidation() throws Exception {
        try (var account = controller(new Provider())) {
            account.loginCookie(COOKIE); settle(account);
            assertNotNull(account.session()); assertFalse(account.hasSaved()); assertEquals(1, changed.get());
            account.remember(); settle(account);
            assertTrue(account.hasSaved());
        }
        Provider unavailable = new Provider(); unavailable.failure = MusicError.NETWORK_ERROR;
        try (var restored = controller(unavailable)) {
            settle(restored);
            assertNotNull(restored.session()); assertEquals("42", restored.session().userId());
            restored.logout(); assertNull(restored.session()); settle(restored);
            assertFalse(restored.hasSaved()); assertFalse(new RememberedSessionStore(directory).exists());
        }
    }
    @Test void jsonImportIsReducedBeforeValidationAndRemembering() throws Exception {
        try (var account = controller(new Provider())) {
            account.loginCookie("""
                    {"cookies":[{"name":"MUSIC_U","value":"fixture-secret","domain":".music.163.com"},
                    {"name":"MUSIC_U","value":"unrelated-secret","domain":".example.com"}],"metadata":"ignored"}
                    """);
            settle(account);
            assertNotNull(account.session());
            assertEquals(COOKIE, account.session().cookie().header());
            account.remember(); settle(account);
            assertTrue(account.hasSaved());
        }
        try (var restored = controller(new Provider())) {
            settle(restored);
            assertNotNull(restored.session());
            assertEquals(COOKIE, restored.session().cookie().header());
        }
    }
    @Test void qrPollingIsBoundedAndInstallsOnlyAfterProfileValidation() throws Exception {
        Provider provider = new Provider();
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); assertNotNull(account.qr());
            account.tick(); settle(account); assertEquals(1, provider.polls); assertNull(account.session());
            account.tick(); assertEquals(1, provider.polls);
            clock.advance(2); account.tick(); settle(account); assertEquals("modernmusic.auth.qr_confirm", account.message());
            clock.advance(2); account.tick(); settle(account);
            assertNull(account.session()); // 803 is followed by a separate, retryable identity check.
            account.tick(); settle(account);
            assertNotNull(account.session()); assertNull(account.qr()); assertFalse(account.qrActive()); assertFalse(account.hasSaved());
        }
    }
    @Test void expiredQrStopsPollingWithoutAuthorizing() throws Exception {
        Provider provider = new Provider();
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); clock.advance(121); account.tick();
            assertFalse(account.qrActive()); assertNull(account.session()); assertEquals(0, provider.polls);
            assertEquals("modernmusic.auth.qr_expired", account.message());
        }
    }
    @Test void securityVerificationPausesUntilUserContinuesThenValidatesAuthorization() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) {
                return ++polls <= 2 ? new QrPoll(QrState.VERIFY_REQUIRED, null, java.net.URI.create("https://music.163.com/st/verify"))
                        : new QrPoll(QrState.AUTHORIZED, SessionCookie.parse(COOKIE));
            }
            @Override public AccountSession validate(SessionCookie cookie) { validations.incrementAndGet(); return super.validate(cookie); }
        };
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); var challenge = account.qr();
            for (int i = 0; i < 2; i++) {
                account.tick(); settle(account);
                assertEquals("modernmusic.auth.qr_security_verification", account.message());
                assertSame(challenge, account.qr()); assertTrue(account.qrActive()); assertNull(account.session());
                assertTrue(account.qrNeedsVerification());
                clock.advance(10); account.tick(); assertEquals(i + 1, provider.polls);
                account.resumeQrAfterVerification(); assertFalse(account.qrNeedsVerification());
            }
            assertEquals(0, validations.get());
            account.tick(); settle(account); assertNull(account.session());
            account.tick(); settle(account); assertNotNull(account.session()); assertEquals(1, validations.get());
        }
    }
    @Test void securityVerificationWithoutLinkStopsRequestsAndAllowsSmsAfterCancel() throws Exception {
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) { polls++; return new QrPoll(QrState.VERIFY_REQUIRED, null); }
            @Override public AccountSession loginSms(PhoneLogin phone, String code) { return validate(SessionCookie.parse(COOKIE)); }
        };
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); account.tick(); settle(account);
            assertTrue(account.qrNeedsVerification()); assertNull(account.qrVerificationUri());
            clock.advance(30); account.resumeQrAfterVerification(); account.tick();
            assertEquals(1, provider.polls); assertFalse(account.busy()); assertNull(account.session());
            account.cancelQr(); assertFalse(account.qrNeedsVerification());
            account.loginSms("1", "2025550123", "123456"); settle(account); assertNotNull(account.session());
        }
    }
    @Test void securityVerificationStillExpiresOrCancelsWithoutInstallingSession() throws Exception {
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) { polls++; return new QrPoll(QrState.VERIFY_REQUIRED, null); }
        };
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); account.tick(); settle(account);
            clock.advance(121); account.tick(); assertFalse(account.qrActive()); assertNull(account.session());
            assertEquals("modernmusic.auth.qr_expired", account.message()); assertEquals(1, provider.polls);
            account.beginQr(); settle(account); account.tick(); settle(account); account.cancelQr();
            clock.advance(2); account.tick(); assertEquals(2, provider.polls); assertNull(account.session());
        }
    }
    @Test void qrNetworkAndPostConfirmationFailuresRetryWithoutLosingAuthorization() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) {
                if (++polls == 1) throw new MusicException(MusicError.NETWORK_ERROR);
                return new QrPoll(QrState.AUTHORIZED, SessionCookie.parse(COOKIE));
            }
            @Override public AccountSession validate(SessionCookie cookie) {
                if (validations.incrementAndGet() < 3) throw new MusicException(MusicError.AUTH_EXPIRED);
                return super.validate(cookie);
            }
        };
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); account.tick(); settle(account);
            assertNotNull(account.qr()); assertEquals("modernmusic.auth.qr_network_retry", account.message());
            account.tick(); assertEquals(1, provider.polls);
            clock.advance(2); account.tick(); settle(account);
            assertTrue(account.qrActive()); assertNull(account.qr()); assertNull(account.session());
            account.tick(); settle(account);
            assertEquals("modernmusic.auth.qr_verify_retry", account.message());
            clock.advance(2); account.tick(); settle(account);
            clock.advance(121); account.tick(); settle(account); // QR expiration no longer discards received authorization.
            assertNotNull(account.session()); assertEquals(2, provider.polls); assertEquals(3, validations.get());
        }
    }
    @Test void postConfirmationRetryIsBoundedAndCannotInstallUnvalidatedAccount() throws Exception {
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) {
                polls++; return new QrPoll(QrState.AUTHORIZED, SessionCookie.parse(COOKIE));
            }
        };
        provider.failure = MusicError.AUTH_EXPIRED;
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); account.tick(); settle(account);
            for (int i = 0; i < 3; i++) { clock.advance(4); account.tick(); settle(account); }
            assertFalse(account.qrActive()); assertNull(account.session());
            assertEquals("modernmusic.auth.qr_verify_failed", account.message());
            clock.advance(10); account.tick(); assertFalse(account.busy()); assertEquals(1, provider.polls);
        }
    }
    @Test void cancellingAfterAuthorizationDiscardsInFlightIdentityResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        Provider provider = new Provider() {
            @Override public QrPoll pollQr(QrChallenge challenge) { return new QrPoll(QrState.AUTHORIZED, SessionCookie.parse(COOKIE)); }
            @Override public AccountSession validate(SessionCookie cookie) {
                started.countDown();
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                finished.countDown(); return super.validate(cookie);
            }
        };
        try (var account = controller(provider)) {
            account.beginQr(); settle(account); account.tick(); settle(account); account.tick();
            assertTrue(started.await(2, TimeUnit.SECONDS)); account.cancelQr(); release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS)); Thread.sleep(30); drain();
            assertNull(account.session()); assertFalse(account.qrActive()); assertEquals(0, changed.get());
        } finally { release.countDown(); }
    }
    @Test void smsCooldownStartsBeforeRequestSurvivesFailureAndCannotBeBypassedByChangingPhone() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        Provider provider = new Provider() {
            @Override public void sendSms(PhoneLogin phone) { sends.incrementAndGet(); throw new MusicException(MusicError.NETWORK_ERROR); }
        };
        try (var account = controller(provider)) {
            assertEquals(0, account.smsCooldownSeconds()); assertEquals(0, account.smsLoginCooldownSeconds());
            account.sendSms("86", "bad"); assertEquals(0, account.smsCooldownSeconds()); assertFalse(account.busy());
            account.sendSms("1", "2025550123"); assertEquals(60, account.smsCooldownSeconds());
            account.sendSms("1", "2025550123"); settle(account); assertEquals(1, sends.get());
            account.sendSms("1", "2025550124"); assertEquals(1, sends.get());
            clock.advance(59); account.sendSms("1", "2025550123"); assertEquals(1, sends.get());
            clock.advance(1); account.sendSms("1", "2025550123"); settle(account); assertEquals(2, sends.get());
        }
    }
    @Test void invalidSmsReplacementPreservesVaultAndValidLoginReplacesItOnlyAfterValidation() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("client-session.bin"), "legacy-fixture");
        AtomicInteger logins = new AtomicInteger();
        Provider provider = new Provider() {
            @Override public AccountSession loginSms(PhoneLogin phone, String code) {
                logins.incrementAndGet();
                if (!code.equals("123456")) throw new AuthException("sms_login_failed");
                return validate(SessionCookie.parse("MUSIC_U=new-fixture"));
            }
        };
        try (var account = controller(provider)) {
            account.loginSms("1", "2025550123", "invalid"); assertFalse(account.busy()); assertEquals(0, logins.get());
            account.loginSms("1", "2025550123", "000000"); settle(account);
            assertNull(account.session()); assertTrue(account.hasSaved()); assertEquals(0, changed.get());
            account.loginSms("1", "2025550123", "123456"); assertEquals(1, logins.get());
            clock.advance(3); account.loginSms("1", "2025550123", "123456"); settle(account);
            assertEquals("MUSIC_U=new-fixture", account.cookie().header()); assertFalse(account.hasSaved());
            assertEquals(1, changed.get()); assertEquals(2, logins.get());
        }
    }
    @Test void selectedQrMethodIsDispatchedAndPhoneCancellationStopsPolling() throws Exception {
        var methods = new java.util.ArrayList<AccountProvider.QrMethod>();
        Provider provider = new Provider() {
            @Override public java.util.List<QrMethod> qrMethods() { return java.util.List.of(QrMethod.QQ, QrMethod.WECHAT); }
            @Override public QrChallenge beginQr(QrMethod method) {
                methods.add(method); return new QrChallenge("fixture-key", clock.instant().plusSeconds(120), "https://example.test/qr", method);
            }
            @Override public QrPoll pollQr(QrChallenge challenge) { polls++; return new QrPoll(QrState.CANCELLED, null); }
        };
        try (var account = controller(provider)) {
            account.beginQr(AccountProvider.QrMethod.APP); assertFalse(account.busy()); assertTrue(methods.isEmpty());
            account.beginQr(AccountProvider.QrMethod.WECHAT); settle(account);
            assertEquals(AccountProvider.QrMethod.WECHAT, account.qr().method());
            account.tick(); settle(account); assertFalse(account.qrActive()); assertNull(account.session());
            assertEquals("modernmusic.auth.qr_cancelled", account.message());
            clock.advance(5); account.tick(); assertEquals(1, provider.polls);
            account.beginQr(); settle(account); assertEquals(AccountProvider.QrMethod.QQ, account.qr().method());
        }
    }
    @Test void cancelledSlowQrCallbackCannotRestoreChallengeOrLogin() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Provider provider = new Provider() {
            @Override public QrChallenge beginQr() {
                started.countDown();
                while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
                return super.beginQr();
            }
        };
        try (var account = controller(provider)) {
            account.beginQr(); assertTrue(started.await(2, TimeUnit.SECONDS));
            account.cancelQr(); release.countDown();
            Thread.sleep(30); drain(); account.tick();
            assertFalse(account.qrActive()); assertNull(account.qr()); assertNull(account.session());
        } finally { release.countDown(); }
    }
    @Test void expirationClearsPlaybackAndVaultButOldSessionFailuresAreIgnored() throws Exception {
        Provider provider = new Provider();
        try (var account = controller(provider)) {
            account.loginCookie(COOKIE); settle(account); account.remember(); settle(account);
            account.invalidate(SessionCookie.parse("MUSIC_U=old")); assertNotNull(account.session());
            account.invalidate(account.cookie()); assertNull(account.session()); settle(account);
            assertEquals(2, changed.get()); assertFalse(account.hasSaved());
        }
    }
    @Test void periodicNetworkFailureKeepsSessionButConfirmedExpirationDeletesIt() throws Exception {
        Provider provider = new Provider();
        try (var account = controller(provider)) {
            account.loginCookie(COOKIE); settle(account);
            provider.failure = MusicError.NETWORK_ERROR; clock.advance(601); account.tick(); settle(account);
            assertNotNull(account.session());
            provider.failure = MusicError.AUTH_EXPIRED; clock.advance(601); account.tick(); settle(account);
            assertNull(account.session()); assertEquals("modernmusic.error.auth_expired", account.message());
        }
    }
    @Test void logoutDuringSaveCannotRecreateStoredAccount() throws Exception {
        try (var account = controller(new Provider())) {
            account.loginCookie(COOKIE); settle(account);
            account.remember(); account.logout(); settle(account);
            assertNull(account.session()); assertFalse(new RememberedSessionStore(directory).exists());
        }
    }
    @Test void invalidReplacementDoesNotEraseAnExistingSavedLoginOrStopGuestPlayback() throws Exception {
        var store = new RememberedSessionStore(directory);
        java.nio.file.Files.writeString(directory.resolve("client-session.bin"), "legacy-fixture");
        Provider provider = new Provider(); provider.failure = MusicError.AUTH_EXPIRED;
        try (var account = controller(provider)) {
            account.loginCookie("MUSIC_U=wrong-replacement"); settle(account);
            assertNull(account.session()); assertTrue(account.hasSaved()); assertEquals(0, changed.get());
            assertTrue(store.hasLegacy());
        }
    }

    @Test void deletingRememberedLoginKeepsActiveSessionAndDisablesRestartRestore() throws Exception {
        try (var account = controller(new Provider())) {
            account.loginCookie(COOKIE); settle(account); account.remember(); settle(account);
            account.forgetSaved(); settle(account);
            assertNotNull(account.session()); assertFalse(account.hasSaved());
        }
        try (var restarted = controller(new Provider())) { settle(restarted); assertNull(restarted.session()); }
    }
    @Test void logoutDuringRestoreDiscardsOldCallback() throws Exception {
        new RememberedSessionStore(directory).save(new AccountSession("42", "Fixture", SessionCookie.parse(COOKIE), clock.instant()));
        try (var account = controller(new Provider())) {
            account.logout(); settle(account);
            assertNull(account.session()); assertFalse(account.hasSaved());
        }
    }
}
