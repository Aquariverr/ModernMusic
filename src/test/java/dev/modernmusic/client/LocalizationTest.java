package dev.modernmusic.client;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    JsonObject language(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/modernmusic/lang/" + name + ".json")) {
            assertNotNull(input); return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void chineseAndEnglishCoverEveryLiteralUiKeyWithMatchingPlaceholders() throws Exception {
        var zh = language("zh_cn"); var en = language("en_us");
        assertEquals(en.keySet(), zh.keySet());
        var placeholders = Pattern.compile("%(?:\\d+\\$)?[sd]");
        for (String key : en.keySet()) {
            assertFalse(zh.get(key).getAsString().isBlank(), key);
            assertFalse(zh.get(key).getAsString().contains("\uFFFD"), key);
            assertEquals(placeholders.matcher(en.get(key).getAsString()).results().count(),
                    placeholders.matcher(zh.get(key).getAsString()).results().count(), key);
        }
        var keys = Pattern.compile("\"((?:modernmusic|key\\.modernmusic|key\\.categories\\.modernmusic)\\.[a-z_][a-z0-9_.]*)\"");
        var authKeys = Pattern.compile("new AuthException\\(\"([a-z_]+)\"\\)");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matches = keys.matcher(Files.readString(file));
                while (matches.find()) {
                    String key = matches.group(1);
                    if (!key.endsWith(".")) assertTrue(zh.has(key), () -> "Missing translation: " + key + " in " + file);
                }
                var authMatches = authKeys.matcher(Files.readString(file));
                while (authMatches.find()) {
                    String key = "modernmusic.auth." + authMatches.group(1);
                    assertTrue(zh.has(key), () -> "Missing authentication translation: " + key + " in " + file);
                }
            }
        }
    }
}
