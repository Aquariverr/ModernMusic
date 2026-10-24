package dev.modernmusic.auth;

import javax.annotation.Nonnull;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.modernmusic.domain.MusicPlatform;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Browser exports are reduced locally to the selected platform's session fields. */
final class BrowserCookieImport {
    private BrowserCookieImport() {}

    static String header(String input, MusicPlatform platform) {
        if (input == null || input.length() > SessionCookie.MAX_IMPORT_LENGTH) throw invalid();
        if (!SessionCookie.isJsonImport(input)) return input;
        String json = input.stripLeading();
        if (json.startsWith("\uFEFF")) json = json.substring(1);
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement root = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            if (root.isJsonObject() && root.getAsJsonObject().has("cookies")) root = root.getAsJsonObject().get("cookies");
            var selected = new LinkedHashMap<String, Candidate>();
            long now = Instant.now().getEpochSecond();
            if (root.isJsonArray()) {
                for (JsonElement entry : root.getAsJsonArray()) {
                    if (!entry.isJsonObject()) throw invalid();
                    select(entry.getAsJsonObject(), platform, selected, now);
                }
            } else if (root.isJsonObject()) {
                for (var entry : root.getAsJsonObject().entrySet()) {
                    selectValue(entry.getKey(), string(entry.getValue()), platform, selected, 0);
                }
            } else throw invalid();
            if (selected.values().stream().anyMatch(candidate -> candidate.conflict)) throw invalid();
            String header = selected.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().value)
                    .collect(Collectors.joining("; "));
            if (header.length() > SessionCookie.MAX_LENGTH) throw invalid();
            return header;
        } catch (IOException | RuntimeException failure) {
            // JSON parse errors can embed a token or path; never propagate their messages or causes.
            throw invalid();
        }
    }

    private static void select(JsonObject cookie, MusicPlatform platform, Map<String, Candidate> selected, long now) {
        String name = string(cookie.get("name"));
        if (SessionCookie.canonicalName(name, platform) == null && !packed(name, platform)) return;
        int rank = domainRank(cookie, platform);
        if (rank < 0) return;
        // These credentials are reused across account and playback endpoints, so import root-path cookies only.
        if (cookie.has("path") && !"/".equals(string(cookie.get("path")))) return;
        if (expired(cookie, now)) return;
        selectValue(name, string(cookie.get("value")), platform, selected, rank);
    }

    private static boolean packed(String name, MusicPlatform platform) {
        return platform == MusicPlatform.KUGOU && "KuGoo".equals(name);
    }
    private static void selectValue(String name, String value, MusicPlatform platform, Map<String, Candidate> selected, int rank) {
        if (packed(name, platform)) {
            var fields = KugouCookieFields.unpack(value);
            fields.forEach((key, entry) -> put(selected, key, entry, rank));
            if (fields.containsKey("token")) put(selected, "appid", "1014", -1);
        } else {
            String canonical = SessionCookie.canonicalName(name, platform);
            if (canonical != null) put(selected, canonical, value, rank);
            if (platform == MusicPlatform.KUGOU && "t".equals(name) && value != null && !value.isEmpty())
                put(selected, "appid", "1014", -1);
        }
    }

    private static int domainRank(JsonObject cookie, MusicPlatform platform) {
        if (!cookie.has("domain")) return 0; // Minimal name/value exports have no scope metadata.
        String domain = string(cookie.get("domain"));
        if (domain == null || domain.length() > 254) return -1;
        domain = domain.toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) domain = domain.substring(1);
        if (!domain.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")) return -1;
        String site = switch (platform) {
            case NETEASE -> "music.163.com";
            case QQ -> "y.qq.com";
            case KUGOU -> "kugou.com";
            case LOCAL -> throw new IllegalArgumentException("Local audio has no account");
        };
        if (domain.equals(site)) return 3;
        String parent = switch (platform) {
            case NETEASE -> "163.com";
            case QQ -> "qq.com";
            case KUGOU -> "kugou.com";
            case LOCAL -> throw new IllegalArgumentException("Local audio has no account");
        };
        if (domain.equals(parent)) return 2;
        if (domain.endsWith("." + site)) return 1;
        return -1;
    }

    private static boolean expired(JsonObject cookie, long now) {
        if (cookie.has("session") && cookie.get("session").isJsonPrimitive()
                && cookie.get("session").getAsJsonPrimitive().isBoolean() && cookie.get("session").getAsBoolean()) return false;
        for (String field : new String[]{"expirationDate", "expires"}) {
            JsonElement value = cookie.get(field);
            if (value == null || value.isJsonNull()) continue;
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
            BigDecimal seconds = value.getAsBigDecimal();
            // Playwright uses -1 for a session cookie; browser expiry timestamps are Unix seconds.
            if (seconds.compareTo(BigDecimal.valueOf(-1)) == 0) continue;
            if (seconds.compareTo(BigDecimal.valueOf(now)) <= 0) return true;
        }
        return false;
    }

    private static void put(Map<String, Candidate> selected, String name, String value, int rank) {
        if (value != null && value.isEmpty()) return; // Empty/deleted optional cookies are common in exports.
        if (!CookieValue.valid(value, SessionCookie.MAX_LENGTH)) throw invalid();
        Candidate previous = selected.get(name);
        if (previous == null || rank > previous.rank) selected.put(name, new Candidate(value, rank, false));
        else if (rank == previous.rank && !previous.value.equals(value))
            selected.put(name, new Candidate(previous.value, rank, true));
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    // Keep Gson's reader in strict mode, reject duplicate object keys, and bound nesting before recursion.
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw invalid();
        return switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                var array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case BEGIN_OBJECT -> {
                var object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw invalid();
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid();
        };
    }

    private record Candidate(String value, int rank, boolean conflict) {
        @Nonnull @Override public String toString() { return "CookieCandidate[redacted]"; }
    }
    private static AuthException invalid() { return new AuthException("invalid_cookie"); }
}
