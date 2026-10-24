package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.LocalAudioProvider;
import dev.modernmusic.client.PlaybackController.SearchDestination;
import dev.modernmusic.domain.PlaybackQueue;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.Track;
import dev.modernmusic.provider.MusicException;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Browse the two audio libraries while preserving the distinction between private and server files. */
public final class AudioBrowserScreen extends MusicScreen {
    private final Screen parent;
    private final PlaybackController player;
    private final LinkedHashMap<SongId, Track> selected = new LinkedHashMap<>();
    private final List<AudioRow> rows = new ArrayList<>();
    private List<Track> tracks = List.of(), filtered = List.of(), submitted = List.of();
    private CompletableFuture<List<Track>> pendingLoad;
    private CompletableFuture<Track> pendingUpload;
    private CompletableFuture<PlaybackController.SelectionResult> pendingAdd;
    private CompletableFuture<LocalAudioProvider.DropResult> pendingDrop;
    private List<Track> selectionAfterLoad = List.of();
    private SearchDestination destination;
    private EditBox input;
    private ThemedButton localTab, serverTab, reload, previous, next, selectPage, clear, upload, destinationButton, add;
    private String filter;
    private Component notice, detailedNotice, noticeDetails;
    private boolean server, picking, loaded;
    private int page, destinationPage, left, top, panelWidth, panelHeight, pageSize, rowHeight, resultsY;

    public AudioBrowserScreen(Screen parent, PlaybackController player, String initialFilter) {
        this(parent, player, initialFilter, false);
    }
    public AudioBrowserScreen(Screen parent, PlaybackController player, String initialFilter, boolean roomDestination) {
        super(Component.translatable("modernmusic.audio.title"));
        this.parent = parent; this.player = player; filter = initialFilter.strip(); server = roomDestination;
        destination = player.searchDestinations().stream()
                .filter(entry -> roomDestination && entry.shared() || !entry.shared() && entry.id().equals(player.library().active().id()))
                .findFirst().orElse(null);
    }

    @Override protected void initMusic() {
        font = UiFont.get(); rows.clear();
        panelWidth = playerPanelWidth(width); panelHeight = playerPanelHeight(height);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        boolean compact = panelHeight < 250;
        resultsY = compact ? 88 : 98; rowHeight = panelHeight < 300 ? 26 : 30;
        pageSize = Math.max(1, (panelHeight - 104 - resultsY) / rowHeight);
        addRenderableWidget(new IconButton(left + panelWidth - 74, top + 6, 60, 20,
                Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
        if (picking) { initDestinations(); return; }
        int toolsY = top + (compact ? 36 : 38), toolsHeight = compact ? 20 : 24;
        localTab = button("modernmusic.audio.local", left + 14, toolsY, 62, toolsHeight, false, () -> switchSource(false));
        serverTab = button("modernmusic.audio.server", left + 82, toolsY, 70, toolsHeight, false, () -> switchSource(true));
        var folder = button("modernmusic.audio.open_folder", left + panelWidth - 138, toolsY, 86, toolsHeight, false,
                () -> Util.getPlatform().openPath(player.localAudio().directory()));
        folder.setTooltip(Tooltip.create(Component.translatable("modernmusic.audio.folder_help", player.localAudio().directory().toString())));
        reload = addRenderableWidget(new IconButton(left + panelWidth - 46, toolsY, 32, toolsHeight,
                Component.translatable("modernmusic.audio.refresh"), Icon.REPEAT, false, false, this::load));
        input = addRenderableWidget(new ThemedEditBox(font, left + 14, top + (compact ? 60 : 68), panelWidth - 28, compact ? 22 : 24,
                Component.translatable("modernmusic.audio.filter"), Icon.SEARCH));
        input.setMaxLength(256); input.setValue(filter);
        input.setHint(Component.translatable("modernmusic.audio.filter").withColor(FIELD_HINT));
        input.setResponder(value -> { filter = value; page = 0; filterTracks(); refresh(); });
        for (int i = 0; i < pageSize; i++) {
            final int index = i;
            var row = new AudioRow(left + 16, top + resultsY + 2 + i * rowHeight, panelWidth - 32, () -> toggle(index));
            row.ghost(); rows.add(addRenderableWidget(row));
        }
        int bottom = top + panelHeight;
        previous = addRenderableWidget(new IconButton(left + 14, bottom - 100, 66, 20,
                Component.translatable("modernmusic.previous_page"), Icon.LEFT, false, true, () -> changePage(-1)));
        next = addRenderableWidget(new IconButton(left + panelWidth - 80, bottom - 100, 66, 20,
                Component.translatable("modernmusic.next_page"), Icon.RIGHT, false, true, () -> changePage(1)));
        previous.bare(); next.bare();
        selectPage = button("modernmusic.search.select_page", left + 14, bottom - 74, 78, 20, false, this::selectPage);
        clear = button("modernmusic.search.clear_selection", left + 98, bottom - 74, 78, 20, false,
                () -> { selected.clear(); notice = null; refresh(); });
        upload = button("modernmusic.audio.upload", left + panelWidth - 112, bottom - 74, 98, 20, false, this::upload);
        destinationButton = addRenderableWidget(new IconButton(left + 14, bottom - 48, panelWidth - 132, 24,
                Component.translatable("modernmusic.search.destination"), Icon.QUEUE, false, true,
                () -> { picking = true; destinationPage = 0; rebuildWidgets(); }));
        add = addRenderableWidget(new IconButton(left + panelWidth - 112, bottom - 48, 98, 24,
                Component.translatable("modernmusic.search.add_selection"), Icon.PLUS, true, true, this::addSelection));
        filterTracks();
        if (!loaded && pendingLoad == null) load();
        refresh(); setInitialFocus(input);
    }

    private ThemedButton button(String key, int x, int y, int w, int h, boolean primary, Runnable action) {
        return addRenderableWidget(new ThemedButton(x, y, w, h, Component.translatable(key), primary, action));
    }
    private boolean busy() { return pendingUpload != null || pendingAdd != null || pendingDrop != null; }
    @ParametersAreNonnullByDefault @Override public void onFilesDrop(List<Path> paths) {
        if (paths.isEmpty()) return;
        if (picking) { picking = false; rebuildWidgets(); }
        if (busy()) { notice = Component.translatable("modernmusic.audio.drop_busy"); refresh(); return; }
        if (paths.size() > LocalAudioProvider.MAX_DROPPED_FILES) { notice = Component.translatable("modernmusic.audio.drop_limit"); refresh(); return; }
        if (server && (!player.localAudio().serverAvailable() || !player.localAudio().canUpload())) {
            notice = Component.translatable(player.localAudio().serverAvailable()
                    ? "modernmusic.audio.upload_permission" : "modernmusic.audio.server_unavailable");
            refresh(); return;
        }
        // Ignore a catalogue begun before this import, then request one fresh catalogue after the batch finishes.
        pendingLoad = null; selectionAfterLoad = List.of();
        pendingDrop = player.localAudio().importDropped(List.copyOf(paths), server);
        notice = Component.translatable(server ? "modernmusic.audio.drop_uploading" : "modernmusic.audio.drop_importing", paths.size());
        refresh();
    }
    private void switchSource(boolean useServer) {
        if (busy() || server == useServer) return;
        server = useServer; tracks = List.of(); selected.clear(); page = 0;
        filterTracks();
        load();
    }
    private void load() {
        if (busy()) return;
        notice = null; loaded = false;
        pendingLoad = server ? player.localAudio().browseServer() : player.localAudio().browseLocal();
        refresh();
    }
    private void filterTracks() {
        String query = filter.strip().toLowerCase(Locale.ROOT);
        filtered = tracks.stream().filter(track -> query.isEmpty() || track.title().toLowerCase(Locale.ROOT).contains(query)
                || track.artistText().toLowerCase(Locale.ROOT).contains(query)).toList();
        page = Math.clamp(page, 0, Math.max(0, (filtered.size() - 1) / pageSize));
    }
    private void changePage(int delta) {
        if (busy() || pendingLoad != null) return;
        page = Math.clamp(page + delta, 0, Math.max(0, (filtered.size() - 1) / pageSize)); refresh();
    }
    private void toggle(int row) {
        int index = page * pageSize + row;
        if (busy() || pendingLoad != null || index >= filtered.size()) return;
        Track track = filtered.get(index);
        if (selected.containsKey(track.id())) selected.remove(track.id());
        else if (selected.size() < PlaybackQueue.MAX_TRACKS) selected.put(track.id(), track);
        else { notice = Component.translatable("modernmusic.search.selection_limit"); return; }
        notice = null; refresh();
    }
    private void selectPage() {
        if (busy() || pendingLoad != null) return;
        for (int i = page * pageSize; i < Math.min(filtered.size(), (page + 1) * pageSize); i++) {
            if (selected.size() >= PlaybackQueue.MAX_TRACKS) break;
            Track track = filtered.get(i); selected.put(track.id(), track);
        }
        notice = null; refresh();
    }
    private void upload() {
        if (!upload.active) return;
        pendingUpload = player.localAudio().upload(selected.values().iterator().next());
        notice = Component.translatable("modernmusic.audio.uploading"); refresh();
    }
    private void addSelection() {
        if (!add.active) return;
        submitted = List.copyOf(selected.values());
        pendingAdd = player.addSearchTracks(destination, submitted);
        notice = Component.translatable("modernmusic.search.adding"); refresh();
    }
    @Override public void tick() {
        if (pendingLoad != null && pendingLoad.isDone()) {
            try {
                tracks = List.copyOf(pendingLoad.join()); loaded = true;
                var ids = tracks.stream().map(Track::id).collect(java.util.stream.Collectors.toSet());
                selected.keySet().retainAll(ids);
                if (!selectionAfterLoad.isEmpty()) {
                    for (Track added : selectionAfterLoad) if (ids.contains(added.id())) selected.put(added.id(), added);
                    var first = selectionAfterLoad.getFirst().id();
                    for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id().equals(first)) { page = i / pageSize; break; }
                }
            } catch (RuntimeException failure) { tracks = List.of(); selected.clear(); notice = failureMessage(failure); }
            pendingLoad = null; selectionAfterLoad = List.of(); filterTracks();
        }
        if (pendingUpload != null && pendingUpload.isDone()) {
            try { notice = Component.translatable("modernmusic.audio.uploaded", pendingUpload.join().title()); }
            catch (RuntimeException failure) { notice = failureMessage(failure); }
            pendingUpload = null;
        }
        if (pendingAdd != null && pendingAdd.isDone()) {
            try {
                var result = pendingAdd.join();
                for (Track track : submitted.subList(0, Math.min(submitted.size(), result.processed()))) selected.remove(track.id());
                notice = Component.translatable(result.message(), result.added(), result.total(), destination.name(), result.skipped());
            } catch (RuntimeException failure) { notice = failureMessage(failure); }
            pendingAdd = null; submitted = List.of();
        }
        if (pendingDrop != null && pendingDrop.isDone()) {
            var complete = pendingDrop; pendingDrop = null;
            try {
                var result = complete.join();
                if (!result.tracks().isEmpty()) {
                    selected.clear(); selectionAfterLoad = List.copyOf(result.tracks());
                }
                filter = ""; if (input != null) input.setValue(""); page = 0;
                load();
                notice = Component.translatable(server ? "modernmusic.audio.drop_uploaded" : "modernmusic.audio.drop_imported",
                        result.tracks().size(), result.total(), result.failures().size());
                var details = notice.copy();
                if (!result.tracks().isEmpty()) details.append("\n").append(Component.translatable("modernmusic.audio.drop_selected"));
                int shown = Math.min(5, result.failures().size());
                for (int i = 0; i < shown; i++) {
                    var failure = result.failures().get(i);
                    details.append("\n").append(Component.literal(failure.fileName() + ": "))
                            .append(failureMessage(new MusicException(failure.error())));
                }
                if (result.failures().size() > shown) details.append("\n").append(Component.translatable("modernmusic.audio.drop_more_failures", result.failures().size() - shown));
                detailedNotice = notice; noticeDetails = details;
            } catch (RuntimeException failure) { load(); notice = failureMessage(failure); }
        }
        if (!picking) refresh();
    }
    private Component failureMessage(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof MusicException music && music.error() == dev.modernmusic.provider.MusicError.SHARED_FORBIDDEN)
            return Component.translatable("modernmusic.audio.upload_permission");
        return Component.translatable(failure instanceof MusicException music
                ? music.error().translationKey() : "modernmusic.error.resource_unavailable");
    }
    private void refresh() {
        if (picking || input == null || add == null) return;
        boolean busy = busy(), loading = pendingLoad != null;
        localTab.selected(!server); serverTab.selected(server);
        localTab.active = !busy; serverTab.active = !busy;
        serverTab.setTooltip(Tooltip.create(Component.translatable(player.localAudio().serverAvailable()
                ? "modernmusic.audio.server_help" : "modernmusic.audio.server_unavailable")));
        reload.active = !busy && !loading;
        input.setEditable(!busy);
        previous.active = !busy && !loading && page > 0;
        next.active = !busy && !loading && (page + 1) * pageSize < filtered.size();
        selectPage.active = !busy && !loading && !filtered.isEmpty() && selected.size() < PlaybackQueue.MAX_TRACKS;
        clear.active = !busy && !selected.isEmpty();
        upload.visible = !server;
        upload.active = !busy && !loading && !server && selected.size() == 1 && player.localAudio().canUpload();
        upload.setTooltip(Tooltip.create(Component.translatable(!player.localAudio().serverAvailable()
                ? "modernmusic.audio.server_unavailable" : !player.localAudio().canUpload() ? "modernmusic.audio.upload_permission"
                : selected.size() != 1 ? "modernmusic.audio.upload_select_one" : "modernmusic.audio.upload_help")));
        destinationButton.active = !busy && player.queuesReady();
        destinationButton.setMessage(Component.translatable("modernmusic.search.target", destination == null ? "—" : destinationName(destination)));
        destinationButton.setTooltip(Tooltip.create(destinationButton.getMessage()));
        boolean available = destination != null && player.searchDestinations().stream().anyMatch(entry -> sameDestination(entry, destination));
        boolean localInRoom = destination != null && destination.shared() && selected.values().stream().anyMatch(track -> track.id().isClientLocal());
        var rooms = dev.modernmusic.client.RoomClient.instance();
        boolean request = destination == null || !destination.shared() || rooms != null && rooms.canRequest();
        add.active = !busy && !loading && player.queuesReady() && !selected.isEmpty() && available && request && !localInRoom;
        add.setTooltip(Tooltip.create(localInRoom ? Component.translatable("modernmusic.error.local_audio_only")
                : !request ? Component.translatable("modernmusic.room.error.request_permission") : add.getMessage()));
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); int index = page * pageSize + i;
            row.track = index < filtered.size() ? filtered.get(index) : null;
            row.visible = row.track != null; row.active = !busy && !loading;
            if (row.track == null) continue;
            row.selected(selected.containsKey(row.track.id()));
            row.setMessage(Component.translatable(selected.containsKey(row.track.id()) ? "modernmusic.search.selected_song" : "modernmusic.search.select_song",
                    row.track.title(), row.track.artistText()));
            row.setTooltip(Tooltip.create(row.getMessage()));
        }
    }
    private static boolean sameDestination(SearchDestination first, SearchDestination second) {
        return first.id().equals(second.id()) && Objects.equals(first.roomEpoch(), second.roomEpoch());
    }
    private Component destinationName(SearchDestination entry) {
        return entry.shared() ? Component.translatable("modernmusic.search.room_target", entry.name()) : Component.literal(entry.name());
    }
    private void initDestinations() {
        var destinations = player.searchDestinations();
        int count = Math.max(1, (panelHeight - 100) / 28);
        destinationPage = Math.clamp(destinationPage, 0, Math.max(0, (destinations.size() - 1) / count));
        for (int i = 0; i < count && destinationPage * count + i < destinations.size(); i++) {
            var entry = destinations.get(destinationPage * count + i);
            var item = addRenderableWidget(new IconButton(left + 14, top + 60 + i * 28, panelWidth - 28, 24,
                    destinationName(entry), entry.shared() ? Icon.GROUP : Icon.QUEUE, false, true,
                    () -> { destination = entry; picking = false; notice = null; rebuildWidgets(); }));
            item.selected(destination != null && sameDestination(destination, entry));
            boolean localInRoom = entry.shared() && (!server || selected.values().stream().anyMatch(track -> track.id().isClientLocal()));
            item.active = player.queuesReady() && !localInRoom;
            if (localInRoom) item.setTooltip(Tooltip.create(Component.translatable("modernmusic.error.local_audio_only")));
        }
        var back = addRenderableWidget(new IconButton(left + 14, top + panelHeight - 32, 66, 20,
                Component.translatable("modernmusic.previous_page"), Icon.LEFT, false, true, () -> { destinationPage--; rebuildWidgets(); }));
        var forward = addRenderableWidget(new IconButton(left + panelWidth - 80, top + panelHeight - 32, 66, 20,
                Component.translatable("modernmusic.next_page"), Icon.RIGHT, false, true, () -> { destinationPage++; rebuildWidgets(); }));
        back.active = destinationPage > 0; forward.active = (destinationPage + 1) * count < destinations.size();
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        if (client().level == null) renderPanorama(g, delta);
        renderBlurredBackground(delta); scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12); header(g, left, top, panelWidth, 32);
        if (!picking) well(g, left + 10, top + resultsY - 2, panelWidth - 20, pageSize * rowHeight + 4, 10);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        super.renderMusic(g, x, y, delta);
        icon(g, Icon.MUSIC, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        if (picking) {
            g.drawString(font, Component.translatable("modernmusic.search.destination"), left + 14, top + 44, MUTED, false); return;
        }
        if (filtered.isEmpty()) {
            var empty = pendingDrop != null && notice != null ? notice : Component.translatable(pendingLoad != null ? "modernmusic.audio.loading" : server && !player.localAudio().serverAvailable()
                    ? "modernmusic.audio.server_unavailable" : !filter.isBlank() ? "modernmusic.audio.no_match"
                    : server ? "modernmusic.audio.server_empty" : "modernmusic.audio.local_empty");
            var lines = font.split(empty, panelWidth - 48);
            int count = Math.min(lines.size(), Math.max(1, pageSize * rowHeight / 12));
            int textY = top + resultsY + (pageSize * rowHeight - count * 12) / 2;
            for (int i = 0; i < count; i++) g.drawString(font, lines.get(i), width / 2 - font.width(lines.get(i)) / 2, textY + i * 12, MUTED, false);
        }
        String pageLabel = Component.translatable("modernmusic.audio.page", page + 1, filtered.size(), selected.size()).getString();
        centered(g, ellipsis(font, pageLabel, panelWidth - 176), width / 2, top + panelHeight - 94, MUTED);
        var status = notice != null ? notice : player.queueStorageMessage() != null ? Component.translatable(player.queueStorageMessage())
                : Component.translatable(server ? "modernmusic.audio.server_help" : "modernmusic.audio.local_help");
        g.drawString(font, ellipsis(font, status.getString(), panelWidth - 28), left + 14, top + panelHeight - 17, MUTED, false);
        if (x >= left + 14 && x < left + panelWidth - 14 && y >= top + panelHeight - 22 && y < top + panelHeight)
            g.renderTooltip(font, font.split(notice == detailedNotice && noticeDetails != null ? noticeDetails : status, panelWidth - 28), x, y);
    }
    @Override protected boolean scrollMusic(double x, double y, double horizontal, double vertical) {
        if (!picking && vertical != 0 && x >= left && x < left + panelWidth && y >= top + resultsY && y < top + panelHeight - 100) {
            changePage(vertical > 0 ? -1 : 1); return true;
        }
        return super.scrollMusic(x, y, horizontal, vertical);
    }
    @Override public void onClose() {
        if (picking) { picking = false; rebuildWidgets(); } else client().setScreen(parent);
    }

    private final class AudioRow extends ThemedButton {
        private Track track;
        private AudioRow(int x, int y, int width, Runnable action) { super(x, y, width, rowHeight - 2, Component.empty(), false, action); }
        @Override protected void drawContent(GuiGraphics g, int color) {
            if (track == null) return;
            boolean chosen = selected.containsKey(track.id());
            int centerY = getY() + height / 2;
            icon(g, chosen ? Icon.CHECK : Icon.PLUS, getX() + 13, centerY, chosen ? ACCENT : MUTED);
            g.drawString(font, ellipsis(font, track.title(), width - 86), getX() + 30, centerY - 10, chosen ? ACCENT : TEXT, false);
            String detail = track.artistText().isBlank() ? Component.translatable(server ? "modernmusic.audio.server" : "modernmusic.audio.local").getString() : track.artistText();
            g.drawString(font, ellipsis(font, detail, width - 86), getX() + 30, centerY + 2, MUTED, false);
            String duration = String.format(Locale.ROOT, "%d:%02d", track.durationMs() / 60000, track.durationMs() / 1000 % 60);
            g.drawString(font, duration, getX() + width - 10 - font.width(duration), centerY - 4, QUIET, false);
        }
    }
}
