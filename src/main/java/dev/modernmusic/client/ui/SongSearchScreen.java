package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.PlaybackController.SearchDestination;
import dev.modernmusic.client.SongSearchController;
import dev.modernmusic.domain.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Search, multi-selection and destination picking stay in one screen, including compact windows. */
public final class SongSearchScreen extends MusicScreen {
    private final Screen parent;
    private final PlaybackController player;
    private final SongSearchController search;
    private final LinkedHashMap<SongId, Track> selected = new LinkedHashMap<>();
    private final List<ResultRow> rows = new ArrayList<>();
    private EditBox input;
    private ThemedButton submit, previous, next, add, destinationButton, selectPage, clear;
    private SearchDestination destination;
    private CompletableFuture<PlaybackController.SelectionResult> pendingAdd;
    private List<Track> submitted = List.of();
    private String draft;
    private Component notice;
    private boolean picking, searchOnOpen;
    private int destinationPage, left, top, panelWidth, panelHeight, pageSize, resultsY, rowHeight, pagerOffset;
    private long resetRevision;

    public SongSearchScreen(Screen parent, PlaybackController player) {
        this(parent, player, "");
    }
    public SongSearchScreen(Screen parent, PlaybackController player, String initialQuery) {
        this(parent, player, initialQuery, false);
    }
    public SongSearchScreen(Screen parent, PlaybackController player, String initialQuery, boolean personalDestination) {
        super(Component.translatable(player.platform().key("search.title")));
        this.parent = parent; this.player = player; this.search = player.search();
        draft = initialQuery.strip(); searchOnOpen = !draft.isEmpty();
        search.reset(); resetRevision = search.resetRevision();
        destination = player.searchDestinations().stream().filter(entry -> (!personalDestination && entry.shared())
                || entry.id().equals(player.library().active().id())).findFirst().orElse(null);
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        rows.clear(); input = null;
        panelWidth = playerPanelWidth(width); panelHeight = playerPanelHeight(height);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        boolean small = panelHeight < 270;
        resultsY = small ? 64 : 74; rowHeight = small ? 26 : 30; pagerOffset = small ? 98 : 100;
        pageSize = Math.clamp((panelHeight - pagerOffset - 6 - resultsY) / rowHeight, 1, SongSearchResult.MAX_PAGE_SIZE);
        addRenderableWidget(new IconButton(left + panelWidth - 74, top + 6, 60, 20, Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
        if (picking) { initDestinations(); return; }
        input = addRenderableWidget(new ThemedEditBox(font, left + 14, top + (small ? 38 : 40), panelWidth - 98, small ? 24 : 26,
                Component.translatable(player.platform().key("search.hint")), Icon.SEARCH));
        // Preserve imported text so an oversized query reports validation instead of silently searching a prefix.
        input.setMaxLength(Math.max(SongSearchResult.MAX_QUERY_LENGTH, draft.length())); input.setValue(draft);
        input.setHint(Component.translatable(player.platform().key("search.hint")).withColor(FIELD_HINT));
        input.setResponder(value -> draft = value);
        submit = addRenderableWidget(new IconButton(left + panelWidth - 78, top + (small ? 38 : 40), 64, small ? 24 : 26, Component.translatable("modernmusic.search.open"), Icon.SEARCH, true, true, this::newSearch));
        for (int i = 0; i < pageSize; i++) {
            final int index = i;
            var row = new ResultRow(left + 16, top + resultsY + 2 + i * rowHeight, panelWidth - 32, () -> toggle(index));
            row.ghost(); rows.add(addRenderableWidget(row));
        }
        int bottom = top + panelHeight;
        previous = addRenderableWidget(new IconButton(left + 14, bottom - pagerOffset, 66, 20, Component.translatable("modernmusic.previous_page"), Icon.LEFT, false, true, () -> page(-1)));
        next = addRenderableWidget(new IconButton(left + panelWidth - 80, bottom - pagerOffset, 66, 20, Component.translatable("modernmusic.next_page"), Icon.RIGHT, false, true, () -> page(1)));
        previous.bare(); next.bare();
        selectPage = addRenderableWidget(new IconButton(left + 14, bottom - 74, 92, 20, Component.translatable("modernmusic.search.select_page"), Icon.CHECK, false, true, this::selectPage));
        clear = button("modernmusic.search.clear_selection", left + 112, bottom - 74, 82, 20, false, () -> { selected.clear(); notice = null; refresh(); });
        destinationButton = addRenderableWidget(new IconButton(left + 14, bottom - 48, panelWidth - 132, 24, Component.translatable("modernmusic.search.destination"), Icon.QUEUE, false, true,
                () -> { picking = true; destinationPage = 0; rebuildWidgets(); }));
        add = addRenderableWidget(new IconButton(left + panelWidth - 112, bottom - 48, 98, 24, Component.translatable("modernmusic.search.add_selection"), Icon.PLUS, true, true, this::addSelection));
        if (searchOnOpen) {
            searchOnOpen = false;
            newSearch();
        } else if (!search.query().isEmpty() && search.limit() != pageSize)
            search.search(search.query(), search.offset() / pageSize * pageSize, pageSize);
        refresh(); setInitialFocus(input);
    }
    private ThemedButton button(String key, int x, int y, int w, int h, boolean primary, Runnable action) {
        return addRenderableWidget(new ThemedButton(x, y, w, h, Component.translatable(key), primary, action));
    }
    private void newSearch() {
        if (pendingAdd != null) return;
        selected.clear(); notice = null; search.search(draft, 0, pageSize); refresh();
    }
    private void page(int direction) {
        if (search.loading() || pendingAdd != null) return;
        if (direction < 0 && search.offset() == 0 || direction > 0 && (search.result() == null || !search.result().hasMore())) return;
        notice = null; search.search(search.query(), Math.max(0, search.offset() + direction * pageSize), pageSize); refresh();
    }
    private void toggle(int index) {
        if (search.result() == null || index >= search.result().tracks().size() || pendingAdd != null) return;
        Track track = search.result().tracks().get(index);
        if (selected.containsKey(track.id())) selected.remove(track.id());
        else if (selected.size() < PlaybackQueue.MAX_TRACKS) selected.put(track.id(), track);
        else { notice = Component.translatable("modernmusic.search.selection_limit"); return; }
        notice = null; refresh();
    }
    private void selectPage() {
        if (search.result() == null || pendingAdd != null) return;
        for (Track track : search.result().tracks()) {
            if (selected.size() >= PlaybackQueue.MAX_TRACKS) break;
            selected.put(track.id(), track);
        }
        notice = null; refresh();
    }
    private void addSelection() {
        if (!add.active) return;
        submitted = List.copyOf(selected.values());
        pendingAdd = player.addSearchTracks(destination, submitted);
        notice = Component.translatable("modernmusic.search.adding"); refresh();
    }
    @Override public void tick() {
        if (resetRevision != search.resetRevision()) {
            resetRevision = search.resetRevision(); selected.clear(); notice = null;
        }
        if (pendingAdd != null && pendingAdd.isDone()) {
            if (pendingAdd.isCompletedExceptionally()) notice = Component.translatable("modernmusic.search.destination_unavailable");
            else {
                var result = pendingAdd.join();
                for (Track song : submitted.subList(0, result.processed())) selected.remove(song.id());
                notice = Component.translatable(result.message(), result.added(), result.total(), destination.name(), result.skipped());
            }
            pendingAdd = null; submitted = List.of();
        }
        if (!picking) refresh();
    }
    private void refresh() {
        boolean busy = pendingAdd != null;
        submit.active = !draft.isBlank() && !busy;
        input.setEditable(!busy);
        previous.active = !busy && !search.loading() && search.offset() > 0;
        next.active = !busy && !search.loading() && search.result() != null && search.result().hasMore();
        selectPage.active = !busy && search.result() != null && !search.result().tracks().isEmpty() && selected.size() < PlaybackQueue.MAX_TRACKS;
        clear.active = !busy && !selected.isEmpty();
        destinationButton.active = !busy && player.queuesReady();
        boolean available = destination != null && player.searchDestinations().stream().anyMatch(entry -> sameDestination(entry, destination));
        destinationButton.setMessage(Component.translatable("modernmusic.search.target", destination == null ? "—" : destinationName(destination)));
        destinationButton.setTooltip(Tooltip.create(destinationButton.getMessage()));
        var rooms = dev.modernmusic.client.RoomClient.instance();
        boolean request = destination == null || !destination.shared() || rooms != null && rooms.canRequest();
        add.active = !busy && player.queuesReady() && !selected.isEmpty() && available && request;
        add.setTooltip(ThemedTooltips.create(request ? add.getMessage() : Component.translatable("modernmusic.room.error.request_permission")));
        for (int i = 0; i < rows.size(); i++) {
            ResultRow row = rows.get(i);
            row.track = search.result() != null && i < search.result().tracks().size() ? search.result().tracks().get(i) : null;
            row.visible = row.track != null; row.active = !busy;
            if (row.track == null) continue;
            row.selected(selected.containsKey(row.track.id()));
            row.setMessage(Component.translatable(selected.containsKey(row.track.id()) ? "modernmusic.search.selected_song" : "modernmusic.search.select_song",
                    row.track.title(), row.track.artistText()));
            row.setTooltip(Tooltip.create(row.getMessage().copy().append(" · " + row.track.id())));
        }
    }
    private static boolean sameDestination(SearchDestination a, SearchDestination b) {
        return a.id().equals(b.id()) && Objects.equals(a.roomEpoch(), b.roomEpoch());
    }
    private Component destinationName(SearchDestination entry) {
        return entry.shared() ? Component.translatable("modernmusic.search.room_target", entry.name()) : Component.literal(entry.name());
    }
    private void initDestinations() {
        var destinations = player.searchDestinations();
        int count = Math.max(1, (panelHeight - 100) / 28);
        destinationPage = Math.clamp(destinationPage, 0, (destinations.size() - 1) / count);
        for (int i = 0; i < count && destinationPage * count + i < destinations.size(); i++) {
            var entry = destinations.get(destinationPage * count + i);
            var item = addRenderableWidget(new IconButton(left + 14, top + 60 + i * 28, panelWidth - 28, 24,
                    destinationName(entry), entry.shared() ? Icon.GROUP : Icon.QUEUE, false, true, () -> { destination = entry; picking = false; notice = null; rebuildWidgets(); }));
            item.selected(destination != null && sameDestination(destination, entry));
            item.active = player.queuesReady();
        }
        var back = addRenderableWidget(new IconButton(left + 14, top + panelHeight - 32, 66, 20, Component.translatable("modernmusic.previous_page"), Icon.LEFT, false, true,
                () -> { destinationPage--; rebuildWidgets(); }));
        var forward = addRenderableWidget(new IconButton(left + panelWidth - 80, top + panelHeight - 32, 66, 20, Component.translatable("modernmusic.next_page"), Icon.RIGHT, false, true,
                () -> { destinationPage++; rebuildWidgets(); }));
        back.active = destinationPage > 0; forward.active = (destinationPage + 1) * count < destinations.size();
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        if (client().level == null) renderPanorama(g, delta);
        renderBlurredBackground(delta);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
        if (!picking) well(g, left + 10, top + resultsY - 2, panelWidth - 20, pageSize * rowHeight + 4, 10);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        super.renderMusic(g, x, y, delta);
        icon(g, Icon.SEARCH, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        if (picking) {
            g.drawString(font, Component.translatable("modernmusic.search.destination"), left + 14, top + 44, MUTED, false);
            return;
        }
        if (search.result() == null || search.result().tracks().isEmpty()) {
            int area = pageSize * rowHeight;
            boolean roomy = area >= 90, loading = search.loading();
            int centerY = top + resultsY + 2 + (roomy ? Math.min(54, area / 2) : area / 2);
            if (roomy) icon(g, loading ? Icon.MUSIC : Icon.SEARCH, width / 2, centerY - 11, loading ? ACCENT : QUIET);
            var lines = font.split(Component.translatable(search.message()), panelWidth - 64);
            int count = Math.min(lines.size(), Math.max(1, (area - (roomy ? 60 : 4)) / 12)), textY = roomy ? centerY + 14 : centerY - count * 6 + 1;
            for (int i = 0; i < count; i++) g.drawString(font, lines.get(i), width / 2 - font.width(lines.get(i)) / 2, textY + i * 12, MUTED, false);
        }
        String pageLabel = Component.translatable("modernmusic.search.page", search.offset() / pageSize + 1,
                search.result() == null ? "—" : search.result().total()).getString();
        centered(g, ellipsis(font, pageLabel, panelWidth - 176), width / 2, top + panelHeight - pagerOffset + 6, MUTED);
        String count = ellipsis(font, Component.translatable("modernmusic.search.selected_count", selected.size()).getString(), panelWidth - 222);
        if (!selected.isEmpty()) badge(g, left + 202, top + panelHeight - 74, font.width(count) + 16, 20, SELECTED);
        g.drawString(font, count, left + 210, top + panelHeight - 68, selected.isEmpty() ? MUTED : ACCENT, false);
        String storageMessage = player.queueStorageMessage();
        Component status = notice != null ? notice : Component.translatable(storageMessage != null ? storageMessage : search.message());
        g.drawString(font, ellipsis(font, status.getString(), panelWidth - 28), left + 14, top + panelHeight - 17, MUTED, false);
        if (x >= left + 14 && x < left + panelWidth - 14 && y >= top + panelHeight - 22 && y < top + panelHeight)
            g.renderTooltip(font, font.split(status, panelWidth - 28), x, y);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!picking && input.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) { newSearch(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override protected boolean scrollMusic(double x, double y, double horizontal, double vertical) {
        if (!picking && vertical != 0 && x >= left && x < left + panelWidth && y >= top + resultsY - 2 && y < top + panelHeight - 97) {
            page(vertical > 0 ? -1 : 1); return true;
        }
        return super.scrollMusic(x, y, horizontal, vertical);
    }
    @Override public void onClose() {
        if (picking) { picking = false; rebuildWidgets(); } else client().setScreen(parent);
    }
    @Override public void removed() { search.reset(); }

    private final class ResultRow extends ThemedButton {
        private Track track;
        private ResultRow(int x, int y, int width, Runnable action) { super(x, y, width, rowHeight - 2, Component.empty(), false, action); }
        @Override protected void drawContent(GuiGraphics g, int color) {
            if (track == null) return;
            boolean chosen = selected.containsKey(track.id());
            int centerY = getY() + height / 2;
            if (skin() != null) checkbox(g, getX() + 6, centerY - 8, chosen, hover() > .5f);
            else if (blocky()) {
                // A Bedrock checkbox: a sunken box that turns into a raised green face with a check.
                int bx = getX() + 6, by = centerY - 8;
                if (chosen) { raised(g, bx, by, 16, 16, ORE_GREEN, ORE_GREEN_HI, ORE_GREEN_DEPTH, false); icon(g, Icon.CHECK, bx + 8, by + 7, 0xFFFFFFFF); }
                else { g.fill(bx, by, bx + 16, by + 16, ORE_OUTLINE); g.fill(bx + 1, by + 1, bx + 15, by + 15, mix(ORE_HEADER, 0xFF4A4B4D, hover())); }
            } else {
                if (chosen) dot(g, getX() + 13.5f, centerY, 7, ACCENT);
                else Shapes.ring(g, getX() + 6.5f, centerY - 7, 14, 14, 7, 1, mix(alpha(OVERLAY, .35), ACCENT, hover()));
                g.pose().pushPose(); g.pose().translate(getX() + 13, centerY - .5f, 0); g.pose().scale(.75f, .75f, 1);
                icon(g, chosen ? Icon.CHECK : Icon.PLUS, 0, 0, chosen ? INK : mix(MUTED, ACCENT, hover()));
                g.pose().popPose();
            }
            g.drawString(font, ellipsis(font, track.title(), width - 86), getX() + 30, getY() + height / 2 - 10, chosen ? ACCENT : TEXT, false);
            g.drawString(font, ellipsis(font, track.artistText(), width - 86), getX() + 30, getY() + height / 2 + 2, MUTED, false);
            String duration = String.format(Locale.ROOT, "%d:%02d", track.durationMs() / 60000, track.durationMs() / 1000 % 60);
            g.drawString(font, duration, getX() + width - 10 - font.width(duration), getY() + (height - 8) / 2, QUIET, false);
        }
    }
}
