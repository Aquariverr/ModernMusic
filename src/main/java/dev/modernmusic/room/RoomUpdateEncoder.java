package dev.modernmusic.room;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

/** One delivery batch shares both room JSON and identical completed packets. */
public final class RoomUpdateEncoder {
    private record EncodedRoom(String json, Map<String, String> packets) {}
    private final long sequence;
    private final Function<RoomSnapshot, String> encodeRoom;
    // Snapshot identity is stable within a batch. Record hashing would traverse all members again.
    private final Map<RoomSnapshot, EncodedRoom> rooms = new IdentityHashMap<>();

    public RoomUpdateEncoder(long sequence) { this(sequence, RoomJson::encode); }
    RoomUpdateEncoder(long sequence, Function<RoomSnapshot, String> encodeRoom) {
        this.sequence = sequence; this.encodeRoom = encodeRoom;
    }

    public String encode(RoomUpdate state) {
        String envelope = RoomJson.encode(new RoomUpdate(sequence, null, state.invitations(), state.message(),
                state.team(), state.savedRooms(), state.roomLimit()));
        if (state.room() == null) return envelope;
        EncodedRoom room = rooms.computeIfAbsent(state.room(), snapshot -> new EncodedRoom(encodeRoom.apply(snapshot), new HashMap<>()));
        // Gson omits the null room in the envelope. Append already encoded JSON without parsing or visiting its members.
        return room.packets.computeIfAbsent(envelope, json -> json.substring(0, json.length() - 1) + ",\"room\":" + room.json + "}");
    }
}
