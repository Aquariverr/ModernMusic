package dev.modernmusic.provider.netease;

import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** NetEase web request envelope. TLS still provides transport security; these are public protocol constants. */
final class WeapiCodec {
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private WeapiCodec() {}

    static byte[] encode(String json) {
        char[] secret = new char[16];
        for (int i = 0; i < secret.length; i++) secret[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        try { return encode(json, new String(secret)); }
        finally { Arrays.fill(secret, '\0'); }
    }
    static byte[] encode(String json, String secret) {
        try {
            if (secret.length() != 16) throw new IllegalArgumentException();
            String first = encrypt(json, "0CoJUm6Qyw8W8jud");
            String second = encrypt(first, secret);
            Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
            rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))));
            byte[] block = new byte[128];
            byte[] reversed = new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(reversed, 0, block, block.length - reversed.length, reversed.length);
            String encryptedKey = HexFormat.of().formatHex(rsa.doFinal(block));
            return ("params=" + URLEncoder.encode(second, StandardCharsets.UTF_8) + "&encSecKey=" + encryptedKey).getBytes(StandardCharsets.US_ASCII);
        } catch (Exception error) { throw new MusicException(MusicError.INTERNAL_ERROR); }
    }
    private static String encrypt(String text, String key) throws Exception {
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"),
                new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.US_ASCII)));
        return Base64.getEncoder().encodeToString(aes.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
}
