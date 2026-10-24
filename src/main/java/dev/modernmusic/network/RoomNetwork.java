package dev.modernmusic.network;

import javax.annotation.Nonnull;
import dev.modernmusic.ModernMusic;
import dev.modernmusic.room.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import dev.modernmusic.network.PacketCodec;
import dev.modernmusic.network.MusicPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import dev.modernmusic.network.ForgeNetwork;

/** Common registration uses callbacks, so dedicated servers never load client classes. */
public final class RoomNetwork {
    public static Consumer<RoomUpdate> clientUpdate = update -> {};
    public static Consumer<ClockPayload> clientClock = sample -> {};
    public static Consumer<RoomImportResult> clientImport = result -> {};
    private final TeamDirectory teams;
    private RoomService rooms;
    private dev.modernmusic.server.TeamQueueStore teamStore;
    private dev.modernmusic.server.RoomStore roomStore;
    private long savedTeamRevision;
    private long savedRoomRevision;
    private long sequence, nextBroadcast;
    private boolean broadcastPending;
    private final RoomUpdateGate updateGate = new RoomUpdateGate();
    private final Map<UUID, Long> lastPing = new HashMap<>();
    private final Map<UUID, long[]> commandRates = new HashMap<>();
    public static long now() { return System.nanoTime() / 1_000_000; }
    public RoomNetwork(IEventBus bus) {
        teams = net.minecraftforge.fml.ModList.get().isLoaded("ftbteams")
                ? new dev.modernmusic.compat.FtbTeamsDirectory() : TeamDirectory.ABSENT;
        rooms = new RoomService(teams);
        register();
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent e) -> {
            rooms = new RoomService(teams, dev.modernmusic.server.RoomConfig::maxOwnedRooms, id -> {
                var player = e.getServer().getPlayerList().getPlayer(id);
                return player != null && player.hasPermissions(2);
            });
            teamStore = new dev.modernmusic.server.TeamQueueStore(e.getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/modernmusic-team-queues.json"));
            rooms.restoreTeamQueues(teamStore.load()); savedTeamRevision = rooms.teamQueueRevision();
            roomStore = new dev.modernmusic.server.RoomStore(e.getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/modernmusic-rooms.json"));
            rooms.restoreRooms(roomStore.load()); savedRoomRevision = rooms.persistenceRevision();
            rooms.openPublicRoom();
            sequence = 0; nextBroadcast = 0; broadcastPending = false; updateGate.clear(); lastPing.clear(); commandRates.clear();
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            saveRooms();
            if (teamStore != null) { teamStore.close(); teamStore = null; }
            if (roomStore != null) { roomStore.close(); roomStore = null; }
            rooms = new RoomService(teams); updateGate.clear(); lastPing.clear(); commandRates.clear();
        });
        MinecraftForge.EVENT_BUS.addListener(this::logout);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    public record CommandPayload(String json) implements MusicPayload {
        public static final Type<CommandPayload> TYPE = new Type<>(id("room_command"), CommandPayload.class);
        public static final PacketCodec<FriendlyByteBuf, CommandPayload> CODEC = PacketCodec.of(
                (buf, data) -> buf.writeUtf(data.json, 512), buf -> new CommandPayload(buf.readUtf(512)));
        @Nonnull @Override public Type<CommandPayload> type() { return TYPE; }
    }
    public record UpdatePayload(String json) implements MusicPayload {
        public static final Type<UpdatePayload> TYPE = new Type<>(id("room_update"), UpdatePayload.class);
        public static final PacketCodec<FriendlyByteBuf, UpdatePayload> CODEC = PacketCodec.of(
                (buf, data) -> buf.writeUtf(data.json, RoomJson.MAX_UPDATE_LENGTH),
                buf -> new UpdatePayload(buf.readUtf(RoomJson.MAX_UPDATE_LENGTH)));
        @Nonnull @Override public Type<UpdatePayload> type() { return TYPE; }
    }
    public record ClockPayload(long sent, long server) implements MusicPayload {
        public static final Type<ClockPayload> TYPE = new Type<>(id("room_clock"), ClockPayload.class);
        public static final PacketCodec<FriendlyByteBuf, ClockPayload> CODEC = PacketCodec.of(
                (buf, data) -> { buf.writeLong(data.sent); buf.writeLong(data.server); },
                buf -> new ClockPayload(buf.readLong(), buf.readLong()));
        @Nonnull @Override public Type<ClockPayload> type() { return TYPE; }
    }
    public record ImportPayload(RoomImportRequest request) implements MusicPayload {
        public static final Type<ImportPayload> TYPE = new Type<>(id("room_import"), ImportPayload.class);
        public static final PacketCodec<FriendlyByteBuf, ImportPayload> CODEC = PacketCodec.of((buf, data) -> {
            var value = data.request;
            buf.writeUUID(value.request()); buf.writeUUID(value.room()); buf.writeUUID(value.epoch());
            buf.writeVarInt(value.total()); buf.writeVarInt(value.songs().size());
            for (String song : value.songs()) buf.writeUtf(song, 64);
        }, buf -> {
            UUID request = buf.readUUID(), room = buf.readUUID(), epoch = buf.readUUID();
            int total = buf.readVarInt(), count = buf.readVarInt();
            if (count < 1 || count > 400) throw new IllegalArgumentException("Invalid import size");
            var songs = new ArrayList<String>(count); for (int i = 0; i < count; i++) songs.add(buf.readUtf(64));
            return new ImportPayload(new RoomImportRequest(request, room, epoch, songs, total));
        });
        @Nonnull @Override public Type<ImportPayload> type() { return TYPE; }
    }
    public record ImportResultPayload(String json) implements MusicPayload {
        public static final Type<ImportResultPayload> TYPE = new Type<>(id("room_import_result"), ImportResultPayload.class);
        public static final PacketCodec<FriendlyByteBuf, ImportResultPayload> CODEC = PacketCodec.of(
                (buf, data) -> buf.writeUtf(data.json, 256), buf -> new ImportResultPayload(buf.readUtf(256)));
        @Nonnull @Override public Type<ImportResultPayload> type() { return TYPE; }
    }
    public record DurationPayload(RoomDurationReport report) implements MusicPayload {
        public static final Type<DurationPayload> TYPE = new Type<>(id("room_duration"), DurationPayload.class);
        public static final PacketCodec<FriendlyByteBuf, DurationPayload> CODEC = PacketCodec.of((buf, data) -> {
            var value = data.report;
            buf.writeUUID(value.room()); buf.writeUUID(value.epoch()); buf.writeLong(value.playbackRevision());
            buf.writeUtf(value.song(), 64); buf.writeLong(value.durationMs());
        }, buf -> new DurationPayload(new RoomDurationReport(buf.readUUID(), buf.readUUID(), buf.readLong(),
                buf.readUtf(64), buf.readLong())));
        @Nonnull @Override public Type<DurationPayload> type() { return TYPE; }
    }
    private static ResourceLocation id(String path) { return new ResourceLocation(ModernMusic.MOD_ID, path); }
    private void register() {
        var registrar = ForgeNetwork.registrar();
        registrar.playToServer(CommandPayload.TYPE, CommandPayload.CODEC, (data, context) -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!allowCommand(player)) return;
            String message = "";
            boolean snapshot = false;
            try {
                RoomCommand command = RoomJson.decode(data.json, RoomCommand.class);
                snapshot = command.action() == RoomCommand.Action.SNAPSHOT;
                var target = switch (command.action()) {
                    case INVITE, DJ, REQUESTER, KICK -> player.server.getPlayerList().getPlayerByName(command.text());
                    default -> null;
                };
                if (target != null && !ForgeNetwork.available(target.connection.connection)) target = null;
                rooms.execute(player.getUUID(), player.getGameProfile().getName(), command,
                        target == null ? null : target.getUUID(), now());
            } catch (RoomService.Rejected rejected) { message = rejected.key; }
            catch (RuntimeException invalid) { message = "modernmusic.room.error.input"; }
            // Coalesce successful commands within this tick; recovery only concerns the requesting player.
            if (message.isEmpty() && !snapshot) broadcastPending = true;
            else recover(player, message);
        });
        registrar.playToServer(ImportPayload.TYPE, ImportPayload.CODEC, (data, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || !allowCommand(player)) return;
            RoomImportResult result;
            try {
                for (String value : data.request.songs()) {
                    var song = dev.modernmusic.domain.SongId.stored(value);
                    if (song.isServerAudio() && !AudioNetwork.contains(song)) throw new RoomService.Rejected("audio_missing");
                }
                result = rooms.append(player.getUUID(), data.request, now());
                broadcastPending = true;
                // The importer applies metadata after its receipt, so its authoritative queue must arrive first.
                recover(player, "");
            } catch (RoomService.Rejected rejected) {
                result = new RoomImportResult(data.request.request(), 0, data.request.total(), rejected.key);
            }
            ForgeNetwork.sendToPlayer(player, new ImportResultPayload(RoomJson.encode(result)));
        });
        registrar.playToClient(ImportResultPayload.TYPE, ImportResultPayload.CODEC, (data, context) ->
                clientImport.accept(RoomJson.decode(data.json, RoomImportResult.class)));
        registrar.playToServer(DurationPayload.TYPE, DurationPayload.CODEC, (data, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || !allowCommand(player)) return;
            try { broadcastPending |= rooms.reportDuration(player.getUUID(), data.report, now()); }
            // Reports are automatic metadata. Late reports need no error UI or forced full-state recovery.
            catch (RoomService.Rejected ignored) { }
        });
        registrar.playToClient(UpdatePayload.TYPE, UpdatePayload.CODEC, (data, context) -> {
            try { clientUpdate.accept(RoomJson.decode(data.json, RoomUpdate.class)); }
            catch (RuntimeException invalid) { context.disconnect(net.minecraft.network.chat.Component.literal("Invalid Modern Music room state")); }
        });
        registrar.playBidirectional(ClockPayload.TYPE, ClockPayload.CODEC, (data, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                long time = now();
                if (time - lastPing.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) < 200) return;
                lastPing.put(player.getUUID(), time);
                ForgeNetwork.sendToPlayer(player, new ClockPayload(data.sent, time));
            } else clientClock.accept(data);
        });
    }
    private boolean allowCommand(ServerPlayer player) {
        long now = now();
        long[] rate = commandRates.computeIfAbsent(player.getUUID(), id -> new long[] { now, 0 });
        if (now - rate[0] >= 1_000) { rate[0] = now; rate[1] = 0; }
        return ++rate[1] <= 12;
    }
    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long time = now(); boolean changed = rooms.tick(time);
        saveRooms();
        if (changed || broadcastPending || time >= nextBroadcast) {
            nextBroadcast = time + 1_000; broadcastPending = false; broadcast(event.getServer(), time);
        }
    }
    private void saveRooms() {
        if (teamStore != null && savedTeamRevision != rooms.teamQueueRevision()) {
            teamStore.save(rooms.teamQueues()); savedTeamRevision = rooms.teamQueueRevision();
        }
        if (roomStore != null && savedRoomRevision != rooms.persistenceRevision()) {
            roomStore.save(rooms.persistentRooms()); savedRoomRevision = rooms.persistenceRevision();
        }
    }
    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            rooms.disconnect(player.getUUID()); updateGate.forget(player.getUUID());
            lastPing.remove(player.getUUID()); commandRates.remove(player.getUUID()); broadcastPending = true;
        }
    }
    private void broadcast(MinecraftServer server, long time) {
        var players = server.getPlayerList().getPlayers().stream()
                .filter(player -> ForgeNetwork.available(player.connection.connection)).toList();
        if (players.isEmpty()) return;
        long seq = ++sequence;
        var encoder = new RoomUpdateEncoder(seq);
        var updates = rooms.updatesFor(players.stream().map(ServerPlayer::getUUID).toList(), time);
        for (ServerPlayer player : players) {
            sendUpdate(player, encoder, updates.get(player.getUUID()), time, false);
        }
    }
    private void recover(ServerPlayer player, String message) {
        if (!ForgeNetwork.available(player.connection.connection)) return;
        long time = now();
        var state = rooms.updatesFor(List.of(player.getUUID()), time).get(player.getUUID());
        sendUpdate(player, new RoomUpdateEncoder(++sequence), new RoomUpdate(0, state.room(), state.invitations(), message, state.team(),
                state.savedRooms(), state.roomLimit()), time, true);
    }
    private void sendUpdate(ServerPlayer player, RoomUpdateEncoder encoder, RoomUpdate state, long time, boolean force) {
        if (!updateGate.shouldSend(player.getUUID(), state, time, force)) return;
        ForgeNetwork.sendToPlayer(player, new UpdatePayload(encoder.encode(state)));
    }
}
