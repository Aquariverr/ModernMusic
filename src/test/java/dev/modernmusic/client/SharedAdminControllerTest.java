package dev.modernmusic.client;

import dev.modernmusic.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import dev.modernmusic.auth.AuthException;
import dev.modernmusic.domain.MusicPlatform;
import static org.junit.jupiter.api.Assertions.*;
import static dev.modernmusic.shared.SharedAdminAction.*;

class SharedAdminControllerTest {
    final SharedPlaybackSourceTest.Time clock = new SharedPlaybackSourceTest.Time();
    final List<SharedAdminController.Request> sent = new ArrayList<>();
    final SharedAdminController admin = new SharedAdminController(sent::add, clock);
    SharedAdminSnapshot state(boolean cookieUpload) {
        var request = dev.modernmusic.compat.Java17.last(sent);
        return new SharedAdminSnapshot(request.view(), request.sequence(), true, true, cookieUpload,
                false, false, false, false, false, true, false, "", "modernmusic.auth.qr_waiting", "fake-challenge", clock.millis() + 120000, "");
    }
    void open() { admin.capability(true); admin.open(); admin.receive(state(true)); }
    @Test void permissionRevocationErasesQrAndBlocksLateViewsAndCommands() {
        admin.open(); assertTrue(sent.isEmpty()); open(); var old = admin.snapshot();
        admin.capability(false); assertEquals("", admin.snapshot().qrKey());
        admin.receive(old); assertEquals("", admin.snapshot().qrKey());
        int before = sent.size(); admin.request(ENABLE, ""); assertEquals(before, sent.size());
    }
    @Test void closedViewsAndOutOfOrderResponsesCannotOverwriteCurrentScreen() {
        open(); var old = admin.snapshot(); admin.close(); admin.receive(old); assertNull(admin.snapshot());
        admin.open(); admin.receive(old); assertNull(admin.snapshot());
        admin.receive(state(true)); var previous = admin.snapshot();
        admin.request(ENABLE, ""); admin.receive(state(true)); admin.receive(previous);
        assertEquals(dev.modernmusic.compat.Java17.last(sent).sequence(), admin.snapshot().acknowledgement());
    }
    @Test void insecureTransportRejectsCookieAndDiagnosticsNeverEchoCredentials() {
        admin.capability(true); admin.open(); admin.receive(state(false));
        int before = sent.size(); admin.request(COOKIE_LOGIN, "MUSIC_U=private-test-secret"); assertEquals(before, sent.size());
        admin.receive(state(true)); admin.request(COOKIE_LOGIN, "MUSIC_U=private-test-secret");
        assertEquals("MUSIC_U=private-test-secret", dev.modernmusic.compat.Java17.last(sent).value());
        assertFalse(dev.modernmusic.compat.Java17.last(sent).toString().contains("private-test-secret"));
        admin.request(ENABLE, ""); assertEquals(COOKIE_LOGIN, dev.modernmusic.compat.Java17.last(sent).action());
    }
    @Test void heartbeatRenewsAndMissingRepliesTimeoutEvenWithPeriodicSnapshots() {
        open(); clock.value = clock.value.plusSeconds(3); admin.tick(); assertEquals(KEEPALIVE, dev.modernmusic.compat.Java17.last(sent).action());
        var old = admin.snapshot();
        clock.value = clock.value.plusSeconds(11); admin.capability(true); admin.receive(old); admin.tick();
        assertFalse(admin.waiting()); assertFalse(admin.snapshot().editing()); assertEquals("", admin.snapshot().qrKey());
        admin.request(OPEN, ""); admin.tick(); assertTrue(admin.waiting());
    }
    @Test void disconnectClearsPermissionAndPrivateView() {
        open(); var old = admin.snapshot(); admin.disconnect(); admin.receive(old);
        assertFalse(admin.allowed()); assertNull(admin.snapshot()); assertFalse(admin.waiting());
        int before = sent.size();
        admin.open(); admin.request(ENABLE, ""); admin.tick(); admin.close();
        assertEquals(before, sent.size());
    }
    @ParameterizedTest @EnumSource(value = MusicPlatform.class, names = "LOCAL", mode = EnumSource.Mode.EXCLUDE)
    void reducesBrowserExportLocallyBeforeSubmittingToServer(MusicPlatform platform) {
        var controller = new SharedAdminController(sent::add, clock, platform);
        controller.capability(true); controller.open(); controller.receive(state(true));
        String json = """
                {"cookies":[
                  {"name":"MUSIC_U","value":"netease-fixture","domain":".music.163.com"},
                  {"name":"uin","value":"123","domain":".y.qq.com"},
                  {"name":"qqmusic_key","value":"qq-fixture","domain":".y.qq.com"},
                  {"name":"userid","value":"456","domain":".kugou.com"},
                  {"name":"token","value":"kugou-fixture","domain":".kugou.com"},
                  {"name":"token","value":"unrelated-secret","domain":".example.com"}
                ],"metadata":"%s"}
                """.formatted("x".repeat(20_000));
        controller.request(COOKIE_LOGIN, json);
        assertEquals(switch (platform) {
            case NETEASE -> "MUSIC_U=netease-fixture";
            case QQ -> "uin=123; qqmusic_key=qq-fixture";
            case KUGOU -> "userid=456; token=kugou-fixture"; case LOCAL -> throw new AssertionError();
        }, dev.modernmusic.compat.Java17.last(sent).value());
        assertFalse(dev.modernmusic.compat.Java17.last(sent).toString().contains("fixture"));
    }
    @Test void malformedJsonNeverLeavesClient() {
        open(); int before = sent.size();
        assertThrows(AuthException.class, () -> admin.request(COOKIE_LOGIN, "{\"MUSIC_U\":\"fixture\",}"));
        assertEquals(before, sent.size()); assertFalse(admin.waiting());
    }
    @Test void kugouWebsiteExportUploadsOnlyCanonicalLoginFields() {
        var controller = new SharedAdminController(sent::add, clock, MusicPlatform.KUGOU);
        controller.capability(true); controller.open(); controller.receive(state(true));
        controller.request(COOKIE_LOGIN, """
                [{"domain":".kugou.com","name":"KuGoo","value":"KugooID=123&t=fixture&a_id=1014&KugooPwd=discard-password&NickName=discard-name"},
                 {"domain":".kugou.com","name":"KugooID","value":"123"},
                 {"domain":".kugou.com","name":"t","value":"fixture"}]
                """);
        assertEquals("userid=123; token=fixture; appid=1014", dev.modernmusic.compat.Java17.last(sent).value());
        assertFalse(dev.modernmusic.compat.Java17.last(sent).toString().contains("fixture"));
    }
}
