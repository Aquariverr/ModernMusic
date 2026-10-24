package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedPlaybackSourceTest {
    @Test void profileMatchesCurrentPlaybackGenerationAndIsClearedWhenAccessIsLost() {
        var profile = new dev.modernmusic.shared.SharedAccountProfile("Server", "https://p1.music.126.net/fixture.png");
        source.state(10, null); source.profile(9, profile); assertEquals("", source.profile().nickname());
        source.profile(10, profile); assertEquals(profile, source.profile());
        source.state(10, MusicError.SHARED_FORBIDDEN); source.profile(10, profile);
        assertEquals("", source.profile().nickname()); assertNull(source.profile().avatarUri());
        source.state(11, null); source.profile(10, profile); assertEquals("", source.profile().nickname());
        source.profile(11, profile); assertEquals(profile, source.profile());
    }
    @Test void profileIsRemovedOnHeartbeatLossAndDisconnect() {
        var profile = new dev.modernmusic.shared.SharedAccountProfile("Server", "");
        ready(); source.profile(1, profile); clock.value = clock.value.plusSeconds(11); source.tick();
        assertEquals("", source.profile().nickname()); assertTrue(source.selected());
        source.state(2, null); source.profile(2, profile); source.disconnect();
        assertEquals("", source.profile().nickname()); assertFalse(source.selected());
    }
    static final class Time extends Clock {
        Instant value = Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return value; }
    }
    final Time clock = new Time();
    final List<SharedPlaybackSource.Request> sent = new ArrayList<>();
    int personalResolves, changes;
    final MusicProvider personal = new MusicProvider() {
        public CompletableFuture<Track> getTrack(SongId id) { return CompletableFuture.completedFuture(new Track(id, "Guest", List.of(), 1000)); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) {
            personalResolves++; return CompletableFuture.completedFuture(new PlaybackResource(id, URI.create("https://m1.music.126.net/local"), clock.instant().plusSeconds(60)));
        }
        public void close() {}
    };
    final SharedPlaybackSource source = new SharedPlaybackSource(personal, sent::add, () -> changes++, clock);
    void ready() { source.state(1, null); source.select(true); }
    void respond(int index, String url) {
        var request = sent.get(index); source.receive(request.request(), request.generation(), request.song(), url, clock.instant().plusSeconds(60).toEpochMilli(), null);
    }
    static void error(MusicError expected, CompletableFuture<?> future) {
        assertEquals(expected, ((MusicException) assertThrows(CompletionException.class, future::join).getCause()).error());
    }
    @Test void explicitOptInWorksWithoutPersonalLoginAndPreservesLocalMetadata() {
        source.state(1, null); source.resolvePlayback(new SongId(1)).join(); assertEquals(1, personalResolves); assertTrue(sent.isEmpty());
        source.select(true); var request = source.resolvePlayback(new SongId(2)); assertFalse(request.isDone());
        respond(0, "https://m1.music.126.net/server"); assertEquals(new SongId(2), request.join().id()); assertEquals(1, personalResolves);
        assertEquals("Guest", source.getTrack(new SongId(2)).join().title());
    }
    @Test void serverWithoutSharedSupportKeepsPersonalPlaybackAndRejectsOptIn() {
        source.select(true);
        assertFalse(source.selected()); assertFalse(source.available());
        assertEquals("modernmusic.shared.unavailable", source.statusKey());
        assertEquals(new SongId(1), source.resolvePlayback(new SongId(1)).join().id());
        assertEquals(new SongId(2), source.refreshPlayback(new SongId(2)).join().id());
        assertEquals(2, personalResolves); assertTrue(sent.isEmpty()); assertEquals(0, changes);
    }
    @Test void leavingSharedServerRestoresPersonalPlaybackWithoutSendingToNextServer() {
        ready(); var pending = source.resolvePlayback(new SongId(1));
        source.disconnect(); error(MusicError.SHARED_DISABLED, pending);
        respond(0, "https://m1.music.126.net/old");
        source.select(true);
        assertFalse(source.selected()); assertFalse(source.available());
        assertEquals("modernmusic.shared.unavailable", source.statusKey());
        source.resolvePlayback(new SongId(2)).join();
        assertEquals(1, personalResolves); assertEquals(1, sent.size());
        source.state(1, null);
        assertTrue(source.available()); assertFalse(source.selected());
    }
    @Test void disablingOrLosingPermissionStopsPlaybackAndDoesNotFallBackSilently() {
        ready(); var request = source.resolvePlayback(new SongId(1));
        source.state(2, MusicError.SHARED_FORBIDDEN); error(MusicError.SHARED_FORBIDDEN, request);
        respond(0, "https://m1.music.126.net/old"); assertEquals(0, personalResolves); assertEquals(2, changes);
        error(MusicError.SHARED_FORBIDDEN, source.resolvePlayback(new SongId(1)));
        source.select(false); source.resolvePlayback(new SongId(1)).join(); assertEquals(1, personalResolves);
    }
    @Test void sessionChangesAndDisconnectDiscardOldResponsesAndResetChoice() {
        ready(); var old = source.resolvePlayback(new SongId(1)); source.state(2, null); error(MusicError.SHARED_EXPIRED, old);
        var next = source.resolvePlayback(new SongId(1)); respond(0, "https://m1.music.126.net/old"); assertFalse(next.isDone());
        source.disconnect(); error(MusicError.SHARED_DISABLED, next); assertFalse(source.selected());
        source.state(1, null); respond(1, "https://m1.music.126.net/old"); assertFalse(source.selected());
    }
    @Test void wrongSongAndWrongGenerationCannotCompleteRequest() {
        ready(); var future = source.resolvePlayback(new SongId(1)); var req = sent.getFirst();
        source.receive(req.request(), 1, "2", "https://m1.music.126.net/wrong", clock.instant().plusSeconds(60).toEpochMilli(), null);
        source.receive(req.request(), 2, "1", "https://m1.music.126.net/wrong", clock.instant().plusSeconds(60).toEpochMilli(), null);
        assertFalse(future.isDone()); respond(0, "https://m1.music.126.net/right"); assertEquals(new SongId(1), future.join().id());
    }
    @Test void validatesReturnedCdnUrlsAndDeadlines() {
        ready(); var first = source.resolvePlayback(new SongId(1)); respond(0, "https://localhost/secret"); error(MusicError.INVALID_RESPONSE, first);
        var second = source.resolvePlayback(new SongId(1)); var req = sent.getLast();
        source.receive(req.request(), 1, "1", "https://m1.music.126.net/song", clock.millis() - 1, null); error(MusicError.INVALID_RESPONSE, second);
    }
    @Test void heartbeatLossStopsPlaybackAndPendingRequests() {
        ready(); var future = source.resolvePlayback(new SongId(1)); clock.value = clock.value.plusSeconds(11); source.tick();
        error(MusicError.NETWORK_ERROR, future); assertFalse(source.available()); assertEquals(2, changes);
        source.state(1, null); assertTrue(source.available()); assertEquals(3, changes);
    }
    @Test void requestTimeoutAndRefreshAreExplicitAndBounded() {
        ready(); var first = source.refreshPlayback(new SongId(1)); assertTrue(sent.getFirst().refresh());
        source.resolvePlayback(new SongId(2)); error(MusicError.RATE_LIMITED, source.resolvePlayback(new SongId(3)));
        clock.value = clock.value.plusSeconds(26); source.state(1, null); source.tick(); error(MusicError.NETWORK_ERROR, first);
        assertFalse(source.resolvePlayback(new SongId(3)).isDone());
    }
    @Test void rememberedLoginWaitsForAccountReadinessAndRestoresOnEachVisitForAllPlatforms() {
        for (var platform : MusicPlatform.values()) {
            var restored = new SharedPlaybackSource(personal, sent::add, () -> changes++, clock, platform, () -> true);
            assertFalse(restored.selected());
            for (var error : List.of(MusicError.SHARED_DISABLED, MusicError.SHARED_FORBIDDEN, MusicError.SHARED_EXPIRED)) {
                restored.state(1, error); assertFalse(restored.selected());
            }
            int before = changes;
            restored.state(2, null); assertTrue(restored.selected()); assertEquals(before + 1, changes);
            restored.state(2, null); assertEquals(before + 1, changes);
            restored.disconnect(); assertFalse(restored.selected()); assertFalse(restored.available());
            restored.state(1, null); assertTrue(restored.selected());
        }
        assertTrue(sent.isEmpty());
    }
    @Test void manualPersonalChoiceOverridesRememberedLoginUntilNextConnection() {
        var restored = new SharedPlaybackSource(personal, sent::add, () -> changes++, clock, MusicPlatform.NETEASE, () -> true);
        restored.state(1, null); assertTrue(restored.selected());
        restored.select(false);
        restored.state(1, null); assertFalse(restored.selected());
        clock.value = clock.value.plusSeconds(11); restored.tick();
        restored.state(1, null); assertFalse(restored.selected());
        restored.disconnect(); restored.state(1, null); assertTrue(restored.selected());
    }
    @Test void personalChoiceBeforeServerBecomesReadyAlsoPreventsAutomaticSwitching() {
        var restored = new SharedPlaybackSource(personal, sent::add, () -> changes++, clock, MusicPlatform.NETEASE, () -> true);
        restored.state(1, MusicError.SHARED_EXPIRED); restored.select(false);
        restored.state(2, null); assertFalse(restored.selected()); assertEquals(0, changes);
    }
    @Test void uncheckingKeepsCurrentSessionButDoesNotRestoreAfterDisconnect() {
        var remembered = new java.util.concurrent.atomic.AtomicBoolean(true);
        var restored = new SharedPlaybackSource(personal, sent::add, () -> changes++, clock, MusicPlatform.NETEASE, remembered::get);
        restored.state(1, null); assertTrue(restored.selected());
        remembered.set(false); restored.state(1, null); assertTrue(restored.selected());
        restored.disconnect(); restored.state(1, null); assertFalse(restored.selected());
        remembered.set(true); restored.state(1, null); assertFalse(restored.selected());
        restored.disconnect(); restored.state(1, null); assertTrue(restored.selected());
    }
}
