package dev.modernmusic.provider;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpTransportTest {
    @Test void qrLoginCookiesStayOnTheirExactAuthorizationStage() {
        var poll = URI.create("https://xui.ptlogin2.qq.com/ssl/ptqrlogin?ptqrtoken=123");
        assertEquals("qrsig=fixture", BoundedHttpTransport.qqLoginRequest(poll, "qrsig=fixture", null).headers().firstValue("Cookie").orElseThrow());
        assertEquals("GET", BoundedHttpTransport.qqLoginRequest(poll, "qrsig=fixture; pt_login_sig=fixture", null).method());
        var qrImage = URI.create("https://xui.ptlogin2.qq.com/ssl/ptqrshow");
        assertEquals("GET", BoundedHttpTransport.qqLoginRequest(qrImage, "pt_login_sig=fixture", null).method());
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(qrImage, "p_skey=fixture", null));
        for (String bad : List.of("p_skey=fixture", "qqmusic_key=fixture", "qrsig=fixture\r\nInjected:yes"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(poll, bad, null));
        var auth = URI.create("https://graph.qq.com/oauth2.0/authorize");
        assertEquals("POST", BoundedHttpTransport.qqLoginRequest(auth, "p_skey=fixture; p_uin=o123; skey=@fixture; pt4_token=fixture==", new byte[]{1}).method());
        assertEquals("POST", BoundedHttpTransport.qqLoginRequest(auth, "pt_oauth_token=fixture@token==; pt_login_type=3; RK=fixture", new byte[]{1}).method());
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(poll, "pt_oauth_token=fixture", null));
        assertTrue(BoundedHttpTransport.qqLoginRequest(auth, "p_skey=fixture", new byte[]{1}).headers().firstValue("Referer").isEmpty());
        for (String bad : List.of("skey=@fixture\r\nInjected:yes", "skey=has space", "skey=\"quoted\"", "skey=comma,value", "skey=back\\slash"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(auth, bad, new byte[]{1}));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(auth, "qrsig=fixture", new byte[]{1}));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(auth, "", null));
        var jump = URI.create("https://graph.qq.com/oauth2.0/login_jump");
        assertEquals("GET", BoundedHttpTransport.qqLoginRequest(jump, "p_skey=fixture; p_uin=o123", null).method());
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(jump, "qrsig=fixture", null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(jump, "p_skey=fixture", new byte[]{1}));
        for (String route : List.of("https://open.weixin.qq.com/connect/qrconnect", "https://open.weixin.qq.com/connect/qrcode/fixture_uuid",
                "https://lp.open.weixin.qq.com/connect/l/qrconnect", "https://xui.ptlogin2.qq.com/cgi-bin/xlogin", "https://ssl.ptlogin2.graph.qq.com/check_sig")) {
            assertTrue(BoundedHttpTransport.qqLoginRequest(URI.create(route), "", null).headers().firstValue("Cookie").isEmpty());
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(URI.create(route), "qrsig=fixture", null));
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(URI.create(route), "", new byte[]{1}));
        }
        for (String route : List.of("http://ssl.ptlogin2.qq.com/ptqrlogin", "https://ssl.ptlogin2.qq.com.evil.test/ptqrlogin",
                "https://ssl.ptlogin2.qq.com:443/ptqrlogin", "https://user@ssl.ptlogin2.qq.com/ptqrlogin",
                "https://ssl.ptlogin2.qq.com/ptqrlogin#fragment", "https://ssl.ptlogin2.qq.com/other", "https://dl.stream.qqmusic.qq.com/ptqrlogin"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqLoginRequest(URI.create(route), "qrsig=fixture", null));
    }
    @Test void kugouQrEndpointsCannotReceiveExistingAccountCookiesOrPostBodies() {
        var cookie = dev.modernmusic.auth.SessionCookie.parse("userid=123;token=fixture", dev.modernmusic.domain.MusicPlatform.KUGOU);
        for (String path : List.of("/v2/qrcode", "/v2/get_userinfo_qrcode")) {
            var uri = URI.create("https://login-user.kugou.com" + path);
            assertEquals("GET", BoundedHttpTransport.kugouRequest(uri, null, null).method());
            assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, cookie, null));
            assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, null, new byte[]{1}));
        }
    }
    @Test void kugouWebsiteSessionIsLimitedToTheOfficialHttpsRoute() {
        var cookie = dev.modernmusic.auth.SessionCookie.parse("KugooID=123;t=fixture;a_id=1014", dev.modernmusic.domain.MusicPlatform.KUGOU);
        var uri = URI.create("https://login-user.kugou.com/v1/cross/?a_id=1014&userid=123&t=fixture&domain=kugou.com");
        var request = BoundedHttpTransport.kugouRequest(uri, cookie, null);
        assertEquals("GET", request.method()); assertTrue(request.headers().firstValue("Cookie").isEmpty());
        assertEquals("no-store", request.headers().firstValue("Cache-Control").orElseThrow());
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, cookie, new byte[]{1}));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, null, null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri,
                dev.modernmusic.auth.SessionCookie.parse("userid=123;token=fixture", dev.modernmusic.domain.MusicPlatform.KUGOU), null));
        for (String route : List.of("http://login-user.kugou.com/v1/cross/", "https://login-user.kugou.com:443/v1/cross/",
                "https://login-user.kugou.com.evil.test/v1/cross/", "https://user@login-user.kugou.com/v1/cross/",
                "https://login-user.kugou.com/v1/cross/#fragment", "https://login-user.kugou.com/v1/other/"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(URI.create(route), cookie, null));
    }
    @Test void encodedKugouMetadataIsConfinedToOnePublicPageRoute() {
        var uri = URI.create("https://www.kugou.com/mixsong/g2ln2xf9.html?share=1");
        var request = BoundedHttpTransport.kugouRequest(uri, null, null);
        assertEquals(uri, request.uri()); assertTrue(request.headers().firstValue("Cookie").isEmpty());
        var cookie = dev.modernmusic.auth.SessionCookie.parse("userid=123;token=fixture", dev.modernmusic.domain.MusicPlatform.KUGOU);
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, cookie, null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(uri, null, new byte[]{1}));
        for (String bad : List.of("http://www.kugou.com/mixsong/g2ln2xf9.html?share=1", "https://www.kugou.com.evil.test/mixsong/g2ln2xf9.html?share=1",
                "https://www.kugou.com/mixsong/../other?share=1", "https://www.kugou.com/mixsong/g2ln2xf9.html?share=1&url=https://evil.test",
                "https://www.kugou.com/song/#g2ln2xf9"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(URI.create(bad), null, null));
    }
    @Test void kugouTransportConfinesCredentialsAndDownloadsWithoutThem() {
        var cookie = dev.modernmusic.auth.SessionCookie.parse("userid=123;token=fixture", dev.modernmusic.domain.MusicPlatform.KUGOU);
        var request = BoundedHttpTransport.kugouRequest(URI.create("https://gateway.kugou.com/v5/url?hash=fixture"), cookie, null);
        assertEquals("trackercdn.kugou.com", request.headers().firstValue("x-router").orElseThrow());
        for (String bad : List.of("http://gateway.kugou.com/v5/url", "https://gateway.kugou.com:443/v5/url",
                "https://gateway.kugou.com.evil.test/v5/url", "https://gateway.kugou.com/other", "https://fsandroid.tx.kugou.com/v5/url",
                "https://songsearch.kugou.com/song_search_v2"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(URI.create(bad), cookie, null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.kugouRequest(URI.create("https://gateway.kugou.com/v5/url"),
                dev.modernmusic.auth.SessionCookie.parse("MUSIC_U=fixture"), null));
        var media = URI.create("https://fsandroid.tx.kugou.com/song.mp3?key=fixture");
        var download = BoundedHttpTransport.request(media, "", false, null);
        assertTrue(download.headers().firstValue("Cookie").isEmpty()); assertEquals("https://www.kugou.com/", download.headers().firstValue("Referer").orElseThrow());
        assertEquals(MusicError.RESOURCE_UNAVAILABLE, BoundedHttpTransport.rejection(media, 403));
        assertThrows(MusicException.class, () -> dev.modernmusic.provider.kugou.KugouUris.audio("https://fsandroid.tx.kugou.com.evil.test/a.mp3"));
    }
    @Test void qqCredentialsCannotCrossPlatformsOrReachMediaHosts() {
        var cookie = dev.modernmusic.auth.SessionCookie.parse("uin=123; qqmusic_key=fixture", dev.modernmusic.domain.MusicPlatform.QQ);
        var api = URI.create("https://u.y.qq.com/cgi-bin/musicu.fcg");
        var request = BoundedHttpTransport.qqRequest(api, cookie, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(cookie.header(), request.headers().firstValue("Cookie").orElseThrow());
        assertEquals("https://y.qq.com/", request.headers().firstValue("Referer").orElseThrow());
        for (String target : java.util.List.of("https://music.163.com/api/test", "https://u.y.qq.com.evil.test/cgi-bin/musicu.fcg",
                "http://u.y.qq.com/cgi-bin/musicu.fcg", "https://u.y.qq.com:443/cgi-bin/musicu.fcg", "https://dl.stream.qqmusic.qq.com/a.mp3"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.qqRequest(URI.create(target), cookie, null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.qqRequest(api, dev.modernmusic.auth.SessionCookie.parse("MUSIC_U=fixture"), null));
        try (var http = new BoundedHttpTransport()) {
            assertThrows(MusicException.class, () -> http.getWithCookies(URI.create("https://music.163.com/api/test"), 1024, cookie));
        }
        var cdn = URI.create("https://dl.stream.qqmusic.qq.com/M500fixture.mp3?token=fixture");
        assertTrue(BoundedHttpTransport.request(cdn, "", false, null).headers().firstValue("Cookie").isEmpty());
        assertEquals(MusicError.RESOURCE_UNAVAILABLE, BoundedHttpTransport.rejection(cdn, 403));
        assertThrows(MusicException.class, () -> dev.modernmusic.provider.ProviderUris.audio(dev.modernmusic.domain.MusicPlatform.NETEASE, cdn.toString()));
        assertThrows(MusicException.class, () -> dev.modernmusic.provider.qq.QqUris.audio("https://dl.stream.qqmusic.qq.com.evil.test/a.mp3"));
    }
    @Test void desktopQrUsesTypeThreeGetWithoutChangingPlaybackIdentity() {
        for (String path : List.of("/api/login/qrcode/unikey?type=3", "/api/login/qrcode/client/login?key=fixture-key&type=3")) {
            URI uri = URI.create("https://music.163.com" + path);
            var qr = BoundedHttpTransport.qrRequest(uri, "NMTID=bootstrap");
            assertEquals("GET", qr.method()); assertEquals(uri, qr.uri());
            assertTrue(qr.bodyPublisher().isEmpty());
            assertTrue(qr.headers().firstValue("User-Agent").orElseThrow().endsWith("NeteaseMusicDesktop/3.1.6"));
            assertEquals("application/x-www-form-urlencoded", qr.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("NMTID=bootstrap", qr.headers().firstValue("Cookie").orElseThrow());
            assertEquals("no-store", qr.headers().firstValue("Cache-Control").orElseThrow());
        }
        var playback = BoundedHttpTransport.request(URI.create("https://music.163.com/weapi/song/enhance/player/url/v1"),
                "MUSIC_U=fixture", true, new byte[]{1});
        assertFalse(playback.headers().firstValue("User-Agent").orElseThrow().contains("NeteaseMusicDesktop"));
    }
    @Test void qrTransportRejectsWrongOriginEndpointTypeAndInjectedQueryOrCookiesBeforeSending() {
        try (var http = new BoundedHttpTransport()) {
            for (String uri : List.of("http://music.163.com/api/login/qrcode/unikey?type=3",
                    "https://evil.example/api/login/qrcode/unikey?type=3", "https://music.163.com:443/api/login/qrcode/unikey?type=3",
                    "https://music.163.com/api/login/qrcode/unikey?type=1", "https://music.163.com/api/login/qrcode/unikey?type=3&phone=fixture",
                    "https://music.163.com/api/login/qrcode/client/login?key=fixture-key&type=3&extra=1",
                    "https://music.163.com/api/login/qrcode/client/login?key=fixture%26key&type=3",
                    "https://music.163.com/api/login/qrcode/client/login?key=short&type=3",
                    "https://music.163.com/weapi/login/qrcode/unikey?type=3", "https://music.163.com/api/login/cellphone?type=3"))
                assertThrows(MusicException.class, () -> http.getQrAccount(URI.create(uri), 1024, ""));
            URI uri = URI.create("https://music.163.com/api/login/qrcode/unikey?type=3");
            assertThrows(MusicException.class, () -> http.getQrAccount(uri, 1024, "NMTID=fixture\r\nInjected: value"));
            assertThrows(MusicException.class, () -> http.getQrAccount(uri, 1024, "x".repeat(32769)));
        }
    }
    @Test void mediaDownloadKeepsResolverIdentityButNeverItsCredentials() {
        URI api = URI.create("https://music.163.com/weapi/song/enhance/player/url/v1");
        URI cdn = URI.create("https://m801.music.126.net/a%2Fb.mp3?auth=A+B%2Fc");
        var resolve = BoundedHttpTransport.request(api, "MUSIC_U=fixture", true, new byte[]{1});
        var download = BoundedHttpTransport.request(cdn, "", false, null);
        assertEquals(resolve.headers().firstValue("User-Agent"), download.headers().firstValue("User-Agent"));
        assertEquals(resolve.headers().firstValue("Referer"), download.headers().firstValue("Referer"));
        assertEquals("bytes=0-", download.headers().firstValue("Range").orElseThrow());
        assertEquals(cdn.toASCIIString(), download.uri().toASCIIString());
        assertTrue(download.headers().firstValue("Cookie").isEmpty());
        assertTrue(download.bodyPublisher().isEmpty());
        assertThrows(MusicException.class, () -> BoundedHttpTransport.request(cdn, "MUSIC_U=fixture", false, null));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.request(cdn, "MUSIC_U=fixture", true, null));
    }
    @Test void acceptsOnlyCompleteMediaRangeResponses() {
        URI cdn = URI.create("https://m801.music.126.net/song.mp3");
        BoundedHttpTransport.requireCompleteAudioRange(cdn, "bytes 0-999/1000", 1000);
        for (String range : List.of("", "bytes 100-1099/1100", "bytes 0-999/2000", "bytes 0-999/*", "bytes 0-998/999"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.requireCompleteAudioRange(cdn, range, 1000));
        assertThrows(MusicException.class, () -> BoundedHttpTransport.requireCompleteAudioRange(
                URI.create("https://music.163.com/api/test"), "bytes 0-999/1000", 1000));
    }
    @Test void cdnRejectionsRefreshResourceWithoutExpiringAccount() {
        URI cdn = URI.create("https://m801.music.126.net/song.mp3");
        URI api = URI.create("https://music.163.com/weapi/song/enhance/player/url/v1");
        for (int status : List.of(401, 403, 404))
            assertEquals(MusicError.RESOURCE_UNAVAILABLE, BoundedHttpTransport.rejection(cdn, status));
        assertEquals(MusicError.AUTH_EXPIRED, BoundedHttpTransport.rejection(api, 401));
        assertEquals(MusicError.UNAVAILABLE, BoundedHttpTransport.rejection(api, 403));
    }
    @Test void cookieTransportIsConfinedToExactFirstPartyHttpsApiOrigin() {
        BoundedHttpTransport.requireAccountOrigin(URI.create("https://music.163.com/api/w/nuser/account/get"));
        BoundedHttpTransport.requireAccountOrigin(URI.create("https://music.163.com/weapi/w/nuser/account/get"));
        for (String uri : List.of("https://m801.music.126.net/api/audio", "http://music.163.com/api/test",
                "https://music.163.com.evil.test/api/test", "https://music.163.com:443/api/test",
                "https://user@music.163.com/api/test", "https://music.163.com/api/test#fragment", "https://music.163.com/login"))
            assertThrows(MusicException.class, () -> BoundedHttpTransport.requireAccountOrigin(URI.create(uri)));
    }
    @Test void accountPostRejectsOtherOriginsQueryCredentialsAndHeaderInjectionBeforeSending() {
        try (var http = new BoundedHttpTransport()) {
            for (String uri : List.of("https://m801.music.126.net/weapi/test", "https://music.163.com/api/test",
                    "https://music.163.com/weapi/test?phone=fixture", "http://music.163.com/weapi/test"))
                assertThrows(MusicException.class, () -> http.postAccount(URI.create(uri), 1024, "", new byte[0]));
            assertThrows(MusicException.class, () -> http.postAccount(URI.create("https://music.163.com/weapi/test"),
                    1024, "MUSIC_U=fixture\r\nInjected: value", new byte[0]));
            assertThrows(MusicException.class, () -> http.postAccount(URI.create("https://music.163.com/weapi/test"),
                    1024, "", new byte[65537]));
        }
    }
    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) {}
        @Override public void cancel() { cancelled = true; }
    }
    @Test void capsChunkedBodiesBeforeAcceptingOversizeChunk() {
        var body = new BoundedHttpTransport.LimitedBody(4);
        var subscription = new Subscription(); body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));
        assertTrue(subscription.cancelled);
        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void acceptsExactLimit() {
        var body = new BoundedHttpTransport.LimitedBody(4); body.onSubscribe(new Subscription());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        body.onComplete(); assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().join());
    }
}
