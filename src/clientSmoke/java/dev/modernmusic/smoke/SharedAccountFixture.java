package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.auth.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.server.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Isolated fake server credential/provider injection. This entire source set is excluded from releases. */
public final class SharedAccountFixture {
    public static final java.net.URI AVATAR = java.net.URI.create("https://p1.music.126.net/shared-profile-fixture.png");
    private static final SessionCookie COOKIE = SessionCookie.parse("MUSIC_U=m5-fixture-no-real-account");
    private static ServerSharedAccount account;
    private static Consumer<SessionCookie> expired;
    public static int resolves;
    private static volatile boolean authorized;
    public static void install(MinecraftServer server) throws Exception {
        var field = ModernMusic.class.getDeclaredField("sharedAccount"); field.setAccessible(true); Object network = field.get(null);
        var current = network.getClass().getDeclaredField("accounts"); current.setAccessible(true);
        @SuppressWarnings("unchecked") var accountMap = (java.util.Map<MusicPlatform, ServerSharedAccount>) current.get(network);
        accountMap.get(MusicPlatform.NETEASE).close();
        Path directory = server.getServerDirectory().resolve("m5-fixture-private");
        new RememberedSessionStore(directory).clear();
        AccountProvider auth = new AccountProvider() {
            public AccountSession validate(SessionCookie cookie) {
                if (!COOKIE.header().equals(cookie.header())) throw new AssertionError("Unexpected fixture credential");
                return new AccountSession("42", "Server fixture", cookie, Instant.now(), AVATAR);
            }
            public QrChallenge beginQr() { authorized = false; return new QrChallenge("m5-fixture-qr-key", Instant.now().plusSeconds(120)); }
            public QrPoll pollQr(QrChallenge challenge) { return new QrPoll(authorized ? QrState.AUTHORIZED : QrState.WAITING, authorized ? COOKIE : null); }
        };
        java.util.function.BiFunction<SessionCookie, Consumer<SessionCookie>, MusicProvider> music = (cookie, invalidator) -> {
            expired = invalidator;
            return new MusicProvider() {
                public CompletableFuture<Track> getTrack(SongId song) { throw new AssertionError("Only resource resolution is shared"); }
                public CompletableFuture<PlaybackResource> resolvePlayback(SongId song) {
                    resolves++;
                    return CompletableFuture.completedFuture(new PlaybackResource(song, java.net.URI.create("https://m801.music.126.net/server-fixture.mp3"), Instant.now().plusSeconds(60)));
                }
                public void close() {}
            };
        };
        var constructor = ServerSharedAccount.class.getDeclaredConstructor(Path.class, java.util.concurrent.Executor.class, Clock.class,
                AccountProvider.class, java.util.function.BiFunction.class, Runnable.class);
        constructor.setAccessible(true);
        account = constructor.newInstance(directory, (java.util.concurrent.Executor) server::execute, Clock.systemUTC(), auth, music, (Runnable) () -> {});
        accountMap.put(MusicPlatform.NETEASE, account); SharedAccountConfig.ENABLED.set(false); SharedAccountConfig.OPERATORS_ONLY.set(false);
    }
    public static void authorize() { authorized = true; }
    public static String cookie() { return COOKIE.header(); }
    public static void expire() { expired.accept(COOKIE); }
    private SharedAccountFixture() {}
}
