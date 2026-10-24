package dev.modernmusic.room;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamRoomTest {
    final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), other = UUID.randomUUID();
    final UUID team = UUID.randomUUID(), otherTeam = UUID.randomUUID();
    final Map<UUID, TeamDirectory.Party> parties = new HashMap<>();
    TeamDirectory.State state = TeamDirectory.State.READY;
    int partyReads;
    final RoomService rooms = new RoomService(new TeamDirectory() {
        public State state() { return state; }
        public Optional<Party> party(UUID id) { partyReads++; return Optional.ofNullable(parties.get(id)); }
    });
    long now = 10_000;
    TeamRoomTest() {
        parties.put(owner, party(team, owner, true));
        parties.put(member, party(team, owner, false));
        parties.put(other, party(otherTeam, other, true));
    }
    TeamDirectory.Party party(UUID id, UUID leader, boolean control) { return new TeamDirectory.Party(id, "Party", leader); }
    void join(UUID player) {
        rooms.execute(player, player.equals(owner) ? "Owner" : "Member", new RoomCommand(JOIN_TEAM, null, null, -1, "", 0), null, now += 1_000);
    }
    void command(UUID player, RoomCommand.Action action, long value) {
        var room = rooms.current(player);
        raw(player, room, action, value);
    }
    void raw(UUID player, RoomSnapshot room, RoomCommand.Action action, long value) {
        rooms.execute(player, "Player", new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), member, now += 1_000);
    }
    void rejected(String reason, Runnable command) {
        assertEquals("modernmusic.room.error." + reason, assertThrows(RoomService.Rejected.class, command::run).key);
    }
    @Test void optionalDependencyAndManagerReadinessDoNotBreakGroups() {
        state = TeamDirectory.State.NOT_INSTALLED;
        assertEquals(TeamStatus.State.NOT_INSTALLED, rooms.teamStatus(owner).state());
        rejected("team_not_installed", () -> join(owner));
        state = TeamDirectory.State.UNAVAILABLE;
        assertEquals(TeamStatus.State.UNAVAILABLE, rooms.teamStatus(owner).state());
        rejected("team_unavailable", () -> join(owner));
        rooms.execute(owner, "Owner", new RoomCommand(CREATE, null, null, -1, "Group", 0), null, now += 1_000);
        command(owner, ADD, 42); command(owner, PLAY, 0);
        assertFalse(rooms.current(owner).isTeamRoom());
        assertEquals(RoomSnapshot.State.PLAYING, rooms.current(owner).state());
    }
    @Test void availabilityDoesNotAutoJoinAndNonPartyCannotJoin() {
        assertEquals(TeamStatus.State.READY, rooms.teamStatus(owner).state());
        assertNull(rooms.current(owner)); assertTrue(rooms.snapshots().isEmpty());
        parties.remove(member);
        assertEquals(TeamStatus.State.NO_PARTY, rooms.teamStatus(member).state());
        rejected("team_required", () -> join(member));
    }
    @Test void partyMapsToOneRoomAndForgedTeamIdsCannotSelectAnotherParty() {
        join(owner); join(member); join(other);
        assertEquals(rooms.current(owner).id(), rooms.current(member).id());
        assertNotEquals(rooms.current(owner).id(), rooms.current(other).id());
        assertEquals(team, rooms.current(owner).teamId());
        var foreign = rooms.current(other); command(member, LEAVE, 0);
        raw(member, foreign, JOIN_TEAM, 0);
        assertEquals(team, rooms.current(member).teamId());
        assertNotEquals(foreign.id(), rooms.current(member).id());
        rejected("member", () -> raw(member, foreign, ADD, 42));
    }
    @Test void ordinaryMemberMayJoinFirstButNeverBecomesOwnerOrController() {
        join(member); var room = rooms.current(member);
        assertEquals(owner, room.owner()); assertFalse(room.canControl(member)); assertFalse(room.canControl(owner));
        rejected("request_permission", () -> command(member, ADD, 42)); rejected("permission", () -> command(member, PLAY, 0));
        join(owner); assertTrue(rooms.current(owner).canControl(owner));
        command(owner, ADD, 42); command(owner, PLAY, 0); command(owner, LEAVE, 0);
        assertFalse(rooms.current(member).canControl(member)); assertEquals(owner, rooms.current(member).owner());
    }
    @Test void onlyOwnerAppointsDjsAndFtbRankChangesDoNotOverrideThem() {
        join(owner); join(member); command(owner, REQUESTER, 1); command(member, ADD, 42);
        parties.put(member, party(team, owner, true)); rooms.tick(now);
        rejected("permission", () -> command(member, PLAY, 0));
        command(owner, DJ, 1);
        command(member, PLAY, 0); var old = rooms.current(member);
        parties.put(member, party(team, owner, false));
        rooms.tick(now);
        assertTrue(rooms.current(member).canControl(member));
        command(member, PAUSE, 0);
        assertEquals(RoomSnapshot.State.PAUSED, rooms.current(owner).state());
        command(member, PAUSE, 0);
        assertEquals(RoomSnapshot.State.PLAYING, rooms.current(owner).state());
        var resumed = rooms.current(owner);
        assertTrue(resumed.anchorMs() >= now && resumed.anchorMs() <= now + 250);
        assertEquals(old.playbackRevision(), resumed.playbackRevision());
        assertSame(resumed, rooms.current(member));
        rejected("owner_permission", () -> command(member, DJ, 1));
        command(owner, DJ, 0);
        rejected("permission", () -> raw(member, old, STOP, 0));
        assertEquals(RoomSnapshot.State.PLAYING, rooms.current(owner).state());
        assertTrue(rooms.current(owner).revision() > old.revision());
    }
    @Test void teamOwnershipTransfersWithoutPromotingListeningOrder() {
        join(owner); join(member);
        parties.put(owner, party(team, member, false)); parties.put(member, party(team, member, true));
        rooms.tick(now); var room = rooms.current(owner);
        assertEquals(member, room.owner()); assertFalse(room.canControl(owner)); assertTrue(room.canControl(member));
        command(member, ADD, 42); command(member, PLAY, 0);
    }
    @Test void invitationAndKickingCannotBypassFtbMembershipAuthority() {
        join(owner); join(member); var room = rooms.current(owner);
        for (var action : List.of(INVITE, KICK, DISSOLVE)) rejected("team_managed", () -> command(owner, action, 1));
        rejected("team_managed", () -> raw(other, room, JOIN, 0));
        assertTrue(rooms.invited(room.id(), now).isEmpty());
    }
    @Test void leavingPartyRevokesCommandsAndEvenCachedImportReceiptsImmediately() {
        join(owner); join(member); command(owner, REQUESTER, 1); var room = rooms.current(member);
        var request = new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(), List.of("42"), 1);
        assertEquals(1, rooms.append(member, request, now).imported());
        parties.remove(member);
        rejected("member", () -> rooms.append(member, request, now += 1_000));
        rejected("member", () -> raw(member, room, ADD, 43));
        assertNull(rooms.current(member)); assertEquals(List.of("42"), rooms.current(owner).songs());
    }
    @Test void switchingPartiesRequiresFreshExplicitJoinAndKeepsOtherQueueIsolated() {
        join(owner); join(member); join(other); command(owner, ADD, 42); command(other, ADD, 43);
        parties.put(member, party(otherTeam, other, false));
        assertTrue(rooms.reconcileTeams()); assertNull(rooms.current(member));
        join(member); assertEquals(List.of("43"), rooms.current(member).songs());
        assertEquals(List.of("42"), rooms.current(owner).songs());
    }
    @Test void disbandAndManagerShutdownRemoveRoomsWhileOrdinaryGroupsSurvive() {
        join(owner); join(member);
        rooms.execute(other, "Other", new RoomCommand(CREATE, null, null, -1, "Group", 0), null, now);
        parties.clear(); rooms.tick(now);
        assertNull(rooms.current(owner)); assertNull(rooms.current(member)); assertNotNull(rooms.current(other));
        parties.put(owner, party(team, owner, true)); join(owner);
        state = TeamDirectory.State.UNAVAILABLE; rooms.tick(now);
        assertNull(rooms.current(owner)); assertEquals(1, rooms.snapshots().size());
    }
    @Test void disconnectRequiresRejoinButLastListenerPreservesPlaylist() {
        join(owner); join(member); command(owner, ADD, 42); var original = rooms.current(owner);
        rooms.disconnect(owner); assertNull(rooms.current(owner)); assertFalse(rooms.current(member).canControl(member));
        join(owner); assertEquals(original.id(), rooms.current(owner).id());
        command(owner, LEAVE, 0); command(member, LEAVE, 0);
        assertTrue(rooms.snapshots().isEmpty()); assertTrue(parties.containsKey(member));
        join(member); assertNotEquals(original.id(), rooms.current(member).id()); assertEquals(List.of("42"), rooms.current(member).songs());
        assertEquals(RoomSnapshot.State.STOPPED, rooms.current(member).state());
    }
    @Test void restartRestoresTeamPlaylistAndModeWithoutRestoringMembersDjsOrPlayback() {
        join(owner); join(member); command(owner, DJ, 1);
        command(member, ADD, 42); command(member, ADD, 43); command(member, PLAY, 1); command(member, MODE, 0);
        var old = rooms.current(owner);
        var restarted = new RoomService(new TeamDirectory() {
            public State state() { return State.READY; }
            public Optional<Party> party(UUID id) { return Optional.ofNullable(parties.get(id)); }
        });
        restarted.restoreTeamQueues(rooms.teamQueues());
        assertNull(restarted.current(member));
        restarted.execute(member, "Member", new RoomCommand(JOIN_TEAM, null, null, -1, "", 0), null, now);
        var restored = restarted.current(member);
        assertEquals(old.songs(), restored.songs()); assertEquals(old.index(), restored.index()); assertEquals(old.mode(), restored.mode());
        assertEquals(owner, restored.owner()); assertFalse(restored.canControl(member));
        assertEquals(RoomSnapshot.State.STOPPED, restored.state()); assertEquals(0, restored.positionMs());
        assertNotEquals(old.epoch(), restored.epoch());
        restarted.execute(owner, "Owner", new RoomCommand(JOIN_TEAM, null, null, -1, "", 0), null, now);
        var current = restarted.current(owner);
        restarted.execute(owner, "Owner", new RoomCommand(CLEAR, current.id(), current.epoch(), current.revision(), "", 0), null, now);
        assertTrue(restarted.teamQueues().getFirst().songs().isEmpty());
    }
    @Test void groupAndTeamMembershipAreMutuallyExclusive() {
        rooms.execute(owner, "Owner", new RoomCommand(CREATE, null, null, -1, "Group", 0), null, now);
        rejected("already_joined", () -> join(owner)); command(owner, LEAVE, 0); join(owner);
        rejected("already_joined", () -> rooms.execute(owner, "Owner", new RoomCommand(CREATE, null, null, -1, "Group", 0), null, now));
    }
    @Test void teamNamesAndProtocolRemainBoundedAndContainNoCredentials() {
        parties.put(owner, new TeamDirectory.Party(team, "名".repeat(80), owner)); join(owner);
        var update = new RoomUpdate(1, rooms.current(owner), List.of(), "", rooms.teamStatus(owner));
        assertEquals(40, update.team().name().length());
        assertEquals(update, RoomJson.decode(RoomJson.encode(update), RoomUpdate.class));
        assertTrue(update.room().isTeamRoom());
        assertThrows(RuntimeException.class, () -> RoomJson.decode("{\"sequence\":1,\"invitations\":[],\"message\":\"\",\"team\":{\"state\":\"UNKNOWN\",\"name\":\"\"}}", RoomUpdate.class));
    }
    @Test void renameUpdatesRoomWithoutResettingQueueOrPlayback() {
        join(owner); command(owner, ADD, 42); command(owner, PLAY, 0); var before = rooms.current(owner);
        parties.put(owner, new TeamDirectory.Party(team, "Renamed", owner)); rooms.tick(now);
        var after = rooms.current(owner); assertEquals("Renamed", after.name()); assertTrue(after.revision() > before.revision());
        assertEquals(before.playbackRevision(), after.playbackRevision()); assertEquals(before.anchorMs(), after.anchorMs());
        assertEquals(before.songs(), after.songs());
    }
    @Test void teamCapacityAndRateLimitsMatchGroupLimits() {
        join(owner);
        for (int i = 1; i <= RoomService.MAX_MEMBERS; i++) {
            UUID id = UUID.randomUUID(); parties.put(id, party(team, owner, false));
            if (i < RoomService.MAX_MEMBERS) join(id); else rejected("full", () -> join(id));
        }
        now += 1_000; UUID id = UUID.randomUUID();
        for (int i = 0; i < 12; i++) rejected("team_required", () -> rooms.execute(id, "Unknown", new RoomCommand(JOIN_TEAM, null, null, -1, "", 0), null, now));
        rejected("rate", () -> rooms.execute(id, "Unknown", new RoomCommand(JOIN_TEAM, null, null, -1, "", 0), null, now));
    }
    @Test void batchReconcilesOnceAndRevocationPrecedesSnapshotDelivery() {
        join(owner); join(member);
        partyReads = 0;
        var batch = rooms.updatesFor(List.of(owner, member, other), now);
        assertEquals(5, partyReads, "Two live member checks plus one status lookup for each recipient");
        assertSame(batch.get(owner).room(), batch.get(member).room());
        assertNull(batch.get(other).room());
        parties.remove(member);
        var revoked = rooms.updatesFor(List.of(owner, member), now);
        assertNull(revoked.get(member).room());
        assertEquals(1, revoked.get(owner).room().members().size());
        assertEquals(TeamStatus.State.NO_PARTY, revoked.get(member).team().state());
    }
}
