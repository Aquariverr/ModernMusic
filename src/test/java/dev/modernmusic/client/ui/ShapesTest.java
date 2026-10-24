package dev.modernmusic.client.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShapesTest {
    @Test void crispCornerIsTransparentOutsideOpaqueInsideAndSymmetric() {
        int r = 8;
        assertEquals(0, Shapes.coverage(r, 0, 0, r, 0, 0), 1e-6);
        assertEquals(1, Shapes.coverage(r, 0, 0, r, r - 1, r - 1), 1e-6);
        assertEquals(1, Shapes.coverage(r, 0, 0, r, r, r), 1e-6, "interior texel");
        for (int y = 0; y < r; y++) assertEquals(1, Shapes.coverage(r, 0, 0, r, r, y), 1e-6, "straight edge profile");
        for (int y = 0; y <= r; y++) for (int x = 0; x <= r; x++)
            assertEquals(Shapes.coverage(r, 0, 0, r, x, y), Shapes.coverage(r, 0, 0, r, y, x), 1e-6);
        float arc = Shapes.coverage(r, 0, 0, r, 1, 2);
        assertTrue(arc > 0 && arc < 1, "anti-aliased arc pixel: " + arc);
    }
    @Test void coverageGrowsTowardTheInterior() {
        int r = 12;
        for (int i = 1; i < r; i++) assertTrue(Shapes.coverage(r, 0, 0, r, i, i) >= Shapes.coverage(r, 0, 0, r, i - 1, i - 1));
    }
    @Test void ringKeepsOnlyTheBorderEvenWithoutRadius() {
        assertEquals(0, Shapes.coverage(6, 0, 2, 6, 6, 6), 1e-6, "ring interior is empty");
        assertEquals(1, Shapes.coverage(6, 0, 2, 6, 6, 0), 1e-6);
        assertEquals(1, Shapes.coverage(6, 0, 2, 6, 6, 1), 1e-6);
        assertEquals(0, Shapes.coverage(6, 0, 2, 6, 6, 2), 1e-6);
        assertEquals(1, Shapes.coverage(0, 0, 1, 1, 0, 0), 1e-6, "square hairline corner");
        assertEquals(0, Shapes.coverage(0, 0, 1, 1, 1, 1), 1e-6);
    }
    @Test void softShadowFadesSymmetricallyAroundTheEdge() {
        int r = 4, soft = 6, n = r + 2 * soft;
        assertEquals(1, Shapes.coverage(r, soft, 0, n, n, n), 1e-6, "deep interior");
        assertEquals(.5, Shapes.coverage(r, soft, 0, n, n, soft), .1, "half strength on the shape edge");
        assertTrue(Shapes.coverage(r, soft, 0, n, n, 0) < .05, "fades out at the blur reach");
        assertTrue(Shapes.coverage(r, soft, 0, n, 0, 0) < .01);
        assertEquals(1, Shapes.coverage(r, soft, 0, n, n, n - 1), 1e-3);
    }
    @Test void mixInterpolatesEveryChannel() {
        assertEquals(0xFF000000, Shapes.mix(0xFF000000, 0xFFFFFFFF, 0));
        assertEquals(0xFFFFFFFF, Shapes.mix(0xFF000000, 0xFFFFFFFF, 1));
        int half = Shapes.mix(0x00000000, 0xFFFFFFFF, .5f);
        for (int shift = 0; shift < 32; shift += 8) assertEquals(128, half >>> shift & 255, 1);
        assertEquals(0xFF102030, Shapes.mix(0xFF102030, 0xFF102030, .3f));
    }
}
