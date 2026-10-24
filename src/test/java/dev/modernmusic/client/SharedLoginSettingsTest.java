package dev.modernmusic.client;

import dev.modernmusic.domain.MusicPlatform;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedLoginSettingsTest {
    @TempDir Path directory;

    @Test void preferencesSurviveRestartAndStaySeparateForEveryServerAndPlatform() {
        Path file = directory.resolve("shared-login.json");
        var settings = new SharedLoginSettings(file);
        for (var platform : MusicPlatform.values()) {
            assertFalse(settings.remembered("server:first", platform));
            assertTrue(settings.remember("server:" + platform, platform, true));
        }
        var restored = new SharedLoginSettings(file);
        for (var serverPlatform : MusicPlatform.values()) for (var platform : MusicPlatform.values())
            assertEquals(serverPlatform == platform, restored.remembered("server:" + serverPlatform, platform));
        assertFalse(restored.remembered("server:other", MusicPlatform.NETEASE));
        assertFalse(restored.remembered(null, MusicPlatform.NETEASE));
        assertFalse(restored.remember(null, MusicPlatform.NETEASE, true));
    }

    @Test void uncheckingRemovesOnlyThatPreferenceAndPersists() {
        Path file = directory.resolve("shared-login.json");
        var settings = new SharedLoginSettings(file);
        for (var platform : MusicPlatform.values()) assertTrue(settings.remember("server:first", platform, true));
        assertTrue(settings.remember("server:second", MusicPlatform.QQ, true));
        assertTrue(settings.remember("server:first", MusicPlatform.QQ, false));
        var restored = new SharedLoginSettings(file);
        assertFalse(restored.remembered("server:first", MusicPlatform.QQ));
        assertTrue(restored.remembered("server:first", MusicPlatform.NETEASE));
        assertTrue(restored.remembered("server:first", MusicPlatform.KUGOU));
        assertTrue(restored.remembered("server:second", MusicPlatform.QQ));
    }

    @Test void invalidSettingsNeverEnableAutomaticLogin() throws Exception {
        Path file = directory.resolve("shared-login.json");
        for (String invalid : new String[]{"{", "{}", "{\"version\":2,\"servers\":{\"server:first\":{\"QQ\":true}}}",
                "{\"version\":1,\"servers\":{\"server:first\":{\"QQ\":\"true\"}}}"}) {
            Files.writeString(file, invalid);
            assertFalse(new SharedLoginSettings(file).remembered("server:first", MusicPlatform.QQ));
        }
    }

    @Test void failedSaveRetainsPreviouslySavedChoice() throws Exception {
        Path file = directory.resolve("shared-login.json");
        var settings = new SharedLoginSettings(file);
        assertTrue(settings.remember("server:first", MusicPlatform.QQ, true));
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "fixture");
        assertFalse(settings.remember("server:first", MusicPlatform.QQ, false));
        assertTrue(settings.remembered("server:first", MusicPlatform.QQ));
        assertFalse(settings.remember("server:first", MusicPlatform.KUGOU, true));
        assertFalse(settings.remembered("server:first", MusicPlatform.KUGOU));
    }
}
