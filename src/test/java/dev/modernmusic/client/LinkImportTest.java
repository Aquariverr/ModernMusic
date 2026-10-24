package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinkImportTest {
    static final String QQ_ID_LINK = "https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect";
    static final String QQ_MID_LINK = "https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2";
    static final String KUGOU_LINK = "https://www.kugou.com/song/#g2ln2xf9";
    static final Track QQ_SONG = new Track(new SongId(216494081, MusicPlatform.QQ), "Beautiful Life", List.of("Artist"), 200000);
    static final Track KUGOU_SONG = new Track(new SongId("78A65512178A7F0ECB797F51BF9EC0B9", MusicPlatform.KUGOU), "Know My Name", List.of("Artist"), 212000);

    static class Provider implements MusicProvider {
        final List<String> references = new ArrayList<>();
        CompletableFuture<Track> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { return false; }
        };
        public CompletableFuture<Track> getReference(String reference) { references.add(reference); return result; }
        public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError("Import must resolve metadata without starting playback"); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { throw new AssertionError("Import must not start playback"); }
        public void close() {}
    }
    private PlaybackController player(Provider provider, MusicPlatform platform) {
        var multi = new MultiPlatformProvider(new Provider(), provider, provider);
        multi.select(platform);
        return new PlaybackController(multi, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run);
    }
    @Test void numericQqLinkIsAddedWithItsNameWithoutPlayingOrChangingSelection() {
        var provider = new Provider();
        try (var player = player(provider, MusicPlatform.QQ)) {
            var existing = new SongId(1);
            player.queue().restore(List.of(existing), 0, PlaybackMode.REPEAT_ALL);
            assertTrue(player.add(QQ_ID_LINK)); assertTrue(player.importing());
            assertEquals(List.of("216494081"), provider.references);
            assertEquals(List.of(existing), player.queue().entries());
            provider.result.complete(QQ_SONG);
            assertFalse(player.importing()); assertEquals(List.of(existing, QQ_SONG.id()), player.queue().entries());
            assertEquals("Beautiful Life", player.track(QQ_SONG.id()).title()); assertEquals(0, player.queue().index());
        }
    }
    @Test void successAndDuplicateMessagesFormatInBothLanguagesForEveryReportedLink() throws Exception {
        for (String link : List.of(QQ_ID_LINK, QQ_MID_LINK, KUGOU_LINK)) {
            var song = link.equals(KUGOU_LINK) ? KUGOU_SONG : QQ_SONG;
            var provider = new Provider(); provider.result.complete(song);
            try (var player = player(provider, song.id().platform())) {
                assertTrue(player.add(link));
                assertEquals("modernmusic.playlist.imported", player.importMessage());
                assertEquals("已导入 1 首歌曲。", formatStatus(player, "zh_cn"));
                assertEquals("Imported 1 songs.", formatStatus(player, "en_us"));
                assertTrue(player.add(link)); assertEquals(List.of(song.id()), player.queue().entries());
                assertEquals(0, player.importedCount()); assertEquals(1, player.importSkipped());
                assertEquals("已导入 0 首，跳过 1 首重复歌曲。", formatStatus(player, "zh_cn"));
                assertEquals("Imported 0 songs; skipped 1 duplicates.", formatStatus(player, "en_us"));
            }
        }
    }
    @Test void repeatImportRepairsOlderIdOnlyEntriesAndDeduplicatesNumericAndMidLinks() {
        var provider = new Provider(); provider.result.complete(QQ_SONG);
        try (var player = player(provider, MusicPlatform.QQ)) {
            player.queue().restore(List.of(QQ_SONG.id()), 0, PlaybackMode.SEQUENTIAL);
            assertNull(player.track(QQ_SONG.id())); assertTrue(player.add(QQ_MID_LINK));
            assertEquals(QQ_SONG, player.track(QQ_SONG.id())); assertEquals(1, player.importSkipped());
            assertTrue(player.add(QQ_ID_LINK)); assertEquals(List.of(QQ_SONG.id()), player.queue().entries());
        }
    }
    @Test void failedMetadataAndLateResultsNeverLeaveIdOnlyRowsOrRefillClearedQueue() {
        var failed = new Provider();
        try (var player = player(failed, MusicPlatform.QQ)) {
            assertTrue(player.add(QQ_ID_LINK));
            failed.result.completeExceptionally(new MusicException(MusicError.NETWORK_ERROR));
            assertTrue(player.queue().entries().isEmpty()); assertFalse(player.importing());
            assertEquals("modernmusic.error.network_error", player.importMessage());
        }
        var delayed = new Provider();
        try (var player = player(delayed, MusicPlatform.QQ)) {
            player.add(QQ_ID_LINK); player.clear(); delayed.result.complete(QQ_SONG);
            assertTrue(player.queue().entries().isEmpty()); assertNull(player.track(QQ_SONG.id())); assertNull(player.importMessage());
        }
    }
    @Test void fullQueueReportsCapacityWithoutSearchPlaceholders() throws Exception {
        var provider = new Provider(); provider.result.complete(QQ_SONG);
        try (var player = player(provider, MusicPlatform.QQ)) {
            for (int id = 1; id <= PlaybackQueue.MAX_TRACKS; id++) player.queue().add(new SongId(id));
            assertTrue(player.add(QQ_ID_LINK)); assertEquals("modernmusic.status.queue_full", player.importMessage());
            assertFalse(formatStatus(player, "zh_cn").contains("%")); assertFalse(player.queue().contains(QQ_SONG.id()));
        }
    }
    private String formatStatus(PlaybackController player, String language) throws Exception {
        String pattern = new LocalizationTest().language(language).get(player.importMessage()).getAsString();
        return String.format(Locale.ROOT, pattern, player.importedCount(), player.importTotal(), player.importSkipped());
    }
}
