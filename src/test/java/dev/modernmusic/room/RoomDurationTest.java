package dev.modernmusic.room;

import dev.modernmusic.audio.AudioFiles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.modernmusic.room.RoomCommand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomDurationTest {
    final UUID admin = UUID.randomUUID(), listener = UUID.randomUUID(), outsider = UUID.randomUUID();
    final Set<UUID> operators = new HashSet<>(Set.of(admin));
    final RoomService service = new RoomService(TeamDirectory.ABSENT, () -> 2, operators::contains);
    long now = 10_000;

    RoomDurationTest() {
        service.openPublicRoom(); join(admin); join(listener);
        var room = room();
        service.append(admin, new RoomImportRequest(UUID.randomUUID(), room.id(), room.epoch(),
                List.of("local:server_" + "a".repeat(32), "42", "43"), 3), now += 1_000);
        command(admin, PLAY, 0);
    }
    RoomSnapshot room() { return service.current(listener); }
    void join(UUID member) {
        var saved = service.updatesFor(List.of(member), now).get(member).savedRooms().getFirst();
        execute(member, new RoomCommand(JOIN, saved.room(), saved.epoch(), -1, "", 0));
    }
    void execute(UUID sender, RoomCommand command) { service.execute(sender, "Member", command, null, now += 1_000); }
    void command(UUID sender, RoomCommand.Action action, long value) {
        var room = room(); execute(sender, new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", value));
    }
    RoomCommand report(long duration) {
        var room = room();
        return new RoomCommand(DURATION, room.id(), room.epoch(), room.playbackRevision(), room.songs().get(room.index()), duration);
    }
    void reject(UUID sender, RoomCommand command, String key) {
        assertEquals("modernmusic.room.error." + key, assertThrows(RoomService.Rejected.class, () -> execute(sender, command)).key);
    }
    @ParameterizedTest @ValueSource(strings = {"disconnect", "deop", "muted"})
    void listenerKeepsAdvancingWhenTheLastControllerCannotReport(String reason) {
        execute(admin, report(1_000));
        if (reason.equals("disconnect")) service.disconnect(admin);
        if (reason.equals("deop")) { operators.clear(); service.current(listener); }
        // A muted controller remains present, but supplies no timing for the following tracks.
        for (int index = 1; index <= 2; index++) {
            now = Math.max(now, room().anchorMs()) + 1_000;
            assertTrue(service.tick(now)); assertEquals(index, room().index());
            assertEquals(0, room().durationMs()); assertFalse(room().canControl(listener));
            execute(listener, report(1_000)); assertEquals(1_000, room().durationMs());
        }
        now = Math.max(now, room().anchorMs()) + 1_000; service.tick(now);
        assertEquals(RoomSnapshot.State.STOPPED, room().state());
    }
    @Test void metadataSurvivesMembershipAndQueueChangesWithoutGrantingControls() {
        var report = report(1_000);
        join(outsider); command(admin, ADD, 44);
        execute(listener, report); assertEquals(1_000, room().durationMs());
        assertTrue(room().allows(listener, DURATION)); assertFalse(room().canRequest(listener));
        for (var action : List.of(PLAY, PAUSE, STOP, SEEK, NEXT, MODE, REMOVE, CLEAR)) {
            var room = room(); reject(listener, new RoomCommand(action, room.id(), room.epoch(), room.revision(), "", 0), "permission");
        }
    }
    @Test void duplicateOrConflictingReportsCannotOverwriteTheEstablishedClock() {
        var first = report(2_000); var conflicting = report(3_000);
        execute(listener, first); var established = room();
        execute(admin, first); execute(listener, conflicting);
        assertSame(established, room()); assertEquals(2_000, room().durationMs());
    }
    @Test void stalePlaybackWrongSongOldEpochAndNonMembersCannotSupplyTiming() {
        var old = report(1_000);
        command(admin, PLAY, 0); // Replaying the same song must reject the previous playback's report.
        reject(listener, old, "stale");
        var current = report(1_000);
        reject(listener, new RoomCommand(DURATION, current.room(), current.epoch(), current.revision(), "42", 1_000), "stale");
        reject(listener, new RoomCommand(DURATION, current.room(), UUID.randomUUID(), current.revision(), current.text(), 1_000), "stale");
        reject(outsider, current, "member");
        command(admin, STOP, 0); reject(listener, report(1_000), "input");
        assertEquals(0, room().durationMs());
    }
    @ParameterizedTest @ValueSource(ints = {1000, 8000})
    void acceptedLongWavCanSeekPastOneHourAndAdvance(int sampleRate) {
        int dataBytes = 3_600 * sampleRate + sampleRate / 1000;
        var wave = ByteBuffer.allocate(44 + dataBytes + (dataBytes & 1)).order(ByteOrder.LITTLE_ENDIAN);
        wave.put(new byte[]{'R','I','F','F'}).putInt(wave.capacity() - 8).put(new byte[]{'W','A','V','E'});
        wave.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        wave.putInt(sampleRate).putInt(sampleRate).putShort((short) 1).putShort((short) 8);
        wave.put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        byte[] bytes = wave.array();
        var metadata = AudioFiles.inspect(bytes, "long.wav");
        var decoded = AudioFiles.decode(bytes, "long.wav");
        assertEquals(3_600_001, metadata.durationMs()); assertEquals(metadata.durationMs(), decoded.durationMs());
        execute(listener, report(decoded.durationMs()));
        command(admin, PAUSE, 0); command(admin, SEEK, 3_600_001);
        assertEquals(3_600_001, room().positionAt(now));
        command(admin, SEEK, 3_600_000); command(admin, PAUSE, 0);
        assertFalse(service.tick(room().anchorMs()));
        assertTrue(service.tick(room().anchorMs() + 1));
        assertEquals(1, room().index()); assertEquals(RoomSnapshot.State.PLAYING, room().state());
    }
    @Test void durationAndSeekKeepFiniteBoundsDerivedFromTheDecoder() {
        for (long value : new long[]{0, -1, AudioFiles.MAX_DURATION_MS + 1, Long.MAX_VALUE}) reject(listener, report(value), "input");
        execute(listener, report(AudioFiles.MAX_DURATION_MS));
        for (long value : new long[]{-1, AudioFiles.MAX_DURATION_MS + 1, Long.MAX_VALUE}) {
            var room = room(); reject(admin, new RoomCommand(SEEK, room.id(), room.epoch(), room.revision(), "", value), "input");
        }
        command(admin, SEEK, AudioFiles.MAX_DURATION_MS);
        assertEquals(AudioFiles.MAX_DURATION_MS, room().positionMs());
    }
}
