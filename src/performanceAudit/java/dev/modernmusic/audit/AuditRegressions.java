package dev.modernmusic.audit;

import com.google.gson.GsonBuilder;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.audio.JavaSoundOutput;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import dev.modernmusic.provider.HttpTransport;
import dev.modernmusic.provider.MultiPlatformProvider;
import dev.modernmusic.provider.netease.NeteaseMusicProvider;
import dev.modernmusic.provider.qq.QqMusicProvider;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;

/** Opt-in checks in an isolated, running Minecraft client; never reads real account credentials. */
public final class AuditRegressions implements AutoCloseable {
    private static final String QR_CONTENT = "https://music.163.com/login?codekey=0123456789abcdef0123456789abcdef";
    private static final String FIRST = "audit-fixture-first.wav", SECOND = "audit-fixture-second.wav";
    private final Minecraft game;
    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("modernmusic-audit-regressions").factory());
    private CompletableFuture<Map<String, Object>> providers, local;
    private PlaybackController localPlayer;
    private Track first, second;
    private long localStart, switchStart, localDeadline;
    private double firstPlaybackMs, switchPlaybackMs;
    private int localStep, fixtureCount;
    private QrScreen qr;
    private boolean closed;

    public AuditRegressions(Minecraft game, Path isolatedRunDirectory) {
        this.game = game;
        directory = isolatedRunDirectory.toAbsolutePath().normalize();
        if (!directory.equals(game.gameDirectory.toPath().toAbsolutePath().normalize())
                || directory.getFileName() == null || !directory.getFileName().toString().startsWith("audit-")
                || directory.getParent() == null || !directory.getParent().getFileName().toString().equals("runs"))
            throw new IllegalArgumentException("Runtime regressions require a runs/audit-* game directory");
    }

    /** Uses production providers with deterministic in-memory HTTP, not a logged-in live service. */
    public CompletableFuture<Map<String, Object>> startProviders() {
        if (providers != null) return providers;
        providers = CompletableFuture.supplyAsync(() -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("transport", "in-memory HTTP fixtures; production provider executors");
            try {
                result.putAll(cancelMetadata());
                result.putAll(slowLyrics());
                result.put("pass", true);
                save("regression-provider.json", result);
                return Map.copyOf(result);
            } catch (Throwable error) {
                result.put("pass", false); result.put("failure", failure(error));
                try { save("regression-provider.json", result); } catch (Exception writing) { error.addSuppressed(writing); }
                throw new CompletionException(error);
            }
        }, worker);
        return providers;
    }

    private static Map<String, Object> cancelMetadata() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        HttpTransport memory = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Unexpected real/network GET"); }
            public CookieResponse qqApi(URI uri, int max, SessionCookie cookie, byte[] json) {
                calls.incrementAndGet(); entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException expected) {
                    interrupted.countDown(); Thread.currentThread().interrupt(); throw new CancellationException();
                }
                throw new AssertionError("Cancelled metadata HTTP should have been interrupted");
            }
        };
        try (var qq = new QqMusicProvider(memory, Clock.systemUTC(), () -> null, ignored -> { throw new AssertionError("Unexpected expiry"); })) {
            // Include the production cross-platform aggregate, then its sequential 50-song batch.
            var aggregate = new MultiPlatformProvider(qq, qq);
            var ids = java.util.stream.LongStream.rangeClosed(1, 50).mapToObj(id -> new SongId(id, MusicPlatform.QQ)).toList();
            var pending = aggregate.getTracks(ids);
            require(entered.await(5, TimeUnit.SECONDS), "Metadata worker did not reach fixture HTTP");
            long start = System.nanoTime();
            require(pending.cancel(true), "Metadata aggregate was not cancellable");
            require(interrupted.await(5, TimeUnit.SECONDS), "Cancellation failed to interrupt active HTTP");
            require(calls.get() == 1, "Cancelled batch started more than its first request");
            return Map.of("metadataBatchSize", ids.size(), "metadataRequestsStarted", calls.get(),
                    "metadataCancelled", pending.isCancelled(), "metadataInterruptMs", (System.nanoTime() - start) / 1e6);
        } finally { release.countDown(); }
    }

    private static Map<String, Object> slowLyrics() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        SessionCookie fixture = SessionCookie.parse("MUSIC_U=audit-fixture; NMTID=ready; __csrf=fixture");
        HttpTransport memory = new HttpTransport() {
            public byte[] get(URI uri, int max) { throw new AssertionError("Unexpected real/network GET"); }
            public CookieResponse getWithCookies(URI uri, int max, SessionCookie supplied) {
                require(uri.getPath().equals("/api/song/lyric"), "Unexpected metadata path");
                entered.countDown();
                try { require(release.await(10, TimeUnit.SECONDS), "Fixture lyric was not released"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return new CookieResponse("{\"code\":200,\"lrc\":{\"lyric\":\"[00:00.00]Audit fixture\"}}".getBytes(StandardCharsets.UTF_8), List.of());
            }
            public CookieResponse postAccount(URI uri, int max, String header, byte[] form) {
                calls.incrementAndGet();
                return new CookieResponse(("{\"code\":200,\"data\":[{\"id\":42,\"code\":200,"
                        + "\"url\":\"https://m804.music.126.net/audit-memory.mp3\",\"type\":\"mp3\",\"expi\":1200}]}")
                        .getBytes(StandardCharsets.UTF_8), List.of());
            }
        };
        try (var provider = new NeteaseMusicProvider(memory, Clock.systemUTC(), () -> fixture,
                ignored -> { throw new AssertionError("Unexpected expiry"); })) {
            var lyrics = provider.getLyrics(new SongId(42));
            require(entered.await(5, TimeUnit.SECONDS), "Slow lyric fixture did not start");
            long start = System.nanoTime();
            try {
                var resource = provider.resolvePlayback(new SongId(42)).get(5, TimeUnit.SECONDS);
                double elapsed = (System.nanoTime() - start) / 1e6;
                require(!lyrics.isDone(), "Slow lyric must still be blocked when playback resolves");
                require(resource.id().equals(new SongId(42)) && calls.get() == 1, "Playback resolution mismatch");
                return Map.of("slowLyricDidNotBlockPlayback", true, "playbackResolveMsWhileLyricBlocked", elapsed,
                        "playbackResourceRequests", calls.get(), "realCdnDownloaded", false);
            } finally { release.countDown(); lyrics.get(5, TimeUnit.SECONDS); }
        } finally { release.countDown(); }
    }

    /** Call after leaving a shared room; tick() observes the actual JavaSoundOutput playback and switch. */
    public CompletableFuture<Map<String, Object>> startLocal(PlaybackController player) {
        if (local != null) return local;
        require(!player.shared() && player.queuesReady(), "Local check requires a ready personal player");
        require(player.localAudio() != null, "Production local provider unavailable");
        require(player.localAudio().directory().equals(directory.resolve("modernmusic/audio")), "Unexpected local audio directory");
        try {
            var output = PlaybackController.class.getDeclaredField("output"); output.setAccessible(true);
            require(output.get(player) instanceof JavaSoundOutput, "Local check requires production JavaSoundOutput");
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        local = new CompletableFuture<>();
        localDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        CompletableFuture.runAsync(() -> {
            try {
                Path audio = directory.resolve("modernmusic/audio"); Files.createDirectories(audio);
                byte[] playing = AuditFixtures.wav(60, 8000, 1), background = AuditFixtures.wav(1, 8000, 1);
                Files.write(audio.resolve(FIRST), playing); Files.write(audio.resolve(SECOND), playing);
                for (int i = 0; i < 30; i++) Files.write(audio.resolve(String.format(Locale.ROOT, "audit-fixture-background-%02d.wav", i)), background);
            } catch (Exception error) { throw new CompletionException(error); }
        }, worker).thenCompose(ignored -> player.localAudio().browseLocal()).whenComplete((tracks, error) -> game.execute(() -> {
            if (closed || local.isDone()) return;
            if (error != null) { failLocal(error); return; }
            try {
                first = tracks.stream().filter(track -> track.title().equals(FIRST)).findFirst().orElseThrow();
                second = tracks.stream().filter(track -> track.title().equals(SECOND)).findFirst().orElseThrow();
                fixtureCount = (int) tracks.stream().filter(track -> track.title().startsWith("audit-fixture-")).count();
                player.clearPersonalQueue(); player.volume(0);
                var destination = player.searchDestinations().stream().filter(value -> !value.shared()
                        && value.id().equals(player.library().active().id())).findFirst().orElseThrow();
                var added = player.addSearchTracks(destination, List.of(first, second)).join();
                require(added.added() == 2, "Local fixtures were not queued");
                localPlayer = player; localStart = System.nanoTime(); localStep = 0; player.playPersonal(0);
            } catch (Throwable failure) { failLocal(failure); }
        }));
        return local;
    }

    /** Client-thread only; never waits for disk, HTTP, playback or a latch. */
    public void tick() {
        if (local == null || local.isDone()) return;
        try {
            require(System.nanoTime() < localDeadline, "Local playback check timed out");
            if (localPlayer == null) return;
            require(localPlayer.state() != PlaybackController.State.ERROR, "Local playback error: " + localPlayer.message());
            if (localPlayer.state() != PlaybackController.State.PLAYING) return;
            var track = localPlayer.track(); var audio = localPlayer.audio();
            if (localStep == 0 && track != null && track.id().equals(first.id())) {
                firstPlaybackMs = (System.nanoTime() - localStart) / 1e6; localStep = 1;
                require(audio.durationMs() >= 30_000 && localPlayer.bufferedBytes() > 0, "Local PCM was not loaded");
            }
            if (localStep == 1 && audio.positionMs() >= 800) {
                switchStart = System.nanoTime(); localStep = 2; localPlayer.playPersonal(1); return;
            }
            if (localStep == 2 && track != null && track.id().equals(second.id())) {
                switchPlaybackMs = (System.nanoTime() - switchStart) / 1e6; localStep = 3;
            }
            if (localStep == 3 && audio.positionMs() >= 800) {
                var result = Map.<String, Object>of("pass", true, "output", JavaSoundOutput.class.getSimpleName(),
                        "muted", true, "fixtureFiles", fixtureCount, "firstPlaybackMs", firstPlaybackMs,
                        "switchPlaybackMs", switchPlaybackMs, "secondPositionMs", audio.positionMs(),
                        "durationMs", audio.durationMs(), "pcmBytes", localPlayer.bufferedBytes());
                save("regression-local.json", result); local.complete(result);
            }
        } catch (Throwable error) { failLocal(error); }
    }

    private void failLocal(Throwable error) {
        try { save("regression-local.json", Map.of("pass", false, "failure", failure(error), "step", localStep)); }
        catch (Exception writing) { error.addSuppressed(writing); }
        local.completeExceptionally(error);
    }

    /** A steady-state screen rendering the production QR path, with the world continuing behind it. */
    public Screen qrScreen() {
        if (qr == null) qr = new QrScreen();
        return qr;
    }

    public Map<String, Object> qrStats() {
        if (qr == null) return Map.of("frames", 0);
        return Map.of("frames", qr.frames, "blackModules", qr.black, "mergedQuads", qr.quads,
                "qrFlushesLastFrame", qr.lastFlushes, "qrGlError", qr.glError,
                "glErrorBeforeQr", qr.initialGlError,
                "productionDrawMethod", "dev.modernmusic.client.ui.QrDrawing.draw");
    }

    /** Invoke after a stable render window. Readback and ZXing decoding are intentionally outside it. */
    public Map<String, Object> captureQr(Path output) throws Exception {
        require(qr != null && qr.frames > 0 && game.screen == qr, "QR screen has not rendered");
        Path destination = output.toAbsolutePath().normalize();
        require(destination.startsWith(directory), "QR screenshot must stay within the isolated run");
        try (var image = Screenshot.takeScreenshot(game.getMainRenderTarget())) {
            image.writeToFile(destination);
            int[] pixels = new int[image.getWidth() * image.getHeight()];
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y);
                pixels[y * image.getWidth() + x] = abgr & 0xFF00FF00 | (abgr & 255) << 16 | (abgr >>> 16 & 255);
            }
            var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
            var decoded = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, true));
            require(QR_CONTENT.equals(decoded.getText()), "Rendered QR pixels did not decode to fixture content");
            require(qr.glError == GL11.GL_NO_ERROR, "OpenGL reported an error after QR rendering: " + qr.glError);
            var result = new LinkedHashMap<>(qrStats()); result.put("pass", true); result.put("screenshotDecoded", true);
            result.put("screenshot", directory.relativize(destination).toString());
            result.put("pixelWidth", image.getWidth()); result.put("pixelHeight", image.getHeight());
            save("regression-qr.json", result); return result;
        }
    }

    private final class QrScreen extends Screen {
        private final BitMatrix matrix;
        private final Method draw;
        private long frames;
        private int black, quads, lastFlushes, glError, initialGlError;
        QrScreen() {
            super(Component.literal("Modern Music runtime QR regression"));
            try {
                matrix = new QRCodeWriter().encode(QR_CONTENT, BarcodeFormat.QR_CODE, 0, 0,
                        Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
                draw = Class.forName("dev.modernmusic.client.ui.QrDrawing").getDeclaredMethod("draw",
                        GuiGraphics.class, BitMatrix.class, int.class, int.class, int.class); draw.setAccessible(true);
                for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) if (matrix.get(x, y)) {
                    black++; if (x == 0 || !matrix.get(x - 1, y)) quads++;
                }
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void render(GuiGraphics graphics, int x, int y, float delta) {
            graphics.fill(0, 0, width, height, 0xF010141B);
            graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFFFF);
            int available = Math.max(matrix.getWidth(), Math.min(230, Math.min(width - 40, height - 95)));
            int left = (width - available) / 2, top = (height - available) / 2;
            int[] flushes = {0};
            var local = new GuiGraphics(game, graphics.bufferSource()) {
                @Override public void flush() { flushes[0]++; super.flush(); }
            };
            local.pose().last().pose().set(graphics.pose().last().pose());
            if (frames == 0) initialGlError = GL11.glGetError();
            try { draw.invoke(null, local, matrix, left, top, available); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Production QR drawing failed", error); }
            lastFlushes = flushes[0]; frames++;
            if (frames == 1) glError = GL11.glGetError();
            graphics.drawCenteredString(font, Component.literal("Production batch: " + black + " black modules / " + quads + " merged quads"),
                    width / 2, height - 31, 0xFFCED5DF);
            graphics.drawCenteredString(font, Component.literal("Synthetic QR fixture; no account or network login"), width / 2, height - 17, 0xFF9EABB9);
        }
    }

    private void save(String file, Map<String, Object> result) throws Exception {
        Files.writeString(directory.resolve(file), new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
    private static String failure(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    @Override public void close() {
        closed = true;
        if (providers != null && !providers.isDone()) providers.cancel(true);
        if (local != null && !local.isDone()) local.cancel(true);
        worker.shutdownNow();
    }
}
