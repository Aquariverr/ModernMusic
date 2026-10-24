package dev.modernmusic.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Bounded memory-only image cache, shared by avatar, player and HUD. No account headers go to image hosts. */
public final class ArtworkCache implements AutoCloseable {
    static final int SIZE = 256, LEVELS = 5;
    /** Artwork corner radius as a fraction of its side, so every display size keeps the same shape. */
    static final float CORNER = .12f;
    private static ArtworkCache instance;
    private final BoundedHttpTransport http = new BoundedHttpTransport();
    private final ThreadPoolExecutor worker = AsyncTasks.executor("modernmusic-artwork", 2, 16);
    private final LinkedHashMap<URI, Entry> entries = new LinkedHashMap<>(32, .75f, true);
    private boolean closed;
    private static final class Entry {
        ResourceLocation texture;
        CompletableFuture<?> pending;
        boolean avatar, square;
        /** The cover's dominant color once installed, 0 before. */
        int tint;
    }
    public static ArtworkCache get() { if (instance == null) instance = new ArtworkCache(); return instance; }
    public static void clearIfCreated() { if (instance != null) instance.clear(); }
    public static void closeIfCreated() { if (instance != null) { instance.close(); instance = null; } }
    public static int textureCount() { return instance == null ? 0 : (int) instance.entries.values().stream().filter(e -> e.texture != null).count(); }
    /** GPU bytes for one cached image, including its mip chain. */
    public static long textureBytes() { return (long) SIZE * SIZE * 4 * 4 / 3; }

    public void draw(GuiGraphics g, URI uri, int x, int y, int size, boolean avatar) {
        draw(g, uri, x, y, size, avatar, 1, true);
    }
    /**
     * {@code background} adds a soft shadow under the image (an outline in OreUI, the skin's frame in skins). A
     * missing image shows a placeholder plate.
     */
    public void draw(GuiGraphics g, URI uri, int x, int y, int size, boolean avatar, double opacity, boolean background) {
        if (opacity < .01) return;
        boolean square = PlayerTheme.pixel();
        float radius = square ? 0 : avatar ? size / 2f : Math.max(2, size * CORNER);
        ResourceLocation texture = texture(uri, avatar);
        if (background && size >= 24) {
            if (PlayerTheme.skin() != null) PlayerTheme.artFrame(g, x, y, size, opacity);
            else if (square) PlayerTheme.frame(g, x - 1, y - 1, size + 2, size + 2, PlayerTheme.alpha(ORE_OUTLINE, opacity));
            else Shapes.shadow(g, x, y + size / 16f, size, size, radius, Math.max(3, size / 10f), PlayerTheme.alpha(0x73000000, opacity));
        }
        if (texture != null) {
            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
            g.setColor(1, 1, 1, (float) opacity);
            g.blit(texture, x, y, size, size, 0f, 0f, SIZE, SIZE, SIZE, SIZE);
            g.setColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
            return;
        }
        Shapes.rect(g, x, y, size, size, radius, PlayerTheme.alpha(mix(CARD, OVERLAY, PlayerTheme.light() ? .06f : .1f), opacity));
        g.pose().pushPose();
        g.pose().translate(x + size / 2f - .5f, y + size / 2f - .5f, 0);
        float scale = avatar ? size / 22f : size / 28f;
        g.pose().scale(scale, scale, 1);
        icon(g, avatar ? Icon.ACCOUNT : Icon.MUSIC, 0, 0, PlayerTheme.alpha(MUTED, opacity * .8));
        g.pose().popPose();
    }
    /** The dominant color of a loaded cover, or 0 while it is unknown. Never starts a download. */
    int tint(URI source) {
        if (closed || source == null) return 0;
        Entry cached = entries.get(source);
        if (cached == null) {
            URI uri = ProviderUris.image(source.toString());
            cached = uri == null ? null : entries.get(uri);
        }
        return cached == null ? 0 : cached.tint;
    }
    private ResourceLocation texture(URI source, boolean avatar) {
        if (closed || source == null) return null;
        boolean square = PlayerTheme.pixel();
        // Entries only contain validated URIs; the usual cache hit needs no URI parsing or host regex.
        Entry cached = entries.get(source);
        URI uri = source;
        if (cached == null) {
            uri = ProviderUris.image(source.toString());
            if (uri == null) return null;
            cached = entries.get(uri);
        }
        if (cached != null) {
            // The corner mask is baked into the texture; a theme with other corners loads the image again.
            if (cached.pending != null || cached.texture == null || cached.square == square) return cached.texture;
            entries.remove(uri); release(cached);
        }
        Entry entry = new Entry(); entry.avatar = avatar; entry.square = square; entries.put(uri, entry);
        while (entries.size() > 24) {
            var iterator = entries.entrySet().iterator(); Entry oldest = iterator.next().getValue(); iterator.remove(); release(oldest);
        }
        final URI key = uri;
        var request = AsyncTasks.submit(worker, () -> load(http, key));
        entry.pending = request;
        request.whenComplete((pixels, error) -> Minecraft.getInstance().execute(() -> {
            if (closed || entries.get(key) != entry) return;
            entry.pending = null;
            if (error == null) install(entry, pixels);
        }));
        return null;
    }
    /** Normalize at download time so existing queues and account images benefit without rewriting their URLs. */
    static int[] load(HttpTransport http, URI source) {
        URI uri = ProviderUris.image(source.toString());
        if (uri == null) throw new MusicException(MusicError.INVALID_RESPONSE);
        URI thumbnail = dev.modernmusic.provider.netease.NeteaseUris.thumbnail(uri.toString(), SIZE * 2);
        return decode(http.get(thumbnail == null ? uri : thumbnail, 4 * 1024 * 1024));
    }
    /** Uploads decoded ARGB pixels with a baked rounded (round for avatars, square in pixel themes) alpha mask and mipmaps. */
    private void install(Entry entry, int[] pixels) {
        entry.tint = Palette.dominant(pixels);
        NativeImage image = new NativeImage(SIZE, SIZE, false);
        try {
            float radius = entry.square ? 0 : entry.avatar ? SIZE / 2f : SIZE * CORNER;
            for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                int argb = pixels[y * SIZE + x];
                int alpha = entry.square ? argb >>> 24 : Math.round((argb >>> 24) * corner(x, y, radius));
                image.setPixelRGBA(x, y, alpha << 24 | (argb & 0xff00) | (argb >>> 16 & 255) | (argb & 255) << 16);
            }
        } catch (RuntimeException | Error failure) { image.close(); throw failure; }
        entry.texture = UploadedTexture.register("artwork", UploadedTexture.mipChain(image, LEVELS), true);
    }
    /** Anti-aliased coverage of pixel (x, y) inside a square with rounded corners of the given radius. */
    static float corner(int x, int y, float radius) {
        float cx = Math.max(0, Math.max(radius - (x + .5f), (x + .5f) - (SIZE - radius)));
        float cy = Math.max(0, Math.max(radius - (y + .5f), (y + .5f) - (SIZE - radius)));
        double distance = Math.hypot(cx, cy) - radius;
        return (float) Math.clamp(.5 - distance, 0, 1);
    }
    private static int[] decode(byte[] bytes) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 8_000_000) throw new IllegalArgumentException();
                var parameters = reader.getDefaultReadParam();
                // Coarse decode-time subsampling keeps memory bounded; the area filter below does the quality pass.
                int step = Math.max(1, Math.min(width, height) / (SIZE * 2));
                parameters.setSourceSubsampling(step, step, 0, 0);
                BufferedImage source = reader.read(0, parameters);
                try {
                    int side = Math.min(source.getWidth(), source.getHeight());
                    int offsetX = (source.getWidth() - side) / 2, offsetY = (source.getHeight() - side) / 2;
                    int[] square = source.getRGB(offsetX, offsetY, side, side, null, 0, side);
                    return resample(square, side);
                } finally { source.flush(); }
            } finally { reader.dispose(); }
        } catch (CancellationException cancelled) { throw cancelled;
        } catch (Exception error) { throw new MusicException(MusicError.INVALID_RESPONSE); }
    }
    /** Box-filters (or bilinearly enlarges) a square ARGB image to {@link #SIZE}², weighting partial pixels. */
    static int[] resample(int[] source, int side) {
        int[] out = new int[SIZE * SIZE];
        double ratio = side / (double) SIZE;
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            if (x == 0 && Thread.currentThread().isInterrupted()) throw new CancellationException();
            double x0 = x * ratio, x1 = x0 + Math.max(1, ratio), y0 = y * ratio, y1 = y0 + Math.max(1, ratio);
            if (ratio < 1) { x0 = Math.max(0, (x + .5) * ratio - .5); x1 = x0 + 1; y0 = Math.max(0, (y + .5) * ratio - .5); y1 = y0 + 1; }
            double a = 0, r = 0, g = 0, b = 0, total = 0;
            for (int sy = (int) y0; sy < Math.min(side, Math.ceil(y1)); sy++) {
                double wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
                for (int sx = (int) x0; sx < Math.min(side, Math.ceil(x1)); sx++) {
                    double weight = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx));
                    if (weight <= 0) continue;
                    int argb = source[sy * side + sx];
                    a += (argb >>> 24) * weight; r += (argb >> 16 & 255) * weight; g += (argb >> 8 & 255) * weight; b += (argb & 255) * weight;
                    total += weight;
                }
            }
            if (total <= 0) continue;
            out[y * SIZE + x] = (int) Math.round(a / total) << 24 | (int) Math.round(r / total) << 16 | (int) Math.round(g / total) << 8 | (int) Math.round(b / total);
        }
        return out;
    }
    private void release(Entry entry) {
        if (entry.pending != null) entry.pending.cancel(true);
        if (entry.texture != null) Minecraft.getInstance().getTextureManager().release(entry.texture);
    }
    public void clear() {
        // Cancellation can invoke completion inline on the render thread. Detach first so
        // stale callbacks cannot install textures or reorder an access-ordered map mid-iteration.
        var removed = List.copyOf(entries.values()); entries.clear();
        removed.forEach(this::release); worker.purge();
    }
    @Override public void close() { closed = true; clear(); worker.shutdownNow(); http.close(); }
}
