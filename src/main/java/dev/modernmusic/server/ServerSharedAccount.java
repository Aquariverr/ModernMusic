package dev.modernmusic.server;

import dev.modernmusic.auth.*;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.qq.*;
import dev.modernmusic.provider.kugou.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.provider.netease.*;
import dev.modernmusic.shared.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.*;

/** Headless account lifecycle. Only an authenticated editor receives a transient QR view. */
public final class ServerSharedAccount implements AutoCloseable {
    private final AccountController account;
    private final ServerPrivateFiles legacyFiles;
    private final Executor main;
    private final BiFunction<SessionCookie, Consumer<SessionCookie>, MusicProvider> music;
    private final Runnable closeTransport;
    private final SharedResolver resolver;
    private SessionCookie installed;
    private AccountSession saveAttempt;
    private BooleanSupplier bindingPermission;
    private boolean enabled, closed;
    private boolean restorePending = true;
    public ServerSharedAccount(Path directory, Executor main, Clock clock, MusicPlatform platform) {
        this(directory, main, clock, platform, new BoundedHttpTransport());
    }
    private ServerSharedAccount(Path directory, Executor main, Clock clock, MusicPlatform platform, BoundedHttpTransport http) {
        this(directory, main, clock, switch (platform) { case NETEASE -> new NeteaseAccountProvider(http, clock); case QQ -> new QqAccountProvider(http, clock); case KUGOU -> new KugouAccountProvider(http, clock); case LOCAL -> throw new IllegalArgumentException("Local audio has no account"); },
                (cookie, expired) -> switch (platform) { case NETEASE -> new NeteaseMusicProvider(http, clock, () -> cookie, expired); case QQ -> new QqMusicProvider(http, clock, () -> cookie, expired); case KUGOU -> new KugouMusicProvider(http, clock, () -> cookie, expired); case LOCAL -> throw new IllegalArgumentException("Local audio has no account"); }, http::close);
    }
    ServerSharedAccount(Path directory, Executor main, Clock clock, AccountProvider auth,
                        BiFunction<SessionCookie, Consumer<SessionCookie>, MusicProvider> music, Runnable closeTransport) {
        this.main = main; this.music = music; this.closeTransport = closeTransport;
        legacyFiles = new ServerPrivateFiles(directory);
        clearLegacyArtifacts();
        resolver = new SharedResolver(clock, main);
        account = new AccountController(auth, new RememberedSessionStore(directory), this::authResult, this::reconcile, clock, true, false);
    }
    private void authResult(Runnable result) {
        main.execute(() -> {
            // Check at completion, before installing credentials, as well as when the packet arrives.
            if (bindingPermission != null && !bindingPermission.getAsBoolean()) cancelBinding();
            result.run();
        });
    }
    public SharedResolver resolver() { return resolver; }
    public String status() { return account.message(); }
    public SharedAccountProfile profile() {
        // Preserve the display protocol without publishing any platform's account identity.
        return SharedAccountProfile.EMPTY;
    }
    public void enabled(boolean value) {
        if (closed) return;
        enabled = value;
        if (enabled && restorePending) { restorePending = false; account.restore(); }
        reconcile();
    }
    private void reconcile() {
        if (closed) return;
        var session = account.session();
        if (saveAttempt != session) saveAttempt = null;
        SessionCookie next = enabled ? account.cookie() : null;
        MusicError reason = enabled ? MusicError.SHARED_EXPIRED : MusicError.SHARED_DISABLED;
        if ((next == installed || next != null && installed != null && next.header().equals(installed.header()))
                && resolver.ready() == (next != null) && (next != null || resolver.unavailable() == reason)) return;
        installed = next;
        long token = resolver.generation() + 1;
        resolver.use(next == null ? null : music.apply(next, expired -> main.execute(() -> {
            var currentCookie = account.cookie();
            if (!closed && resolver.generation() == token && currentCookie != null && currentCookie.header().equals(expired.header())) {
                account.invalidate(currentCookie); reconcile();
            }
        })), reason);
    }
    public void tick() {
        if (closed) return;
        if (bindingPermission != null && !bindingPermission.getAsBoolean()) cancelBinding();
        account.tick(); reconcile();
        if (account.session() != null || !account.busy() && !account.qrActive()) bindingPermission = null;
        if (account.session() != null && !account.hasSaved() && !account.busy() && saveAttempt != account.session()) {
            saveAttempt = account.session(); account.remember();
        }
    }
    public SharedAdminSnapshot snapshot(UUID view, long acknowledgement, boolean operatorsOnly, boolean cookieUpload) {
        // The network checks administrator permission and the editor lease before requesting a view.
        var qr = account.qr(); var session = account.session(); var verification = account.qrVerificationUri();
        return new SharedAdminSnapshot(view, acknowledgement, true, true, cookieUpload, enabled, operatorsOnly,
                session != null, account.busy(), account.hasSaved(), account.qrActive(), account.qrNeedsVerification(),
                "", account.message(), qr == null ? "" : qr.key(),
                qr == null ? 0 : qr.expiresAt().toEpochMilli(), verification == null ? "" : verification.toASCIIString());
    }
    public void manage(SharedAdminAction action, String value, BooleanSupplier permission) {
        if (closed || !permission.getAsBoolean()) throw new IllegalStateException("Shared account permission required");
        switch (action) {
            case QR_START, COOKIE_LOGIN -> {
                // The current shared-account wire format carries only NetEase QR keys.
                if (action == SharedAdminAction.QR_START && account.platform().sharedCookieOnly()) return;
                if (account.session() != null || account.busy() || account.qrActive()) return;
                bindingPermission = permission;
                if (action == SharedAdminAction.QR_START) account.beginQr(); else account.loginCookie(value);
            }
            case CANCEL_LOGIN -> cancelBinding();
            case QR_RESUME -> account.resumeQrAfterVerification();
            case RESTORE -> {
                if (account.session() == null && !account.busy() && !account.qrActive() && account.hasSaved()) {
                    bindingPermission = permission; account.restore();
                }
            }
            case SAVE -> account.remember();
            case FORGET -> { bindingPermission = null; account.logout(); saveAttempt = null; }
            default -> { }
        }
        reconcile();
    }
    public void cancelBinding() {
        if (bindingPermission == null) return;
        bindingPermission = null; account.cancelLogin();
    }
    @Override public void close() {
        closed = true; bindingPermission = null; resolver.close(); installed = null;
        try { account.close(); }
        finally { closeTransport.run(); clearLegacyArtifacts(); }
    }
    private void clearLegacyArtifacts() {
        try { legacyFiles.clearLoginArtifacts(); }
        catch (AuthException failure) {
            org.slf4j.LoggerFactory.getLogger(ServerSharedAccount.class).warn("Cannot remove obsolete shared login artifacts");
        }
    }
}
