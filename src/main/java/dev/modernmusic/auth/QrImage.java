package dev.modernmusic.auth;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/** Decode the provider's original QR image on the account worker; UI renders its exact payload. */
public final class QrImage {
    private QrImage() {}
    public static String content(byte[] bytes) {
        if (bytes == null || bytes.length > 1024 * 1024) throw new AuthException("invalid_response");
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new AuthException("invalid_response");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 21 || height < 21 || width > 1024 || height > 1024) throw new AuthException("invalid_response");
                var image = reader.read(0);
                var source = new RGBLuminanceSource(width, height, image.getRGB(0, 0, width, height, null, 0, width));
                String content = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
                if (content.isBlank() || content.length() > 2048) throw new AuthException("invalid_response");
                return content;
            } finally { reader.dispose(); }
        } catch (Exception invalid) { throw new AuthException("invalid_response"); }
    }
}
