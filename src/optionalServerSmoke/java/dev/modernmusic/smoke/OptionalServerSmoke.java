package dev.modernmusic.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.TickEvent;

/** Lifecycle-only fixture: no Modern Music registration or payloads on this server. */
@Mod("modernmusic_optional_smoke")
public final class OptionalServerSmoke {
    private boolean started, finished;
    private long deadline;
    public OptionalServerSmoke() {
        if (!Boolean.getBoolean("modernmusic.optionalServerSmoke")) return;
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private void started(ServerStartedEvent event) {
        try {
            if (ModList.get().isLoaded("modernmusic")) throw new IllegalStateException("Modern Music must be absent from this server");
            Files.writeString(Path.of("optional-ready.txt"), "READY: Modern Music absent\n");
            started = true; deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(7);
        } catch (Exception failure) { finish(event.getServer(), "FAIL: " + failure); }
    }
    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!started || finished) return;
        try {
            Path result = Path.of("../optional-client-smoke/optional-result.txt");
            if (Files.isRegularFile(result)) {
                String value = Files.readString(result);
                if (value.startsWith("PASS:")) finish(event.getServer(), "PASS: dedicated Forge server without Modern Music accepted client and completed compatibility scenario");
                else if (value.startsWith("FAIL:")) finish(event.getServer(), "FAIL: client compatibility scenario failed");
            }
            if (System.nanoTime() > deadline) finish(event.getServer(), "FAIL: compatibility client timed out");
        } catch (Exception failure) { finish(event.getServer(), "FAIL: " + failure); }
    }
    private void finish(net.minecraft.server.MinecraftServer server, String result) {
        finished = true;
        try { Files.writeString(Path.of("optional-result.txt"), result + "\n"); }
        catch (Exception failure) { throw new IllegalStateException("Cannot write server result", failure); }
        server.halt(false);
    }
}
