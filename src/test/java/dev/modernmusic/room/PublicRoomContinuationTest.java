package dev.modernmusic.room;

import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicRoomContinuationTest {
    final UUID admin = UUID.randomUUID(), listener = UUID.randomUUID(), other = UUID.randomUUID();
    final Set<UUID> administrators = new HashSet<>(Set.of(admin));
    final RoomService service = new RoomService(TeamDirectory.ABSENT, () -> 2, administrators::contains);
    long now = 1_000;

    PublicRoomContinuationTest() { service.openPublicRoom(); join(admin); join(listener); }
    RoomSnapshot room() { return service.current(listener); }
    void join(UUID player) {
        var room = service.snapshots().get(0);
        service.execute(player, "Player", new RoomCommand(JOIN, room.id(), room.epoch(), -1, "", 0), null, now += 1_000);
    }
    void command(UUID sender, RoomCommand.Action action, long value, UUID target) {
        var room = room();
        service.execute(sender, "Player", new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value), target, now += 1_000);
    }
    void prepare() {
        command(admin, ADD, 1, null); command(admin, ADD, 2, null); command(admin, ADD, 3, null);
        command(admin, PLAY, 0, null);
    }
    RoomDurationReport report(long duration) {
        var room = room();
        return new RoomDurationReport(room.id(), room.epoch(), room.playbackRevision(), room.songs().get(room.index()), duration);
    }
    boolean report(UUID sender, RoomDurationReport report) { return service.reportDuration(sender, report, now += 1_000); }
    void denied(String reason, Runnable action) {
        assertEquals("modernmusic.room.error." + reason, assertThrows(RoomService.Rejected.class, action::run).key);
    }

    @Test void listenersContinueMultipleSongsAfterAllControllersLeaveWithoutReceivingControlRights() {
        prepare(); assertTrue(report(admin, report(10_000)));
        service.disconnect(admin);
        now = room().anchorMs() + 10_001; assertTrue(service.tick(now));
        assertEquals(1, room().index()); assertEquals(0, room().durationMs());
        assertTrue(room().canReportDuration(listener));
        assertFalse(room().canControl(listener)); assertFalse(room().canRequest(listener)); assertFalse(room().canManage(listener));
        var before = room(); assertTrue(report(listener, report(10_000)));
        assertEquals(before.playbackRevision(), room().playbackRevision());
        assertEquals(before.state(), room().state()); assertEquals(before.anchorMs(), room().anchorMs());
        assertEquals(before.positionMs(), room().positionMs()); assertEquals(before.songs(), room().songs());
        for (var action : List.of(PLAY, PAUSE, STOP, SEEK, NEXT, MODE, DURATION, REMOVE, CLEAR))
            denied("permission", () -> command(listener, action, 1, null));
        now = Math.max(now, room().anchorMs() + 10_001); assertTrue(service.tick(now));
        assertEquals(2, room().index()); assertTrue(report(listener, report(10_000)));
        now = room().anchorMs() + 10_001; assertTrue(service.tick(now));
        assertEquals(RoomSnapshot.State.STOPPED, room().state());
    }

    @Test void reportsCannotReplaceKnownDurationOrTargetAnOldSongOrPlayback() {
        prepare(); var first = report(10_000);
        service.disconnect(admin);
        assertTrue(report(listener, first));
        long revision = room().revision();
        assertFalse(report(listener, report(20_000)));
        assertEquals(10_000, room().durationMs()); assertEquals(revision, room().revision());
        join(admin); command(admin, NEXT, 0, null); service.disconnect(admin);
        denied("stale", () -> report(listener, first));
        var current = report(10_000);
        denied("stale", () -> report(listener, new RoomDurationReport(current.room(), current.epoch(),
                current.playbackRevision(), first.song(), 10_000)));
        join(admin); command(admin, PLAY, 0, null); service.disconnect(admin);
        assertEquals(first.song(), room().songs().get(room().index()));
        denied("stale", () -> report(listener, first)); // Same song, a different playback.
        assertTrue(report(listener, report(10_000)));
    }

    @Test void authorityPresenceRevocationAndMembershipAreCheckedWhenReportArrives() {
        prepare(); var unknown = report(10_000);
        assertFalse(room().canReportDuration(listener)); denied("permission", () -> report(listener, unknown));
        join(other); command(admin, DJ, 1, other); service.disconnect(admin);
        assertFalse(room().canReportDuration(listener)); denied("permission", () -> report(listener, report(10_000)));
        service.disconnect(other); assertTrue(room().canReportDuration(listener));
        administrators.add(listener); assertTrue(room().canControl(listener));
        administrators.clear(); assertFalse(room().canControl(listener)); assertTrue(room().canReportDuration(listener));
        var current = report(10_000);
        denied("member", () -> report(other, current));
        denied("stale", () -> report(listener, new RoomDurationReport(current.room(), UUID.randomUUID(),
                current.playbackRevision(), current.song(), 10_000)));
        join(admin); administrators.add(admin); // An administrator returning before receipt restores the restriction.
        denied("permission", () -> report(listener, current));
    }

    @Test void stoppedAndPrivateRoomsNeverGiveOrdinaryListenersMetadataAuthority() {
        prepare(); command(admin, STOP, 0, null); service.disconnect(admin);
        assertFalse(room().canReportDuration(listener)); denied("input", () -> report(listener, report(10_000)));
        var privateService = new RoomService();
        privateService.execute(admin, "Admin", new RoomCommand(CREATE, null, null, -1, "Private", 0), null, 1_000);
        var created = privateService.current(admin);
        privateService.execute(admin, "Admin", new RoomCommand(INVITE, created.id(), created.epoch(), created.revision(), "", 0), listener, 2_000);
        privateService.execute(listener, "Listener", new RoomCommand(JOIN, created.id(), created.epoch(), -1, "", 0), null, 3_000);
        var current = privateService.current(admin);
        privateService.execute(admin, "Admin", new RoomCommand(ADD, current.id(), current.epoch(), current.revision(), "", 1), null, 4_000);
        current = privateService.current(admin);
        privateService.execute(admin, "Admin", new RoomCommand(PLAY, current.id(), current.epoch(), current.revision(), "", 0), null, 5_000);
        privateService.disconnect(admin);
        var playing = privateService.current(listener);
        assertFalse(playing.canReportDuration(listener));
        denied("permission", () -> privateService.reportDuration(listener, new RoomDurationReport(playing.id(), playing.epoch(),
                playing.playbackRevision(), "1", 10_000), 6_000));
    }
}
