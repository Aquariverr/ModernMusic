package dev.modernmusic.room;

import com.google.gson.Gson;

/** Record constructors validate decoded data. Wire lengths are bounded before JSON parsing. */
public final class RoomJson {
    public static final int MAX_UPDATE_LENGTH = 262144;
    private static final Gson JSON = new Gson();
    private RoomJson() {}
    public static String encode(Object value) { return JSON.toJson(value); }
    public static <T> T decode(String text, Class<T> type) {
        T value = JSON.fromJson(text, type);
        if (value == null) throw new IllegalArgumentException("Empty room packet");
        return value;
    }
}
