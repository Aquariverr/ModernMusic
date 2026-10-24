package dev.modernmusic.network;

import javax.annotation.Nonnull;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.provider.*;
import dev.modernmusic.server.*;
import dev.modernmusic.shared.SharedAccountProfile;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import dev.modernmusic.network.PacketCodec;
import dev.modernmusic.network.MusicPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.event.TickEvent;
import dev.modernmusic.network.ForgeNetwork;

/** Optional shared-playback channel, independent of the permission-gated administrator UI. */
public final class SharedAccountNetwork {
    public static Consumer<StatePayload> clientState = value -> {};
    public static Consumer<ResultPayload> clientResult = value -> {};
    public static Consumer<ProfilePayload> clientProfile = value -> {};
    private final Map<MusicPlatform, ServerSharedAccount> accounts = new EnumMap<>(MusicPlatform.class);
    private final Map<UUID, long[]> ingress = new HashMap<>();
    private record Delivery(long generation, MusicError error, SharedAccountProfile profile, boolean stateChannel, boolean profileChannel) {}
    private record Recipient(UUID player, MusicPlatform platform) {}
    private final StateHeartbeat<Recipient, Delivery> delivered = new StateHeartbeat<>();
    private long nextStatus;
    public SharedAccountNetwork(IEventBus bus) {
        register();
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> {
            for (var platform : MusicPlatform.onlineValues()) {
                var directory = event.getServer().getServerDirectory().toPath().resolve(platform == MusicPlatform.NETEASE ? "modernmusic-private/server" : "modernmusic-private/server/" + platform.name().toLowerCase(java.util.Locale.ROOT));
                var account = new ServerSharedAccount(directory, event.getServer()::execute, Clock.systemUTC(), platform);
                accounts.put(platform, account); account.enabled(SharedAccountConfig.enabled(platform).get());
            } nextStatus = 0; ingress.clear(); delivered.clear();
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            accounts.values().forEach(ServerSharedAccount::close); accounts.clear(); ingress.clear(); delivered.clear();
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ingress.remove(player.getUUID()); for (var platform : MusicPlatform.values()) delivered.remove(new Recipient(player.getUUID(), platform));
                accounts.values().forEach(account -> account.resolver().disconnect(player.getUUID()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        new SharedAdminNetwork(bus, accounts::get, () -> nextStatus = 0);
    }
    public record StatePayload(MusicPlatform platform, long generation, MusicError error) implements MusicPayload {
        public static final Type<StatePayload> TYPE = new Type<>(id("shared_state"), StatePayload.class);
        public static final PacketCodec<FriendlyByteBuf, StatePayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeEnum(v.platform); b.writeLong(v.generation); writeError(b, v.error);
        }, b -> new StatePayload(b.readEnum(MusicPlatform.class), b.readLong(), readError(b)));
        @Nonnull @Override public Type<StatePayload> type() { return TYPE; }
    }
    public record RequestPayload(UUID request, long generation, String song, boolean refresh) implements MusicPayload {
        public static final Type<RequestPayload> TYPE = new Type<>(id("shared_request"), RequestPayload.class);
        public static final PacketCodec<FriendlyByteBuf, RequestPayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeUUID(v.request); b.writeLong(v.generation); b.writeUtf(v.song, 64); b.writeBoolean(v.refresh);
        }, b -> new RequestPayload(b.readUUID(), b.readLong(), b.readUtf(64), b.readBoolean()));
        @Nonnull @Override public Type<RequestPayload> type() { return TYPE; }
    }
    public record ProfilePayload(MusicPlatform platform, long generation, SharedAccountProfile profile) implements MusicPayload {
        public ProfilePayload { Objects.requireNonNull(profile); }
        public static final Type<ProfilePayload> TYPE = new Type<>(id("shared_profile"), ProfilePayload.class);
        public static final PacketCodec<FriendlyByteBuf, ProfilePayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeEnum(v.platform); b.writeLong(v.generation); b.writeUtf(v.profile.nickname(), 64); b.writeUtf(v.profile.avatar(), 4096);
        }, b -> new ProfilePayload(b.readEnum(MusicPlatform.class), b.readLong(), new SharedAccountProfile(b.readUtf(64), b.readUtf(4096))));
        @Nonnull @Override public Type<ProfilePayload> type() { return TYPE; }
    }
    public record ResultPayload(UUID request, long generation, String song, String url, long expiresAt, MusicError error) implements MusicPayload {
        public static final Type<ResultPayload> TYPE = new Type<>(id("shared_result"), ResultPayload.class);
        public static final PacketCodec<FriendlyByteBuf, ResultPayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeUUID(v.request); b.writeLong(v.generation); b.writeUtf(v.song, 64); writeError(b, v.error);
            if (v.error == null) { b.writeUtf(v.url, 4096); b.writeLong(v.expiresAt); }
        }, b -> {
            UUID request = b.readUUID(); long generation = b.readLong(); String song = b.readUtf(64); MusicError error = readError(b);
            return new ResultPayload(request, generation, song, error == null ? b.readUtf(4096) : "", error == null ? b.readLong() : 0, error);
        });
        @Nonnull @Override public Type<ResultPayload> type() { return TYPE; }
        @Nonnull @Override public String toString() { return "SharedResult[song=" + song + ", url=<redacted>, error=" + error + "]"; }
    }
    private static ResourceLocation id(String name) { return new ResourceLocation(ModernMusic.MOD_ID, name); }
    private static void writeError(FriendlyByteBuf buf, MusicError error) { buf.writeBoolean(error != null); if (error != null) buf.writeEnum(error); }
    private static MusicError readError(FriendlyByteBuf buf) { return buf.readBoolean() ? buf.readEnum(MusicError.class) : null; }
    private void register() {
        ForgeNetwork.registrar().playToClient(ProfilePayload.TYPE, ProfilePayload.CODEC,
                (data, context) -> clientProfile.accept(data));
        var registrar = ForgeNetwork.registrar();
        registrar.playToClient(StatePayload.TYPE, StatePayload.CODEC, (data, context) -> clientState.accept(data));
        registrar.playToClient(ResultPayload.TYPE, ResultPayload.CODEC, (data, context) -> clientResult.accept(data));
        registrar.playToServer(RequestPayload.TYPE, RequestPayload.CODEC, (data, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || !ForgeNetwork.available(player.connection.connection)) return;
            SongId song;
            try { song = SongId.fromWire(data.song); }
            catch (RuntimeException invalid) { result(player, data, null, MusicError.INVALID_RESPONSE); return; }
            MusicPlatform platform = song.platform();
            ServerSharedAccount account = accounts.get(platform);
            if (account == null) return;
            long time = RoomNetwork.now();
            var rate = ingress.computeIfAbsent(player.getUUID(), id -> new long[] {time, 0});
            if (time - rate[0] >= 1_000) { rate[0] = time; rate[1] = 0; }
            if (++rate[1] > 12) return;
            account.enabled(SharedAccountConfig.enabled(platform).get());
            ServerSharedAccount instance = account;
            MusicError denied = access(player, platform);
            if (denied != null || data.generation != account.resolver().generation()) {
                sendState(player, platform); result(player, data, null, denied != null ? denied : MusicError.SHARED_EXPIRED); return;
            }
            account.resolver().resolve(player.getUUID(), song, data.refresh).whenComplete((resource, error) -> player.server.execute(() -> {
                // Connection identity matters: a result from before disconnect must never reach a rejoined UUID.
                if (accounts.get(platform) != instance || player.server.getPlayerList().getPlayer(player.getUUID()) != player) return;
                account.enabled(SharedAccountConfig.enabled(platform).get());
                MusicError revoked = access(player, platform);
                if (revoked == null && data.generation != account.resolver().generation()) revoked = MusicError.SHARED_EXPIRED;
                result(player, data, revoked == null && error == null ? resource : null,
                        revoked != null ? revoked : error == null ? null : SharedResolver.category(error));
            }));
        });
    }
    private MusicError access(ServerPlayer player, MusicPlatform platform) {
        var account = accounts.get(platform);
        if (!SharedAccountConfig.enabled(platform).get()) return MusicError.SHARED_DISABLED;
        if (SharedAccountConfig.operatorsOnly(platform).get() && !player.hasPermissions(2)) return MusicError.SHARED_FORBIDDEN;
        return account.resolver().ready() ? null : MusicError.SHARED_EXPIRED;
    }
    private void result(ServerPlayer player, RequestPayload request, PlaybackResource resource, MusicError error) {
        ForgeNetwork.sendToPlayer(player, new ResultPayload(request.request, request.generation, request.song,
                resource == null ? "" : resource.uri().toASCIIString(), resource == null ? 0 : resource.expiresAt().toEpochMilli(), error));
    }
    private void sendState(ServerPlayer player, MusicPlatform platform) {
        var account = accounts.get(platform);
        MusicError denied = access(player, platform);
        Delivery state = new Delivery(account.resolver().generation(), denied,
                denied == null ? account.profile() : SharedAccountProfile.EMPTY,
                ForgeNetwork.available(player.connection.connection), ForgeNetwork.available(player.connection.connection));
        if (!delivered.update(new Recipient(player.getUUID(), platform), state, RoomNetwork.now())) return;
        if (state.stateChannel)
            ForgeNetwork.sendToPlayer(player, new StatePayload(platform, state.generation, state.error));
        if (state.profileChannel)
            ForgeNetwork.sendToPlayer(player, new ProfilePayload(platform, state.generation, state.profile));
    }
    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (accounts.isEmpty()) return;
        accounts.forEach((platform, account) -> { account.enabled(SharedAccountConfig.enabled(platform).get()); account.tick(); });
        if (RoomNetwork.now() < nextStatus) return;
        nextStatus = RoomNetwork.now() + 1_000;
        for (var player : event.getServer().getPlayerList().getPlayers()) for (var platform : accounts.keySet()) sendState(player, platform);
    }
}
