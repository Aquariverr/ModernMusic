package dev.modernmusic.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedAdminLeaseTest {
    final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), view = UUID.randomUUID();
    @Test void onlyCurrentEditorAndIncreasingSequenceCanRenew() {
        var lease = new SharedAdminLease();
        assertTrue(lease.open(first, view, 1, 100));
        assertFalse(lease.open(second, view, 2, 200));
        assertFalse(lease.accept(first, UUID.randomUUID(), 2, 200));
        assertFalse(lease.accept(first, view, 1, 200));
        assertTrue(lease.accept(first, view, 2, 1000));
        assertTrue(lease.valid(first, view, 15999));
        assertFalse(lease.valid(first, view, 16000));
        assertFalse(lease.accept(first, view, 3, 16000));
    }
    @Test void releaseAndTimeoutRequireANewOpen() {
        var lease = new SharedAdminLease();
        assertFalse(lease.open(first, view, 0, 1));
        assertTrue(lease.open(first, view, 1, 1));
        assertTrue(lease.expired(15001)); lease.clear();
        assertFalse(lease.accept(first, view, 2, 15002));
        assertTrue(lease.open(second, UUID.randomUUID(), 1, 15002));
        assertFalse(lease.accept(first, view, 3, 15003));
    }
}
