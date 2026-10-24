package dev.modernmusic.room;

import com.google.gson.Gson;

/** Record constructors validate decoded data. Wire lengths are bounded before JSON parsing. */
public final class RoomJson {
    // 1000 public members plus full invitations/directory/queue, including worst-case JSON escaping.
    // FriendlyByteBuf's three-byte-per-character allowance remains below the 1 MiB custom payload cap.
    public static final int MAX_UPDATE_LENGTH = 320 * 1024;
    private static final Gson JSON = new Gson();
    private RoomJson() {}
    public static String encode(Object value) { return JSON.toJson(value); }
    public static <T> T decode(String text, Class<T> type) {
        T value = JSON.fromJson(text, type);
        if (value == null) throw new IllegalArgumentException("Empty room packet");
        return value;
    }
}
