package dev.modernmusic.server;

import dev.modernmusic.room.RoomService;
import net.neoforged.neoforge.common.ModConfigSpec;

/** World/server settings. Existing rooms survive a lower creation limit. */
public final class RoomConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_OWNED_ROOMS;
    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("rooms");
        MAX_OWNED_ROOMS = builder.comment(
                "Maximum persistent rooms each player may create. FTB Teams rooms do not count.",
                "Lowering this limit keeps existing rooms. Set to 0 to disable new room creation.")
                .defineInRange("maxOwnedRooms", 2, 0, RoomService.MAX_ROOMS);
        builder.pop(); SPEC = builder.build();
    }
    public static int maxOwnedRooms() { return MAX_OWNED_ROOMS.get(); }
    private RoomConfig() {}
}
