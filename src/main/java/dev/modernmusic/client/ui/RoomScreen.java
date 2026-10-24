package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlayerInputAction;
import dev.modernmusic.client.RoomClient;
import dev.modernmusic.domain.SongId;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.SongReferenceParser;
import dev.modernmusic.room.RoomCommand.Action;
import dev.modernmusic.room.RoomSnapshot;
import dev.modernmusic.room.RoomUpdate;
import dev.modernmusic.room.TeamStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Full-size room workspace with members and an independent shared queue. */
public final class RoomScreen extends MusicScreen {
    private final Screen parent;
    private final RoomClient rooms;
    private final List<MemberRow> members = new ArrayList<>();
    private final List<SongRow> songs = new ArrayList<>();
    private final List<IconButton> removes = new ArrayList<>();
    private final List<ThemedButton> menuButtons = new ArrayList<>();
    private final List<LobbyEntry> lobbyEntries = new ArrayList<>();
    private EditBox input, musicInput;
    private IconButton listen, pause, stop, next, mode, clear, search, previousPage, nextPage, submit;
    private IconButton importButton, inputType, platform, leave, dissolve;
    private int searchY, searchWidth;
    private boolean playlistInput, clearingSubmittedInput;
    private UUID musicRoom, musicEpoch;
    private String inputError;
    private int left, top, panelWidth, panelHeight, memberX, memberWidth, queueX, queueWidth, bodyY, bodyHeight;
    private int footerStatusX, footerStatusWidth;
    private int memberOffset, invitationOffset, queuePage, memberSlots, memberBottom;
    private int queueSlots = 1;
    private int menuX, menuY, menuWidth, menuHeight, menuIndex;
    private int pointerX, pointerY, menuPointerX, menuPointerY;
    private boolean menuKeyboard;
    private UUID menuMember, inputRoom;
    private String signature = "";
    private RoomUpdate viewUpdate;
    private boolean viewAvailable;

    public RoomScreen(Screen parent, RoomClient rooms) {
        super(Component.translatable("modernmusic.room.title")); this.parent = parent; this.rooms = rooms;
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        var room = rooms.update().room();
        UUID id = room == null ? null : room.id();
        String draft = input != null && java.util.Objects.equals(inputRoom, id) ? input.getValue() : "";
        boolean sameMusicRoom = room != null && room.id().equals(musicRoom) && room.epoch().equals(musicEpoch);
        String musicDraft = musicInput != null && sameMusicRoom ? musicInput.getValue() : "";
        if (!sameMusicRoom) { playlistInput = false; inputError = null; }
        musicRoom = id; musicEpoch = room == null ? null : room.epoch();
        inputRoom = id;
        menuMember = null; menuButtons.clear(); members.clear(); songs.clear(); removes.clear(); lobbyEntries.clear();
        input = null; listen = pause = stop = next = mode = clear = search = previousPage = nextPage = submit = null;
        musicInput = null; importButton = inputType = platform = leave = dissolve = null;
        panelWidth = playerPanelWidth(width); panelHeight = playerPanelHeight(height);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        memberX = left + 12; memberWidth = Math.min(210, Math.max(124, (panelWidth - 34) * 31 / 100));
        queueX = memberX + memberWidth + 10; queueWidth = left + panelWidth - 12 - queueX;
        bodyY = top + (room == null ? 50 : 73); bodyHeight = top + panelHeight - 42 - bodyY;
        button("gui.back", left + panelWidth - 78, top + 10, 66, 22, Icon.LEFT, true, this::onClose);
        if (room == null) initLobby(draft); else { initMusicInput(musicDraft); initRoom(room, draft); }
        signature = signature(); refresh();
        if (musicInput != null && musicInput.active) setInitialFocus(musicInput);
        else if (input != null && input.active) setInitialFocus(input);
    }
    private void initMusicInput(String draft) {
        boolean narrow = panelWidth < 380;
        boolean localAudio = rooms.player().platform() == MusicPlatform.LOCAL;
        if (localAudio) playlistInput = false;
        searchY = top + 39;
        int right = left + panelWidth - 12, searchButton = narrow ? 44 : 64, addButton = narrow ? 54 : 70;
        searchWidth = right - searchButton - addButton - 12 - (left + 12);
        musicInput = new EditBox(font, left + 70, searchY + 9, searchWidth - 66, 16,
                tr(localAudio ? "modernmusic.local.room_input.hint" : rooms.player().platform().key(playlistInput ? "playlist.hint" : "input.hint"))) {
            @ParametersAreNonnullByDefault @Override public void renderWidget(GuiGraphics g, int x, int y, float delta) {
                if (!getValue().isEmpty()) { super.renderWidget(g, x, y, delta); ThemedEditBox.cursor(g, this); return; }
                g.drawString(font, ellipsis(font, getMessage().getString(), getInnerWidth() - 4), getX() + 3, getY(), FIELD_HINT, false);
                if (isFocused() && net.minecraft.Util.getMillis() / 300 % 2 == 0)
                    g.fill(getX(), getY() - 1, getX() + 1, getY() + font.lineHeight, FIELD_TEXT);
            }
        };
        musicInput.setBordered(false); musicInput.setTextShadow(false);
        musicInput.setMaxLength(SongReferenceParser.MAX_INPUT_LENGTH);
        musicInput.setTextColor(FIELD_TEXT); musicInput.setTextColorUneditable(FIELD_HINT);
        musicInput.setTooltip(ThemedTooltips.create(musicInput.getMessage())); musicInput.setValue(draft);
        musicInput.setResponder(value -> {
            inputError = null;
            if (!clearingSubmittedInput) rooms.player().dismissImportMessage();
        });
        addRenderableWidget(musicInput);
        inputType = button(localAudio ? "modernmusic.audio.tab" : playlistInput ? "modernmusic.playlist.tab" : "modernmusic.song.tab",
                left + 16, searchY + 4, 48, 18, null, true,
                () -> { playlistInput = !playlistInput; inputError = null; rebuildWidgets(); });
        inputType.selected(true);
        if (localAudio) inputType.selectedOnly();
        importButton = addRenderableWidget(new IconButton(right - searchButton - 6 - addButton, searchY, addButton, 26,
                tr(playlistInput ? "modernmusic.playlist.import" : "modernmusic.add"), Icon.PLUS, true, true, this::addMusic));
        search = button(localAudio ? "modernmusic.audio.browse" : "modernmusic.search.open", right - searchButton, searchY, searchButton, 26,
                narrow ? null : localAudio ? Icon.QUEUE : Icon.SEARCH, true, this::searchMusic);
    }
    private void searchMusic() {
        if (!rooms.isCurrentRoom(musicRoom, musicEpoch) || !rooms.canRequest() || rooms.player().importing()) return;
        client().setScreen(rooms.player().platform() == MusicPlatform.LOCAL
                ? new AudioBrowserScreen(this, rooms.player(), musicInput.getValue(), true)
                : new SongSearchScreen(this, rooms.player(), musicInput.getValue()));
    }
    @ParametersAreNonnullByDefault @Override public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (rooms.player().platform() != MusicPlatform.LOCAL || paths.isEmpty()) return;
        var browser = new AudioBrowserScreen(this, rooms.player(), "", true);
        client().setScreen(browser); browser.onFilesDrop(paths);
    }
    private void submitMusic() {
        refresh(); if (search == null || !search.active) return;
        try {
            var action = PlayerInputAction.resolve(musicInput.getValue(), playlistInput, rooms.player().platform());
            if (action == PlayerInputAction.SEARCH) { searchMusic(); return; }
            boolean playlist = action == PlayerInputAction.PLAYLIST;
            if (playlistInput != playlist) { playlistInput = playlist; rebuildWidgets(); }
            addMusic();
        } catch (SongReferenceParser.InputException invalid) { inputError = invalid.translationKey(); }
    }
    private void addMusic() {
        refresh(); if (importButton == null || !importButton.active) return;
        try {
            rooms.player().dismissImportMessage();
            if (rooms.importReference(musicRoom, musicEpoch, musicInput.getValue(), playlistInput)) {
                clearingSubmittedInput = true;
                try { musicInput.setValue(""); } finally { clearingSubmittedInput = false; }
                inputError = null;
            } else inputError = !rooms.message().isEmpty() ? rooms.message() : rooms.player().importMessage() != null
                    ? rooms.player().importMessage() : rooms.player().message();
        } catch (SongReferenceParser.InputException invalid) { inputError = invalid.translationKey(); }
        refresh();
    }
    private void initRoom(RoomSnapshot room, String draft) {
        boolean invite = owner() && !room.isTeamRoom() && !room.isPublicRoom();
        memberBottom = bodyY + bodyHeight - (invite ? tight() ? 41 : 68 : 30);
        memberSlots = Math.max(1, (memberBottom - bodyY - 38) / 36);
        memberOffset = Math.clamp(memberOffset, 0, Math.max(0, room.members().size() - memberSlots));
        for (int i = 0; i < memberSlots && memberOffset + i < room.members().size(); i++)
            members.add(addRenderableWidget(new MemberRow(bodyY + 38 + i * 36, room.members().get(memberOffset + i))));
        if (invite) {
            input = addRenderableWidget(new ThemedEditBox(font, memberX + 10, bodyY + bodyHeight - 38,
                    memberWidth - 54, 24, tr("modernmusic.room.invite_hint"), Icon.ACCOUNT));
            input.setMaxLength(16); input.setValue(draft); input.setHint(tr("modernmusic.room.invite_hint"));
            submit = button("modernmusic.room.invite", memberX + memberWidth - 36, bodyY + bodyHeight - 38, 26, 24,
                    Icon.PLUS, false, () -> rooms.command(Action.INVITE, input.getValue().trim(), 0));
        }
        clear = button("modernmusic.clear", queueX + queueWidth - 34, bodyY + 8, 24, 22, Icon.CLEAR,
                false, () -> rooms.command(Action.CLEAR, "", 0)); clear.ghost();
        queueSlots = Math.max(1, (bodyHeight - (compact() ? 100 : 138)) / 36);
        for (int i = 0; i < queueSlots; i++) {
            final int slot = i;
            songs.add(addRenderableWidget(new SongRow(queueX + 6, bodyY + 38 + i * 36, queueWidth - 40,
                    () -> rooms.command(Action.PLAY, "", (long) queuePage * queueSlots + slot))));
            var remove = button("modernmusic.queues.remove_song", queueX + queueWidth - 31, bodyY + 45 + i * 36,
                    22, 22, Icon.CLOSE, false, () -> rooms.command(Action.REMOVE, "", (long) queuePage * queueSlots + slot));
            remove.ghost(); removes.add(remove);
        }
        int pagerY = tight() ? bodyY + 9 : bodyY + bodyHeight - (compact() ? 56 : 88);
        previousPage = button("modernmusic.previous_page", tight() ? queueX + queueWidth - 90 : queueX + 10, pagerY, 22, 20, Icon.LEFT,
                false, () -> { queuePage--; refresh(); }); previousPage.bare();
        nextPage = button("modernmusic.next_page", queueX + queueWidth - (tight() ? 62 : 32), pagerY, 22, 20, Icon.RIGHT,
                false, () -> { queuePage++; refresh(); }); nextPage.bare();
        int controlsY = bodyY + bodyHeight - 35, center = queueX + queueWidth / 2;
        mode = button(room.mode().translationKey(), queueX + 10, controlsY, 26, 26, Icon.REPEAT,
                false, () -> rooms.command(Action.MODE, "", 0)); mode.bare();
        stop = button("modernmusic.stop", queueWidth < 220 ? queueX + 36 : center - 53, controlsY, 26, 26, Icon.STOP,
                false, () -> rooms.command(Action.STOP, "", 0)); stop.bare();
        pause = button("modernmusic.play", queueWidth < 220 ? queueX + 67 : center - 14, controlsY - 1, 28, 28, Icon.PLAY, false, () -> {
            var current = rooms.update().room();
            if (current != null) rooms.command(current.state() == RoomSnapshot.State.STOPPED ? Action.PLAY : Action.PAUSE,
                    "", Math.max(0, current.index()));
        }); pause.solid();
        next = button("modernmusic.next", queueWidth < 220 ? queueX + 100 : center + 27, controlsY, 26, 26, Icon.NEXT,
                false, () -> rooms.command(Action.NEXT, "", 0)); next.bare();
        boolean narrow = panelWidth < 500;
        int footerY = top + panelHeight - 32, platformWidth = narrow ? 68 : 96, listenWidth = narrow ? 26 : 102;
        platform = button(rooms.player().platform().translationKey(), memberX, footerY, platformWidth, 22,
                null, true, () -> { rooms.player().switchPlatform(); musicInput.setValue(""); inputError = null; rebuildWidgets(); });
        listen = button("modernmusic.room.listen", memberX + platformWidth + 6, footerY, listenWidth, 22, Icon.VOLUME,
                !narrow, rooms::toggleListening); listen.ghost();
        int leaveWidth = narrow ? 82 : 96, right = left + panelWidth - 12;
        leave = button("modernmusic.room.leave", right - leaveWidth, footerY, leaveWidth, 22,
                null, true, () -> rooms.command(Action.LEAVE, "", 0));
        int actionsLeft = right - leaveWidth;
        if (rooms.canDissolve(room.id(), room.epoch())) {
            int dissolveWidth = narrow ? 26 : 80;
            actionsLeft -= dissolveWidth + 6;
            dissolve = button("modernmusic.room.dissolve", actionsLeft, footerY, dissolveWidth, 22,
                    narrow ? Icon.CLEAR : null, !narrow, () -> confirmDissolve(room.id(), room.epoch(), room.name()));
        }
        footerStatusX = listen.getX() + listenWidth + 8; footerStatusWidth = actionsLeft - footerStatusX - 8;
    }
    private void initLobby(String draft) {
        boolean compactTeam = compact() && showTeamEntry();
        memberBottom = bodyY + bodyHeight - (compactTeam ? 38 : 16);
        memberSlots = Math.max(1, (memberBottom - bodyY - 38) / 40);
        UUID local = client().player == null ? null : client().player.getUUID();
        for (var saved : rooms.update().savedRooms()) {
            boolean owned = !saved.isPublicRoom() && saved.owner().equals(local);
            lobbyEntries.add(new LobbyEntry(saved.room(), saved.epoch(),
                    saved.isPublicRoom() ? tr("modernmusic.room.public_name").getString() : saved.name(),
                    saved.isPublicRoom() ? "modernmusic.room.saved_public" : owned ? "modernmusic.room.saved_owned" : "modernmusic.room.saved_joined", owned,
                    tr("modernmusic.room.saved_details", saved.members(), saved.songs()), () -> rooms.join(saved)));
        }
        for (var invitation : rooms.update().invitations()) {
            if (lobbyEntries.stream().anyMatch(entry -> entry.room().equals(invitation.room()))) continue;
            lobbyEntries.add(new LobbyEntry(invitation.room(), invitation.epoch(), invitation.name(),
                    "modernmusic.room.saved_invitation", false, tr("modernmusic.room.join"), () -> rooms.join(invitation)));
        }
        invitationOffset = Math.clamp(invitationOffset, 0, Math.max(0, lobbyEntries.size() - memberSlots));
        for (int i = 0; i < memberSlots && invitationOffset + i < lobbyEntries.size(); i++) {
            var entry = lobbyEntries.get(invitationOffset + i); int y = bodyY + 38 + i * 40;
            var row = addRenderableWidget(new RoomEntry(memberX + 6, y, memberWidth - (entry.owned() ? 40 : 12), entry));
            row.active = rooms.available();
            if (entry.owned()) {
                var remove = button("modernmusic.room.dissolve", memberX + memberWidth - 30, y + 7, 22, 22,
                        Icon.CLEAR, false, () -> confirmDissolve(entry.room(), entry.epoch(), entry.name()));
                remove.setTooltip(ThemedTooltips.create(tr("modernmusic.room.dissolve_named", entry.name())));
            }
        }
        int inset = Math.min(26, queueWidth / 12), formWidth = queueWidth - inset * 2;
        int formY = bodyY + (compact() ? 48 : Math.min(116, bodyHeight / 3));
        input = addRenderableWidget(new ThemedEditBox(font, queueX + inset, formY, formWidth, 28,
                tr("modernmusic.room.create_hint"), Icon.GROUP));
        input.setMaxLength(40); input.setValue(draft); input.setHint(tr("modernmusic.room.create_hint"));
        input.active = rooms.available(); input.setEditable(input.active);
        submit = button("modernmusic.room.create", queueX + inset, formY + 36, formWidth, 26,
                Icon.PLUS, true, () -> rooms.command(Action.CREATE, input.getValue().trim(), 0));
        if (showTeamEntry()) {
            var team = rooms.update().team();
            var join = button("modernmusic.team.join", compactTeam ? memberX + 10 : queueX + inset,
                    compactTeam ? bodyY + bodyHeight - 32 : formY + 92, compactTeam ? memberWidth - 20 : formWidth, 26,
                    Icon.GROUP, true, rooms::joinTeam);
            join.active &= team.state() == TeamStatus.State.READY;
            join.setTooltip(ThemedTooltips.create(tr(team.messageKey(), team.name())));
        }
    }
    private void confirmDissolve(UUID room, UUID epoch, String name) {
        if (rooms.canDissolve(room, epoch)) client().setScreen(new DissolveRoomScreen(this, rooms, room, epoch, name));
    }
    private record LobbyEntry(UUID room, UUID epoch, String name, String roleKey, boolean owned, Component detail, Runnable join) {}
    private IconButton button(String key, int x, int y, int w, int h, Icon icon, boolean labelled, Runnable action) {
        var button = addRenderableWidget(new IconButton(x, y, w, h, tr(key), icon, false, labelled, action));
        button.active = key.equals("gui.back") || rooms.available();
        if (!button.active) button.setTooltip(ThemedTooltips.create(tr("modernmusic.room.unavailable")));
        return button;
    }
    private boolean owner() {
        var room = rooms.update().room();
        return rooms.available() && room != null && client().player != null && room.canManage(client().player.getUUID());
    }
    private boolean compact() { return bodyHeight < 220; }
    private boolean tight() { return bodyHeight < 132; }
    private boolean control() {
        var room = rooms.update().room();
        return rooms.available() && room != null && client().player != null && room.canControl(client().player.getUUID());
    }
    private boolean showTeamEntry() {
        return rooms.available() && rooms.update().sequence() > 0 && rooms.update().team().state() != TeamStatus.State.NOT_INSTALLED;
    }
    private String signature() {
        var room = rooms.update().room();
        if (room == null) return "lobby/" + rooms.update().invitations() + rooms.update().savedRooms()
                + rooms.update().roomLimit() + rooms.update().team() + rooms.available() + showTeamEntry();
        return room.id() + "/" + room.epoch() + "/" + room.name() + "/" + room.owner() + "/" + room.members() + rooms.available();
    }
    /**
     * Network updates run on the client thread, including between a tick and the next render/input.
     * Reconcile before using widgets so the live room and their layout always describe the same state.
     * Return true when an input aimed at the previous layout/queue should be consumed.
     */
    private boolean reconcileState() {
        var update = rooms.update();
        if (update == viewUpdate && rooms.available() == viewAvailable) return false;
        if (!signature.equals(signature())) {
            rebuildWidgets();
            return true;
        }
        var previous = viewUpdate == null ? null : viewUpdate.room();
        var current = update.room();
        boolean queueChanged = previous != null && current != null && !previous.songs().equals(current.songs());
        refresh();
        return queueChanged;
    }
    @Override public void tick() { reconcileState(); refresh(); }
    private void refresh() {
        viewUpdate = rooms.update(); viewAvailable = rooms.available();
        if (submit != null) submit.active = rooms.available() && input != null && !input.getValue().isBlank();
        var room = rooms.update().room();
        if (room == null) {
            if (submit != null) {
                boolean capacity = ownedRooms() < rooms.update().roomLimit();
                submit.active &= capacity;
                submit.setTooltip(ThemedTooltips.create(capacity ? submit.getMessage() : tr("modernmusic.room.error.owned_limit")));
            }
            return;
        }
        if (clear == null) return;
        boolean allowed = control();
        int size = room.songs().size(), pages = Math.max(1, (size + queueSlots - 1) / queueSlots);
        queuePage = Math.clamp(queuePage, 0, pages - 1);
        previousPage.active = queuePage > 0; nextPage.active = queuePage + 1 < pages;
        previousPage.visible = nextPage.visible = pages > 1;
        for (int i = 0; i < songs.size(); i++) {
            var row = songs.get(i); var remove = removes.get(i); int index = queuePage * queueSlots + i;
            row.visible = remove.visible = index < size; row.active = remove.active = allowed;
            if (!row.visible) continue;
            row.index = index;
            var id = SongId.fromWire(room.songs().get(index)); var track = rooms.player().track(id);
            row.song = track == null ? id.key() : track.title();
            row.artist = id.isServerAudio() ? tr("modernmusic.audio.server").getString()
                    : id.isClientLocal() ? tr("modernmusic.audio.local").getString()
                    : (track == null ? "" : track.artistText() + " · ") + tr(id.platform().translationKey()).getString();
            row.setMessage(Component.literal(row.song + " · " + row.artist));
            row.setTooltip(ThemedTooltips.create(allowed ? row.getMessage() : row.getMessage().copy().append("\n").append(tr("modernmusic.room.error.permission"))));
            remove.setTooltip(ThemedTooltips.create(allowed ? tr("modernmusic.queues.remove_named", row.song) : tr("modernmusic.room.error.permission")));
        }
        clear.active = pause.active = stop.active = next.active = allowed && size > 0;
        mode.active = allowed;
        boolean canAdd = rooms.canRequest() && rooms.isCurrentRoom(musicRoom, musicEpoch) && rooms.player().queuesReady();
        boolean busy = rooms.player().importing();
        musicInput.active = canAdd; musicInput.setEditable(canAdd && !busy);
        inputType.active = canAdd && !busy && rooms.player().platform() != MusicPlatform.LOCAL;
        search.active = canAdd && !busy;
        importButton.active = canAdd && !busy && !musicInput.getValue().isBlank();
        search.setTooltip(ThemedTooltips.create(tr(!rooms.canRequest() ? "modernmusic.room.error.request_permission"
                : busy ? "modernmusic.playlist.loading" : "modernmusic.room.search")));
        importButton.setTooltip(ThemedTooltips.create(rooms.canRequest() ? importButton.getMessage() : tr("modernmusic.room.error.request_permission")));
        platform.setMessage(tr(rooms.player().platform().translationKey()));
        platform.setTooltip(ThemedTooltips.create(tr("modernmusic.platform.switch", tr(rooms.player().platform().next().translationKey()))));
        pause.symbol = room.state() == RoomSnapshot.State.PLAYING ? Icon.PAUSE : Icon.PLAY;
        pause.setMessage(tr(room.state() == RoomSnapshot.State.PLAYING ? "modernmusic.pause" : "modernmusic.play"));
        mode.symbol = switch (room.mode()) { case SEQUENTIAL -> Icon.ORDER; case REPEAT_ONE -> Icon.REPEAT_ONE; case REPEAT_ALL -> Icon.REPEAT; case SHUFFLE -> Icon.SHUFFLE; };
        mode.setMessage(tr(room.mode().translationKey()));
        for (var button : List.of(clear, pause, stop, next, mode))
            button.setTooltip(ThemedTooltips.create(allowed ? button.getMessage() : tr("modernmusic.room.error.permission")));
        listen.selected(rooms.listening());
        listen.setMessage(tr(rooms.listening() ? "modernmusic.room.mute_action" : "modernmusic.room.listen_action"));
        listen.setTooltip(ThemedTooltips.create(tr(rooms.listening() ? "modernmusic.room.listening" : "modernmusic.room.muted")));
    }
    private long ownedRooms() {
        UUID local = client().player == null ? null : client().player.getUUID();
        return rooms.update().savedRooms().stream().filter(saved -> !saved.isPublicRoom() && saved.owner().equals(local)).count();
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        if (client().level == null) renderPanorama(g, delta);
        renderBlurredBackground(delta); scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12); header(g, left, top, panelWidth, musicInput == null ? 40 : 32);
        if (musicInput != null) field(g, left + 12, searchY, searchWidth, 26, 13, musicInput.isFocused());
        card(g, memberX, bodyY, memberWidth, bodyHeight, 10); card(g, queueX, bodyY, queueWidth, bodyHeight, 10);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        reconcileState();
        pointerX = x; pointerY = y;
        super.renderMusic(g, menuMember == null ? x : -1, menuMember == null ? y : -1, delta);
        var room = rooms.update().room();
        String name = room == null ? title.getString() : room.isPublicRoom() ? tr("modernmusic.room.public_name").getString()
                : room.isTeamRoom() ? tr("modernmusic.team.named", room.name()).getString() : room.name();
        icon(g, Icon.GROUP, left + 23, top + 21, HEAD_ACCENT);
        heading(g, ellipsis(titleFont(), name, (int) ((panelWidth - 138) / titleScale())), left + 38, top + 21, HEAD_TEXT);
        section(g, room == null ? "modernmusic.room.library" : "modernmusic.room.members", memberX, memberWidth,
                room == null ? lobbyEntries.size() : room.members().size());
        if (room == null) renderLobby(g); else renderRoom(g, room);
        Component status = inputError != null && room != null ? tr(inputError) : !rooms.message().isEmpty() ? tr(rooms.message())
                : room != null && rooms.player().importMessage() != null ? tr(rooms.player().importMessage(),
                        rooms.player().importedCount(), rooms.player().importTotal(), rooms.player().importSkipped()) : null;
        if (status != null) {
            int sx = room == null ? memberX : footerStatusX, sw = room == null ? panelWidth - 24 : footerStatusWidth;
            int color = inputError != null ? ERROR : rooms.message().equals("modernmusic.room.song_added") ? ACCENT
                    : rooms.message().isEmpty() ? MUTED : ERROR;
            if (sw >= 28) label(g, status.getString(), sx, top + panelHeight - 25, sw, color);
            if (menuMember == null && sw >= 28 && inside(x, y, sx, top + panelHeight - 33, sw, 26))
                g.renderTooltip(font, font.split(status, Math.min(320, panelWidth - 24)), x, y);
        } else if (room != null && footerStatusWidth >= 28)
            label(g, tr(rooms.listening() ? "modernmusic.room.sync_status" : "modernmusic.room.muted_status").getString(),
                    footerStatusX, top + panelHeight - 25, footerStatusWidth, MUTED);
        if (menuMember != null) renderMenu(g, x, y, delta);
    }
    private void section(GuiGraphics g, String key, int x, int w, int count) {
        boolean queue = key.equals("modernmusic.room.queue");
        icon(g, queue ? Icon.QUEUE : Icon.GROUP, x + 18, bodyY + 19, ACCENT);
        int titleWidth = w - (queue ? tight() ? 156 : 100 : 74);
        if (titleWidth > 8) label(g, tr(key).getString(), x + 31, bodyY + 15, titleWidth, TEXT);
        String total = Integer.toString(count);
        g.drawString(font, total, x + w - (queue ? tight() ? 100 : 44 : 12) - font.width(total), bodyY + 15, MUTED, false);
        divider(g, x + 10, bodyY + 32, w - 20);
    }
    private void renderRoom(GuiGraphics g, RoomSnapshot room) {
        section(g, "modernmusic.room.queue", queueX, queueWidth, room.songs().size());
        if (!tight() || input == null) label(g, tr(owner() ? "modernmusic.room.member_hint" : "modernmusic.room.member_view_hint").getString(),
                memberX + 12, bodyY + bodyHeight - (input != null ? 59 : 20), memberWidth - 24, QUIET);
        scrollBar(g, memberX + memberWidth - 5, bodyY + 40, memberSlots * (tight() ? 30 : 36) - 4, memberOffset, memberSlots, room.members().size());
        if (room.songs().isEmpty()) {
            int cy = bodyY + 38 + queueSlots * 18;
            icon(g, Icon.QUEUE, queueX + queueWidth / 2, cy - 18, QUIET);
            centerLabel(g, tr("modernmusic.ui.empty_queue").getString(), queueX + 12, cy, queueWidth - 24, MUTED);
            if (!tight()) centerLabel(g, tr("modernmusic.room.queue_hint").getString(), queueX + 12, cy + 17, queueWidth - 24, QUIET);
        } else if (!tight() && room.songs().size() > queueSlots)
            centerLabel(g, (queuePage + 1) + " / " + ((room.songs().size() + queueSlots - 1) / queueSlots),
                    queueX + 34, bodyY + bodyHeight - (compact() ? 50 : 82), queueWidth - 68, MUTED);
        if (compact()) return;
        int nowY = bodyY + bodyHeight - 62;
        divider(g, queueX + 12, nowY - 4, queueWidth - 24);
        String current = tr("modernmusic.room.nothing_playing").getString();
        if (room.index() >= 0) {
            var id = SongId.fromWire(room.songs().get(room.index())); var track = rooms.player().track(id);
            current = track == null ? id.key() : track.title();
        }
        circle(g, queueX + 15, nowY + 9, 2, room.state() == RoomSnapshot.State.PLAYING ? ACCENT : QUIET);
        String time = MusicHud.time(rooms.positionMs());
        label(g, current, queueX + 24, nowY + 5, queueWidth - 48 - font.width(time), TEXT);
        g.drawString(font, time, queueX + queueWidth - 12 - font.width(time), nowY + 5, MUTED, false);
    }
    private void renderLobby(GuiGraphics g) {
        if (lobbyEntries.isEmpty()) {
            int middle = bodyY + (memberBottom - bodyY) / 2;
            icon(g, Icon.GROUP, memberX + memberWidth / 2, middle - 6, QUIET);
            centerLabel(g, tr("modernmusic.room.library_empty").getString(), memberX + 12, middle + 12, memberWidth - 24, MUTED);
        }
        scrollBar(g, memberX + memberWidth - 5, bodyY + 38, memberSlots * 40 - 4, invitationOffset, memberSlots, lobbyEntries.size());
        int inset = Math.min(26, queueWidth / 12);
        heading(g, ellipsis(titleFont(), tr("modernmusic.room.welcome").getString(), (int) ((queueWidth - inset * 2) / titleScale())),
                queueX + inset, bodyY + 29, TEXT);
        if (!compact()) label(g, tr("modernmusic.room.welcome_hint").getString(), queueX + inset, bodyY + 49, queueWidth - inset * 2, MUTED);
        label(g, tr("modernmusic.room.create_limit", ownedRooms(), rooms.update().roomLimit()).getString(),
                queueX + inset, input.getY() + 66, queueWidth - inset * 2, MUTED);
        if (showTeamEntry() && !compact()) {
            var team = rooms.update().team();
            divider(g, queueX + inset, input.getY() + 77, queueWidth - inset * 2);
            label(g, tr(team.messageKey(), team.name()).getString(), queueX + inset, input.getY() + 126, queueWidth - inset * 2, MUTED);
        }
    }
    private void openMenu(MemberRow row, int x, int y) {
        var room = rooms.update().room();
        if (!owner() || room == null || room.canManage(row.member.id())) return;
        closeMenu(); menuMember = row.member.id(); menuWidth = Math.min(202, panelWidth - 24);
        menuHeight = room.isTeamRoom() ? 117 : 143;
        menuX = Math.clamp(x, left + 8, left + panelWidth - menuWidth - 8);
        menuY = Math.clamp(y, top + 8, top + panelHeight - menuHeight - 8);
        menuItem("modernmusic.room.requester", Action.REQUESTER, 1, row.member.requester());
        menuItem("modernmusic.room.dj", Action.DJ, 1, row.member.dj());
        menuItem("modernmusic.room.listener", Action.DJ, 0, !row.member.dj() && !row.member.requester());
        if (!room.isTeamRoom()) menuItem("modernmusic.room.kick", Action.KICK, 0, false);
        // A mouse-opened menu has no keyboard focus until the user navigates with keys.
        menuIndex = -1; menuKeyboard = false;
        menuPointerX = pointerX; menuPointerY = pointerY;
        setFocused(null);
    }
    private void menuItem(String key, Action action, int value, boolean selected) {
        var button = new MemberMenuItem(menuX + 6, menuY + 31 + menuButtons.size() * 26, menuWidth - 12,
                tr(key), selected, () -> {
                    var room = rooms.update().room(); UUID target = menuMember;
                    if (owner() && room != null && target != null && !room.canManage(target))
                        room.members().stream().filter(m -> m.id().equals(target)).findFirst()
                                .ifPresent(m -> rooms.command(action, m.name(), value));
                    closeMenu();
                });
        button.ghost(); menuButtons.add(addWidget(button));
    }
    private void closeMenu() {
        if (menuMember == null) return;
        UUID previous = menuMember; menuMember = null;
        for (var button : menuButtons) removeWidget(button);
        menuButtons.clear();
        members.stream().filter(row -> row.member.id().equals(previous)).findFirst().ifPresent(this::setFocused);
    }
    private void renderMenu(GuiGraphics g, int x, int y, float delta) {
        clearTooltipForNextRenderPass();
        if (x != menuPointerX || y != menuPointerY) {
            menuPointerX = x; menuPointerY = y;
            menuKeyboard = false; setFocused(null);
        }
        if (!menuKeyboard) {
            menuIndex = -1;
            for (int i = 0; i < menuButtons.size(); i++)
                if (menuButtons.get(i).isMouseOver(x, y)) { menuIndex = i; break; }
        }
        g.pose().pushPose(); g.pose().translate(0, 0, 300);
        panel(g, menuX, menuY, menuWidth, menuHeight, 9);
        var room = rooms.update().room();
        var member = room == null ? null : room.members().stream().filter(m -> m.id().equals(menuMember)).findFirst().orElse(null);
        if (member != null) label(g, member.name(), menuX + 12, menuY + 12, menuWidth - 24, TEXT);
        divider(g, menuX + 8, menuY + 27, menuWidth - 16);
        for (var button : menuButtons) button.render(g, menuKeyboard ? -1 : x, menuKeyboard ? -1 : y, delta);
        g.pose().popPose();
    }
    @Override protected boolean clickMusic(double x, double y, int button) {
        if (reconcileState()) return true;
        if (menuMember != null) {
            if (button == 0 && inside(x, y, menuX, menuY, menuWidth, menuHeight)) {
                for (var item : List.copyOf(menuButtons)) if (item.mouseClicked(x, y, button)) return true;
            } else closeMenu();
            return true;
        }
        if (button == 1)
            for (var row : members) if (row.isMouseOver(x, y)) { openMenu(row, (int) x, (int) y); return true; }
        return super.clickMusic(x, y, button);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (reconcileState()) return true;
        if (menuMember != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) closeMenu();
            else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_TAB) {
                int direction = key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_TAB && hasShiftDown() ? -1 : 1;
                focusMenu(menuIndex < 0 ? direction > 0 ? 0 : menuButtons.size() - 1
                        : Math.floorMod(menuIndex + direction, menuButtons.size()));
            } else if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) && menuIndex >= 0)
                menuButtons.get(menuIndex).onPress();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_MENU || key == GLFW.GLFW_KEY_F10 && hasShiftDown()) && getFocused() instanceof MemberRow row) {
            openMenu(row, row.getX() + 20, row.getY() + 20);
            if (menuMember != null) focusMenu(0);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && musicInput != null && musicInput.isFocused()) {
            submitMusic(); return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && input != null && input.isFocused()) {
            refresh(); if (submit.active) submit.onPress(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    private void focusMenu(int index) {
        menuKeyboard = true; menuIndex = index;
        setFocused(menuButtons.get(index));
    }
    @Override public boolean charTyped(char character, int modifiers) {
        if (reconcileState()) return true;
        return super.charTyped(character, modifiers);
    }
    @Override protected boolean scrollMusic(double x, double y, double horizontal, double vertical) {
        if (reconcileState()) return true;
        if (menuMember != null) { closeMenu(); return true; }
        if (vertical == 0) return super.scrollMusic(x, y, horizontal, vertical);
        int direction = vertical > 0 ? -1 : 1;
        if (inside(x, y, memberX, bodyY + 34, memberWidth, memberBottom - bodyY - 34)) {
            if (rooms.update().room() == null) invitationOffset += direction; else memberOffset += direction;
            rebuildWidgets(); return true;
        }
        if (rooms.update().room() != null && inside(x, y, queueX, bodyY + 34, queueWidth, bodyHeight - (compact() ? 65 : 100))) {
            queuePage += direction; refresh(); return true;
        }
        return super.scrollMusic(x, y, horizontal, vertical);
    }
    private void label(GuiGraphics g, String text, int x, int y, int w, int color) {
        g.drawString(font, ellipsis(font, text, Math.max(0, w)), x, y, color, false);
    }
    private void centerLabel(GuiGraphics g, String text, int x, int y, int w, int color) {
        String display = ellipsis(font, text, w); g.drawString(font, display, x + (w - font.width(display)) / 2, y, color, false);
    }
    private void scrollBar(GuiGraphics g, int x, int y, int h, int offset, int slots, int count) {
        if (count <= slots) return;
        int thumb = Math.max(14, h * slots / count), sy = y + (h - thumb) * offset / (count - slots);
        round(g, x, y, 2, h, 1, HAIRLINE); round(g, x, sy, 2, thumb, 1, QUIET);
    }
    private void rowSurface(GuiGraphics g, int x, int y, int w, int h, boolean selected, boolean hovered) {
        if (skin() != null) row(g, x, y, w, h, selected, hovered ? 1 : 0);
        else if (selected || hovered) round(g, x, y, w, h, pixel() ? 0 : 8,
                alpha(selected ? ACCENT : OVERLAY, selected ? .14 : .07));
    }
    private static Component tr(String key, Object... args) { return Component.translatable(key, args); }
    private static boolean inside(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }
    private static String role(RoomSnapshot room, RoomSnapshot.Member member) {
        return room.isPublicRoom() && member.administrator() ? "administrator"
                : member.id().equals(room.owner()) ? "owner" : member.dj() ? "dj" : member.requester() ? "requester" : "listener";
    }
    private final class RoomEntry extends ThemedButton {
        private final LobbyEntry entry;
        private RoomEntry(int x, int y, int w, LobbyEntry entry) {
            super(x, y, w, 36, tr("modernmusic.room.join_named", entry.name()), false, entry.join());
            this.entry = entry; ghost();
            setTooltip(ThemedTooltips.create(Component.literal(entry.name()).append("\n").append(tr(entry.roleKey()))
                    .append(" · ").append(entry.detail())));
        }
        @Override protected void drawContent(GuiGraphics g, int color) {
            RoomScreen.this.label(g, entry.name(), getX() + 8, getY() + 5, width - 16, color);
            RoomScreen.this.label(g, tr(entry.roleKey()).getString(), getX() + 8, getY() + 21, width - 16,
                    entry.owned() ? ACCENT : MUTED);
        }
    }
    /** Current membership is a checkmark; the highlighted row only indicates the pending action. */
    private static final class MemberMenuItem extends ThemedButton {
        private final boolean currentRole;
        private MemberMenuItem(int x, int y, int w, Component label, boolean currentRole, Runnable action) {
            super(x, y, w, 24, label, false, action); this.currentRole = currentRole;
            setTooltip(null);
        }
        @Override protected void drawContent(GuiGraphics g, int color) {
            if (currentRole) icon(g, Icon.CHECK, getX() + 12, getY() + height / 2 - 1, ACCENT);
            label(g, ellipsis(PlayerTheme.font(), getMessage().getString(), width - 34),
                    getX() + 26, getY() + (height - 8) / 2, color);
        }
    }
    private final class MemberRow extends ThemedButton {
        private final RoomSnapshot.Member member;
        private MemberRow(int y, RoomSnapshot.Member member) {
            super(memberX + 6, y, memberWidth - 12, tight() ? 30 : 34, Component.literal(member.name()), false, () -> {});
            this.member = member; plain();
            setTooltip(ThemedTooltips.create(Component.literal(member.name()).append(" · ")
                    .append(tr("modernmusic.room.role." + role(rooms.update().room(), member)))
                    .append("\n").append(tr(owner() && !rooms.update().room().canManage(member.id())
                            ? "modernmusic.room.member_hint" : "modernmusic.room.member_view_hint"))));
        }
        private PlayerSkin skin() {
            var connection = client().getConnection();
            var info = connection == null ? null : connection.getPlayerInfo(member.id());
            // Reuse the game's asynchronous skin cache; querying each frame also picks up a late player-info update.
            if (info != null) {
                try { return info.getSkin(); }
                catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException unavailable) {
                    // A failed skin download must not prevent the member list from rendering.
                }
            }
            return DefaultPlayerSkin.get(member.id());
        }
        @Override protected void drawContent(GuiGraphics g, int fg) {
            var room = rooms.update().room(); if (room == null) return;
            rowSurface(g, getX(), getY(), width, height, member.id().equals(menuMember), isHoveredOrFocused());
            PlayerFaceRenderer.draw(g, skin(), getX() + 5, getY() + (height - 24) / 2, 24);
            RoomScreen.this.label(g, member.name(), getX() + 36, getY() + 5, width - 42, TEXT);
            RoomScreen.this.label(g, tr("modernmusic.room.role." + role(room, member)).getString(), getX() + 36, getY() + 19, width - 42, MUTED);
        }
    }
    private final class SongRow extends ThemedButton {
        private int index;
        private String song = "", artist = "";
        private SongRow(int x, int y, int w, Runnable action) { super(x, y, w, tight() ? 30 : 34, Component.empty(), false, action); plain(); }
        @Override protected void drawContent(GuiGraphics g, int fg) {
            var room = rooms.update().room(); if (room == null) return;
            boolean selected = index == room.index();
            rowSurface(g, getX(), getY(), width, height, selected, isHoveredOrFocused());
            if (selected) equalizer(g, getX() + 9, getY() + 13, room.state() == RoomSnapshot.State.PLAYING, rooms.positionMs(), ACCENT);
            else centerLabel(g, Integer.toString(index + 1), getX() + 2, getY() + 13, 24, QUIET);
            RoomScreen.this.label(g, song, getX() + 30, getY() + 6, width - 36, selected ? ACCENT : TEXT);
            RoomScreen.this.label(g, artist, getX() + 30, getY() + 20, width - 36, MUTED);
        }
    }
    @Override public void onClose() { client().setScreen(parent); }
}
