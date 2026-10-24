package dev.modernmusic.shared;

import dev.modernmusic.room.RoomJson;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedAdminSnapshotTest {
    @Test void privateViewRejectsUnsafeLinksAndUnauthorisedQr() {
        for (String url : new String[] {"http://music.163.com/login", "https://music.163.com.evil/login", "https://music.163.com@evil/login", "file:///secret", "https://music.163.com/other", "https://music.163.com/login#fragment"})
            assertNull(SharedAdminSnapshot.verificationUri(url));
        assertNotNull(SharedAdminSnapshot.verificationUri("https://music.163.com/st/verify?token=fixture"));
        assertThrows(IllegalArgumentException.class, () -> new SharedAdminSnapshot(UUID.randomUUID(), 1, false, false, false,
                false, false, false, false, false, true, false, "", "modernmusic.auth.guest", "private-qr-key", 123, ""));
    }
    @Test void jsonRoundTripUsesRecordValidationAndRedactsDiagnostics() {
        var view = new SharedAdminSnapshot(UUID.randomUUID(), 1, true, true, true, false, false, false, false, false,
                true, false, "", "modernmusic.auth.qr_waiting", "private-qr-key", 123, "");
        String json = RoomJson.encode(view); assertEquals(view, RoomJson.decode(json, SharedAdminSnapshot.class));
        assertFalse(view.toString().contains("private-qr-key")); assertFalse(json.contains("MUSIC_U"));
        assertThrows(RuntimeException.class, () -> RoomJson.decode(json.replace("private-qr-key", "invalid/?key"), SharedAdminSnapshot.class));
    }
}
