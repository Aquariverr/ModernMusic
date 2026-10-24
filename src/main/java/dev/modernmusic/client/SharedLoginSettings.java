package dev.modernmusic.client;

import com.google.gson.*;
import dev.modernmusic.domain.MusicPlatform;
import java.nio.file.*;
import java.util.*;

/** Local opt-ins only; each server and platform has an independent preference, without account credentials. */
public final class SharedLoginSettings {
    private final Path file;
    private final Map<String, EnumSet<MusicPlatform>> servers = new TreeMap<>();

    public SharedLoginSettings(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(this.file) || Files.size(this.file) > 1_048_576) return;
            var json = JsonParser.parseString(Files.readString(this.file)).getAsJsonObject();
            if (json.get("version").getAsInt() != 1) return;
            var loaded = new TreeMap<String, EnumSet<MusicPlatform>>();
            for (var entry : json.getAsJsonObject("servers").entrySet()) {
                var platforms = EnumSet.noneOf(MusicPlatform.class);
                for (var platform : MusicPlatform.values()) {
                    var value = entry.getValue().getAsJsonObject().get(platform.name());
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean())
                        platforms.add(platform);
                }
                if (!platforms.isEmpty()) loaded.put(entry.getKey(), platforms);
            }
            servers.putAll(loaded);
        } catch (Exception ignored) { /* Missing or invalid settings keep shared playback opt-in. */ }
    }

    public boolean remembered(String server, MusicPlatform platform) {
        return server != null && servers.containsKey(server) && servers.get(server).contains(platform);
    }

    public boolean remember(String server, MusicPlatform platform, boolean value) {
        if (server == null || server.isBlank()) return false;
        boolean previous = remembered(server, platform);
        if (value == previous) return true;
        set(server, platform, value);
        if (save()) return true;
        set(server, platform, previous);
        return false;
    }

    private void set(String server, MusicPlatform platform, boolean value) {
        if (value) servers.computeIfAbsent(server, ignored -> EnumSet.noneOf(MusicPlatform.class)).add(platform);
        else {
            var platforms = servers.get(server);
            if (platforms != null && platforms.remove(platform) && platforms.isEmpty()) servers.remove(server);
        }
    }

    private boolean save() {
        Path temporary = null;
        try {
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) return false;
            Files.createDirectories(file.getParent());
            var json = new JsonObject(); json.addProperty("version", 1);
            var rows = new JsonObject(); json.add("servers", rows);
            servers.forEach((server, platforms) -> {
                var row = new JsonObject(); rows.add(server, row);
                platforms.forEach(platform -> row.addProperty(platform.name(), true));
            });
            temporary = Files.createTempFile(file.getParent(), "modernmusic-shared-login-", ".tmp");
            Files.writeString(temporary, json.toString());
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (Exception ignored) { return false; }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }
}
