package dev.modernmusic.server;

import dev.modernmusic.room.RoomService;
import net.minecraftforge.common.ForgeConfigSpec;

/** World/server settings. Existing rooms survive a lower creation limit. */
public final class RoomConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_OWNED_ROOMS;
    static {
        var builder = new ForgeConfigSpec.Builder();
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
