package dev.modernmusic.smoke;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

/** One Forge entry point; client fixtures are never loaded on dedicated servers. */
@Mod("modernmusic_smoke")
public final class ForgeSmoke {
    public ForgeSmoke() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientFixtures::initialize);
        DistExecutor.safeRunWhenOn(Dist.DEDICATED_SERVER, () -> ServerFixtures::initialize);
    }
    private static final class ServerFixtures {
        static void initialize() { new RoomServerSmoke(); }
    }
    private static final class ClientFixtures {
        static void initialize() {
            if (Boolean.getBoolean("modernmusic.smokeHidden"))
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ClientTickEvent event) ->
                    org.lwjgl.glfw.GLFW.glfwHideWindow(net.minecraft.client.Minecraft.getInstance().getWindow().getWindow()));
            new ClientPlaybackSmoke();
            new FontSmoke();
            new LifecycleSmoke();
            new SearchScreenSmoke();
            new UiGallery();
            new OptionalClientSmoke();
            new RoomNetworkSmoke(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
