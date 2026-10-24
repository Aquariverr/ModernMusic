package dev.modernmusic.smoke;

import dev.modernmusic.ModernMusic;
import dev.modernmusic.client.*;
import dev.modernmusic.client.audio.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.domain.*;
import dev.modernmusic.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.glfw.GLFW;

/** Deterministic real-client UI check; no account credentials or audio playback. */
public final class SearchScreenSmoke {
    private PlaybackController player;
    private final Fixture provider = new Fixture();
    private final LinkFixture qqLink = new LinkFixture(new Track(new SongId(216494081, MusicPlatform.QQ), "Beautiful Life", List.of("测试歌手"), 200000));
    private final LinkFixture kugouLink = new LinkFixture(new Track(new SongId("78A65512178A7F0ECB797F51BF9EC0B9", MusicPlatform.KUGOU), "Know My Name", List.of("True Damage"), 212000));
    private QueueLibrary.NamedQueue original, destination;
    private int step, frames;
    private long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
    public SearchScreenSmoke() {
        if (Boolean.getBoolean("modernmusic.searchSmoke")) MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (step < 0) return;
        var game = Minecraft.getInstance();
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Search smoke timeout at " + step);
            if (game.getOverlay() != null || frames-- > 0) return;
            switch (step) {
                case 0 -> {
                    if (game.screen instanceof AccessibilityOnboardingScreen) {  game.setScreen(new TitleScreen()); }
                    if (!(game.screen instanceof TitleScreen)) return;
                    if (!game.options.languageCode.equals("zh_cn")) {
                        game.options.languageCode = "zh_cn"; game.getLanguageManager().setSelected("zh_cn"); game.reloadResourcePacks(); return;
                    }
                    game.options.pauseOnLostFocus = false; game.options.guiScale().set(2);
                    game.getWindow().setWindowed(1280, 900); game.resizeDisplay();
                    player = new PlaybackController(provider, (uri, max) -> new byte[0], new SilentOutput(), game::execute);
                    player.renameQueue("正在收听"); original = player.library().active();
                    player.createQueue("收藏歌曲"); destination = player.library().active(); player.switchQueue(original.id());
                    game.setScreen(new PlayerScreen(game.screen, player)); step++; frames = 8;
                }
                case 1 -> {
                    capture("search-entry-wide.png"); input().setValue("晴天"); enter(false);
                    if (!(game.screen instanceof SongSearchScreen) || !player.search().query().equals("晴天"))
                        throw new IllegalStateException("Enter did not search player input");
                    step++; frames = 6;
                }
                case 2 -> {
                    if (player.search().loading()) return;
                    press("modernmusic.search.select_page");
                    capture("search-results-wide.png"); press("modernmusic.next_page"); step++; frames = 6;
                }
                case 3 -> {
                    pressFirstSong(); press("modernmusic.search.target"); step++; frames = 6;
                }
                case 4 -> { capture("search-destinations-wide.png"); pressLiteral("收藏歌曲"); press("modernmusic.search.add_selection"); step++; frames = 6; }
                case 5 -> {
                    if (destination.queue().entries().size() != player.search().limit() + 1) throw new IllegalStateException("Selection lost across pages");
                    if (player.library().active() != original || !original.queue().entries().isEmpty() || player.state() != PlaybackController.State.IDLE)
                        throw new IllegalStateException("Destination addition changed active playback");
                    capture("search-added-wide.png");
                    press("modernmusic.search.select_page"); press("modernmusic.search.add_selection"); step = 12; frames = 6;
                }
                case 6 -> {
                    if (!input().getValue().equals("晴天")) throw new IllegalStateException("Resize lost draft");
                    capture("search-results-narrow.png"); press("modernmusic.search.target"); step++; frames = 6;
                }
                case 7 -> { capture("search-destinations-narrow.png"); press("gui.back"); input().setValue("empty"); press("modernmusic.search.open"); step++; frames = 6; }
                case 8 -> { capture("search-empty-narrow.png"); input().setValue("error"); press("modernmusic.search.open"); step++; frames = 6; }
                case 9 -> { capture("search-error-narrow.png"); input().setValue("pending"); press("modernmusic.search.open"); step++; frames = 6; }
                case 10 -> {
                    capture("search-loading-narrow.png"); press("gui.back");
                    provider.pending.complete(new SongSearchResult(List.of(track(999)), 1, false));
                    if (player.search().result() != null || player.search().loading()) throw new IllegalStateException("Late result survived close");
                    step++; frames = 6;
                }
                case 11 -> {
                    capture("search-entry-narrow.png");
                    var target = player.searchDestinations().stream().filter(entry -> entry.id().equals(original.id())).findFirst().orElseThrow();
                    player.addSearchTracks(target, List.of(track(42))).join(); input().setValue("https://music.163.com/song?id=42");
                    enter(true); step = 14; frames = 6;
                }
                case 12 -> {
                    checkDuplicateSelection(); capture("search-deduplicated-wide.png");
                    press("modernmusic.search.select_page"); press("modernmusic.search.add_selection"); step = 13; frames = 6;
                }
                case 13 -> {
                    checkDuplicateSelection(); capture("search-duplicates-only-wide.png");
                    game.getWindow().setWindowed(680, 480); game.resizeDisplay(); step = 6; frames = 8;
                }
                case 14 -> {
                    if (original.queue().entries().size() != 1 || !player.message().equals("modernmusic.status.duplicate"))
                        throw new IllegalStateException("Duplicate URL was not rejected");
                    capture("search-duplicate-url-narrow.png");
                    input().setValue("分享歌单 https://music.163.com/#/playlist?id=123"); enter(false); step = 15; frames = 6;
                }
                case 15 -> {
                    if (provider.playlistId != 123 || !input().getValue().isEmpty() || !hasButton("modernmusic.playlist.tab"))
                        throw new IllegalStateException("Playlist link did not import and switch tab");
                    input().setValue("456"); enter(true); step = 16; frames = 6;
                }
                case 16 -> {
                    if (provider.playlistId != 456 || !input().getValue().isEmpty())
                        throw new IllegalStateException("Bare ID did not use playlist tab");
                    input().setValue("晴天"); enter(true);
                    if (!(game.screen instanceof SongSearchScreen)) throw new IllegalStateException("Playlist tab did not allow keyword search");
                    press("gui.back");
                    input().setValue("https://music.163.com/song?id=42"); enter(false);
                    if (!hasButton("modernmusic.song.tab") || !player.message().equals("modernmusic.status.duplicate"))
                        throw new IllegalStateException("Song link did not override playlist tab");
                    original.queue().select(0); // Keep addition checks silent; an existing selection must not restart playback.
                    input().setValue("43"); enter(true);
                    if (!original.queue().contains(new SongId(43)) || !input().getValue().isEmpty())
                        throw new IllegalStateException("Song ID was not added");
                    input().setValue("https://music.163.com/song?id=0"); enter(false);
                    if (!(game.screen instanceof PlayerScreen) || original.queue().entries().size() != 2 || input().getValue().isEmpty())
                        throw new IllegalStateException("Invalid URL triggered search/add or lost input");
                    if (player.track(new SongId(43)) == null) throw new IllegalStateException("NetEase ID did not load its name");
                    input().setValue("https://music.163.com/song?id=44"); enter(false);
                    if (player.track(new SongId(44)) == null || player.queue().index() != 0)
                        throw new IllegalStateException("NetEase URL did not load its name or changed selection");
                    game.getWindow().setWindowed(1600, 900); game.resizeDisplay(); step = 22; frames = 8;
                }
                case 22 -> {
                    capture("link-netease-names-wide.png");
                    player.close();
                    player = new PlaybackController(new MultiPlatformProvider(new Fixture(), qqLink, kugouLink), (uri, max) -> new byte[0], new SilentOutput(), game::execute);
                    player.addSearchTracks(player.searchDestinations().get(0), List.of(track(42))).join();
                    player.switchPlatform();
                    game.getWindow().setWindowed(1600, 900); game.resizeDisplay();
                    game.setScreen(new PlayerScreen(game.screen, player)); step = 17; frames = 8;
                }
                case 17 -> {
                    input().setValue("https://i.y.qq.com/v8/playsong.html?songid=216494081#webchat_redirect"); enter(false);
                    if (!player.importing() || player.queue().contains(qqLink.song.id())) throw new IllegalStateException("Numeric QQ import skipped metadata lookup");
                    qqLink.result.complete(qqLink.song); step = 18; frames = 8;
                }
                case 18 -> {
                    if (!qqLink.song.equals(player.track(qqLink.song.id())) || player.queue().index() != 0)
                        throw new IllegalStateException("Imported QQ title missing or selection changed");
                    checkImportStatus("已导入 1 首歌曲。"); capture("link-qq-title-wide.png");
                    input().setValue("https://y.qq.com/n/ryqq_v2/player?mid=001NKo0b2Z4uGu&type=0&t=2"); enter(false);
                    step = 19; frames = 8;
                }
                case 19 -> {
                    checkImportStatus("已导入 0 首，跳过 1 首重复歌曲。"); capture("link-qq-duplicate-wide.png");
                    if (player.queue().entries().size() != 2) throw new IllegalStateException("Duplicate QQ link inserted");
                    player.switchPlatform(); kugouLink.result.complete(kugouLink.song);
                    game.setScreen(new PlayerScreen(game.screen, player));
                    input().setValue("https://www.kugou.com/song/#g2ln2xf9"); enter(true); step = 20; frames = 8;
                }
                case 20 -> {
                    checkImportStatus("已导入 1 首歌曲。");
                    input().setValue("https://www.kugou.com/song/#g2ln2xf9"); enter(false); step = 21; frames = 8;
                }
                case 21 -> {
                    checkImportStatus("已导入 0 首，跳过 1 首重复歌曲。"); capture("link-kugou-duplicate-wide.png");
                    if (player.queue().entries().size() != 3 || !kugouLink.song.equals(player.track(kugouLink.song.id())))
                        throw new IllegalStateException("Kugou metadata or duplicate handling failed");
                    finish("PASS: search UI, Enter/keypad routing, tabs, pagination, selection, destinations, duplicates, resize, errors/cancellation; NetEase ID/URL names, QQ numeric title and QQ/Kugou duplicate import text rendered in Chinese");
                }
            }
        } catch (Throwable failure) {
            ModernMusic.LOGGER.error("Search smoke failed at step {}", step, failure);
            finish("FAIL: step=" + step + " " + failure.getClass().getSimpleName());
        }
    }
    private static Track track(long id) { return new Track(new SongId(id), "晴天 · 测试歌曲 " + id, List.of("测试歌手", "另一位歌手"), 269000); }
    private void checkImportStatus(String expected) {
        String text = net.minecraft.network.chat.Component.translatable(player.importMessage(), player.importedCount(), player.importTotal(), player.importSkipped()).getString();
        if (!text.equals(expected)) throw new IllegalStateException("Import status failed to format: " + text);
    }
    private static final class LinkFixture implements MusicProvider {
        final Track song;
        final CompletableFuture<Track> result = new CompletableFuture<>();
        LinkFixture(Track song) { this.song = song; }
        public CompletableFuture<Track> getReference(String reference) {
            if (song.id().platform() == MusicPlatform.QQ) QqReferenceParser.song(reference); else KugouReferenceParser.song(reference);
            return result;
        }
        public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError("Import should not start playback"); }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { throw new AssertionError("Import should not resolve audio"); }
        public void close() {}
    }
    private static final class Fixture implements MusicProvider {
        long playlistId;
        final CompletableFuture<SongSearchResult> pending = new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
        public CompletableFuture<SongSearchResult> searchSongs(String query, int offset, int limit) {
            if (query.equals("pending")) return pending;
            if (query.equals("error")) return CompletableFuture.failedFuture(new MusicException(MusicError.NETWORK_ERROR));
            if (query.equals("empty")) return CompletableFuture.completedFuture(new SongSearchResult(List.of(), 0, false));
            var tracks = new ArrayList<Track>(); for (int i = 0; i < limit; i++) tracks.add(track(offset + i + 1));
            return CompletableFuture.completedFuture(new SongSearchResult(tracks, 100, offset + limit < 100));
        }
        public CompletableFuture<Track> getTrack(SongId id) { throw new AssertionError("Search must not start playback"); }
        public CompletableFuture<List<Track>> getTracks(List<SongId> ids) {
            return CompletableFuture.completedFuture(ids.stream().map(id -> track(id.value())).toList());
        }
        public CompletableFuture<Playlist> getPlaylist(SongId id) {
            playlistId = id.value();
            return CompletableFuture.completedFuture(new Playlist("测试歌单", List.of(new SongId(42)), List.of(track(42)), 1));
        }
        public CompletableFuture<PlaybackResource> resolvePlayback(SongId id) { throw new AssertionError("Search must not resolve playback"); }
        public void close() {}
    }
    private static final class SilentOutput implements AudioOutput {
        public void load(PcmAudio audio) { throw new AssertionError("Unexpected playback"); }
        public void pause(boolean paused) {}
        public void seek(long position) {}
        public void volume(float volume) {}
        public void stop() {}
        public Snapshot snapshot() { return new Snapshot(State.STOPPED, 0, 0); }
        public void close() {}
    }
    private EditBox input() {
        return Minecraft.getInstance().screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private void enter(boolean keypad) {
        var screen = Minecraft.getInstance().screen;
        screen.setFocused(input()); input().setFocused(true);
        if (!screen.keyPressed(keypad ? GLFW.GLFW_KEY_KP_ENTER : GLFW.GLFW_KEY_ENTER, 0, 0))
            throw new IllegalStateException("Enter was not handled");
    }
    private boolean hasButton(String key) {
        return Minecraft.getInstance().screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .anyMatch(button -> button.visible && button.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key));
    }
    private void pressFirstSong() { press("modernmusic.search.select_song"); }
    private void checkDuplicateSelection() {
        if (destination.queue().entries().size() != player.search().limit() * 2) throw new IllegalStateException("Duplicate search song inserted");
        if (Minecraft.getInstance().screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .anyMatch(button -> button.active && button.getMessage().getContents() instanceof TranslatableContents text
                        && text.getKey().equals("modernmusic.search.add_selection")))
            throw new IllegalStateException("Processed duplicate selection was retained");
    }
    private void press(String key) {
        var screen = Minecraft.getInstance().screen; screen.tick();
        click(screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals(key))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing button " + key)));
    }
    private void pressLiteral(String label) {
        click(Minecraft.getInstance().screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().equals(label)).findFirst().orElseThrow());
    }
    private void click(Button button) {
        var screen = Minecraft.getInstance().screen;
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        if (screen instanceof dev.modernmusic.client.ui.MusicScreen music) {
            x = music.toGuiCoordinate(x); y = music.toGuiCoordinate(y);
        }
        if (!screen.mouseClicked(x, y, 0)) throw new IllegalStateException("Missed button"); screen.mouseReleased(x, y, 0);
    }
    private void capture(String name) throws Exception {
        var game = Minecraft.getInstance(); var screen = game.screen;
        for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible
                && (widget.getX() < 0 || widget.getY() < 0 || SmokeCompat.right(widget) > screen.width || SmokeCompat.bottom(widget) > screen.height))
            throw new IllegalStateException("Widget outside viewport");
        try (var frame = Screenshot.takeScreenshot(game.getMainRenderTarget())) { frame.writeToFile(Path.of(name)); }
    }
    private void finish(String result) {
        step = -1;
        try { Files.writeString(Path.of("search-result.txt"), result + System.lineSeparator()); }
        catch (Exception ignored) { ModernMusic.LOGGER.error("Cannot write search smoke marker"); }
        ModernMusic.LOGGER.info("{}", result); if (player != null) player.close(); Minecraft.getInstance().stop();
    }
}
