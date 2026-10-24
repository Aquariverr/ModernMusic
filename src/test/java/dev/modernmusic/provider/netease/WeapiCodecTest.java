package dev.modernmusic.provider.netease;

import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class WeapiCodecTest {
    private static String decrypt(String value, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"),
                new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.US_ASCII)));
        return new String(cipher.doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
    }
    @Test void encodesUtf8JsonInTwoAesLayersAndFullWidthRsaEnvelope() throws Exception {
        String json = "{\"phone\":\"2025550123\",\"captcha\":\"123456\",\"label\":\"测试\"}";
        String form = new String(WeapiCodec.encode(json, "abcdefghijklmnop"), StandardCharsets.US_ASCII);
        String[] parts = form.split("&encSecKey=");
        assertEquals(2, parts.length); assertTrue(parts[1].matches("[a-f0-9]{256}"));
        String outer = URLDecoder.decode(parts[0].substring("params=".length()), StandardCharsets.UTF_8);
        assertEquals(json, decrypt(decrypt(outer, "abcdefghijklmnop"), "0CoJUm6Qyw8W8jud"));
        assertFalse(form.contains("2025550123")); assertFalse(form.contains("captcha"));
        // Public-key byte accuracy and RSA endianness are also exercised by the real anonymous probe.
    }
    @Test void everyRequestUsesFreshRandomEnvelope() {
        assertFalse(java.util.Arrays.equals(WeapiCodec.encode("{}"), WeapiCodec.encode("{}")));
    }
}
