package dev.modernmusic.smoke;

import java.nio.file.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Dedicated-server classloading regression; excluded from the distributed mod. */
@Mod(value = "modernmusic_smoke", dist = Dist.DEDICATED_SERVER)
public final class RoomServerSmoke {
    private boolean started;
    private int ticks;
    public RoomServerSmoke() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> started = true);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (!started || ++ticks != 20) return;
            try {
                boolean ftb = net.neoforged.fml.ModList.get().isLoaded("ftbteams");
                var teams = ftb ? new dev.modernmusic.compat.FtbTeamsDirectory() : dev.modernmusic.room.TeamDirectory.ABSENT;
                var expected = ftb ? dev.modernmusic.room.TeamDirectory.State.READY : dev.modernmusic.room.TeamDirectory.State.NOT_INSTALLED;
                if (teams.state() != expected) throw new IllegalStateException("Unexpected FTB manager state: " + teams.state());
                if (dev.modernmusic.server.RoomConfig.maxOwnedRooms() != 2)
                    throw new IllegalStateException("The dedicated server did not load the default per-owner room limit");
                var config = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("modernmusic-server.toml");
                if (!Files.isRegularFile(config)) throw new IllegalStateException("Server room config was not generated");
                SharedProtocolSmoke.verify();
                var command = event.getServer().getCommands().getDispatcher().getRoot().getChild("modernmusic");
                if (command != null && command.getChild("shared") != null)
                    throw new IllegalStateException("Obsolete shared-account commands are still registered");
                Files.writeString(Path.of(ftb ? "server-ftb-result.txt" : "server-no-ftb-result.txt"),
                        "PASS: dedicated server loaded UI administration and saved-room config (limit=2); room directory/dissolve payload codecs, URL bounds and removal of obsolete commands passed; FTB=" + teams.state() + "\n");
            }
            catch (Exception failure) { throw new IllegalStateException("Cannot write dedicated smoke result", failure); }
            event.getServer().halt(false);
        });
    }
}
