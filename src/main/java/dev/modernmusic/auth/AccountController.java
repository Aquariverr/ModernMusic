package dev.modernmusic.auth;

import dev.modernmusic.provider.*;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owned by the caller's main thread. Provider workers read only the immutable current session. */
public final class AccountController implements AutoCloseable {
    private final AccountProvider provider;
    private final RememberedSessionStore store;
    private final Executor mainThread;
    private final Runnable sessionChanged;
    private final Clock clock;
    private final boolean validateRestore;
    private final ThreadPoolExecutor worker = AsyncTasks.executor("modernmusic-account", 1, 8);
    private volatile AccountSession session;
    private AccountProvider.QrChallenge qr;
    private SessionCookie authorizedQrCookie;
    private boolean qrNeedsVerification;
    private java.net.URI qrVerificationUri;
    private int qrPollFailures, qrValidationFailures;
    private CompletableFuture<?> pending;
    private long generation;
    private boolean qrAttempt, closed, saved, mustForgetOnClose;
    private Instant nextPoll = Instant.MIN, nextValidation = Instant.MAX;
    private Instant nextSmsSend = Instant.MIN, nextSmsLogin = Instant.MIN;
    private String message = "modernmusic.auth.guest";

    public AccountController(AccountProvider provider, RememberedSessionStore store, Executor mainThread, Runnable sessionChanged, Clock clock) {
        this(provider, store, mainThread, sessionChanged, clock, false);
    }
    /** Servers validate restored credentials before making them available to other players. */
    public AccountController(AccountProvider provider, RememberedSessionStore store, Executor mainThread, Runnable sessionChanged, Clock clock, boolean validateRestore) {
        this(provider, store, mainThread, sessionChanged, clock, validateRestore, true);
    }
    /** Disabled server sharing can defer restoration until enabled or explicitly requested by an editor. */
    public AccountController(AccountProvider provider, RememberedSessionStore store, Executor mainThread, Runnable sessionChanged, Clock clock, boolean validateRestore, boolean restoreOnStart) {
        this.provider = provider; this.store = store; this.mainThread = mainThread; this.sessionChanged = sessionChanged; this.clock = clock;
        this.validateRestore = validateRestore;
        boolean remembered = store.exists(), legacy = !remembered && store.hasLegacy();
        saved = remembered || legacy;
        if (remembered && restoreOnStart) restore();
        else if (legacy) message = "modernmusic.auth.legacy";
    }
    public AccountSession session() { return session; }
    public dev.modernmusic.domain.MusicPlatform platform() { return provider.platform(); }
    public SessionCookie cookie() { AccountSession snapshot = session; return snapshot == null ? null : snapshot.cookie(); }
    public AccountProvider.QrChallenge qr() { return qr; }
    public java.util.List<AccountProvider.QrMethod> qrMethods() { return provider.qrMethods(); }
    public boolean busy() { return pending != null; }
    public boolean qrActive() { return qrAttempt; }
    public boolean qrNeedsVerification() { return qrNeedsVerification; }
    public java.net.URI qrVerificationUri() { return qrVerificationUri; }
    public boolean hasSaved() { return saved; }
    public String message() { return message; }
    public int smsCooldownSeconds() { return secondsUntil(nextSmsSend); }
    public int smsLoginCooldownSeconds() { return secondsUntil(nextSmsLogin); }
    private int secondsUntil(Instant deadline) {
        Instant now = clock.instant();
        if (!deadline.isAfter(now)) return 0;
        return (int) Math.min(3600, (java.time.Duration.between(now, deadline).toMillis() + 999) / 1000);
    }

    public void loginCookie(String input) {
        try { SessionCookie cookie = SessionCookie.parse(input, platform()); loginCookie(() -> cookie); }
        catch (AuthException error) { message = error.translationKey(); }
    }
    /** Reads a local secret on the account worker; the caller never needs to put it in a command. */
    public void loginCookie(Supplier<SessionCookie> input) {
        if (closed || busy() || qrAttempt || session != null) return;
        try {
            message = "modernmusic.auth.verifying";
            submit(() -> {
                AccountSession verified = provider.validate(input.get());
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                store.clear(); return verified;
            }, this::install);
        } catch (AuthException error) { message = error.translationKey(); }
    }
    public void beginQr() {
        beginQr(qrMethods().getFirst());
    }
    public void beginQr(AccountProvider.QrMethod method) {
        if (closed || busy() || qrAttempt || session != null) return;
        if (!qrMethods().contains(method)) { message = "modernmusic.auth.qr_unavailable"; return; }
        qrAttempt = true; authorizedQrCookie = null; qrPollFailures = qrValidationFailures = 0; message = "modernmusic.auth.generating";
        submit(() -> provider.beginQr(method), result -> { qr = result; nextPoll = clock.instant(); message = "modernmusic.auth.qr_waiting"; });
    }
    public void cancelQr() {
        if (!qrAttempt) return;
        cancel(); clearQr(); message = "modernmusic.auth.guest";
    }
    /** Cancel a UI-owned login, including cookie verification and post-QR authorization callbacks. */
    public void cancelLogin() {
        if (session != null) return;
        cancel(); clearQr(); message = "modernmusic.auth.guest";
    }
    public void resumeQrAfterVerification() {
        if (closed || busy() || !qrNeedsVerification || qr == null || qrVerificationUri == null) return;
        qrNeedsVerification = false; qrVerificationUri = null;
        nextPoll = clock.instant(); message = "modernmusic.auth.qr_verifying";
    }
    public void tick() {
        if (closed) return;
        Instant now = clock.instant();
        if (qr != null && authorizedQrCookie == null && !qr.expiresAt().isAfter(now)) {
            cancelQr(); message = "modernmusic.auth.qr_expired";
        }
        if (busy()) return;
        if (authorizedQrCookie != null && !now.isBefore(nextPoll)) {
            SessionCookie received = authorizedQrCookie;
            nextPoll = now.plusSeconds(2);
            message = "modernmusic.auth.qr_verifying";
            submit(() -> {
                AccountSession verified = provider.validate(received);
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                store.clear(); return verified;
            }, this::install, failure -> {
                safeFailure("qr_validate", failure);
                if (retryable(failure, true) && ++qrValidationFailures < 3) {
                    nextPoll = clock.instant().plusSeconds(2L * qrValidationFailures);
                    message = "modernmusic.auth.qr_verify_retry";
                } else {
                    clearQr(); message = retryable(failure, true) ? "modernmusic.auth.qr_verify_failed" : failureKey(failure);
                }
            });
        } else if (qr != null && !qrNeedsVerification && !now.isBefore(nextPoll)) {
            nextPoll = now.plusSeconds(2);
            AccountProvider.QrChallenge challenge = qr;
            submit(() -> provider.pollQr(challenge), result -> {
                qrPollFailures = 0;
                switch (result.state()) {
                    case WAITING -> message = "modernmusic.auth.qr_waiting";
                    case CONFIRM -> message = "modernmusic.auth.qr_confirm";
                    case VERIFY_REQUIRED -> {
                        qrNeedsVerification = true; qrVerificationUri = result.verificationUri();
                        message = "modernmusic.auth.qr_security_verification";
                    }
                    case EXPIRED -> { clearQr(); message = "modernmusic.auth.qr_expired"; }
                    case CANCELLED -> { clearQr(); message = "modernmusic.auth.qr_cancelled"; }
                    case AUTHORIZED -> {
                        if (result.cookie() == null) { clearQr(); message = "modernmusic.auth.qr_missing_cookie"; }
                        else { authorizedQrCookie = result.cookie(); qr = null; nextPoll = clock.instant(); message = "modernmusic.auth.qr_verifying"; }
                    }
                }
            }, failure -> {
                safeFailure("qr_poll", failure);
                if (retryable(failure, false) && ++qrPollFailures < 3) {
                    nextPoll = clock.instant().plusSeconds(2L * qrPollFailures);
                    message = "modernmusic.auth.qr_network_retry";
                } else { clearQr(); message = failureKey(failure); }
            });
        } else if (session != null && !now.isBefore(nextValidation)) {
            AccountSession snapshot = session;
            nextValidation = now.plusSeconds(600);
            submit(() -> {
                AccountSession updated = provider.validate(snapshot.cookie());
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (store.exists()) store.save(updated);
                return updated;
            }, updated -> { session = updated; });
        }
    }
    public void sendSms(String country, String number) {
        if (closed || busy() || qrAttempt || session != null) return;
        try {
            PhoneLogin phone = new PhoneLogin(country, number);
            if (smsCooldownSeconds() > 0) { message = "modernmusic.auth.sms_cooldown"; return; }
            // Start the cooldown before dispatch, even if the response is lost after a successful send.
            nextSmsSend = clock.instant().plusSeconds(60); message = "modernmusic.auth.sms_sending";
            submit(() -> { provider.sendSms(phone); return true; }, ignored -> message = "modernmusic.auth.sms_sent");
        } catch (AuthException error) { message = error.translationKey(); }
    }
    public void loginSms(String country, String number, String value) {
        if (closed || busy() || qrAttempt || session != null) return;
        try {
            PhoneLogin phone = new PhoneLogin(country, number);
            String code = PhoneLogin.code(value);
            if (smsLoginCooldownSeconds() > 0) return;
            nextSmsLogin = clock.instant().plusSeconds(3); message = "modernmusic.auth.verifying";
            submit(() -> {
                AccountSession verified = provider.loginSms(phone, code);
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                store.clear(); return verified;
            }, this::install);
        } catch (AuthException error) { message = error.translationKey(); }
    }
    public void remember() {
        if (closed || busy() || session == null) return;
        AccountSession snapshot = session;
        message = "modernmusic.auth.saving";
        submit(() -> { store.save(snapshot); return true; }, ignored -> { saved = true; message = "modernmusic.auth.saved"; });
    }
    public void restore() {
        if (closed || busy() || qrAttempt || session != null || !store.exists()) return;
        message = "modernmusic.auth.restoring";
        // Restores immediately without a password, QR/SMS challenge or blocking network validation.
        submit(() -> {
            AccountSession loaded = store.load();
            if (loaded.cookie().platform() != platform()) throw new AuthException("restore_failed");
            return validateRestore ? provider.validate(loaded.cookie()) : loaded;
        }, value -> { install(value); message = "modernmusic.auth.restored"; });
    }
    public void forgetSaved() {
        if (closed || busy()) return;
        mustForgetOnClose = true;
        submit(() -> { store.clear(); return true; }, ignored -> {
            saved = false; mustForgetOnClose = false; message = "modernmusic.auth.forgotten";
        });
    }
    public void logout() { forget("modernmusic.auth.logged_out"); }
    public void invalidate(SessionCookie failedCookie) {
        if (session != null && session.cookie() == failedCookie) forget("modernmusic.error.auth_expired");
    }
    private void forget(String status) {
        if (closed) return;
        boolean hadSession = session != null;
        cancel(); clearQr(); session = null;
        if (hadSession) sessionChanged.run();
        message = status; mustForgetOnClose = true;
        // Serialized after any in-flight save; an old write cannot recreate a logged-out account.
        submit(() -> { store.clear(); return true; }, ignored -> { saved = false; mustForgetOnClose = false; message = status; });
    }
    private void install(AccountSession value) {
        session = value; clearQr(); saved = store.exists();
        nextValidation = clock.instant().plusSeconds(600); message = "modernmusic.auth.signed_in";
        sessionChanged.run();
    }
    private <T> CompletableFuture<T> submit(Supplier<T> action, Consumer<T> success) {
        return submit(action, success, failure -> {
            clearQr(); saved = store.exists() || store.hasLegacy(); safeFailure("account", failure);
            if (failure instanceof MusicException music && music.error() == MusicError.AUTH_EXPIRED
                    && (session != null || validateRestore && store.exists()))
                forget("modernmusic.error.auth_expired");
            else message = failureKey(failure);
        });
    }
    private <T> CompletableFuture<T> submit(Supplier<T> action, Consumer<T> success, Consumer<Throwable> failed) {
        long token = ++generation;
        CompletableFuture<T> task = AsyncTasks.submit(worker, action);
        pending = task;
        task.whenComplete((value, error) -> mainThread.execute(() -> {
            if (closed || token != generation) return;
            pending = null;
            if (error == null) success.accept(value);
            else {
                Throwable failure = error;
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                failed.accept(failure);
            }
        }));
        return task;
    }
    private void clearQr() {
        qr = null; qrAttempt = false; authorizedQrCookie = null;
        qrNeedsVerification = false; qrVerificationUri = null;
    }
    private static String failureKey(Throwable failure) {
        return failure instanceof AuthException auth ? auth.translationKey()
                : failure instanceof MusicException music ? music.error().translationKey() : "modernmusic.error.internal_error";
    }
    private static boolean retryable(Throwable failure, boolean validation) {
        return failure instanceof MusicException music && (music.error() == MusicError.NETWORK_ERROR
                || validation && music.error() == MusicError.AUTH_EXPIRED);
    }
    private static void safeFailure(String stage, Throwable failure) {
        org.slf4j.LoggerFactory.getLogger(AccountController.class).warn("Account operation stage={} category={} type={}",
                stage, failureKey(failure), failure.getClass().getSimpleName());
    }
    private void cancel() {
        generation++;
        if (pending != null) { pending.cancel(true); pending = null; }
        worker.purge();
    }
    @Override public void close() {
        closed = true; cancel(); clearQr(); session = null;
        worker.shutdownNow();
        // Shutdown must finish a requested deletion even when a pending background save ignored interruption.
        if (mustForgetOnClose) store.clear();
    }
}
