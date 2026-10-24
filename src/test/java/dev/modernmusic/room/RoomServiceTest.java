package dev.modernmusic.room;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.modernmusic.room.RoomCommand.Action.*;

class RoomServiceTest {
    final RoomService service = new RoomService();
    final UUID owner = UUID.randomUUID(), listener = UUID.randomUUID(), outsider = UUID.randomUUID();
    long now = 10_000;
    void create() { service.execute(owner, "Owner", new RoomCommand(CREATE, null, null, -1, "Test", 0), null, now); }
    void command(UUID sender, RoomCommand.Action action, long value, UUID target) {
        var room = service.current(sender); now += 100;
        service.execute(sender, sender.equals(owner) ? "Owner" : "Listener", new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), target, now);
    }
    void join() {
        command(owner, INVITE, 0, listener); var room = service.current(owner); now += 100;
        service.execute(listener, "Listener", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now);
    }
    void rejected(Runnable action, String reason) {
        var failure = assertThrows(RoomService.Rejected.class, action::run); assertEquals("modernmusic.room.error." + reason, failure.key);
    }
    @Test void invitationDoesNotJoinOrPlayUntilAccepted() {
        create(); command(owner, INVITE, 0, listener);
        assertNull(service.current(listener)); assertEquals(Set.of(listener), service.invited(service.current(owner).id(), now));
        var room = service.current(owner);
        service.execute(listener, "Listener", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now);
        assertEquals(room.id(), service.current(listener).id()); assertTrue(service.invited(room.id(), now).isEmpty());
    }
    @Test void invitationExpiresAndCannotBeForged() {
        create(); command(owner, INVITE, 0, listener); var room = service.current(owner);
        rejected(() -> service.execute(outsider, "Other", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now), "invite");
        now += 60_001;
        rejected(() -> service.execute(listener, "Listener", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now), "invite");
        assertTrue(service.invited(room.id(), now).isEmpty());
    }
    @Test void onlyGrantedRequesterCanRequestSongsButCannotControlOrInvite() {
        create(); join(); rejected(() -> command(listener, ADD, 42, null), "request_permission");
        command(owner, REQUESTER, 1, listener); command(listener, ADD, 42, null);
        assertEquals(List.of("42"), service.current(owner).songs());
        for (var action : List.of(PLAY, STOP, PAUSE, SEEK, NEXT, MODE, REMOVE, CLEAR, DURATION)) {
            now += 1_000; rejected(() -> command(listener, action, 0, outsider), "permission");
        }
        for (var action : List.of(DJ, REQUESTER, KICK, INVITE, DISSOLVE)) {
            now += 1_000; rejected(() -> command(listener, action, 0, outsider), "owner_permission");
        }
    }
    @Test void grantsAndRevokesDjWithoutGivingMembershipManagement() {
        create(); join(); command(owner, DJ, 1, listener);
        command(listener, ADD, 42, null); command(listener, PLAY, 0, null);
        assertEquals(RoomSnapshot.State.PLAYING, service.current(owner).state());
        rejected(() -> command(listener, INVITE, 0, outsider), "owner_permission");
        command(owner, DJ, 0, listener); rejected(() -> command(listener, STOP, 0, null), "permission");
    }
    @Test void identityAndRoomAreCheckedOnEveryAction() {
        create(); var room = service.current(owner);
        rejected(() -> service.execute(outsider, "Owner", new RoomCommand(ADD, room.id(), room.epoch(), room.revision(), "", 42), null, now), "member");
        rejected(() -> service.execute(owner, "Owner", new RoomCommand(ADD, room.id(), UUID.randomUUID(), room.revision(), "", 42), null, now), "stale");
    }
    @Test void rejectsStaleControlAndAcceptsAdditiveSongRequests() {
        create(); var old = service.current(owner); command(owner, ADD, 42, null);
        rejected(() -> service.execute(owner, "Owner", new RoomCommand(PLAY, old.id(), old.epoch(), old.revision(), "", 0), null, now), "stale");
        service.execute(owner, "Owner", new RoomCommand(ADD, old.id(), old.epoch(), old.revision(), "", 43), null, now);
        assertEquals(List.of("42", "43"), service.current(owner).songs());
    }
    @Test void scheduledStartPauseResumeAndSeekUseMonotonicTime() {
        create(); command(owner, ADD, 42, null); command(owner, PLAY, 0, null);
        var start = service.current(owner); assertEquals(now + 2_000, start.anchorMs());
        assertEquals(0, start.positionAt(now + 1_000)); assertEquals(1_000, start.positionAt(now + 3_000));
        now += 4_000; command(owner, PAUSE, 0, null); var paused = service.current(owner);
        assertEquals(2_100, paused.positionMs()); assertEquals(paused.positionMs(), paused.positionAt(now + 60_000));
        command(owner, SEEK, 8_000, null); assertEquals(8_000, service.current(owner).positionAt(now + 5_000));
        command(owner, PAUSE, 0, null); var resumed = service.current(owner);
        assertTrue(resumed.anchorMs() >= now && resumed.anchorMs() <= now + 250, "Buffered resume must not wait for the initial load delay");
        assertEquals(8_000, resumed.positionAt(resumed.anchorMs() - 1));
        assertEquals(9_000, resumed.positionAt(resumed.anchorMs() + 1_000));
        assertEquals(start.playbackRevision(), resumed.playbackRevision());
    }
    @Test void automaticAdvanceHasOneAuthorityAndManualNextSkipsRepeatOne() {
        create(); command(owner, ADD, 42, null); command(owner, ADD, 43, null); command(owner, PLAY, 0, null);
        command(owner, DURATION, 1_000, null); var room = service.current(owner);
        service.tick(room.anchorMs() + 1_000); assertEquals(1, service.current(owner).index());
        assertEquals(0, service.current(owner).durationMs()); assertTrue(service.current(owner).playbackRevision() > room.playbackRevision());
        command(owner, PLAY, 0, null); command(owner, MODE, 0, null); command(owner, DURATION, 1_000, null);
        room = service.current(owner); service.tick(room.anchorMs() + 1_000); assertEquals(0, service.current(owner).index());
        command(owner, NEXT, 0, null); assertEquals(1, service.current(owner).index());
    }
    @Test void removeOtherTrackPreservesTimelineRemoveCurrentRestartsAndClearStops() {
        create(); command(owner, ADD, 41, null); command(owner, ADD, 42, null); command(owner, PLAY, 1, null);
        var before = service.current(owner); command(owner, REMOVE, 0, null); var after = service.current(owner);
        assertEquals(0, after.index()); assertEquals(before.playbackRevision(), after.playbackRevision()); assertEquals(before.anchorMs(), after.anchorMs());
        command(owner, ADD, 43, null); command(owner, REMOVE, 0, null);
        assertTrue(service.current(owner).playbackRevision() > after.playbackRevision());
        command(owner, CLEAR, 0, null); assertTrue(service.current(owner).songs().isEmpty()); assertEquals(RoomSnapshot.State.STOPPED, service.current(owner).state());
    }
    @Test void disconnectPreservesOwnerAndRoomWhileKickRevokesReentry() {
        create(); join(); command(owner, KICK, 0, listener); assertNull(service.current(listener));
        var room = service.current(owner);
        rejected(() -> service.execute(listener, "Listener", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now), "invite");
        join(); service.disconnect(owner); assertEquals(owner, service.current(listener).owner());
        assertFalse(service.current(listener).canControl(listener)); service.disconnect(listener);
        assertEquals(1, service.snapshots().size()); assertTrue(service.snapshots().get(0).members().isEmpty());
    }
    @Test void roomsAreIsolatedAndEachPlayerCanJoinOnlyOne() {
        create(); service.execute(outsider, "Other", new RoomCommand(CREATE, null, null, -1, "Other", 0), null, now);
        command(owner, ADD, 42, null); assertTrue(service.current(outsider).songs().isEmpty());
        command(owner, INVITE, 0, outsider); var room = service.current(owner);
        rejected(() -> service.execute(outsider, "Other", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now), "already_joined");
    }
    @Test void boundsRejectQueueOverflowIndicesDurationsAndRequestFloods() {
        create();
        for (int i = 1; i <= 400; i++) { now += 1_000; command(owner, ADD, i, null); }
        rejected(() -> command(owner, ADD, 401, null), "full");
        rejected(() -> command(owner, REMOVE, Long.MAX_VALUE, null), "input");
        rejected(() -> command(owner, PLAY, -1, null), "input");
        command(owner, PLAY, 0, null); rejected(() -> command(owner, DURATION, 3_600_001, null), "input");
        now += 1_000; var room = service.current(owner);
        for (int i = 0; i < 12; i++) service.execute(owner, "Owner", new RoomCommand(SNAPSHOT, room.id(), room.epoch(), room.revision(), "", 0), null, now);
        rejected(() -> service.execute(owner, "Owner", new RoomCommand(SNAPSHOT, room.id(), room.epoch(), room.revision(), "", 0), null, now), "rate");
    }
    @Test void roomAndMemberCountsAreBounded() {
        create();
        for (int i = 1; i <= 16; i++) {
            UUID id = UUID.randomUUID(); now += 1_000; command(owner, INVITE, 0, id); var room = service.current(owner);
            RoomCommand join = new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0);
            if (i < 16) service.execute(id, "Member", join, null, now);
            else rejected(() -> service.execute(id, "Member", join, null, now), "full");
        }
        for (int i = 1; i < 64; i++) service.execute(UUID.randomUUID(), "Other", new RoomCommand(CREATE, null, null, -1, "Room", 0), null, now);
        rejected(() -> service.execute(outsider, "Other", new RoomCommand(CREATE, null, null, -1, "Room", 0), null, now), "full");
    }
    @Test void requesterBatchSkipsDuplicatesPreservesOrderAndCurrentPlaybackAndIsIdempotent() {
        create(); join(); command(owner, REQUESTER, 1, listener); command(owner, ADD, 42, null); command(owner, PLAY, 0, null);
        var before = service.current(owner);
        var request = new RoomImportRequest(UUID.randomUUID(), before.id(), before.epoch(), List.of("50", "50", "51"), 3);
        var receipt = service.append(listener, request, now);
        var after = service.current(owner);
        assertEquals(2, receipt.imported()); assertEquals(1, receipt.skipped()); assertEquals(3, receipt.processedCount());
        assertEquals(List.of("42", "50", "51"), after.songs());
        assertEquals(before.queueRevision() + 1, after.queueRevision()); assertEquals(before.revision() + 1, after.revision());
        assertEquals(before.playbackRevision(), after.playbackRevision()); assertEquals(before.anchorMs(), after.anchorMs());
        assertEquals(before.state(), after.state()); assertEquals(before.index(), after.index());
        assertEquals(receipt, service.append(listener, request, now)); assertEquals(after, service.current(owner));
        var repeated = service.append(listener, new RoomImportRequest(UUID.randomUUID(), before.id(), before.epoch(), List.of("50", "51"), 2), now);
        assertEquals(0, repeated.imported()); assertEquals(2, repeated.skipped()); assertEquals(after, service.current(owner));
    }
    @Test void concurrentSongRequestsCannotInsertDuplicateOrChangeTimeline() {
        create(); join(); command(owner, REQUESTER, 1, listener); command(owner, ADD, 42, null); command(owner, PLAY, 0, null);
        var before = service.current(owner);
        rejected(() -> command(listener, ADD, 42, null), "duplicate"); assertEquals(before, service.current(owner));
        var result = service.append(listener, new RoomImportRequest(UUID.randomUUID(), before.id(), before.epoch(), List.of("42", "43"), 2), now);
        assertEquals(1, result.imported()); assertEquals(1, result.skipped());
        assertEquals(List.of("42", "43"), service.current(owner).songs());
        assertEquals(before.playbackRevision(), service.current(owner).playbackRevision());
    }
    @Test void batchUsesServerCapacityAtCommitAndReportsActualPartialCount() {
        create(); join(); command(owner, REQUESTER, 1, listener); var initial = service.current(owner);
        var request = new RoomImportRequest(UUID.randomUUID(), initial.id(), initial.epoch(), List.of("500", "501", "502"), 600);
        for (int i = 1; i < 400; i++) { now += 1_000; command(owner, ADD, i, null); }
        var receipt = service.append(listener, request, now);
        assertEquals(1, receipt.imported()); assertEquals(600, receipt.total());
        assertEquals(400, service.current(owner).songs().size()); assertEquals("500", dev.modernmusic.compat.Java17.last(service.current(owner).songs()));
        var full = service.append(listener, new RoomImportRequest(UUID.randomUUID(), initial.id(), initial.epoch(), List.of("600"), 1), now);
        assertEquals(0, full.imported()); assertEquals("modernmusic.status.queue_full", full.message());
    }
    @Test void batchRequiresActualMembershipCurrentEpochAndSharesCommandRateLimit() {
        create(); join(); var room = service.current(owner);
        var request = new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of("50"), 1);
        rejected(() -> service.append(outsider, request, now), "member");
        rejected(() -> service.append(listener, new RoomImportRequest(UUID.randomUUID(), room.id(), UUID.randomUUID(), List.of("50"), 1), now), "stale");
        command(owner, KICK, 0, listener); rejected(() -> service.append(listener, request, now), "member");
        now += 1_000;
        for (int i = 0; i < 11; i++) service.append(owner, new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of("50"), 1), now);
        service.execute(owner, "Owner", new RoomCommand(SNAPSHOT, room.id(), room.epoch(), room.revision(), "", 0), null, now);
        rejected(() -> service.append(owner, request, now), "rate");
    }
    @Test void unchangedTicksReuseSnapshotAndBatchSharesItBetweenRoomMembers() {
        create(); join(); command(owner, ADD, 42, null); command(owner, PLAY, 0, null);
        command(owner, DURATION, 60_000, null);
        var before = service.current(owner);
        for (int i = 0; i < 100; i++) assertFalse(service.tick(now + i * 50));
        assertSame(before, service.current(owner));
        var batch = service.updatesFor(List.of(owner, listener, outsider), now);
        assertSame(before, batch.get(owner).room());
        assertSame(batch.get(owner).room(), batch.get(listener).room());
        assertNull(batch.get(outsider).room());
        command(owner, ADD, 43, null);
        assertNotSame(before, service.current(owner));
        assertEquals(List.of("42"), before.songs(), "Cached snapshots remain immutable after mutation");
    }
    @Test void batchOnlyRevealsEachRecipientsInvitationsAndExpiryTriggersDelivery() {
        create(); command(owner, INVITE, 0, listener);
        var batch = service.updatesFor(List.of(owner, listener, outsider), now);
        assertEquals(1, batch.get(listener).invitations().size());
        assertTrue(batch.get(owner).invitations().isEmpty());
        assertTrue(batch.get(outsider).invitations().isEmpty());
        assertTrue(service.tick(now + 60_000));
        assertTrue(service.updatesFor(List.of(listener), now + 60_000).get(listener).invitations().isEmpty());
    }
}
