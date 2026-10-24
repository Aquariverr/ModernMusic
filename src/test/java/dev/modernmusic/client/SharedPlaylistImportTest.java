package dev.modernmusic.client;

import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import dev.modernmusic.room.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedPlaylistImportTest {
    final UUID id = UUID.randomUUID(), epoch = UUID.randomUUID(), owner = UUID.randomUUID();
    RoomSnapshot room(long revision, List<String> songs) {
        return new RoomSnapshot(id, epoch, "Room", revision, revision, 1, owner, List.of(), songs, 0,
                PlaybackMode.SEQUENTIAL, RoomSnapshot.State.STOPPED, 0, 0, 0);
    }
    static class Provider implements MusicProvider {
        final CompletableFuture<Playlist> list = new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        public CompletableFuture<Track> getTrack(SongId song) { return new CompletableFuture<>(); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId song) { return new CompletableFuture<>(); }
        public CompletableFuture<Playlist> getPlaylist(SongId song) { return list; }
        public void close() {}
    }
    @Test void batchWaitsForServerAckAndReportsActualCountWithoutEditingLocalLibrary() {
        Provider provider = new Provider(); var output = new SharedPlaybackTest.Output();
        List<RoomImportRequest> sent = new ArrayList<>(); CompletableFuture<RoomImportResult> ack = new CompletableFuture<>();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], output, Runnable::run)) {
            player.add("1"); var personal = player.queue(); player.applyRoom(room(1, List.of("42")));
            player.roomImporter(request -> { sent.add(request); return ack; });
            assertTrue(player.importPlaylist("99")); assertFalse(player.importPlaylist("100"));
            provider.list.complete(new Playlist("Private playlist", List.of(new SongId(50), new SongId(51)),
                    List.of(new Track(new SongId(50), "Song", List.of("Artist"), 1_000)), 5));
            assertTrue(player.importing()); assertEquals(1, sent.size()); assertEquals(List.of("50", "51"), sent.getFirst().songs());
            assertEquals(List.of(new SongId(42)), player.queue().entries()); assertEquals(0, player.importedCount());
            player.applyRoom(room(2, List.of("42", "50")));
            ack.complete(new RoomImportResult(sent.getFirst().request(), 1, 5, ""));
            assertFalse(player.importing()); assertEquals(1, player.importedCount()); assertEquals(5, player.importTotal());
            assertEquals("modernmusic.playlist.partial", player.importMessage()); assertNotNull(player.track(new SongId(50)));
            assertEquals(0, output.loads); assertEquals(0, player.queue().index());
            player.applyRoom(null); assertSame(personal, player.queue()); assertEquals(List.of(new SongId(1)), player.queue().entries());
        }
    }
    @Test void leaveAndClearCancelLatePlaylistBeforeItCanSubmitToServer() {
        for (boolean clear : List.of(false, true)) {
            Provider provider = new Provider(); List<RoomImportRequest> sent = new ArrayList<>();
            try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
                player.applyRoom(room(1, List.of("42"))); player.roomImporter(request -> { sent.add(request); return new CompletableFuture<>(); });
                player.importPlaylist("99"); if (clear) player.clear(); else player.applyRoom(null);
                provider.list.complete(new Playlist("Old", List.of(new SongId(50)), List.of(), 1));
                assertTrue(sent.isEmpty()); assertFalse(player.importing()); assertNull(player.importMessage());
            }
        }
    }
    @Test void acknowledgementAfterLeaveCannotChangeNewPersonalQueue() {
        Provider provider = new Provider(); var ack = new CompletableFuture<RoomImportResult>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        List<RoomImportRequest> sent = new ArrayList<>();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.applyRoom(room(1, List.of("42"))); player.roomImporter(request -> { sent.add(request); return ack; });
            player.importPlaylist("99"); provider.list.complete(new Playlist("List", List.of(new SongId(50)), List.of(), 1));
            player.applyRoom(null); ack.complete(new RoomImportResult(sent.getFirst().request(), 1, 1, ""));
            assertFalse(player.shared()); assertTrue(player.queue().entries().isEmpty()); assertNull(player.importMessage());
        }
    }
    @Test void emptyPlaylistDoesNotSendBatchAndServerFailureIsShown() {
        Provider provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.applyRoom(room(1, List.of("42"))); player.roomImporter(request -> { fail("No batch for empty list"); return null; });
            player.importPlaylist("99"); provider.list.complete(new Playlist("Empty", List.of(), List.of(), 0));
            assertEquals("modernmusic.playlist.empty", player.importMessage()); assertFalse(player.importing());
        }
        provider = new Provider();
        try (var player = new PlaybackController(provider, (uri, max) -> new byte[0], new SharedPlaybackTest.Output(), Runnable::run)) {
            player.applyRoom(room(1, List.of("42"))); player.roomImporter(request -> CompletableFuture.completedFuture(
                    new RoomImportResult(request.request(), 0, request.total(), "modernmusic.room.import_timeout")));
            player.importPlaylist("99"); provider.list.complete(new Playlist("List", List.of(new SongId(50)), List.of(), 1));
            assertEquals("modernmusic.room.import_timeout", player.importMessage()); assertEquals(0, player.importedCount());
        }
    }
}
