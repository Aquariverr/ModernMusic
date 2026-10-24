package dev.modernmusic.smoke;

import java.nio.file.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.TickEvent;

/** Dedicated-server classloading regression; excluded from the distributed mod. */
public final class RoomServerSmoke {
    private boolean started;
    private int ticks;
    public RoomServerSmoke() {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> started = true);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase != TickEvent.Phase.END) return;
            if (!started || ++ticks != 20) return;
            try {
                boolean ftb = net.minecraftforge.fml.ModList.get().isLoaded("ftbteams");
                var teams = ftb ? new dev.modernmusic.compat.FtbTeamsDirectory() : dev.modernmusic.room.TeamDirectory.ABSENT;
                var expected = ftb ? dev.modernmusic.room.TeamDirectory.State.READY : dev.modernmusic.room.TeamDirectory.State.NOT_INSTALLED;
                if (teams.state() != expected) throw new IllegalStateException("Unexpected FTB manager state: " + teams.state());
                if (dev.modernmusic.server.RoomConfig.maxOwnedRooms() != 2)
                    throw new IllegalStateException("The dedicated server did not load the default per-owner room limit");
                var config = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig/modernmusic-server.toml");
                if (!Files.isRegularFile(config)) throw new IllegalStateException("Server room config was not generated");
                SharedProtocolSmoke.verify();
                var savedRooms = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("data/modernmusic-rooms.json");
                try (var store = new dev.modernmusic.server.RoomStore(savedRooms)) {
                    var publicRooms = store.load().stream()
                            .filter(room -> dev.modernmusic.room.RoomService.PUBLIC_ROOM_ID.equals(room.id())).toList();
                    if (store.failed() || publicRooms.size() != 1
                            || !dev.modernmusic.room.RoomService.SERVER_OWNER.equals(publicRooms.get(0).owner()))
                        throw new IllegalStateException("Server public room was not opened and persisted at startup");
                }
                var command = event.getServer().getCommands().getDispatcher().getRoot().getChild("modernmusic");
                if (command != null && command.getChild("shared") != null)
                    throw new IllegalStateException("Obsolete shared-account commands are still registered");
                Files.writeString(Path.of(ftb ? "server-ftb-result.txt" : "server-no-ftb-result.txt"),
                        "PASS: dedicated server opened and persisted the public room; saved-room config (limit=2), 1000-member public room/administrator payload codecs, URL bounds and removal of obsolete commands passed; FTB=" + teams.state() + "\n");
            }
            catch (Exception failure) { throw new IllegalStateException("Cannot write dedicated smoke result", failure); }
            event.getServer().halt(false);
        });
    }
}
