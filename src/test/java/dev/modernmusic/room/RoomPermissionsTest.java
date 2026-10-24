package dev.modernmusic.room;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomPermissionsTest {
    static final class Fixture {
        final UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), stranger = UUID.randomUUID();
        final RoomService service;
        long now;
        Fixture(boolean team) {
            var party = new TeamDirectory.Party(UUID.randomUUID(), "Party", owner);
            service = new RoomService(team ? new TeamDirectory() {
                public State state() { return State.READY; }
                public Optional<Party> party(UUID player) {
                    return player.equals(owner) || player.equals(member) ? Optional.of(party) : Optional.empty();
                }
            } : TeamDirectory.ABSENT);
            service.execute(owner, "Owner", new RoomCommand(team ? JOIN_TEAM : CREATE, null, null, -1, "Group", 0), null, now += 1_000);
            if (!team) command(owner, INVITE, 0, member);
            var room = room();
            service.execute(member, "Member", new RoomCommand(team ? JOIN_TEAM : JOIN, room.id(), room.epoch(), -1, "", 0), null, now += 1_000);
        }
        RoomSnapshot room() { return service.current(owner); }
        void command(UUID sender, RoomCommand.Action action, long value, UUID target) {
            var room = room();
            service.execute(sender, "Player", new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), target, now += 1_000);
        }
        void role(RoomCommand.Action action, long value) { command(owner, action, value, member); }
        RoomImportRequest request(String song) {
            return new RoomImportRequest(UUID.randomUUID(), room().id(), room().epoch(), List.of(song), 1);
        }
        RoomImportResult append(RoomImportRequest request) { return service.append(member, request, now += 1_000); }
        void denied(String reason, Runnable action) {
            assertEquals("modernmusic.room.error." + reason, assertThrows(RoomService.Rejected.class, action::run).key);
        }
        void permissions(boolean request, boolean control) {
            var room = RoomJson.decode(RoomJson.encode(room()), RoomSnapshot.class);
            assertEquals(request, room.canRequest(member)); assertEquals(control, room.canControl(member));
            assertFalse(room.canManage(member));
            assertTrue(room.canRequest(owner)); assertTrue(room.canControl(owner)); assertTrue(room.canManage(owner));
            assertFalse(room.canRequest(stranger)); assertFalse(room.canControl(stranger)); assertFalse(room.canManage(stranger));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fourLevelsEnforceRequestsPlaybackAndOwnerOnlyGrants(boolean team) {
        var f = new Fixture(team);
        f.permissions(false, false);
        var initial = f.room();
        var batch = f.request("42");
        f.denied("request_permission", () -> f.command(f.member, ADD, 41, null));
        f.denied("request_permission", () -> f.append(batch));
        assertEquals(initial, f.room());

        f.role(REQUESTER, 1); f.permissions(true, false);
        f.command(f.member, ADD, 41, null); assertEquals(1, f.append(batch).imported());
        assertEquals(List.of("41", "42"), f.room().songs());
        for (var action : List.of(PLAY, PAUSE, STOP, SEEK, NEXT, MODE, DURATION, REMOVE, CLEAR)) {
            f.denied("permission", () -> f.command(f.member, action, 0, null));
        }
        for (var action : List.of(DJ, REQUESTER)) {
            f.denied("owner_permission", () -> f.command(f.member, action, 1, f.member));
            f.denied("owner_permission", () -> f.command(f.member, action, 1, f.owner));
        }
        f.role(DJ, 1); f.permissions(true, true);
        f.command(f.member, ADD, 43, null); assertEquals(1, f.append(f.request("44")).imported());
        f.command(f.member, PLAY, 0, null); f.command(f.member, PAUSE, 0, null);
        assertEquals(RoomSnapshot.State.PAUSED, f.room().state());
        for (var action : List.of(DJ, REQUESTER))
            f.denied("owner_permission", () -> f.command(f.member, action, 1, f.member));

        f.role(REQUESTER, 1); f.permissions(true, false);
        f.denied("permission", () -> f.command(f.member, STOP, 0, null));
        f.command(f.member, ADD, 45, null);
        f.role(DJ, 0); f.permissions(false, false);
        var revoked = f.room();
        f.denied("request_permission", () -> f.command(f.member, ADD, 46, null));
        f.denied("request_permission", () -> f.append(batch)); // Even an acknowledged request needs current permission.
        f.denied("request_permission", () -> f.append(f.request("47")));
        assertEquals(revoked, f.room());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void grantsCannotChangeOwnerAndOnlyTeamRejoinResetsRequestPermission(boolean team) {
        var f = new Fixture(team);
        f.denied("target", () -> f.command(f.owner, REQUESTER, 1, f.owner));
        f.denied("target", () -> f.command(f.owner, REQUESTER, 1, f.stranger));
        f.denied("input", () -> f.role(REQUESTER, 2));
        f.role(REQUESTER, 1);
        var old = f.room();
        f.command(f.member, LEAVE, 0, null);
        f.denied("member", () -> f.append(new RoomImportRequest(UUID.randomUUID(), old.id(), old.epoch(), List.of("42"), 1)));
        if (!team) f.command(f.owner, INVITE, 0, f.member);
        f.command(f.member, team ? JOIN_TEAM : JOIN, 0, null);
        f.permissions(!team, false);
        if (team) f.denied("request_permission", () -> f.command(f.member, ADD, 42, null));
        else f.command(f.member, ADD, 42, null);
    }
}
