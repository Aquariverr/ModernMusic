package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.network.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import dev.modernmusic.network.ForgeNetwork;
import static dev.modernmusic.room.RoomCommand.Action.*;

/** Real host/guest packet and PCM smoke; synthetic WAV only, no upstream service or user library. */
public final class AudioNetworkScenario {
    private final boolean host;
    private final Path directory, peer;
    private final LocalAudioProvider local;
    private final String filename;
    private final UUID deniedUpload = UUID.randomUUID(), forgedLocal = UUID.randomUUID(), missingServer = UUID.randomUUID();
    private final Map<UUID, RoomImportResult> receipts = new HashMap<>();
    private UUID session;
    private AudioNetwork.ReplyPayload denied;
    private CompletableFuture<List<Track>> listing;
    private CompletableFuture<Track> uploading, metadata;
    private CompletableFuture<byte[]> downloading;
    private Track privateTrack, serverTrack;
    private SongId serverId;
    private boolean personalPlaybackVerified;
    private int step;
    private long nextProgress;

    public AudioNetworkScenario(boolean host, Path directory, Path peer, LocalAudioProvider local, String filename) {
        this.host = host; this.directory = directory; this.peer = peer; this.local = local; this.filename = filename;
        var state = AudioNetwork.clientState; AudioNetwork.clientState = value -> { state.accept(value); session = value.session(); };
        var reply = AudioNetwork.clientReply; AudioNetwork.clientReply = value -> { reply.accept(value); if (value.request().equals(deniedUpload)) denied = value; };
        var imported = RoomNetwork.clientImport; RoomNetwork.clientImport = value -> { imported.accept(value); receipts.put(value.request(), value); };
    }
    public String tick(Minecraft game, RoomClient rooms, PlaybackController player) throws Exception {
        if (System.nanoTime() >= nextProgress) {
            ModernMusic.LOGGER.info("Audio smoke {} step={} available={} upload={} player={}", host ? "host" : "guest", step, ServerAudioClient.available(), ServerAudioClient.canUpload(), player.state());
            nextProgress = System.nanoTime() + 10_000_000_000L;
        }
        if (player.state() == PlaybackController.State.ERROR) throw new AssertionError("Audio playback failed: " + player.message());
        var room = rooms.update().room();
        switch (step) {
            case 0 -> {
                if (!ServerAudioClient.available() || session == null || !rooms.available()) return null;
                if (ServerAudioClient.canUpload() != host) throw new AssertionError("OP upload capability mismatch");
                listing = local.browseLocal(); while (player.platform() != MusicPlatform.LOCAL) player.switchPlatform(); step++;
            }
            case 1 -> {
                if (!listing.isDone()) return null;
                privateTrack = listing.join().stream().filter(track -> track.title().equals(filename)).findFirst().orElseThrow();
                if (!player.add(filename)) throw new AssertionError("Local filename was rejected"); step++;
            }
            case 2 -> {
                if (player.state() == PlaybackController.State.STOPPED && player.personalQueue().contains(privateTrack.id())) player.playPersonal(0);
                if (player.state() != PlaybackController.State.PLAYING || player.audio().positionMs() < 300) return null;
                if (!player.track().id().equals(privateTrack.id())) throw new AssertionError("Local filename selected wrong source");
                player.stop();
                if (host) uploading = local.upload(privateTrack);
                else {
                    try { ServerAudioClient.upload(filename, wav()).join(); throw new AssertionError("Guest upload accepted"); }
                    catch (CompletionException error) {
                        if (!(error.getCause() instanceof MusicException music) || music.error() != MusicError.SHARED_FORBIDDEN) throw error;
                    }
                    // Bypass the client capability check; the actual server must reject the forged begin packet.
                    ForgeNetwork.sendToServer(new AudioNetwork.CommandPayload(session, deniedUpload, AudioNetwork.Command.UPLOAD_BEGIN, filename, wav().length, 0, new byte[0]));
                }
                step++;
            }
            case 3 -> {
                if (host) {
                    if (!uploading.isDone()) return null;
                    serverTrack = uploading.join(); serverId = serverTrack.id();
                    if (!serverId.isServerAudio() || !serverTrack.title().equals(filename)) throw new AssertionError("Upload identity/title mismatch");
                    Files.writeString(directory.resolve("audio-id.txt"), serverId.toString());
                } else {
                    if (denied == null || !Files.exists(peer.resolve("audio-id.txt"))) return null;
                    if (denied.reply() != AudioNetwork.Reply.ERROR || denied.error() != MusicError.SHARED_FORBIDDEN) throw new AssertionError("Server accepted guest upload");
                    serverId = SongId.stored(Files.readString(peer.resolve("audio-id.txt")));
                }
                listing = local.browseServer(); step++;
            }
            case 4 -> {
                if (!listing.isDone()) return null;
                if (listing.join().stream().noneMatch(track -> track.id().equals(serverId))) throw new AssertionError("Server catalogue missing uploaded track");
                metadata = local.getTrack(serverId);
                var cancelled = ServerAudioClient.download(serverId); cancelled.cancel(true);
                downloading = ServerAudioClient.download(serverId); step++;
            }
            case 5 -> {
                if (!metadata.isDone() || !downloading.isDone()) return null;
                serverTrack = metadata.join();
                var digest = MessageDigest.getInstance("SHA-256");
                if (!Arrays.equals(digest.digest(wav()), digest.digest(downloading.join()))) throw new AssertionError("Transferred WAV hash mismatch");
                var destination = player.searchDestinations().stream().filter(item -> !item.shared()).findFirst().orElseThrow();
                if (player.addSearchTracks(destination, List.of(serverTrack)).join().added() != 1) throw new AssertionError("Server audio personal queue rejected");
                player.playPersonal(1); step++;
            }
            case 6 -> {
                if (!personalPlaybackVerified) {
                    if (player.state() != PlaybackController.State.PLAYING || player.audio().positionMs() < 300) return null;
                    if (!player.track().id().equals(serverId) || player.audio().durationMs() != 30_000) throw new AssertionError("Server audio did not route through local provider/decoder");
                    Files.writeString(directory.resolve("audio-personal.txt"), "PASS: local WAV and server WAV personal playback; catalogue, SHA-256, cancellation, OP capability\n");
                    personalPlaybackVerified = true;
                }
                // The peer may take longer to start than the 30-second fixture lasts.
                if (host) {
                    if (!Files.exists(peer.resolve("audio-personal.txt"))) return null;
                    rooms.command(CREATE, "Audio smoke", 0);
                }
                step++;
            }
            case 7 -> {
                if (host) {
                    if (room == null) return null;
                    var guest = game.level.players().stream().filter(item -> !item.getUUID().equals(game.player.getUUID())).findFirst();
                    if (guest.isEmpty()) return null;
                    rooms.command(INVITE, guest.get().getGameProfile().getName(), 0); rooms.addSong(serverId);
                } else {
                    if (rooms.update().invitations().isEmpty()) return null;
                    rooms.join(rooms.update().invitations().get(0));
                }
                step++;
            }
            case 8 -> {
                if (room == null || room.members().size() != 2 || !room.songs().equals(List.of(serverId.toString()))) return null;
                if (host) {
                    var destination = player.searchDestinations().stream().filter(PlaybackController.SearchDestination::shared).findFirst().orElseThrow();
                    var refused = player.addSearchTracks(destination, List.of(privateTrack)).join();
                    if (refused.added() != 0 || !refused.message().equals("modernmusic.error.local_audio_only")) throw new AssertionError("Client local group selection accepted");
                    rooms.addSong(privateTrack.id());
                    if (!rooms.message().equals("modernmusic.error.local_audio_only")) throw new AssertionError("Client local room command accepted");
                    ForgeNetwork.sendToServer(new RoomNetwork.ImportPayload(new RoomImportRequest(forgedLocal, room.id(), room.epoch(), List.of(privateTrack.id().toString()), 1)));
                    ForgeNetwork.sendToServer(new RoomNetwork.ImportPayload(new RoomImportRequest(missingServer, room.id(), room.epoch(),
                            List.of("local:server_" + "f".repeat(32)), 1)));
                }
                step++;
            }
            case 9 -> {
                if (host) {
                    if (!receipts.containsKey(forgedLocal) || !receipts.containsKey(missingServer)) return null;
                    var deniedLocal = receipts.get(forgedLocal); var deniedMissing = receipts.get(missingServer);
                    if (deniedLocal.imported() != 0 || !deniedLocal.message().equals("modernmusic.room.error.local_audio"))
                        throw new AssertionError("Authoritative local audio restriction failed: " + deniedLocal);
                    if (deniedMissing.imported() != 0 || !deniedMissing.message().equals("modernmusic.room.error.audio_missing"))
                        throw new AssertionError("Authoritative missing server audio restriction failed: " + deniedMissing);
                    rooms.command(PLAY, "", 0);
                }
                step++;
            }
            case 10 -> {
                if (room == null || player.state() != PlaybackController.State.PLAYING || player.audio().positionMs() < 300) return null;
                if (!player.shared() || !player.track().id().equals(serverId) || !room.songs().equals(List.of(serverId.toString())))
                    throw new AssertionError("Server audio group routing failed");
                if (player.personalQueue().entries().size() != 2 || !player.personalQueue().contains(privateTrack.id()) || !player.personalQueue().contains(serverId))
                    throw new AssertionError("Group playback damaged personal queue");
                Files.writeString(directory.resolve("audio-group.txt"), "PASS: server WAV group playback with personal queue preserved; local/unknown imports rejected by server\n");
                if (!Files.exists(peer.resolve("audio-group.txt"))) return null;
                return "PASS: 1.0 audio real host/guest; OP upload and forged guest denial; chunk SHA-256/cancel; local + server personal PCM; server group PCM; private and unknown audio authority rejection";
            }
        }
        return null;
    }
    public static byte[] wav() {
        int samples = 8000 * 30;
        var buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R','I','F','F'}).putInt(buffer.capacity() - 8).put(new byte[] {'W','A','V','E'});
        buffer.put(new byte[] {'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[] {'d','a','t','a'}).putInt(samples * 2);
        return buffer.array();
    }
}
