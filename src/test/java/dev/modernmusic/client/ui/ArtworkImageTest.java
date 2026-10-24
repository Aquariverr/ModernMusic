package dev.modernmusic.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtworkImageTest {
    @Test void largeNeteaseCoverUsesCdnThumbnailBeforeBoundedDecode() throws Exception {
        // The reported song's original cover is 3000 x 3000: 9 MP, beyond the decoder's 8 MP limit.
        var original = java.net.URI.create("https://p1.music.126.net/tdVjGGLeF9dnTPDySyLWXw==/109951167013803499.jpg");
        byte[] fullSize = image(3000), thumbnail = image(512);
        var requested = new java.util.ArrayList<java.net.URI>();
        int[] pixels = ArtworkCache.load((uri, limit) -> {
            requested.add(uri);
            assertEquals(4 * 1024 * 1024, limit);
            return "param=512y512".equals(uri.getRawQuery()) ? thumbnail : fullSize;
        }, original);
        assertEquals(java.util.List.of(java.net.URI.create(original + "?param=512y512")), requested);
        assertEquals(ArtworkCache.SIZE * ArtworkCache.SIZE, pixels.length);
        for (int pixel : pixels) assertEquals(0xFF3366CC, pixel);
        assertNull(original.getRawQuery(), "Stored metadata must keep its original artwork URL");
    }
    @Test void otherPlatformsRetainTheirImageUrlsAndInvalidHostsAreNeverRequested() throws Exception {
        byte[] png = image(32);
        for (String url : java.util.List.of("https://y.gtimg.cn/music/photo_new/cover.jpg", "https://imge.kugou.com/cover/400/cover.jpg")) {
            var original = java.net.URI.create(url);
            assertEquals(ArtworkCache.SIZE * ArtworkCache.SIZE, ArtworkCache.load((uri, limit) -> {
                assertEquals(original, uri); return png;
            }, original).length);
        }
        assertThrows(dev.modernmusic.provider.MusicException.class, () -> ArtworkCache.load((uri, limit) -> {
            throw new AssertionError("Invalid host was requested");
        }, java.net.URI.create("https://localhost/cover.jpg")));
    }
    private static byte[] image(int side) throws Exception {
        var image = new java.awt.image.BufferedImage(side, side, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(new java.awt.Color(0x3366CC)); graphics.fillRect(0, 0, side, side); }
        finally { graphics.dispose(); }
        try (var bytes = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } finally { image.flush(); }
    }
    @Test void roundedMaskClearsCornersAndKeepsEdges() {
        float radius = ArtworkCache.SIZE * ArtworkCache.CORNER;
        assertEquals(0, ArtworkCache.corner(0, 0, radius), 1e-6);
        assertEquals(1, ArtworkCache.corner(128, 128, radius), 1e-6);
        assertEquals(1, ArtworkCache.corner(128, 0, radius), 1e-6);
        assertEquals(0, ArtworkCache.corner(255, 255, radius), 1e-6);
        float edge = ArtworkCache.corner(8, 9, radius); // Straddles the arc of the default radius.
        assertTrue(edge > 0 && edge < 1, "anti-aliased corner: " + edge);
        assertEquals(1, ArtworkCache.corner(128, 128, ArtworkCache.SIZE / 2f), 1e-6, "avatar circle center");
        assertEquals(0, ArtworkCache.corner(0, 0, ArtworkCache.SIZE / 2f), 1e-6, "avatar square corner is outside the circle");
        assertTrue(ArtworkCache.corner(0, 128, ArtworkCache.SIZE / 2f) > .9, "avatar circle reaches the edge midpoint");
    }
    @Test void areaResampleAveragesInsteadOfSkippingPixels() {
        int side = ArtworkCache.SIZE * 2;
        int[] stripes = new int[side * side];
        for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) stripes[y * side + x] = x % 2 == 0 ? 0xFF000000 : 0xFFFFFFFF;
        int[] out = ArtworkCache.resample(stripes, side);
        for (int pixel : out) {
            assertEquals(0xFF, pixel >>> 24);
            assertEquals(128, pixel >> 16 & 255, 1, "1-pixel stripes must blend to gray, not alias");
        }
    }
    @Test void uniformColorSurvivesUpAndDownscaling() {
        for (int side : new int[]{100, 256, 777}) {
            int[] source = new int[side * side]; java.util.Arrays.fill(source, 0xFF3366CC);
            for (int pixel : ArtworkCache.resample(source, side)) assertEquals(0xFF3366CC, pixel);
        }
    }
}
