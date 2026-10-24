package dev.modernmusic.room;

import dev.modernmusic.domain.PlaybackMode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomProtocolTest {
    final UUID id = UUID.randomUUID(), epoch = UUID.randomUUID(), owner = UUID.randomUUID();
    RoomSnapshot snapshot(long revision) {
        return new RoomSnapshot(id, epoch, "房间", revision, revision, revision, owner,
                List.of(new RoomSnapshot.Member(owner, "Owner", true)), List.of("42"), 0,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.PLAYING, 500, 10_000, 60_000);
    }
    RoomUpdate update(long seq, RoomSnapshot room) { return new RoomUpdate(seq, room, List.of(), ""); }
    @Test void recordsRoundTripAndCarryNoPrivateResourceData() {
        RoomUpdate before = update(1, snapshot(3)); String json = RoomJson.encode(before);
        assertEquals(before, RoomJson.decode(json, RoomUpdate.class));
        assertFalse(json.contains("Cookie")); assertFalse(json.contains("http")); assertFalse(json.contains("MUSIC_U"));
    }
    @Test void olderMemberPayloadsDefaultToNoRequestPermissionUnlessDj() {
        var listener = new RoomSnapshot.Member(UUID.randomUUID(), "Listener", false);
        String legacy = RoomJson.encode(listener).replace(",\"requester\":false", "");
        assertEquals(listener, RoomJson.decode(legacy, RoomSnapshot.Member.class));
        assertFalse(RoomJson.decode(legacy, RoomSnapshot.Member.class).requester());
    }
    @Test void administratorFlagRoundTripsAndOlderMembersDefaultToNoAdministration() {
        var admin = new RoomSnapshot.Member(owner, "Admin", false, false, true);
        var publicRoom = new RoomSnapshot(RoomService.PUBLIC_ROOM_ID, epoch, "Server Public Room", 0, 0, 0,
                RoomService.SERVER_OWNER, List.of(admin), List.of(), -1, PlaybackMode.SEQUENTIAL,
                RoomSnapshot.State.STOPPED, 0, 0, 0);
        var decoded = RoomJson.decode(RoomJson.encode(publicRoom), RoomSnapshot.class);
        assertEquals(publicRoom, decoded); assertTrue(decoded.canManage(owner));
        assertTrue(decoded.canControl(owner)); assertTrue(decoded.canRequest(owner));
        String legacy = RoomJson.encode(admin).replace(",\"administrator\":true", "");
        assertFalse(RoomJson.decode(legacy, RoomSnapshot.Member.class).administrator());
        var privateRoom = new RoomSnapshot(id, epoch, "Private", 0, 0, 0, UUID.randomUUID(),
                List.of(admin), List.of(), -1, PlaybackMode.SEQUENTIAL, RoomSnapshot.State.STOPPED, 0, 0, 0);
        assertFalse(privateRoom.canManage(owner)); assertFalse(privateRoom.canRequest(owner));
    }
    @Test void playlistBatchContainsOnlyIdsAndRejectsInvalidOrOversizedLists() {
        var request = new RoomImportRequest(UUID.randomUUID(), id, epoch, List.of("50", "50", "51"), 3);
        String json = RoomJson.encode(request); assertEquals(request, RoomJson.decode(json, RoomImportRequest.class));
        assertFalse(json.contains("http")); assertFalse(json.contains("Cookie"));
        for (List<String> songs : List.of(List.<String>of(), List.of("0"), List.of("invalid"), Collections.nCopies(401, "50")))
            assertThrows(IllegalArgumentException.class, () -> new RoomImportRequest(UUID.randomUUID(), id, epoch, songs, 500));
        assertThrows(IllegalArgumentException.class, () -> new RoomImportRequest(UUID.randomUUID(), id, epoch, List.of("50"), 0));
    }
    @Test void malformedAndUnboundedCommandsAreRejected() {
        for (String json : List.of("null", "{}", "{\"action\":\"UNKNOWN\",\"text\":\"\"}",
                "{\"action\":\"ADD\",\"text\":null}", "{\"action\":\"ADD\",\"text\":\"" + "a".repeat(65) + "\"}"))
            assertThrows(RuntimeException.class, () -> RoomJson.decode(json, RoomCommand.class));
    }
    @Test void duplicateAwareReceiptFitsExistingWireLimitAndAcceptsLegacyReceipts() {
        var result = new RoomImportResult(UUID.randomUUID(), 399, Integer.MAX_VALUE, "x".repeat(80), 400);
        String json = RoomJson.encode(result); assertTrue(json.length() <= 256); assertEquals(result, RoomJson.decode(json, RoomImportResult.class));
        var legacy = RoomJson.decode("{\"request\":\"" + id + "\",\"imported\":2,\"total\":3,\"message\":\"\"}", RoomImportResult.class);
        assertEquals(2, legacy.processedCount()); assertEquals(0, legacy.skipped());
        for (int invalid : List.of(-1, 0, 401))
            assertThrows(IllegalArgumentException.class, () -> new RoomImportResult(id, 1, 400, "", invalid));
    }
    @Test void maximumQueueMembersInvitationsAndSavedDirectoryFitExpandedPayloadLimit() {
        String name = String.valueOf((char) 0).repeat(40);
        var members = Collections.nCopies(16, new RoomSnapshot.Member(owner, "a".repeat(16), false));
        var largest = new RoomSnapshot(id, epoch, name, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, owner,
                members, Collections.nCopies(400, "kugou:" + "F".repeat(32)), 399, PlaybackMode.SEQUENTIAL,
                RoomSnapshot.State.PLAYING, 3_600_000, Long.MAX_VALUE, 3_600_000, UUID.randomUUID());
        var invitations = Collections.nCopies(64, new RoomUpdate.Invitation(id, epoch, name));
        var saved = Collections.nCopies(64, new RoomUpdate.SavedRoom(id, epoch, name, owner, 16, 400));
        var update = new RoomUpdate(Long.MAX_VALUE, largest, invitations, "x".repeat(80),
                new TeamStatus(TeamStatus.State.READY, name), saved, 64);
        String json = RoomJson.encode(update);
        assertTrue(json.length() <= 131072, () -> "Encoded payload exceeds codec bound: " + json.length());
        assertEquals(update, RoomJson.decode(json, RoomUpdate.class));
    }
    @Test void savedDirectoryAndDissolveRoundTripAndRejectInvalidBounds() {
        var saved = new RoomUpdate.SavedRoom(id, epoch, "Saved", owner, 0, 2);
        var before = new RoomUpdate(1, null, List.of(), "", TeamStatus.ABSENT, List.of(saved), 0);
        assertEquals(before, RoomJson.decode(RoomJson.encode(before), RoomUpdate.class));
        var dissolve = new RoomCommand(RoomCommand.Action.DISSOLVE, id, epoch, -1, "", 0);
        assertEquals(dissolve, RoomJson.decode(RoomJson.encode(dissolve), RoomCommand.class));
        assertEquals(2, new RoomUpdate(0, null, List.of(), "").roomLimit());
        for (int invalid : List.of(-1, 65))
            assertThrows(IllegalArgumentException.class, () -> new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT, List.of(), invalid));
        assertThrows(IllegalArgumentException.class, () -> new RoomUpdate.SavedRoom(id, epoch, "Saved", owner, 17, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoomUpdate.SavedRoom(id, epoch, "Saved", owner, 0, 401));
        assertThrows(IllegalArgumentException.class, () -> new RoomUpdate(0, null, List.of(), "", TeamStatus.ABSENT, Collections.nCopies(65, saved), 2));
    }
    @Test void duplicateReorderedAndOldRoomPacketsCannotUndoLeave() {
        RoomReplica replica = new RoomReplica();
        assertTrue(replica.accept(update(1, snapshot(1)))); assertTrue(replica.accept(update(3, snapshot(3))));
        assertFalse(replica.accept(update(2, snapshot(2)))); assertFalse(replica.accept(update(3, snapshot(3))));
        assertFalse(replica.accept(update(4, snapshot(1))));
        assertTrue(replica.accept(update(5, null))); assertFalse(replica.accept(update(4, snapshot(4)))); assertNull(replica.current().room());
    }
    @Test void replacementEpochAcceptsRestartedRoomRevisions() {
        RoomReplica replica = new RoomReplica(); assertTrue(replica.accept(update(1, snapshot(20))));
        var restarted = new RoomSnapshot(id, UUID.randomUUID(), "Room", 0, 0, 0, owner, List.of(), List.of(), -1,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.STOPPED, 0, 0, 0);
        assertTrue(replica.accept(update(2, restarted)));
    }
    @Test void clockFiltersOutliersAndSelectsLowestRtt() {
        ClockEstimator clock = new ClockEstimator(); assertFalse(clock.ready());
        assertTrue(clock.sample(1_000, 1_100, 5_050)); assertEquals(5_000, clock.serverNow(1_000)); assertEquals(100, clock.rtt());
        clock.sample(2_000, 2_300, 6_250); assertEquals(6_000, clock.serverNow(2_000));
        assertFalse(clock.sample(4_000, 3_000, 7_000)); assertFalse(clock.sample(1_000, 4_000, 5_000));
        clock.sample(40_000, 40_120, 44_060); assertEquals(44_000, clock.serverNow(40_000));
    }
    @Test void twoClientsWithDifferentClockOriginsConvergeUnderRtt100Jitter30() {
        ClockEstimator a = new ClockEstimator(), b = new ClockEstimator(); Random random = new Random(31);
        // Clock A is 5 seconds behind the server, B is 8 seconds ahead.
        for (int i = 0; i < 20; i++) {
            long server = 20_000 + i * 1_000; int up = 50 + random.nextInt(61) - 30, down = 50 + random.nextInt(61) - 30;
            a.sample(server - 5_000, server - 5_000 + up + down, server + up);
            up = 50 + random.nextInt(61) - 30; down = 50 + random.nextInt(61) - 30;
            b.sample(server + 8_000, server + 8_000 + up + down, server + up);
        }
        var timeline = new RoomSnapshot(id, epoch, "Room", 1, 1, 1, owner, List.of(), List.of("42"), 0,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.PLAYING, 0, 50_000, 240_000);
        List<Long> errors = new ArrayList<>();
        for (int t = 0; t < 180_000; t += 50) {
            long server = 50_000 + t;
            errors.add(Math.abs(timeline.positionAt(a.serverNow(server - 5_000)) - timeline.positionAt(b.serverNow(server + 8_000))));
        }
        Collections.sort(errors); assertTrue(errors.get((int) (errors.size() * .95)) <= 200);
        assertTrue(Math.abs(timeline.positionAt(a.serverNow(100_000 - 5_000)) - 50_000) <= 30);
    }
}
