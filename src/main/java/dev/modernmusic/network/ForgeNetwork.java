package dev.modernmusic.network;

import java.util.function.BiConsumer;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Optional Forge play channel. All handlers execute on their receiving game thread. */
public final class ForgeNetwork {
    private static final String VERSION = "1.20.1-3-bounded-room";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("modernmusic", "main"), () -> VERSION,
            ForgeNetwork::compatible, ForgeNetwork::compatible);
    private static final Registrar REGISTRAR = new Registrar();
    private static int nextId;

    private ForgeNetwork() {}
    private static boolean compatible(String version) {
        return VERSION.equals(version) || NetworkRegistry.ABSENT.version().equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }
    public static boolean available(Connection connection) { return CHANNEL.isRemotePresent(connection); }
    public static void sendToServer(MusicPayload packet) { CHANNEL.sendToServer(packet); }
    public static void sendToPlayer(ServerPlayer player, MusicPayload packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
    public static Registrar registrar() { return REGISTRAR; }

    public record Context(NetworkEvent.Context forge) {
        public Player player() { return forge.getSender(); }
        public void disconnect(Component reason) { forge.getNetworkManager().disconnect(reason); }
    }

    public static final class Registrar {
        public <T extends MusicPayload> void playToServer(MusicPayload.Type<T> type, PacketCodec<FriendlyByteBuf, T> codec, BiConsumer<T, Context> handler) {
            register(type, codec, handler, NetworkDirection.PLAY_TO_SERVER);
        }
        public <T extends MusicPayload> void playToClient(MusicPayload.Type<T> type, PacketCodec<FriendlyByteBuf, T> codec, BiConsumer<T, Context> handler) {
            register(type, codec, handler, NetworkDirection.PLAY_TO_CLIENT);
        }
        public <T extends MusicPayload> void playBidirectional(MusicPayload.Type<T> type, PacketCodec<FriendlyByteBuf, T> codec, BiConsumer<T, Context> handler) {
            register(type, codec, handler, null);
        }
        private <T extends MusicPayload> void register(MusicPayload.Type<T> type, PacketCodec<FriendlyByteBuf, T> codec, BiConsumer<T, Context> handler, NetworkDirection direction) {
            CHANNEL.messageBuilder(type.packetClass(), nextId++, direction)
                    .encoder((packet, buffer) -> codec.encoder().accept(buffer, packet))
                    .decoder(codec.decoder())
                    .consumerMainThread((packet, context) -> handler.accept(packet, new Context(context.get())))
                    .add();
        }
    }
}
