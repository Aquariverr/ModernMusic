package dev.modernmusic.room;

import dev.modernmusic.domain.PlaybackMode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicRoomTest {
    final UUID admin = UUID.randomUUID(), listener = UUID.randomUUID(), other = UUID.randomUUID();
    final Set<UUID> administrators = new HashSet<>(Set.of(admin));
    final RoomService service = new RoomService(TeamDirectory.ABSENT, () -> 2, administrators::contains);
    long now;

    PublicRoomTest() { service.openPublicRoom(); }
    RoomUpdate update(RoomService rooms, UUID player) { return rooms.updatesFor(List.of(player), now).get(player); }
    RoomSnapshot room() { return service.snapshots().stream().filter(RoomSnapshot::isPublicRoom).findFirst().orElseThrow(); }
    void join(RoomService rooms, UUID player) {
        var entry = update(rooms, player).savedRooms().stream().filter(RoomUpdate.SavedRoom::isPublicRoom).findFirst().orElseThrow();
        rooms.execute(player, "Player", new RoomCommand(JOIN, entry.room(), entry.epoch(), -1, "", 0), null, now += 1_000);
    }
    void command(UUID player, RoomCommand.Action action, long value, UUID target) {
        var room = room();
        service.execute(player, "Player", new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), target, now += 1_000);
    }
    RoomImportRequest request(String song) {
        return new RoomImportRequest(UUID.randomUUID(), room().id(), room().epoch(), List.of(song), 1);
    }
    void denied(String reason, Runnable action) {
        assertEquals("modernmusic.room.error." + reason, assertThrows(RoomService.Rejected.class, action::run).key);
    }

    @Test void everyPlayerCanDiscoverAndJoinWithoutInvitesEvenWhenCreationIsDisabled() {
        var rooms = new RoomService(() -> 0); rooms.openPublicRoom(); rooms.openPublicRoom();
        assertEquals(1, rooms.snapshots().size());
        assertNull(update(rooms, listener).room()); // Discovery never opts anyone into listening.
        assertTrue(update(rooms, listener).invitations().isEmpty());
        assertTrue(update(rooms, listener).savedRooms().get(0).isPublicRoom());
        join(rooms, listener);
        assertFalse(rooms.current(listener).canManage(listener));
        assertFalse(rooms.current(listener).canRequest(listener));
        for (int i = 0; i < 40; i++) join(rooms, UUID.randomUUID());
        assertEquals(41, rooms.current(listener).members().size());
        var wire = update(rooms, listener);
        assertEquals(wire, RoomJson.decode(RoomJson.encode(wire), RoomUpdate.class));
        denied("already_joined", () -> join(rooms, listener));
    }

    @Test void administratorsGrantAndRevokeRequestAndDjPermissionsOnBothImportPaths() {
        join(service, admin); join(service, listener);
        assertTrue(room().canManage(admin)); assertTrue(room().canRequest(admin)); assertTrue(room().canControl(admin));
        denied("request_permission", () -> command(listener, ADD, 41, null));
        var request = request("42");
        denied("request_permission", () -> service.append(listener, request, now += 1_000));
        command(admin, REQUESTER, 1, listener);
        command(listener, ADD, 41, null);
        assertEquals(1, service.append(listener, request, now += 1_000).imported());
        for (var action : List.of(PLAY, PAUSE, STOP, SEEK, NEXT, MODE, DURATION, REMOVE, CLEAR))
            denied("permission", () -> command(listener, action, 0, null));
        command(admin, DJ, 1, listener);
        command(listener, PLAY, 0, null); command(listener, PAUSE, 0, null);
        assertEquals(RoomSnapshot.State.PAUSED, room().state());
        assertFalse(room().canManage(listener));
        denied("owner_permission", () -> command(listener, REQUESTER, 1, admin));
        command(admin, DJ, 0, listener);
        denied("request_permission", () -> service.append(listener, request, now += 1_000));
        denied("permission", () -> command(listener, CLEAR, 0, null));
    }

    @Test void opChangesInvalidateCachedSnapshotsAndAreEnforcedBeforeCommandsAndImports() {
        join(service, admin); join(service, listener);
        var before = room();
        administrators.remove(admin);
        // Send a command with the old snapshot without first asking for fresh state.
        denied("owner_permission", () -> service.execute(admin, "Player",
                new RoomCommand(DJ, before.id(), before.epoch(), before.revision(), "", 1), listener, now += 1_000));
        assertTrue(room().revision() > before.revision()); assertFalse(room().canManage(admin));
        administrators.add(listener);
        assertTrue(service.tick(now)); assertTrue(update(service, admin).room().canManage(listener));
        var request = request("43");
        assertEquals(1, service.append(listener, request, now += 1_000).imported());
        administrators.remove(listener);
        denied("request_permission", () -> service.append(listener, request, now += 1_000));
        assertFalse(service.current(listener).canControl(listener));
    }

    @Test void multipleAdministratorsCannotDemoteOrKickEachOtherAndPublicRoomCannotBeDissolved() {
        administrators.add(other);
        join(service, admin); join(service, other); join(service, listener);
        for (var action : List.of(DJ, REQUESTER, KICK)) denied("target", () -> command(admin, action, 0, other));
        assertTrue(room().canManage(other));
        assertFalse(room().allows(admin, DISSOLVE)); assertFalse(room().allows(admin, INVITE));
        denied("public_managed", () -> command(admin, DISSOLVE, 0, null));
        denied("public_managed", () -> command(admin, INVITE, 0, UUID.randomUUID()));
        command(admin, DJ, 1, listener); command(admin, KICK, 0, listener);
        assertNull(service.current(listener));
        join(service, listener); assertFalse(room().canRequest(listener));
        service.leave(admin);
        denied("member", () -> command(admin, REQUESTER, 1, listener));
        var entry = update(service, admin).savedRooms().get(0);
        denied("public_managed", () -> service.execute(admin, "Player",
                new RoomCommand(DISSOLVE, entry.room(), entry.epoch(), -1, "", 0), null, now += 1_000));
    }

    @Test void emptyRoomAndRestartPreserveQueueAndGrantsButNeverPersistAdministratorStatus() {
        join(service, admin); join(service, listener); join(service, other);
        command(admin, DJ, 1, listener); command(admin, REQUESTER, 1, other);
        command(listener, ADD, 42, null); command(admin, PLAY, 0, null);
        var old = room();
        service.disconnect(admin); service.disconnect(listener); service.disconnect(other);
        assertTrue(room().members().isEmpty()); assertEquals(RoomSnapshot.State.STOPPED, room().state());
        assertEquals(List.of("42"), room().songs());
        assertTrue(service.persistentRooms().get(0).members().stream().noneMatch(RoomSnapshot.Member::administrator));
        administrators.clear();
        var restarted = new RoomService(TeamDirectory.ABSENT, () -> 2, administrators::contains);
        restarted.restoreRooms(service.persistentRooms()); restarted.openPublicRoom();
        join(restarted, admin); join(restarted, listener); join(restarted, other);
        var restored = restarted.current(admin);
        assertEquals(old.id(), restored.id()); assertNotEquals(old.epoch(), restored.epoch());
        assertEquals(old.songs(), restored.songs()); assertEquals(old.index(), restored.index());
        assertEquals(RoomSnapshot.State.STOPPED, restored.state());
        assertFalse(restored.canManage(admin)); assertFalse(restored.canRequest(admin));
        assertTrue(restored.canControl(listener)); assertTrue(restored.canRequest(other)); assertFalse(restored.canControl(other));
        denied("stale", () -> restarted.execute(other, "Player",
                new RoomCommand(ADD, old.id(), old.epoch(), old.revision(), "", 43), null, now += 1_000));
    }

    @Test void publicAdministrationNeverGrantsControlOverPrivateRooms() {
        service.execute(other, "Owner", new RoomCommand(CREATE, null, null, -1, "Private", 0), null, now += 1_000);
        var privateRoom = service.current(other);
        service.execute(other, "Owner", new RoomCommand(INVITE, privateRoom.id(), privateRoom.epoch(), privateRoom.revision(), "", 0), admin, now += 1_000);
        service.execute(admin, "Player", new RoomCommand(JOIN, privateRoom.id(), privateRoom.epoch(), -1, "", 0), null, now += 1_000);
        assertFalse(service.current(admin).canManage(admin)); assertFalse(service.current(admin).canRequest(admin));
        assertTrue(update(service, other).savedRooms().get(0).isPublicRoom());
        assertEquals(privateRoom.id(), update(service, other).savedRooms().get(1).room());
        denied("already_joined", () -> join(service, admin));
    }

    @Test void publicRoomDoesNotConsumeGlobalOrPersonalRoomQuotas() {
        var rooms = new RoomService(() -> RoomService.MAX_ROOMS); rooms.openPublicRoom();
        for (int i = 0; i < RoomService.MAX_ROOMS; i++) {
            rooms.execute(admin, "Admin", new RoomCommand(CREATE, null, null, -1, "Private " + i, 0), null, now += 1_000);
            rooms.leave(admin);
        }
        assertEquals(65, rooms.persistentRooms().size()); assertEquals(65, update(rooms, admin).savedRooms().size());
        var restarted = new RoomService(); restarted.restoreRooms(rooms.persistentRooms()); restarted.openPublicRoom();
        assertEquals(65, restarted.snapshots().size()); join(restarted, listener);
        assertTrue(restarted.current(listener).isPublicRoom());
    }

    @Test void persistedPublicIdentityCannotBeClaimedByAPlayerOrStoreAdministratorGrants() {
        assertThrows(IllegalArgumentException.class, () -> new RoomService.PersistentRoom(RoomService.PUBLIC_ROOM_ID, "Public", admin,
                List.of(new RoomSnapshot.Member(admin, "Admin", true)), List.of(), -1, PlaybackMode.SEQUENTIAL));
        assertThrows(IllegalArgumentException.class, () -> new RoomService.PersistentRoom(RoomService.PUBLIC_ROOM_ID, "Public", RoomService.SERVER_OWNER,
                List.of(new RoomSnapshot.Member(admin, "Admin", false, false, true)), List.of(), -1, PlaybackMode.SEQUENTIAL));
    }

    @Test void publicMembershipIsBoundedAndAReleasedSlotCanBeReused() {
        var initial = room(); var joined = new ArrayList<UUID>();
        for (int i = 0; i < RoomService.MAX_PUBLIC_MEMBERS; i++) {
            UUID player = new UUID(123, i + 1); joined.add(player);
            service.execute(player, "Player", new RoomCommand(JOIN, initial.id(), initial.epoch(), -1, "", 0), null, now += 1_000);
        }
        assertEquals(RoomService.MAX_PUBLIC_MEMBERS, room().members().size());
        long revision = room().revision();
        denied("public_full", () -> join(service, listener));
        assertNull(service.current(listener)); assertEquals(revision, room().revision());
        service.disconnect(joined.get(0)); join(service, listener);
        assertEquals(RoomService.MAX_PUBLIC_MEMBERS, room().members().size());
        assertTrue(service.current(listener).isPublicRoom());
        // The online cap must not truncate historical membership grants on disk.
        assertEquals(RoomService.MAX_PUBLIC_MEMBERS + 1, service.persistentRooms().get(0).members().size());
        var restored = new RoomService(); restored.restoreRooms(service.persistentRooms());
        assertTrue(restored.snapshots().get(0).members().isEmpty());
        assertEquals(RoomService.MAX_PUBLIC_MEMBERS + 1, restored.persistentRooms().get(0).members().size());
    }
}
