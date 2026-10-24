package dev.modernmusic;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/** Common entry point. Keep client UI and audio classes out of this class. */
@Mod(ModernMusic.MOD_ID)
public final class ModernMusic {
    public static final String MOD_ID = "modernmusic";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static dev.modernmusic.network.SharedAccountNetwork sharedAccount;

    public ModernMusic() {
        IEventBus modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        var container = net.minecraftforge.fml.ModLoadingContext.get();
        container.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, dev.modernmusic.server.SharedAccountConfig.SPEC, "modernmusic-common.toml");
        container.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, dev.modernmusic.server.RoomConfig.SPEC, "modernmusic-server.toml");
        new dev.modernmusic.network.RoomNetwork(modEventBus);
        new dev.modernmusic.network.AudioNetwork(modEventBus);
        sharedAccount = new dev.modernmusic.network.SharedAccountNetwork(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        net.minecraftforge.fml.DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> dev.modernmusic.client.ClientBootstrap::initialize);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Modern Music initialized. Personal guest playback is available on the client.");
    }
}
