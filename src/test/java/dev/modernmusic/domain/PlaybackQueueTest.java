package dev.modernmusic.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackQueueTest {
    @Test void immutableSnapshotsStayStableAcrossEditsAndAreReusedBetweenEdits() {
        var queue = new PlaybackQueue(); queue.add(new SongId(1));
        var first = queue.entries();
        for (int frame = 0; frame < 600; frame++) assertSame(first, queue.entries());
        queue.select(0); queue.mode(PlaybackMode.REPEAT_ALL);
        assertSame(first, queue.entries());
        queue.add(new SongId(2)); var second = queue.entries();
        assertEquals(java.util.List.of(new SongId(1)), first);
        assertEquals(java.util.List.of(new SongId(1), new SongId(2)), second);
        queue.remove(0); assertEquals(java.util.List.of(new SongId(2)), queue.entries());
        assertEquals(2, second.size());
        var restored = new java.util.ArrayList<>(java.util.List.of(new SongId(3)));
        queue.restore(restored, 0, PlaybackMode.SEQUENTIAL); restored.clear();
        var third = queue.entries(); assertEquals(java.util.List.of(new SongId(3)), third);
        queue.clear(); assertTrue(queue.entries().isEmpty()); assertEquals(1, third.size());
        assertThrows(UnsupportedOperationException.class, () -> third.add(new SongId(4)));
    }

    @Test void duplicateAddsDoNotChangeSelectionOrOrderAndRemovalAllowsReadding() {
        var queue = new PlaybackQueue(); queue.add(new SongId(1)); queue.add(new SongId(2)); queue.select(1);
        assertFalse(queue.add(new SongId(1))); assertFalse(queue.add(new SongId(2)));
        assertEquals(java.util.List.of(new SongId(1), new SongId(2)), queue.entries()); assertEquals(1, queue.index());
        queue.remove(0); assertTrue(queue.add(new SongId(1))); assertEquals(new SongId(2), queue.current().orElseThrow());
    }
    @Test void bulkSkipsExistingAndInternalDuplicatesThenStopsAtCapacity() {
        var queue = new PlaybackQueue(); for (int i = 1; i < 400; i++) queue.add(new SongId(i));
        var result = queue.append(java.util.List.of(new SongId(1), new SongId(500), new SongId(500), new SongId(501)));
        assertEquals(1, result.added()); assertEquals(2, result.skipped()); assertEquals(3, result.processed());
        assertEquals(400, queue.entries().size()); assertEquals(new SongId(500), queue.entries().getLast());
        assertEquals(new PlaybackQueue.AppendResult(0, 2), queue.append(java.util.List.of(new SongId(1), new SongId(500))));
    }
    @Test void repeatModesDistinguishNaturalFinishAndManualNext() {
        var queue = new PlaybackQueue(); queue.add(new SongId(1)); queue.add(new SongId(2)); queue.select(1);
        assertEquals(-1, queue.following(true));
        queue.mode(PlaybackMode.REPEAT_ONE); assertEquals(1, queue.following(true)); assertEquals(0, queue.following(false));
        queue.mode(PlaybackMode.REPEAT_ALL); assertEquals(0, queue.following(true));
        queue.clear(); assertEquals(-1, queue.following(true));
    }
    @Test void shuffleDoesNotRepeatWithinBagOrImmediatelyAtBoundary() {
        var queue = new PlaybackQueue(); for (int i = 1; i <= 8; i++) queue.add(new SongId(i));
        queue.select(0); queue.mode(PlaybackMode.SHUFFLE);
        var visited = new java.util.HashSet<Integer>(); visited.add(0);
        for (int i = 0; i < 7; i++) { int next = queue.following(true); assertTrue(visited.add(next)); queue.select(next); }
        assertNotEquals(queue.index(), queue.following(true));
        queue.clear(); queue.add(new SongId(1)); queue.select(0); assertEquals(0, queue.following(true));
    }
    @Test void boundedQueueDoesNotWrapOrExposeMutableStorage() {
        PlaybackQueue queue = new PlaybackQueue();
        assertTrue(queue.next().isEmpty());
        for (int i = 1; i <= 400; i++) assertTrue(queue.add(new SongId(i)));
        assertFalse(queue.add(new SongId(401)));
        assertEquals(new SongId(1), queue.next().orElseThrow());
        assertEquals(new SongId(400), queue.select(399).orElseThrow());
        assertTrue(queue.next().isEmpty());
        assertEquals(399, queue.index());
        assertThrows(UnsupportedOperationException.class, () -> queue.entries().clear());
        queue.clear();
        assertEquals(-1, queue.index());
        assertTrue(queue.current().isEmpty());
    }
}
