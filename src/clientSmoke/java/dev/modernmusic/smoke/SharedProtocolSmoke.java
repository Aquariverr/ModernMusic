package dev.modernmusic.smoke;

import dev.modernmusic.network.SharedAccountNetwork.*;
import dev.modernmusic.provider.MusicError;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import io.netty.buffer.Unpooled;

/** Actual payload codecs under the game runtime; never bundled in the release JAR. */
public final class SharedProtocolSmoke {
    public static void verify() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var request = new RequestPayload(UUID.randomUUID(), 9, "42", true);
            RequestPayload.CODEC.encode(buffer, request);
            if (buffer.readableBytes() != 28 || !request.equals(RequestPayload.CODEC.decode(buffer))) throw new AssertionError("Request codec");
            buffer.clear();
            var result = new ResultPayload(request.request(), 9, "42", "https://m1.music.126.net/signed?token=fixture", 123456, null);
            ResultPayload.CODEC.encode(buffer, result);
            if (!result.equals(ResultPayload.CODEC.decode(buffer)) || result.toString().contains("fixture")) throw new AssertionError("Result codec/redaction");
            buffer.clear();
            var denied = new ResultPayload(request.request(), 9, "42", "", 0, MusicError.SHARED_FORBIDDEN);
            ResultPayload.CODEC.encode(buffer, denied);
            if (!denied.equals(ResultPayload.CODEC.decode(buffer))) throw new AssertionError("Error codec");
            buffer.clear();
            var kugouId = "kugou:" + "ABCDEF0123456789".repeat(2);
            var kugou = new RequestPayload(UUID.randomUUID(), 10, kugouId, false);
            RequestPayload.CODEC.encode(buffer, kugou);
            if (!kugou.equals(RequestPayload.CODEC.decode(buffer))) throw new AssertionError("Kugou request hash codec");
            buffer.clear();
            var batch = new dev.modernmusic.network.RoomNetwork.ImportPayload(new dev.modernmusic.room.RoomImportRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    java.util.List.of("42", "qq:42", kugouId), 3));
            dev.modernmusic.network.RoomNetwork.ImportPayload.CODEC.encode(buffer, batch);
            if (!batch.equals(dev.modernmusic.network.RoomNetwork.ImportPayload.CODEC.decode(buffer))) throw new AssertionError("Mixed room import codec");
            buffer.clear();
            var roomId = UUID.randomUUID();
            var roomEpoch = UUID.randomUUID();
            var owner = UUID.randomUUID();
            var directory = new dev.modernmusic.room.RoomUpdate(19, null,
                    java.util.List.of(new dev.modernmusic.room.RoomUpdate.Invitation(UUID.randomUUID(), roomEpoch, "邀请群组")), "",
                    dev.modernmusic.room.TeamStatus.ABSENT,
                    java.util.List.of(new dev.modernmusic.room.RoomUpdate.SavedRoom(roomId, roomEpoch, "我创建的群组", owner, 2, 12),
                            new dev.modernmusic.room.RoomUpdate.SavedRoom(UUID.randomUUID(), roomEpoch, "我加入的群组", UUID.randomUUID(), 4, 36)), 2);
            var directoryPayload = new dev.modernmusic.network.RoomNetwork.UpdatePayload(dev.modernmusic.room.RoomJson.encode(directory));
            dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.encode(buffer, directoryPayload);
            var decodedDirectory = dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.decode(buffer);
            if (!directoryPayload.equals(decodedDirectory)
                    || !directory.equals(dev.modernmusic.room.RoomJson.decode(decodedDirectory.json(), dev.modernmusic.room.RoomUpdate.class)))
                throw new AssertionError("Saved-room directory/limit payload codec");
            buffer.clear();
            var dissolve = new dev.modernmusic.room.RoomCommand(dev.modernmusic.room.RoomCommand.Action.DISSOLVE,
                    roomId, roomEpoch, -1, "", 0);
            var dissolvePayload = new dev.modernmusic.network.RoomNetwork.CommandPayload(dev.modernmusic.room.RoomJson.encode(dissolve));
            dev.modernmusic.network.RoomNetwork.CommandPayload.CODEC.encode(buffer, dissolvePayload);
            var decodedDissolve = dev.modernmusic.network.RoomNetwork.CommandPayload.CODEC.decode(buffer);
            if (!dissolve.equals(dev.modernmusic.room.RoomJson.decode(decodedDissolve.json(), dev.modernmusic.room.RoomCommand.class)))
                throw new AssertionError("Saved-room dissolve payload codec");
            buffer.clear();
            var duration = new dev.modernmusic.network.RoomNetwork.DurationPayload(new dev.modernmusic.room.RoomDurationReport(
                    roomId, roomEpoch, 42, "qq:42", dev.modernmusic.room.RoomDurationReport.MAX_DURATION_MS));
            dev.modernmusic.network.RoomNetwork.DurationPayload.CODEC.encode(buffer, duration);
            if (!duration.equals(dev.modernmusic.network.RoomNetwork.DurationPayload.CODEC.decode(buffer)))
                throw new AssertionError("Playback-bound duration report codec");
            buffer.clear();
            // Valid maximum directories plus a full mixed-provider room exceeded the former 64 KiB character limit.
            var maximumSaved = new java.util.ArrayList<dev.modernmusic.room.RoomUpdate.SavedRoom>();
            var maximumInvites = new java.util.ArrayList<dev.modernmusic.room.RoomUpdate.Invitation>();
            for (int i = 0; i < dev.modernmusic.room.RoomService.MAX_ROOMS; i++) {
                maximumSaved.add(new dev.modernmusic.room.RoomUpdate.SavedRoom(new UUID(101, i), roomEpoch, "<".repeat(40), owner, 16, 400));
                maximumInvites.add(new dev.modernmusic.room.RoomUpdate.Invitation(new UUID(102, i), roomEpoch, "<".repeat(40)));
            }
            var fullRoom = new dev.modernmusic.room.RoomSnapshot(roomId, roomEpoch, "<".repeat(40), 1, 1, 1, owner,
                    java.util.stream.IntStream.range(0, 16).mapToObj(i -> new dev.modernmusic.room.RoomSnapshot.Member(
                            i == 0 ? owner : new UUID(103, i), "<".repeat(16), i == 0, i == 0)).toList(),
                    java.util.stream.IntStream.range(0, 400).mapToObj(i -> "kugou:" + String.format("%032X", i + 1)).toList(), 0,
                    dev.modernmusic.domain.PlaybackMode.SEQUENTIAL, dev.modernmusic.room.RoomSnapshot.State.STOPPED, 0, 0, 0);
            var maximumUpdate = new dev.modernmusic.room.RoomUpdate(20, fullRoom, maximumInvites, "",
                    dev.modernmusic.room.TeamStatus.ABSENT, maximumSaved, 2);
            var maximumPayload = new dev.modernmusic.network.RoomNetwork.UpdatePayload(dev.modernmusic.room.RoomJson.encode(maximumUpdate));
            if (maximumPayload.json().length() <= 65536) throw new AssertionError("Maximum update did not exercise the extended payload bound");
            dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.encode(buffer, maximumPayload);
            if (!maximumPayload.equals(dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.decode(buffer)))
                throw new AssertionError("Maximum saved-room directory payload codec");
            buffer.clear();
            var publicId = dev.modernmusic.room.RoomService.PUBLIC_ROOM_ID;
            var serverOwner = dev.modernmusic.room.RoomService.SERVER_OWNER;
            int publicLimit = dev.modernmusic.room.RoomService.MAX_PUBLIC_MEMBERS;
            maximumSaved.add(new dev.modernmusic.room.RoomUpdate.SavedRoom(publicId, roomEpoch, "<".repeat(40), serverOwner, publicLimit, 400));
            String longestSong = "kugou:collection_99_" + "9".repeat(19) + "_" + "9".repeat(19) + "_9";
            var publicRoom = new dev.modernmusic.room.RoomSnapshot(publicId, roomEpoch, "<".repeat(40),
                    Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, serverOwner,
                    java.util.stream.IntStream.range(0, publicLimit).mapToObj(i -> new dev.modernmusic.room.RoomSnapshot.Member(
                            i == 0 ? owner : new UUID(104, i), "<".repeat(16), false, false, i == 0)).toList(),
                    java.util.Collections.nCopies(400, longestSong), 399, fullRoom.mode(), fullRoom.state(),
                    Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
            var publicUpdate = new dev.modernmusic.room.RoomUpdate(Long.MAX_VALUE, publicRoom, maximumInvites, "<".repeat(80),
                    new dev.modernmusic.room.TeamStatus(dev.modernmusic.room.TeamStatus.State.READY, "<".repeat(40)), maximumSaved, 64);
            var publicPayload = new dev.modernmusic.network.RoomNetwork.UpdatePayload(dev.modernmusic.room.RoomJson.encode(publicUpdate));
            if (publicPayload.json().length() <= 262144) throw new AssertionError("Public room did not exercise the corrected payload bound");
            dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.encode(buffer, publicPayload);
            var decodedPublic = dev.modernmusic.network.RoomNetwork.UpdatePayload.CODEC.decode(buffer);
            var publicState = dev.modernmusic.room.RoomJson.decode(decodedPublic.json(), dev.modernmusic.room.RoomUpdate.class);
            if (!publicUpdate.equals(publicState) || !publicState.room().canManage(owner))
                throw new AssertionError("Public room member/administrator payload codec");
            buffer.clear();
            var state = new StatePayload(dev.modernmusic.domain.MusicPlatform.QQ, 10, MusicError.SHARED_DISABLED); StatePayload.CODEC.encode(buffer, state);
            if (!state.equals(StatePayload.CODEC.decode(buffer))) throw new AssertionError("State codec");
            buffer.clear();
            var profile = new ProfilePayload(dev.modernmusic.domain.MusicPlatform.QQ, 10, new dev.modernmusic.shared.SharedAccountProfile("Server fixture", "https://q.qlogo.cn/fixture.png"));
            ProfilePayload.CODEC.encode(buffer, profile);
            if (!profile.equals(ProfilePayload.CODEC.decode(buffer))) throw new AssertionError("Public profile codec");
            buffer.clear();
            try {
                ResultPayload.CODEC.encode(buffer, new ResultPayload(request.request(), 9, "42", "x".repeat(4097), 0, null));
                throw new AssertionError("Unbounded URL");
            } catch (io.netty.handler.codec.EncoderException expected) { }
            buffer.clear();
            var admin = new dev.modernmusic.network.SharedAdminNetwork.CommandPayload(UUID.randomUUID(), 1,
                    dev.modernmusic.shared.SharedAdminAction.COOKIE_LOGIN, "MUSIC_U=fixture-private-value");
            dev.modernmusic.network.SharedAdminNetwork.CommandPayload.CODEC.encode(buffer, admin);
            if (!admin.equals(dev.modernmusic.network.SharedAdminNetwork.CommandPayload.CODEC.decode(buffer))
                    || admin.toString().contains("fixture-private-value")) throw new AssertionError("Admin command codec/redaction");
            buffer.clear();
            var snapshot = dev.modernmusic.shared.SharedAdminSnapshot.unavailable(admin.view(), 1, false, "modernmusic.admin.denied");
            var packet = new dev.modernmusic.network.SharedAdminNetwork.SnapshotPayload(dev.modernmusic.room.RoomJson.encode(snapshot));
            dev.modernmusic.network.SharedAdminNetwork.SnapshotPayload.CODEC.encode(buffer, packet);
            if (!packet.equals(dev.modernmusic.network.SharedAdminNetwork.SnapshotPayload.CODEC.decode(buffer))) throw new AssertionError("Admin view codec");
            if (!snapshot.equals(dev.modernmusic.room.RoomJson.decode(packet.json(), dev.modernmusic.shared.SharedAdminSnapshot.class))) throw new AssertionError("Admin view validation");
            try {
                new dev.modernmusic.network.SharedAdminNetwork.CommandPayload(admin.view(), 1, dev.modernmusic.shared.SharedAdminAction.COOKIE_LOGIN, "x".repeat(16385));
                throw new AssertionError("Unbounded admin cookie");
            } catch (IllegalArgumentException expected) { }
        } finally { buffer.release(); }
    }
}
