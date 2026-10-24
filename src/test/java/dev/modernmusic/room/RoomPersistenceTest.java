package dev.modernmusic.room;

import dev.modernmusic.domain.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomPersistenceTest {
    final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), other = UUID.randomUUID();
    long now;

    RoomSnapshot create(RoomService service, UUID player, String name) {
        service.execute(player, name(player), new RoomCommand(CREATE, null, null, -1, name, 0), null, now += 1_000);
        return service.current(player);
    }
    String name(UUID player) { return player.equals(owner) ? "Owner" : player.equals(member) ? "Member" : "Other"; }
    void command(RoomService service, UUID player, RoomCommand.Action action, long value, UUID target) {
        var room = service.current(player);
        service.execute(player, name(player), new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), target, now += 1_000);
    }
    void join(RoomService service, UUID player, RoomSnapshot room) {
        service.execute(player, name(player), new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now += 1_000);
    }
    void join(RoomService service, UUID player, RoomUpdate.SavedRoom room) {
        service.execute(player, name(player), new RoomCommand(JOIN, room.room(), room.epoch(), -1, "", 0), null, now += 1_000);
    }
    void dissolve(RoomService service, UUID player, RoomSnapshot room) {
        service.execute(player, name(player), new RoomCommand(DISSOLVE, room.id(), room.epoch(), -1, "", 0), null, now += 1_000);
    }
    RoomUpdate update(RoomService service, UUID player) { return service.updatesFor(List.of(player), now).get(player); }
    void denied(String reason, Runnable action) {
        assertEquals("modernmusic.room.error." + reason, assertThrows(RoomService.Rejected.class, action::run).key);
    }

    @Test void ownerDepartureNeverTransfersOwnershipAndLastListenerStopsButKeepsPlaylist() {
        var service = new RoomService(); create(service, owner, "Keep me");
        command(service, owner, INVITE, 0, member); join(service, member, service.current(owner));
        command(service, owner, ADD, 42, null); command(service, owner, ADD, 43, null);
        command(service, owner, PLAY, 1, null); command(service, owner, MODE, 0, null);
        var before = service.current(owner);
        command(service, owner, LEAVE, 0, null);
        var remaining = service.current(member);
        assertEquals(owner, remaining.owner()); assertFalse(remaining.canManage(member));
        assertFalse(remaining.canControl(member)); assertEquals(RoomSnapshot.State.PLAYING, remaining.state());
        denied("owner_permission", () -> dissolve(service, member, remaining));
        service.disconnect(member);
        var empty = service.snapshots().getFirst();
        assertTrue(empty.members().isEmpty()); assertEquals(before.id(), empty.id()); assertEquals(owner, empty.owner());
        assertEquals(before.songs(), empty.songs()); assertEquals(before.index(), empty.index()); assertEquals(before.mode(), empty.mode());
        assertEquals(RoomSnapshot.State.STOPPED, empty.state()); assertEquals(0, empty.positionMs()); assertEquals(0, empty.durationMs());
        assertEquals(1, update(service, owner).savedRooms().size());
        assertEquals(1, update(service, member).savedRooms().size());
        join(service, owner, update(service, owner).savedRooms().getFirst());
        assertTrue(service.current(owner).canManage(owner)); assertEquals(empty.id(), service.current(owner).id());
    }

    @Test void twoOwnedRoomLimitExcludesJoinedRoomsAndDissolveReleasesCapacity() {
        var service = new RoomService(); var first = create(service, owner, "First"); service.leave(owner);
        create(service, owner, "Second"); service.leave(owner);
        create(service, other, "Someone else's"); command(service, other, INVITE, 0, owner);
        join(service, owner, service.current(other)); service.leave(owner);
        assertEquals(3, update(service, owner).savedRooms().size()); assertEquals(2, update(service, owner).roomLimit());
        denied("owned_limit", () -> create(service, owner, "Third"));
        dissolve(service, owner, first);
        create(service, owner, "Replacement");
        assertEquals(2, service.persistentRooms().stream().filter(room -> room.owner().equals(owner)).count());
        assertEquals(3, service.persistentRooms().size());
    }

    @Test void runtimeLimitCanDisableCreationWithoutDeletingOrBlockingReentryToExistingGroups() {
        var limit = new AtomicInteger(3); var service = new RoomService(TeamDirectory.ABSENT, limit::get);
        var existing = create(service, owner, "Saved"); service.leave(owner);
        limit.set(0); assertEquals(0, update(service, owner).roomLimit());
        denied("owned_limit", () -> create(service, owner, "Disabled"));
        join(service, owner, existing); assertEquals(existing.id(), service.current(owner).id()); service.leave(owner);
        limit.set(1); denied("owned_limit", () -> create(service, owner, "Still full"));
        limit.set(3); create(service, owner, "Second"); service.leave(owner); create(service, owner, "Third");
        assertEquals(3, service.persistentRooms().size());
    }

    @Test void restartRetainsStableIdsMembershipRolesSelectedTrackAndModeButNotOnlineStateOrTimeline() {
        var service = new RoomService(); create(service, owner, "Persistent");
        command(service, owner, INVITE, 0, member); join(service, member, service.current(owner));
        command(service, owner, DJ, 1, member);
        command(service, member, ADD, 42, null); command(service, member, ADD, -43, null);
        var hash = "kugou:0123456789ABCDEF0123456789ABCDEF";
        var room = service.current(member);
        service.append(member, new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of(hash), 1), now += 1_000);
        command(service, member, PLAY, 2, null); command(service, member, MODE, 0, null);
        var playing = service.current(member); var persisted = service.persistentRooms();
        var restarted = new RoomService(); restarted.restoreRooms(persisted);
        assertNull(restarted.current(owner)); assertNull(restarted.current(member));
        var saved = update(restarted, member).savedRooms().getFirst();
        assertEquals(playing.id(), saved.room()); assertNotEquals(playing.epoch(), saved.epoch()); assertEquals(0, saved.members());
        denied("stale", () -> join(restarted, member, playing));
        join(restarted, member, saved); var restored = restarted.current(member);
        assertTrue(restored.canControl(member)); assertEquals(owner, restored.owner());
        assertEquals(playing.songs(), restored.songs()); assertEquals(playing.index(), restored.index()); assertEquals(playing.mode(), restored.mode());
        assertEquals(RoomSnapshot.State.STOPPED, restored.state()); assertEquals(0, restored.positionMs()); assertEquals(0, restored.durationMs());
        assertEquals(persisted, restarted.persistentRooms());
    }

    @Test void roleChangesSurviveLeaveAndRestartWhileKickRevokesDirectoryAndReentry() {
        var service = new RoomService(); create(service, owner, "Roles");
        command(service, owner, INVITE, 0, member); join(service, member, service.current(owner));
        command(service, owner, REQUESTER, 1, member); service.disconnect(member);
        join(service, member, update(service, member).savedRooms().getFirst());
        assertTrue(service.current(member).canRequest(member)); assertFalse(service.current(member).canControl(member));
        command(service, owner, DJ, 0, member); service.leave(member);
        join(service, member, update(service, member).savedRooms().getFirst()); assertFalse(service.current(member).canRequest(member));
        var old = service.current(member); command(service, owner, KICK, 0, member);
        assertTrue(update(service, member).savedRooms().isEmpty());
        denied("invite", () -> join(service, member, old));
        var restarted = new RoomService(); restarted.restoreRooms(service.persistentRooms());
        assertTrue(update(restarted, member).savedRooms().isEmpty());
        var ownerRoom = update(restarted, owner).savedRooms().getFirst();
        denied("invite", () -> join(restarted, member, ownerRoom));
    }

    @Test void directoryOnlyRevealsJoinedRoomsSortsOwnedFirstAndNeverPersistsUnacceptedInvitations() {
        var service = new RoomService(); create(service, other, "Joined first");
        command(service, other, INVITE, 0, owner); command(service, other, INVITE, 0, member);
        assertTrue(update(service, owner).savedRooms().isEmpty()); assertEquals(1, update(service, owner).invitations().size());
        join(service, owner, service.current(other)); service.leave(owner);
        var owned = create(service, owner, "Created later"); service.leave(owner);
        var listed = update(service, owner).savedRooms();
        assertEquals(owned.id(), listed.getFirst().room()); assertEquals(other, listed.getLast().owner());
        assertTrue(update(service, member).savedRooms().isEmpty());
        var restarted = new RoomService(); restarted.restoreRooms(service.persistentRooms());
        assertTrue(update(restarted, member).invitations().isEmpty()); assertTrue(update(restarted, member).savedRooms().isEmpty());
        assertEquals(2, update(restarted, owner).savedRooms().size());
    }

    @Test void onlyCreatorMayDissolveAndDissolveEvictsEveryoneAndRevokesAllSavedAccess() {
        var service = new RoomService(); var original = create(service, owner, "Dissolve");
        command(service, owner, INVITE, 0, member); join(service, member, service.current(owner));
        command(service, owner, DJ, 1, member);
        denied("owner_permission", () -> dissolve(service, member, original));
        denied("owner_permission", () -> dissolve(service, other, original));
        denied("stale", () -> service.execute(owner, "Owner",
                new RoomCommand(DISSOLVE, original.id(), original.epoch(), original.revision(), "", 0), null, now += 1_000));
        long savedRevision = service.persistenceRevision(); dissolve(service, owner, original);
        assertNull(service.current(owner)); assertNull(service.current(member)); assertTrue(service.snapshots().isEmpty());
        assertTrue(service.persistentRooms().isEmpty()); assertTrue(service.persistenceRevision() > savedRevision);
        assertTrue(update(service, owner).savedRooms().isEmpty()); assertTrue(update(service, member).savedRooms().isEmpty());
        denied("missing", () -> join(service, member, original));
    }

    @Test void lobbyDissolutionDoesNotInterruptCreatorsOtherActiveGroup() {
        var service = new RoomService(); var inactive = create(service, owner, "Old"); service.leave(owner);
        var active = create(service, owner, "Current");
        dissolve(service, owner, inactive);
        assertEquals(active.id(), service.current(owner).id()); assertEquals(1, service.persistentRooms().size());
    }

    @Test void persistenceRevisionTracksDurableEditsButNotHeartbeatsOrDepartures() {
        var service = new RoomService(); create(service, owner, "Revision");
        long created = service.persistenceRevision(); assertTrue(created > 0);
        command(service, owner, INVITE, 0, member); assertEquals(created, service.persistenceRevision());
        join(service, member, service.current(owner)); assertTrue(service.persistenceRevision() > created);
        long joined = service.persistenceRevision(); command(service, owner, REQUESTER, 1, member);
        assertTrue(service.persistenceRevision() > joined);
        long permission = service.persistenceRevision(); command(service, member, ADD, 42, null);
        assertTrue(service.persistenceRevision() > permission);
        long added = service.persistenceRevision(); command(service, owner, PLAY, 0, null);
        assertTrue(service.persistenceRevision() > added);
        long selected = service.persistenceRevision(); service.tick(now); service.disconnect(member); service.disconnect(owner);
        assertEquals(selected, service.persistenceRevision());
    }

    @Test void historicalMembershipMayOutnumberSimultaneousListenersAndStillRejoin() {
        var service = new RoomService(); create(service, owner, "History");
        var history = new ArrayList<UUID>();
        for (int i = 0; i < RoomService.MAX_MEMBERS + 1; i++) {
            var guest = UUID.randomUUID(); history.add(guest);
            command(service, owner, INVITE, 0, guest); join(service, guest, service.current(owner)); service.leave(guest);
        }
        assertEquals(1, service.current(owner).members().size());
        assertEquals(history.size() + 1, service.persistentRooms().getFirst().members().size());
        for (var guest : history) assertEquals(1, update(service, guest).savedRooms().size());
        join(service, history.getFirst(), update(service, history.getFirst()).savedRooms().getFirst());
        assertNotNull(service.current(history.getFirst()));
    }

    @Test void restoreValidatesAtomicallyAndMayBeCombinedWithIndependentTeamPlaylistRestore() {
        var service = new RoomService(); create(service, owner, "Saved"); var saved = service.persistentRooms().getFirst();
        var restarted = new RoomService();
        assertThrows(IllegalArgumentException.class, () -> restarted.restoreRooms(List.of(saved, saved)));
        assertTrue(restarted.snapshots().isEmpty()); assertTrue(restarted.persistentRooms().isEmpty());
        restarted.restoreRooms(List.of(saved));
        var team = new RoomService.TeamQueue(UUID.randomUUID(), List.of(new SongId(42)), 0, PlaybackMode.REPEAT_ALL);
        restarted.restoreTeamQueues(List.of(team)); assertEquals(List.of(team), restarted.teamQueues());
        assertThrows(IllegalArgumentException.class, () -> new RoomService.PersistentRoom(UUID.randomUUID(), "No owner", owner,
                List.of(new RoomSnapshot.Member(member, "Member", false)), List.of(), -1, PlaybackMode.SEQUENTIAL));
    }
}
