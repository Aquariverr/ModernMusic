package dev.modernmusic.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateHeartbeatTest {
    @Test void unchangedOneSecondChecksProduceTwelveHeartbeatsPerMinute() {
        var heartbeat = new StateHeartbeat<String, String>();
        int packets = 0;
        for (long now = 0; now < 60_000; now += 1_000)
            if (heartbeat.update("player", "ready", now)) packets++;
        assertEquals(12, packets); // Previously 60 identical state/profile/capability deliveries.
    }
    @Test void revocationAndAccountChangesDoNotWaitForTheHeartbeat() {
        var heartbeat = new StateHeartbeat<String, String>();
        assertTrue(heartbeat.update("player", "ready:account-one", 0));
        assertFalse(heartbeat.update("player", "ready:account-one", 1_000));
        assertTrue(heartbeat.update("player", "denied", 1_001));
        assertTrue(heartbeat.update("player", "ready:account-two", 1_002));
        assertFalse(heartbeat.update("player", "ready:account-two", 6_001));
        assertTrue(heartbeat.update("player", "ready:account-two", 6_002));
    }
    @Test void reconnectRestartAndClockRollbackAlwaysDeliverInitialState() {
        var heartbeat = new StateHeartbeat<String, Boolean>();
        assertTrue(heartbeat.update("player", true, 1_000));
        heartbeat.remove("player"); assertTrue(heartbeat.update("player", true, 1_001));
        heartbeat.clear(); assertTrue(heartbeat.update("player", true, 1_002));
        assertTrue(heartbeat.update("player", true, 900));
        assertTrue(heartbeat.update("another", true, 900));
    }
}
