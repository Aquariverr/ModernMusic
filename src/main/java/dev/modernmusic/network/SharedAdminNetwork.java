package dev.modernmusic.network;

import javax.annotation.Nonnull;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.room.RoomJson;
import dev.modernmusic.server.*;
import dev.modernmusic.shared.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Administration never trusts client permission flags. Private views are sent only to the current editor. */
public final class SharedAdminNetwork {
    public static Consumer<Boolean> clientCapability = value -> {};
    public static Consumer<SharedAdminSnapshot> clientSnapshot = value -> {};
    private final Function<MusicPlatform, ServerSharedAccount> account;
    private final Runnable playbackChanged;
    private final SharedAdminLease lease = new SharedAdminLease();
    private final Map<UUID, long[]> rates = new HashMap<>();
    private final StateHeartbeat<UUID, Boolean> capabilities = new StateHeartbeat<>();
    private final StateHeartbeat<UUID, SharedAdminSnapshot> snapshots = new StateHeartbeat<>();
    private ServerPlayer editor;
    private MusicPlatform editorPlatform = MusicPlatform.NETEASE;
    private long nextBroadcast;
    public SharedAdminNetwork(IEventBus bus, Function<MusicPlatform, ServerSharedAccount> account, Runnable playbackChanged) {
        this.account = account; this.playbackChanged = playbackChanged;
        bus.addListener(this::register);
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> { lease.clear(); editor = null; rates.clear(); capabilities.clear(); snapshots.clear(); nextBroadcast = 0; });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> { lease.clear(); editor = null; rates.clear(); capabilities.clear(); snapshots.clear(); });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            rates.remove(event.getEntity().getUUID());
            capabilities.remove(event.getEntity().getUUID());
            if (event.getEntity() == editor) release();
        });
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    public static boolean mayManage(ServerPlayer player) {
        return hasPermission(player);
    }
    private static boolean hasPermission(ServerPlayer player) {
        return player.hasPermissions(4) || player.server.isSingleplayerOwner(player.getGameProfile());
    }
    public static boolean cookieUpload(ServerPlayer player) {
        return hasPermission(player);
    }
    public record CapabilityPayload(boolean allowed) implements CustomPacketPayload {
        public static final Type<CapabilityPayload> TYPE = new Type<>(id("shared_admin_capability"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CapabilityPayload> CODEC = StreamCodec.of(
                (b, v) -> b.writeBoolean(v.allowed), b -> new CapabilityPayload(b.readBoolean()));
        @Nonnull @Override public Type<CapabilityPayload> type() { return TYPE; }
    }
    public record CommandPayload(MusicPlatform platform, UUID view, long sequence, SharedAdminAction action, String value) implements CustomPacketPayload {
        public CommandPayload(UUID view, long sequence, SharedAdminAction action, String value) { this(MusicPlatform.NETEASE, view, sequence, action, value); }
        public CommandPayload {
            if (platform == null || view == null || sequence < 1 || action == null || value == null || value.length() > SessionCookie.MAX_LENGTH
                    || action != SharedAdminAction.COOKIE_LOGIN && !value.isEmpty()) throw new IllegalArgumentException("Invalid admin command");
        }
        public static final Type<CommandPayload> TYPE = new Type<>(id("shared_admin_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CommandPayload> CODEC = StreamCodec.of((b, v) -> {
            b.writeEnum(v.platform); b.writeUUID(v.view); b.writeLong(v.sequence); b.writeEnum(v.action);
            if (v.action == SharedAdminAction.COOKIE_LOGIN) b.writeUtf(v.value, SessionCookie.MAX_LENGTH);
        }, b -> {
            MusicPlatform platform = b.readEnum(MusicPlatform.class); UUID view = b.readUUID(); long sequence = b.readLong(); var action = b.readEnum(SharedAdminAction.class);
            return new CommandPayload(platform, view, sequence, action, action == SharedAdminAction.COOKIE_LOGIN ? b.readUtf(SessionCookie.MAX_LENGTH) : "");
        });
        @Nonnull @Override public Type<CommandPayload> type() { return TYPE; }
        @Nonnull @Override public String toString() { return "SharedAdminCommand[action=" + action + ", value=<redacted>]"; }
    }
    public record SnapshotPayload(String json) implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(id("shared_admin_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
                (b, v) -> b.writeUtf(v.json, 8192), b -> new SnapshotPayload(b.readUtf(8192)));
        @Nonnull @Override public Type<SnapshotPayload> type() { return TYPE; }
        @Nonnull @Override public String toString() { return "SharedAdminView[redacted]"; }
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ModernMusic.MOD_ID, path); }
    private void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("m5-admin-platform-3").optional();
        registrar.playToClient(CapabilityPayload.TYPE, CapabilityPayload.CODEC, (data, context) -> clientCapability.accept(data.allowed));
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.CODEC, (data, context) -> {
            try { clientSnapshot.accept(RoomJson.decode(data.json, SharedAdminSnapshot.class)); }
            catch (RuntimeException invalid) { context.disconnect(net.minecraft.network.chat.Component.literal("Invalid shared account administration state")); }
        });
        registrar.playToServer(CommandPayload.TYPE, CommandPayload.CODEC, (data, context) -> {
            if (account.apply(data.platform) == null) return;
            if (!(context.player() instanceof ServerPlayer player) || account.apply(editorPlatform) == null || !player.connection.hasChannel(SnapshotPayload.TYPE)) return;
            long now = RoomNetwork.now();
            var rate = rates.computeIfAbsent(player.getUUID(), ignored -> new long[] { now, 0 });
            if (now - rate[0] >= 1_000) { rate[0] = now; rate[1] = 0; }
            if (++rate[1] > 8) return;
            reconcile(player.server);
            if (!mayManage(player)) {
                deny(player, data, false, "modernmusic.admin.denied");
                return;
            }
            if (data.action == SharedAdminAction.OPEN) {
                if (editor != null && editorPlatform != data.platform) { deny(player, data, true, "modernmusic.admin.locked"); return; }
                if (!lease.open(player.getUUID(), data.view, data.sequence, now)) { deny(player, data, true, "modernmusic.admin.locked"); return; }
                editorPlatform = data.platform; editor = player; sendEditor(); return;
            }
            if (editor != player || editorPlatform != data.platform || !lease.accept(player.getUUID(), data.view, data.sequence, now)) {
                deny(player, data, true, "modernmusic.admin.expired"); return;
            }
            if (data.action == SharedAdminAction.CLOSE) { release(); return; }
            ServerSharedAccount current = account.apply(editorPlatform);
            try {
                switch (data.action) {
                    case ENABLE, DISABLE -> {
                        SharedAccountConfig.enabled(editorPlatform).set(data.action == SharedAdminAction.ENABLE); SharedAccountConfig.SPEC.save();
                        current.enabled(SharedAccountConfig.enabled(editorPlatform).get());
                    }
                    case ACCESS_ALL, ACCESS_OPS -> {
                        SharedAccountConfig.operatorsOnly(editorPlatform).set(data.action == SharedAdminAction.ACCESS_OPS); SharedAccountConfig.SPEC.save();
                    }
                    case COOKIE_LOGIN -> {
                        current.manage(data.action, data.value, () -> authorized(player, data.view, current));
                    }
                    default -> current.manage(data.action, "", () -> authorized(player, data.view, current));
                }
                playbackChanged.run(); sendEditor();
            } catch (RuntimeException failure) { deny(player, data, true, "modernmusic.auth.storage_error"); }
        });
    }
    private boolean authorized(ServerPlayer player, UUID view, ServerSharedAccount current) {
        return account.apply(editorPlatform) == current && editor == player && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && mayManage(player) && lease.valid(player.getUUID(), view, RoomNetwork.now());
    }
    private void deny(ServerPlayer player, CommandPayload command, boolean allowed, String reason) {
        send(player, SharedAdminSnapshot.unavailable(command.view, command.sequence, allowed, reason));
    }
    private void send(ServerPlayer player, SharedAdminSnapshot snapshot) {
        PacketDistributor.sendToPlayer(player, new SnapshotPayload(RoomJson.encode(snapshot)));
    }
    private void sendEditor() {
        if (editor == null || account.apply(editorPlatform) == null || !authorized(editor, lease.view(), account.apply(editorPlatform))) return;
        SharedAdminSnapshot snapshot = account.apply(editorPlatform).snapshot(lease.view(), lease.sequence(), SharedAccountConfig.operatorsOnly(editorPlatform).get(), cookieUpload(editor));
        if (snapshots.update(lease.view(), snapshot, RoomNetwork.now())) send(editor, snapshot);
    }
    private void reconcile(MinecraftServer server) {
        if (editor != null && (!mayManage(editor) || server.getPlayerList().getPlayer(editor.getUUID()) != editor || lease.expired(RoomNetwork.now()))) {
            if (server.getPlayerList().getPlayer(editor.getUUID()) == editor)
                send(editor, SharedAdminSnapshot.unavailable(lease.view(), lease.sequence(), mayManage(editor), "modernmusic.admin.expired"));
            release();
        }
    }
    private void release() {
        if (account.apply(editorPlatform) != null) account.apply(editorPlatform).cancelBinding();
        lease.clear(); editor = null; snapshots.clear();
    }
    private void tick(ServerTickEvent.Post event) {
        if (account.apply(editorPlatform) == null) return;
        reconcile(event.getServer());
        if (RoomNetwork.now() < nextBroadcast) return;
        nextBroadcast = RoomNetwork.now() + 1_000;
        for (var player : event.getServer().getPlayerList().getPlayers()) {
            boolean allowed = mayManage(player);
            if (player.connection.hasChannel(CapabilityPayload.TYPE) && capabilities.update(player.getUUID(), allowed, RoomNetwork.now()))
                PacketDistributor.sendToPlayer(player, new CapabilityPayload(allowed));
        }
        sendEditor();
    }
}
