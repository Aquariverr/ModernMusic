package dev.modernmusic.client.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.detector.Detector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QrDrawingTest {
    @Test void mergedRunsKeepEveryModuleAndQuietZoneAtIntegerScales() throws Exception {
        String content = "https://music.163.com/login?codekey=0123456789abcdef0123456789abcdef";
        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        for (int scale : new int[]{1, 2, 5}) {
            int left = 7, top = 11;
            var image = new BitMatrix(matrix.getWidth() * scale + 2 * left, matrix.getHeight() * scale + 2 * top);
            var rectangles = new AtomicInteger();
            QrDrawing.modules(matrix, left, top, scale, (x0, y0, x1, y1) -> {
                assertEquals(0, (x0 - left) % scale); assertEquals(0, (y0 - top) % scale);
                assertEquals(scale, y1 - y0); assertTrue(x1 > x0);
                for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                    assertFalse(image.get(x, y), "runs must not overlap"); image.set(x, y);
                }
                rectangles.incrementAndGet();
            });
            int black = 0;
            for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) black++;
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++)
                    assertEquals(matrix.get(x, y), image.get(left + x * scale + dx, top + y * scale + dy));
            }
            assertTrue(rectangles.get() < black, "adjacent modules should share a quad");
            assertEquals(content, new Decoder().decode(new Detector(image).detect().getBits()).getText());
        }
    }

    @Test void emptyAndEdgeRunsNeedNoPaddingOrExtraModules() {
        var matrix = new BitMatrix(7, 3);
        var runs = new java.util.ArrayList<String>();
        QrDrawing.modules(matrix, 0, 0, 1, (x0, y0, x1, y1) -> fail("empty QR emits no quads"));
        matrix.setRegion(0, 0, 7, 1); matrix.set(0, 2); matrix.set(6, 2);
        QrDrawing.modules(matrix, 10, 20, 2, (x0, y0, x1, y1) -> runs.add(x0 + "," + y0 + "," + x1 + "," + y1));
        assertEquals(java.util.List.of("10,20,24,22", "10,24,12,26", "22,24,24,26"), runs);
    }
}
