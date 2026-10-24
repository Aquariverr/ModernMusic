package dev.modernmusic.network;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.audio.AudioFiles;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.server.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
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

/** Optional, session-bound file transport. No client class is loaded on dedicated servers. */
public final class AudioNetwork {
    public static Consumer<StatePayload> clientState = value -> {};
    public static Consumer<ReplyPayload> clientReply = value -> {};
    private static volatile ServerAudioStore activeStore;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final AudioTransferQueue transfers = new AudioTransferQueue();
    private final Map<UUID, long[]> ingress = new HashMap<>();
    private ServerAudioStore store;
    private ExecutorService worker;
    private UUID session = UUID.randomUUID();
    private long nextState;

    public AudioNetwork(IEventBus bus) {
        register();
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) -> {
            session = UUID.randomUUID(); worker = AsyncTasks.executor("modernmusic-server-audio", 2, 8);
            store = new ServerAudioStore(event.getServer().getServerDirectory().toPath().resolve("modernmusic/audio-server"));
            activeStore = store; nextState = 0;
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            var abandoned = List.copyOf(jobs.values()); jobs.clear(); transfers.clear(); ingress.clear();
            abandoned.forEach(Job::cancel);
            if (worker != null) worker.shutdownNow(); worker = null; store = null; activeStore = null;
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                for (Job job : List.copyOf(jobs.values())) if (job.player == player) { remove(job); job.cancel(); }
                ingress.remove(player.getUUID());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    public static boolean contains(SongId id) { var current = activeStore; return current != null && current.contains(id); }
    public enum Command { CATALOG, DOWNLOAD, UPLOAD_BEGIN, UPLOAD_CHUNK, UPLOAD_END, CANCEL }
    public enum Reply { CATALOG, DOWNLOAD_BEGIN, DOWNLOAD_CHUNK, UPLOAD_READY, UPLOADED, ERROR }
    public record StatePayload(UUID session, boolean canUpload) implements MusicPayload {
        public static final Type<StatePayload> TYPE = new Type<>(id("audio_state"), StatePayload.class);
        public static final PacketCodec<FriendlyByteBuf, StatePayload> CODEC = PacketCodec.of(
                (b, v) -> { b.writeUUID(v.session); b.writeBoolean(v.canUpload); }, b -> new StatePayload(b.readUUID(), b.readBoolean()));
        @Nonnull @Override public Type<StatePayload> type() { return TYPE; }
    }
    public record CommandPayload(UUID session, UUID request, Command command, String value, int total, int offset, byte[] bytes) implements MusicPayload {
        public static final Type<CommandPayload> TYPE = new Type<>(id("audio_command"), CommandPayload.class);
        public static final PacketCodec<FriendlyByteBuf, CommandPayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeUUID(v.session); b.writeUUID(v.request); b.writeEnum(v.command); b.writeUtf(v.value, 180);
            b.writeVarInt(v.total); b.writeVarInt(v.offset); b.writeByteArray(v.bytes);
        }, b -> new CommandPayload(b.readUUID(), b.readUUID(), b.readEnum(Command.class), b.readUtf(180), b.readVarInt(), b.readVarInt(), b.readByteArray(AudioTransfer.CHUNK_BYTES)));
        @Nonnull @Override public Type<CommandPayload> type() { return TYPE; }
    }
    public record ReplyPayload(UUID session, UUID request, Reply reply, List<Track> tracks, int total, int offset, byte[] bytes, MusicError error) implements MusicPayload {
        public static final Type<ReplyPayload> TYPE = new Type<>(id("audio_reply"), ReplyPayload.class);
        public static final PacketCodec<FriendlyByteBuf, ReplyPayload> CODEC = PacketCodec.of((b, v) -> {
            b.writeUUID(v.session); b.writeUUID(v.request); b.writeEnum(v.reply); b.writeVarInt(v.tracks.size());
            for (Track track : v.tracks) { b.writeUtf(track.id().toString(), 64); b.writeUtf(track.title(), 180); b.writeLong(track.durationMs()); }
            b.writeVarInt(v.total); b.writeVarInt(v.offset); b.writeByteArray(v.bytes); b.writeBoolean(v.error != null); if (v.error != null) b.writeEnum(v.error);
        }, b -> {
            UUID session = b.readUUID(), request = b.readUUID(); Reply reply = b.readEnum(Reply.class); int count = b.readVarInt();
            if (count < 0 || count > ServerAudioStore.MAX_FILES) throw new IllegalArgumentException("audio_catalog_size");
            var tracks = new ArrayList<Track>(count);
            for (int i = 0; i < count; i++) {
                var song = SongId.stored(b.readUtf(64)); if (!song.isServerAudio()) throw new IllegalArgumentException("audio_id");
                tracks.add(new Track(song, b.readUtf(180), List.of(), b.readLong()));
            }
            return new ReplyPayload(session, request, reply, List.copyOf(tracks), b.readVarInt(), b.readVarInt(), b.readByteArray(AudioTransfer.CHUNK_BYTES), b.readBoolean() ? b.readEnum(MusicError.class) : null);
        });
        @Nonnull @Override public Type<ReplyPayload> type() { return TYPE; }
    }
    private static ResourceLocation id(String name) { return new ResourceLocation(ModernMusic.MOD_ID, name); }
    private void register() {
        var registrar = ForgeNetwork.registrar();
        registrar.playToClient(StatePayload.TYPE, StatePayload.CODEC, (data, context) -> clientState.accept(data));
        registrar.playToClient(ReplyPayload.TYPE, ReplyPayload.CODEC, (data, context) -> clientReply.accept(data));
        registrar.playToServer(CommandPayload.TYPE, CommandPayload.CODEC, (data, context) -> {
            if (context.player() instanceof ServerPlayer player && ForgeNetwork.available(player.connection.connection)) receive(player, data);
        });
    }
    private void receive(ServerPlayer player, CommandPayload data) {
        if (store == null || !session.equals(data.session)) return;
        long now = RoomNetwork.now();
        var rate = ingress.computeIfAbsent(player.getUUID(), id -> new long[] {now, 0, 0});
        if (now - rate[0] >= 1_000) { rate[0] = now; rate[1] = rate[2] = 0; }
        if (++rate[1] > 256 || (data.command != Command.UPLOAD_CHUNK && ++rate[2] > 12)) return;
        Job job = jobs.get(data.request);
        if (job != null && job.player != player) { error(player, data.request, MusicError.INVALID_RESPONSE); return; }
        try {
            switch (data.command) {
                case CATALOG -> send(player, data.request, Reply.CATALOG, store.catalog(), 0, 0, new byte[0], null);
                case DOWNLOAD -> {
                    SongId song = SongId.stored(data.value); Track track = store.track(song);
                    if (job != null) {
                        if (!job.upload && song.equals(job.song)) return;
                        throw new MusicException(MusicError.INVALID_RESPONSE);
                    }
                    boolean active = transfers.download(player.getUUID(), data.request);
                    Job download = new Job(player, data.request, false, now); jobs.put(data.request, download);
                    download.song = song; download.track = track;
                    if (active) startDownload(download);
                }
                case UPLOAD_BEGIN -> {
                    if (!player.hasPermissions(2)) throw new MusicException(MusicError.SHARED_FORBIDDEN);
                    String filename = AudioFiles.validateFileName(data.value);
                    if (data.total <= 0 || data.total > AudioFiles.MAX_AUDIO_BYTES) throw new MusicException(MusicError.RESOURCE_TOO_LARGE);
                    transfers.upload(player.getUUID(), data.request);
                    Job upload = new Job(player, data.request, true, now); upload.filename = filename;
                    upload.assembly = new AudioTransfer(data.total, player.hasPermissions(2), now); jobs.put(data.request, upload);
                    send(player, data.request, Reply.UPLOAD_READY, List.of(), data.total, 0, new byte[0], null);
                }
                case UPLOAD_CHUNK -> {
                    requireUpload(job);
                    if (data.total != job.assembly.total()) throw new MusicException(MusicError.INVALID_RESPONSE);
                    job.assembly.append(data.offset, data.bytes, player.hasPermissions(2), now); job.touched = now;
                }
                case UPLOAD_END -> {
                    requireUpload(job);
                    if (data.total != job.assembly.total() || data.offset != job.assembly.received() || data.bytes.length != 0)
                        throw new MusicException(MusicError.INVALID_RESPONSE);
                    byte[] bytes = job.assembly.finish(player.hasPermissions(2), now);
                    job.assembly = null; job.touched = now; ServerAudioStore destination = store;
                    job.task = AsyncTasks.submit(worker, () -> destination.add(job.filename, bytes, () -> job.authorized));
                    Job upload = job;
                    upload.task.whenComplete((track, failure) -> player.server.execute(() -> {
                        if (!current(upload, destination)) return;
                        if (!player.hasPermissions(2)) { fail(upload, MusicError.SHARED_FORBIDDEN); return; }
                        if (failure != null) { fail(upload, category(failure)); return; }
                        remove(upload); send(player, upload.id, Reply.UPLOADED, List.of((Track) track), 0, 0, new byte[0], null);
                    }));
                }
                case CANCEL -> { if (job != null) { remove(job); job.cancel(); } }
            }
        } catch (RuntimeException failure) {
            Job rejected = jobs.get(data.request);
            if (rejected != null) { remove(rejected); rejected.cancel(); }
            else transfers.remove(data.request);
            error(player, data.request, category(failure));
        }
    }
    private void startDownload(Job download) {
        ServerAudioStore source = store;
        download.touched = RoomNetwork.now();
        download.task = AsyncTasks.submit(worker, () -> source.read(download.song));
        download.task.whenComplete((bytes, failure) -> download.player.server.execute(() -> {
            if (!current(download, source)) return;
            if (failure != null) { fail(download, category(failure)); return; }
            download.bytes = (byte[]) bytes; download.touched = RoomNetwork.now();
            send(download.player, download.id, Reply.DOWNLOAD_BEGIN, List.of(download.track), download.bytes.length, 0, new byte[0], null);
        }));
    }
    private static void requireUpload(Job job) {
        if (job == null || !job.upload || job.assembly == null) throw new MusicException(MusicError.INVALID_RESPONSE);
    }
    private boolean current(Job job, ServerAudioStore source) {
        return store == source && jobs.get(job.id) == job && job.player.server.getPlayerList().getPlayer(job.player.getUUID()) == job.player;
    }
    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (store == null) return;
        long now = RoomNetwork.now(); int budget = AudioTransferQueue.MAX_ACTIVE * AudioTransferQueue.CHUNKS_PER_TICK;
        for (var job : List.copyOf(jobs.values())) {
            if (job.upload && !job.player.hasPermissions(2)) { fail(job, MusicError.SHARED_FORBIDDEN); continue; }
            if (transfers.waiting(job.id)) {
                if (now - job.touched >= AudioTransfer.WAIT_TIMEOUT_MS) fail(job, MusicError.NETWORK_ERROR);
                continue;
            }
            if (now - job.touched > AudioTransfer.TIMEOUT_MS) { fail(job, MusicError.NETWORK_ERROR); continue; }
            if (!job.upload && job.bytes != null) {
                for (int count = 0; count < AudioTransferQueue.CHUNKS_PER_TICK && budget > 0 && job.offset < job.bytes.length; count++, budget--) {
                    int end = Math.min(job.bytes.length, job.offset + AudioTransfer.CHUNK_BYTES);
                    send(job.player, job.id, Reply.DOWNLOAD_CHUNK, List.of(), job.bytes.length, job.offset, Arrays.copyOfRange(job.bytes, job.offset, end), null);
                    job.offset = end; job.touched = now;
                }
                if (job.offset == job.bytes.length) remove(job);
            }
        }
        for (UUID request : transfers.startWaiting()) startDownload(jobs.get(request));
        if (now >= nextState) {
            nextState = now + 1_000;
            for (var player : event.getServer().getPlayerList().getPlayers()) if (ForgeNetwork.available(player.connection.connection)) {
                boolean allowed = player.hasPermissions(2);
                ForgeNetwork.sendToPlayer(player, new StatePayload(session, allowed));
            }
        }
    }
    private void remove(Job job) { jobs.remove(job.id); transfers.remove(job.id); }
    private void fail(Job job, MusicError error) { remove(job); job.cancel(); error(job.player, job.id, error); }
    private void error(ServerPlayer player, UUID request, MusicError error) { send(player, request, Reply.ERROR, List.of(), 0, 0, new byte[0], error); }
    private void send(ServerPlayer player, UUID request, Reply reply, List<Track> tracks, int total, int offset, byte[] bytes, MusicError error) {
        ForgeNetwork.sendToPlayer(player, new ReplyPayload(session, request, reply, tracks, total, offset, bytes, error));
    }
    private static MusicError category(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure instanceof MusicException music ? music.error() : MusicError.INVALID_RESPONSE;
    }
    private static final class Job {
        final ServerPlayer player; final UUID id; final boolean upload;
        volatile boolean authorized = true;
        long touched; int offset; String filename; SongId song; Track track; AudioTransfer assembly; byte[] bytes; CompletableFuture<?> task;
        Job(ServerPlayer player, UUID id, boolean upload, long now) { this.player = player; this.id = id; this.upload = upload; touched = now; }
        void cancel() { authorized = false; if (task != null) task.cancel(true); if (assembly != null) assembly.clear(); bytes = null; }
    }
}
