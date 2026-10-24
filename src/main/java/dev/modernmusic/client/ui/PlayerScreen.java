package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.RoomClient;
import dev.modernmusic.client.HudSettings;
import dev.modernmusic.client.PlaybackAccountView;
import dev.modernmusic.client.PlayerInputAction;
import dev.modernmusic.domain.MusicPlatform;
import dev.modernmusic.domain.Track;
import dev.modernmusic.client.SharedAccountClient;
import dev.modernmusic.auth.AccountController;
import dev.modernmusic.domain.SongReferenceParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/**
 * Personal player with a responsive, pixel-aligned layout and native keyboard navigation. The now-playing stage is lit
 * by the theme's glow (the cover's color in the cover theme); the queue is a plain list beside it.
 */
public final class PlayerScreen extends MusicScreen {
    private static final int HEADER = 22, SEARCH = 26, GAP = 8;
    private static final int FOOTER_HEIGHT = 18, FOOTER_PADDING = 12;
    private final Screen parent;
    private final PlaybackController player;
    private AccountController account;
    private final java.util.function.Function<MusicPlatform, AccountController> accounts;
    private final HudSettings hud;
    private final List<QueueRow> rows = new ArrayList<>();
    private final List<FlatButton> removeRows = new ArrayList<>();
    private final List<FlatButton> groupRows = new ArrayList<>();
    private FlatButton queueManager, accountButton, roomButton;
    private Component accountTooltip;
    private EditBox input;
    private FlatButton pause, previousPage, nextPage, mode, importButton, stop, next, clear;
    private ProgressSlider progress;
    private final PlaybackWaveform waveform = new PlaybackWaveform();
    private String inputError;
    private String savedInput = "";
    private int page, left, top, panelWidth, panelHeight, searchY, searchWidth, bodyY, bodyHeight, footerY, headerButtonsLeft;
    private int playerX, playerWidth, queueX, queueWidth, volumeX, controlsY, progressY;
    private boolean narrow, queueView, playlistInput, clearingSubmittedInput;

    public PlayerScreen(Screen parent, PlaybackController player) {
        this(parent, player, null);
    }
    public PlayerScreen(Screen parent, PlaybackController player, AccountController account) {
        this(parent, player, account, new HudSettings(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config/modernmusic-hud.json")));
    }
    public PlayerScreen(Screen parent, PlaybackController player, AccountController account, HudSettings hud) {
        this(parent, player, account, hud, platform -> account != null && account.platform() == platform ? account : null);
    }
    public PlayerScreen(Screen parent, PlaybackController player, AccountController account, HudSettings hud,
                        java.util.function.Function<MusicPlatform, AccountController> accounts) {
        super(Component.translatable("modernmusic.title"));
        this.parent = parent;
        this.player = player;
        this.account = account; this.accounts = accounts;
        this.hud = hud;
    }

    @Override protected void initMusic() {
        font = UiFont.get();
        boolean localAudio = player.platform() == MusicPlatform.LOCAL;
        if (localAudio) playlistInput = false;
        account = accounts.apply(player.platform());
        SharedAccountClient.select(player.platform());
        if (input != null) savedInput = input.getValue();
        rows.clear(); removeRows.clear(); groupRows.clear(); queueManager = null; accountButton = null; roomButton = null; accountTooltip = null; pause = null; mode = null; progress = null; previousPage = null; nextPage = null; stop = next = clear = null;
        panelWidth = playerPanelWidth(width);
        panelHeight = playerPanelHeight(height);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        narrow = panelWidth < 380;
        int right = left + panelWidth - 12, headerY = top + 10;

        right -= HEADER;
        button("gui.done", right, headerY, HEADER, HEADER, Icon.CLOSE, false, false, this::onClose).bare();
        if (account != null) {
            right -= HEADER + 6;
            accountButton = button("modernmusic.auth.title", right, headerY, HEADER, HEADER, Icon.ACCOUNT, false, false,
                    () -> {
                        var source = player.sharedSource();
                        client().setScreen(accountView().server() && source != null
                                ? new SharedSourceScreen(this, source) : new AccountScreen(this, account));
                    });
            accountButton.bare();
        }
        right -= HEADER + 6;
        button("modernmusic.theme.title", right, headerY, HEADER, HEADER, Icon.PALETTE, false, false,
                () -> client().setScreen(new ThemeScreen(this, hud, () -> player.track() == null ? null : player.track().artwork()))).bare();
        int hudWidth = narrow ? HEADER : 54;
        right -= hudWidth + 6;
        button("modernmusic.hud.edit", right, headerY, hudWidth, HEADER, Icon.LAYOUT, false, !narrow,
                () -> client().setScreen(new HudEditorScreen(this, player, hud))).setMessage(Component.literal("HUD"));
        if (dev.modernmusic.client.RoomClient.instance() != null) {
            int roomWidth = narrow ? HEADER : 58;
            right -= roomWidth + 6;
            roomButton = button("modernmusic.room.title", right, headerY, roomWidth, HEADER, Icon.GROUP, false, !narrow,
                    () -> client().setScreen(new RoomScreen(this, dev.modernmusic.client.RoomClient.instance())));
        }
        headerButtonsLeft = right;

        searchY = top + 39;
        int searchRight = left + panelWidth - 12, searchButton = narrow ? 44 : 64, addButton = narrow ? 54 : 70;
        searchWidth = searchRight - searchButton - addButton - 12 - (left + 12);
        input = new EditBox(font, left + 70, searchY + 9, searchWidth - 66, 16,
                Component.translatable(player.platform().key(playlistInput ? "playlist.hint" : "input.hint"))) {
            @ParametersAreNonnullByDefault @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                if (!getValue().isEmpty()) { super.renderWidget(g, mouseX, mouseY, partialTick); ThemedEditBox.cursor(g, this); return; }
                // Native EditBox hides hints while focused; keep this import instruction visible whenever empty.
                g.drawString(font, ellipsis(font, getMessage().getString(), getInnerWidth() - 4), getX() + 3, getY(), FIELD_HINT, false);
                if (isFocused() && net.minecraft.Util.getMillis() / 300 % 2 == 0) g.fill(getX(), getY() - 1, getX() + 1, getY() + font.lineHeight, FIELD_TEXT);
            }
        };
        input.setBordered(false); input.setTextShadow(false);
        input.setMaxLength(SongReferenceParser.MAX_INPUT_LENGTH);
        input.setTextColor(FIELD_TEXT); input.setTextColorUneditable(FIELD_HINT);
        input.setTooltip(Tooltip.create(input.getMessage()));
        input.setValue(savedInput);
        input.setResponder(value -> { inputError = null; if (!clearingSubmittedInput) player.dismissImportMessage(); });
        addRenderableWidget(input);
        var inputType = button(localAudio ? "modernmusic.audio.tab" : playlistInput ? "modernmusic.playlist.tab" : "modernmusic.song.tab",
                left + 16, searchY + 4, 48, SEARCH - 8, null, false, true,
                () -> { playlistInput = !playlistInput; rebuildWidgets(); });
        inputType.selected(true);
        if (localAudio) inputType.selectedOnly();
        importButton = button(playlistInput ? "modernmusic.playlist.import" : "modernmusic.add", searchRight - searchButton - 6 - addButton, searchY,
                addButton, SEARCH, Icon.PLUS, true, true, this::add);
        button(localAudio ? "modernmusic.audio.browse" : "modernmusic.search.open", searchRight - searchButton, searchY, searchButton, SEARCH,
                narrow ? null : localAudio ? Icon.QUEUE : Icon.SEARCH, false, true,
                this::search);

        bodyY = searchY + SEARCH + GAP;
        // Reserve the whole footer row and its bottom inset, including themed button borders and focus rings.
        footerY = top + panelHeight - FOOTER_PADDING - FOOTER_HEIGHT;
        var platformButton = button(player.platform().translationKey(), left + 12, footerY, narrow ? 68 : 84, FOOTER_HEIGHT, null, false, true,
                () -> { player.switchPlatform(); savedInput = ""; input.setValue(""); inputError = null; rebuildWidgets(); });
        platformButton.setTooltip(Tooltip.create(Component.translatable("modernmusic.platform.switch", Component.translatable(player.platform().next().translationKey()))));
        button("modernmusic.cache.title", left + panelWidth - 76, footerY, 64, FOOTER_HEIGHT, null, false, true,
                () -> client().setScreen(new CacheScreen(this, player)));
        if (narrow) button(queueView ? "modernmusic.ui.player_tab" : "modernmusic.ui.queue_tab",
                left + 84, footerY, 62, FOOTER_HEIGHT, queueView ? Icon.MUSIC : Icon.QUEUE, false, true,
                () -> { queueView = !queueView; rebuildWidgets(); }).ghost();
        bodyHeight = footerY - bodyY - GAP;
        playerX = left + 12;
        playerWidth = narrow ? panelWidth - 24 : (int) ((panelWidth - 34) * 0.59);
        queueX = narrow ? playerX : playerX + playerWidth + 10;
        queueWidth = left + panelWidth - 12 - queueX;
        if (!narrow || !queueView) initPlayer();
        if (!narrow || queueView) initQueue();
        setInitialFocus(input);
        refresh();
    }

    private void initPlayer() {
        controlsY = bodyY + bodyHeight - 40;
        progressY = controlsY - 18;
        // Times sit beside the rail, so the rail leaves a slot for the widest time at each end.
        int slot = font.width("00:00") + 8;
        progress = addRenderableWidget(new ProgressSlider(playerX + 14 + slot, progressY - 7, playerWidth - 28 - 2 * slot));
        int center = playerX + playerWidth / 2, right = playerX + playerWidth - 14;
        if (playerWidth >= 300) {
            // Transport centered as in a music app: stop · play · next, the mode toggle at the left, volume at the right.
            stop = button("modernmusic.stop", center - 50, controlsY + 1, 26, 26, Icon.STOP, false, false, player::stop); stop.bare();
            pause = button("modernmusic.play", center - 14, controlsY, 28, 28, Icon.PLAY, false, false, player::togglePause);
            pause.solid();
            next = button("modernmusic.next", center + 24, controlsY + 1, 26, 26, Icon.NEXT, false, false, player::next); next.bare();
            mode = button(player.queue().mode().translationKey(), playerX + 12, controlsY + 1, 26, 26, Icon.REPEAT, false, false, () -> { player.cycleMode(); refresh(); });
            mode.bare();
            int volumeWidth = Math.min(84, right - (center + 50) - 26);
            volumeX = right - volumeWidth - 18;
            addRenderableWidget(new VolumeSlider(right - volumeWidth, controlsY + 4, volumeWidth));
        } else {
            // Narrow stages keep the controls in one run and give the rest to the volume slider.
            int x = playerX + 10;
            mode = button(player.queue().mode().translationKey(), x, controlsY + 1, 24, 26, Icon.REPEAT, false, false, () -> { player.cycleMode(); refresh(); });
            mode.bare();
            stop = button("modernmusic.stop", x += 26, controlsY + 1, 24, 26, Icon.STOP, false, false, player::stop); stop.bare();
            pause = button("modernmusic.play", x += 28, controlsY, 28, 28, Icon.PLAY, false, false, player::togglePause);
            pause.solid();
            next = button("modernmusic.next", x += 32, controlsY + 1, 24, 26, Icon.NEXT, false, false, player::next); next.bare();
            volumeX = x + 30;
            addRenderableWidget(new VolumeSlider(volumeX + 16, controlsY + 4, Math.max(20, right - volumeX - 16)));
        }
    }

    private void initQueue() {
        queueManager = addRenderableWidget(new QueueHeading(queueX + 4, bodyY + 4, queueWidth - 38, 24,
                () -> client().setScreen(new QueueManagerScreen(this, player))));
        clear = button("modernmusic.clear", queueX + queueWidth - 30, bodyY + 5, 22, 22, Icon.CLEAR, false, false,
                () -> { player.clearPersonalQueue(); page = 0; }); clear.ghost();
        int count = Math.max(1, (bodyHeight - 68) / 30);
        for (int row = 0; row < count; row++) {
            final int offset = row;
            rows.add(addRenderableWidget(new QueueRow(queueX + 4, bodyY + 34 + row * 30, queueWidth - 58,
                    () -> player.playPersonal(page * rows.size() + offset))));
            var group = button("modernmusic.room.add_song", queueX + queueWidth - 52, bodyY + 38 + row * 30, 20, 20, Icon.PLUS, false, false,
                    () -> addToGroup(page * rows.size() + offset));
            group.ghost(); groupRows.add(group);
            var remove = button("modernmusic.queues.remove_song", queueX + queueWidth - 28, bodyY + 38 + row * 30, 20, 20, Icon.CLOSE, false, false,
                    () -> { player.removePersonalTrack(page * rows.size() + offset); refresh(); });
            remove.ghost(); removeRows.add(remove);
        }
        previousPage = button("modernmusic.previous_page", queueX + 6, bodyY + bodyHeight - 26, 22, 20,
                Icon.LEFT, false, false, () -> { page--; refresh(); });
        nextPage = button("modernmusic.next_page", queueX + queueWidth - 28, bodyY + bodyHeight - 26, 22, 20,
                Icon.RIGHT, false, false, () -> { page++; refresh(); });
        previousPage.bare(); nextPage.bare();
    }

    private FlatButton button(String key, int x, int y, int w, int h, Icon icon, boolean primary,
                              boolean text, Runnable action) {
        return addRenderableWidget(new FlatButton(x, y, w, h, Component.translatable(key), icon, primary, text, action));
    }

    private void search() {
        client().setScreen(player.platform() == MusicPlatform.LOCAL
                ? new AudioBrowserScreen(this, player, input.getValue())
                : new SongSearchScreen(this, player, input.getValue(), true));
    }

    @ParametersAreNonnullByDefault @Override public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (player.platform() != MusicPlatform.LOCAL || paths.isEmpty()) return;
        var browser = new AudioBrowserScreen(this, player, "");
        client().setScreen(browser); browser.onFilesDrop(paths);
    }

    private void addToGroup(int index) {
        var rooms = RoomClient.instance();
        var entries = player.personalQueue().entries();
        if (rooms == null || !rooms.canRequest() || index < 0 || index >= entries.size()) return;
        var room = rooms.update().room();
        var id = entries.get(index);
        if (id.isClientLocal()) return;
        if (room == null || room.songs().contains(id.toString()) || room.songs().size() >= dev.modernmusic.domain.PlaybackQueue.MAX_TRACKS) return;
        rooms.addSong(id);
        refresh();
    }

    private void submitInput() {
        try {
            var action = PlayerInputAction.resolve(input.getValue(), playlistInput, player.platform());
            if (action == PlayerInputAction.SEARCH) { search(); return; }
            boolean playlist = action == PlayerInputAction.PLAYLIST;
            if (playlistInput != playlist) { playlistInput = playlist; rebuildWidgets(); }
            refresh();
            if (importButton.active) add();
        } catch (SongReferenceParser.InputException exception) { inputError = exception.translationKey(); }
    }

    private void add() {
        try {
            if ((playlistInput ? player.importPersonalPlaylist(input.getValue()) : player.addPersonal(input.getValue()))) {
                // Clearing a submitted value is not a new edit: a fast import may already have a result to display.
                clearingSubmittedInput = true;
                try { input.setValue(""); } finally { clearingSubmittedInput = false; }
                inputError = null;
            }
            else inputError = playlistInput ? player.importMessage() : player.message();
        } catch (SongReferenceParser.InputException exception) { inputError = exception.translationKey(); }
        refresh();
    }

    @Override public void tick() { refresh(); }

    private PlaybackAccountView accountView() {
        return PlaybackAccountView.of(account == null ? null : account.session(),
                account == null ? "modernmusic.auth.guest" : account.message(), player.sharedSource());
    }
    private void refreshAccount() {
        if (accountButton == null) return;
        var view = accountView();
        var tooltip = Component.translatable(player.platform().translationKey()).append(" · ")
                .append(Component.translatable(view.server() ? "modernmusic.shared.account_name" : "modernmusic.auth.local_name"));
        if (player.platform() != MusicPlatform.NETEASE && !view.nickname().isEmpty()) tooltip.append("\n").append(Component.literal(view.nickname()));
        if (!view.ready() && (view.server() || view.statusKey().startsWith("modernmusic.error.")))
            tooltip.append("\n").append(Component.translatable(view.statusKey()));
        if (!tooltip.equals(accountTooltip)) {
            accountTooltip = tooltip; accountButton.setTooltip(ThemedTooltips.create(tooltip));
        }
    }
    private void refresh() {
        refreshAccount();
        if (roomButton != null) {
            var rooms = dev.modernmusic.client.RoomClient.instance();
            roomButton.active = rooms != null && rooms.available();
            roomButton.setTooltip(Tooltip.create(Component.translatable(roomButton.active
                    ? "modernmusic.room.title" : "modernmusic.room.unavailable")));
        }
        var roomClient = dev.modernmusic.client.RoomClient.instance();
        if (importButton != null) {
            importButton.active = player.queuesReady() && (!playlistInput || !player.importing());
            importButton.setTooltip(ThemedTooltips.create(importButton.getMessage()));
        }
        if (stop != null) stop.active = player.queuesReady();
        if (next != null) next.active = player.queuesReady();
        if (clear != null) clear.active = player.queuesReady();
        if (queueManager != null) {
            queueManager.setMessage(Component.literal(player.library().active().name()));
            queueManager.symbol = Icon.QUEUE;
            queueManager.setTooltip(Tooltip.create(Component.translatable("modernmusic.queues.manage", player.library().active().name())));
        }
        if (mode != null) {
            mode.active = player.queuesReady();
            mode.symbol = switch (player.queue().mode()) {
                case SEQUENTIAL -> Icon.ORDER;
                case REPEAT_ONE -> Icon.REPEAT_ONE;
                case REPEAT_ALL -> Icon.REPEAT;
                case SHUFFLE -> Icon.SHUFFLE;
            };
            mode.setMessage(Component.translatable(player.queue().mode().translationKey()));
            mode.setTooltip(Tooltip.create(Component.translatable("modernmusic.mode.change", mode.getMessage())));
        }
        var entries = player.personalQueue().entries();
        var room = roomClient == null ? null : roomClient.update().room();
        int pageSize = Math.max(1, rows.size());
        int lastPage = Math.max(0, (entries.size() - 1) / pageSize);
        page = Math.clamp(page, 0, lastPage);
        if (previousPage != null) {
            previousPage.active = page > 0; nextPage.active = page < lastPage;
            previousPage.visible = nextPage.visible = !entries.isEmpty();
        }
        for (int row = 0; row < rows.size(); row++) {
            int index = page * pageSize + row;
            QueueRow button = rows.get(row);
            button.visible = index < entries.size();
            removeRows.get(row).visible = button.visible;
            groupRows.get(row).visible = button.visible;
            button.active = player.queuesReady() && !player.shared();
            removeRows.get(row).active = player.queuesReady();
            if (!button.visible) continue;
            var id = entries.get(index);
            var track = player.track(id);
            button.number = index + 1;
            button.selected = !player.shared() && index == player.personalQueue().index();
            button.song = track == null ? id.toString() : track.title();
            button.artist = id.isClientLocal() ? translated("modernmusic.audio.local") : id.isServerAudio() ? translated("modernmusic.audio.server")
                    : track == null ? translated("modernmusic.ui.queued") : track.artistText();
            button.setMessage(Component.literal(button.number + ". " + button.song + " · " + button.artist));
            button.setTooltip(Tooltip.create(player.shared() ? button.getMessage().copy().append("\n")
                    .append(Component.translatable("modernmusic.room.personal_playback_locked")) : button.getMessage()));
            removeRows.get(row).setTooltip(Tooltip.create(Component.translatable("modernmusic.queues.remove_named", button.song)));
            var group = groupRows.get(row);
            String unavailable = id.isClientLocal() ? "modernmusic.error.local_audio_only"
                    : room == null ? "modernmusic.room.join_to_add"
                    : !roomClient.canRequest() ? "modernmusic.room.error.request_permission"
                    : room.songs().contains(id.toString()) ? "modernmusic.room.already_queued"
                    : room.songs().size() >= dev.modernmusic.domain.PlaybackQueue.MAX_TRACKS ? "modernmusic.status.queue_full" : null;
            group.active = player.queuesReady() && unavailable == null;
            group.setMessage(Component.translatable("modernmusic.room.add_named", button.song));
            group.setTooltip(Tooltip.create(unavailable == null ? group.getMessage() : Component.translatable(unavailable)));
        }
        if (pause != null) {
            boolean playing = player.playbackPlaying();
            pause.symbol = playing ? Icon.PAUSE : Icon.PLAY;
            pause.setMessage(Component.translatable(playing ? "modernmusic.pause" : "modernmusic.play"));
            pause.setTooltip(Tooltip.create(pause.getMessage()));
            pause.active = player.canTogglePlayback();
            progress.refresh();
        }
        if (player.shared()) {
            boolean control = room != null && client().player != null && room.canControl(client().player.getUUID());
            if (stop != null) stop.active &= control;
            if (next != null) next.active &= control;
            if (progress != null) progress.active &= control;
            if (pause != null) pause.active &= control;
            if (mode != null) mode.active = control;
        }
    }

    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Screen.render calls this before widgets, so the blur never reaches foreground text.
        if (client().level == null) renderPanorama(g, partialTick);
        renderBlurredBackground(partialTick);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
        field(g, left + 12, searchY, searchWidth, SEARCH, SEARCH / 2, input.isFocused());
        if (!narrow || !queueView) stage(g, playerX, bodyY, playerWidth, bodyHeight, 12);
        if (!narrow || queueView) well(g, queueX, bodyY, queueWidth, bodyHeight, 10);
        divider(g, left + 12, footerY - GAP / 2, panelWidth - 24);
    }

    @Override protected void renderMusic(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        refreshAccount();
        follow(player.track() == null ? null : player.track().artwork());
        super.renderMusic(g, mouseX, mouseY, partialTick);
        boolean playing = player.state() == PlaybackController.State.PLAYING;
        var identity = accountView();
        String heading = narrow ? translated("modernmusic.ui.player_tab") : "Modern Music";
        equalizer(g, left + 16, top + 16, playing, player.audio().positionMs(), HEAD_ACCENT);
        PlayerTheme.heading(g, heading, left + 34, top + 21, HEAD_TEXT);
        if (panelWidth >= 500) {
            // Who is listening, as quiet text beside the header actions rather than a badge.
            String mode = translated(identity.modeKey(player.shared(), player.teamRoom()));
            int modeLeft = headerButtonsLeft - 12 - font.width(mode);
            if (modeLeft - 10 >= left + 34 + headingWidth(heading) + 12) {
                boolean error = identity.server() && !identity.ready();
                dot(g, modeLeft - 6, top + 21, 2, error ? ERROR : HEAD_ACCENT);
                g.drawString(font, mode, modeLeft, top + 17, error ? ERROR : HEAD_MUTED, false);
            }
        }
        if (!narrow || !queueView) renderNowPlaying(g);
        if (!narrow || queueView) renderQueue(g);
        boolean expiredAccount = !identity.server() && account != null && account.message().equals("modernmusic.error.auth_expired") && player.personalQueue().entries().isEmpty();
        boolean sharedUnavailable = identity.server() && !identity.ready();
        String roomMessage = player.shared() && dev.modernmusic.client.RoomClient.instance() != null ? dev.modernmusic.client.RoomClient.instance().message() : "";
        String status = inputError != null ? translated(inputError) : sharedUnavailable ? translated(identity.statusKey()) : !roomMessage.isEmpty() ? translated(roomMessage) : player.queueStorageMessage() != null ? translated(player.queueStorageMessage()) : expiredAccount ? translated(account.message()) : player.state() == PlaybackController.State.ERROR ? translated(player.message()) : player.importMessage() != null
                ? Component.translatable(player.importMessage(), player.importedCount(), player.importTotal(), player.importSkipped()).getString() : translated(player.message());
        if (identity.server()) status = translated("modernmusic.shared.source_short") + " · " + status;
        boolean failed = inputError != null || expiredAccount || sharedUnavailable || player.state() == PlaybackController.State.ERROR;
        int statusX = left + (narrow ? 152 : 104), statusWidth = left + panelWidth - 84 - statusX;
        int textX = statusX + (failed || playing ? 10 : 0);
        int statusY = footerY + (FOOTER_HEIGHT - 8) / 2;
        if (failed || playing) dot(g, statusX + 2.5f, statusY + 3.5f, 2, failed ? ERROR : ACCENT);
        label(g, status, textX, statusY, statusX + statusWidth - textX, failed ? ERROR : MUTED, false);
        if (inside(mouseX, mouseY, statusX, footerY, statusWidth, FOOTER_HEIGHT)) {
            g.renderTooltip(font, font.split(Component.literal(status), panelWidth - 24), mouseX, mouseY);
        } else if ((!narrow || !queueView) && player.track() != null
                && inside(mouseX, mouseY, playerX + 8, bodyY + 8, playerWidth - 16, progressY - 16 - bodyY)) {
            g.renderTooltip(font, font.split(Component.literal(player.track().title() + " · " + trackDetail(player.track())),
                    panelWidth - 24), mouseX, mouseY);
        }
    }

    private static String trackDetail(Track track) {
        return track.id().isClientLocal() ? translated("modernmusic.audio.local")
                : track.id().isServerAudio() ? translated("modernmusic.audio.server") : track.artistText();
    }

    private void renderNowPlaying(GuiGraphics g) {
        boolean spacious = bodyHeight >= 270;
        int waveHeight = bodyHeight >= 230 ? 24 : bodyHeight >= 170 ? 18 : 10;
        int waveY = progressY - 10 - waveHeight;
        int progressTop = waveY - 3;
        var track = player.track();
        var artwork = track == null ? null : track.artwork();
        String song = track == null ? translated("modernmusic.ui.no_track") : track.title();
        String artist = track == null ? translated(player.platform() == MusicPlatform.LOCAL ? "modernmusic.audio.add_hint" : "modernmusic.ui.add_hint") : trackDetail(track);
        var lyric = player.lyrics().at(player.audio().positionMs());
        String lyricText = lyric != null ? lyric.text() : translated(player.lyrics().lines().isEmpty() ? player.lyricMessage() : "modernmusic.lyrics.waiting");
        int lyricsY;
        if (spacious) {
            int size = Math.min(132, bodyHeight - 196), artX = playerX + (playerWidth - size) / 2, artY = bodyY + 18;
            ArtworkCache.get().draw(g, artwork, artX, artY, size, false);
            int titleY = artY + size + 18;
            String shown = ellipsis(titleFont(), song, (int) ((playerWidth - 32) / titleScale()));
            title(g, shown, playerX + (playerWidth - titleWidth(shown)) / 2, titleY, TEXT);
            label(g, artist, playerX + 16, titleY + 10, playerWidth - 32, MUTED, true);
            lyricsY = titleY + 30;
        } else {
            // Compact: cover beside the title; the current line joins the text column when no row fits below.
            int artX = playerX + 12, artY = bodyY + (bodyHeight < 150 ? 8 : 12);
            int size = Math.min(bodyHeight >= 180 ? 64 : 46, progressTop - artY);
            boolean cover = size >= 24;
            if (cover) ArtworkCache.get().draw(g, artwork, artX, artY, size, false);
            int textX = cover ? artX + size + 12 : playerX + 14, textWidth = playerX + playerWidth - 14 - textX;
            int titleY = artY + (bodyHeight < 150 ? 4 : 7);
            title(g, ellipsis(titleFont(), song, (int) (textWidth / titleScale())), textX, titleY, TEXT);
            label(g, artist, textX, titleY + 10, textWidth, MUTED, false);
            lyricsY = Math.max(artY + (cover ? size : 0), titleY + 20) + 8;
            if (lyricsY + 17 > progressTop && titleY + 23 + font.lineHeight <= progressTop) {
                label(g, lyricText, textX, titleY + 23, textWidth, lyric != null ? TEXT : MUTED, false);
                lyricsY = progressTop;
            }
        }
        int x = playerX + 16, w = playerWidth - 32;
        // The line being sung is set one step larger and heavier: its row is 17 tall, every other row 13.
        int lyricRows = lyricsY + 17 > progressTop ? 0 : (progressTop - lyricsY - 4) / 13;
        int rowY = lyricsY;
        if (lyric == null && lyricRows > 0) {
            label(g, lyricText, x, rowY, w, MUTED, spacious);
            rowY += 13; lyricRows--;
        }
        var window = LyricWindow.rows(player.lyrics(), player.audio().positionMs(), lyricRows);
        int focus = 0;
        while (focus < window.size() && !window.get(focus).current()) focus++;
        for (int i = 0; i < window.size(); i++) {
            var row = window.get(i);
            if (row.current() && !row.translation()) {
                String shown = ellipsis(titleFont(), row.text(), (int) (w / titleScale()));
                title(g, shown, spacious ? x + (w - titleWidth(shown)) / 2 : x, rowY + 6, TEXT);
                rowY += 17;
                continue;
            }
            // Context fades with distance from the line being sung; its translation stays fully legible.
            int color = row.current() ? MUTED : alpha(MUTED, Math.max(.3, .8 - Math.abs(i - focus) * .14));
            label(g, row.text(), x, rowY, w, color, spacious);
            rowY += 13;
        }
        var audio = player.audio();
        waveform.render(g, player, progress.getX() + 4, waveY, progress.getWidth() - 8, waveHeight);
        String current = time((long) (progress.value() * audio.durationMs())), total = time(audio.durationMs());
        g.drawString(font, current, progress.getX() - 6 - font.width(current), progressY - 4, progress.dragging ? TEXT : MUTED, false);
        g.drawString(font, total, progress.getX() + progress.getWidth() + 6, progressY - 4, MUTED, false);
        icon(g, Icon.VOLUME, volumeX + 4, controlsY + 13, MUTED);
    }

    private void renderQueue(GuiGraphics g) {
        if (player.personalQueue().entries().isEmpty()) {
            int centerY = bodyY + bodyHeight / 2;
            icon(g, Icon.MUSIC, queueX + queueWidth / 2, centerY - 10, QUIET);
            label(g, translated("modernmusic.ui.empty_queue"), queueX + 8, centerY + 4, queueWidth - 16, MUTED, true);
        } else {
            int pageCount = Math.max(1, (player.personalQueue().entries().size() + rows.size() - 1) / Math.max(1, rows.size()));
            if (pageCount > 1) label(g, (page + 1) + " / " + pageCount, queueX + 34, bodyY + bodyHeight - 20, queueWidth - 68, QUIET, true);
        }
    }

    private void label(GuiGraphics g, String text, int x, int y, int w, int color, boolean centered) {
        String display = ellipsis(font, text, w);
        g.drawString(font, display, centered ? x + (w - font.width(display)) / 2 : x, y, color, false);
    }
    private static String translated(String key) { return Component.translatable(key).getString(); }
    private static boolean inside(double x, double y, int left, int top, int w, int h) {
        return x >= left && x < left + w && y >= top && y < top + h;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && input.isFocused()) { submitInput(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override protected boolean scrollMusic(double x, double y, double horizontal, double vertical) {
        if (!rows.isEmpty() && vertical != 0 && inside(x, y, queueX, bodyY, queueWidth, bodyHeight)) {
            page += vertical > 0 ? -1 : 1; refresh(); return true;
        }
        return super.scrollMusic(x, y, horizontal, vertical);
    }
    @Override public void onClose() { client().setScreen(parent); }

    private class FlatButton extends IconButton {
        private FlatButton(int x, int y, int w, int h, Component label, Icon icon, boolean primary, boolean text, Runnable action) {
            super(x, y, w, h, label, icon, primary, text, action);
        }
        @Override protected void drawContent(GuiGraphics g, int fg) {
            if (symbol != Icon.ACCOUNT) { super.drawContent(g, fg); return; }
            var view = accountView();
            if (view.ready()) ArtworkCache.get().draw(g, view.avatar(), getX() + 2, getY() + 2, height - 4, true, 1, false);
            else icon(g, Icon.ACCOUNT, getX() + width / 2, getY() + height / 2 - 1, view.server() ? QUIET : fg);
            if (view.server()) sharedAccountBadge(g, getX() + width - 5, getY() + height - 4, view.ready() ? ACCENT : ERROR);
            else if (view.ready()) { circle(g, getX() + width - 3, getY() + height - 3, 3, SHELL); circle(g, getX() + width - 3, getY() + height - 3, 2, ACCENT); }
        }
    }

    /** The queue's title: its name set as a heading with a chevron to manage queues, and the song count at the end. */
    private final class QueueHeading extends FlatButton {
        private QueueHeading(int x, int y, int w, int h, Runnable action) {
            super(x, y, w, h, Component.empty(), Icon.QUEUE, false, true, action);
            ghost();
        }
        @Override protected void drawContent(GuiGraphics g, int fg) {
            int centerY = getY() + height / 2;
            String count = Component.translatable("modernmusic.ui.track_count", player.personalQueue().entries().size()).getString();
            // The count gives way before the queue name is shortened.
            int countWidth = player.personalQueue().entries().isEmpty() || 40 + titleWidth(getMessage().getString()) + font.width(count) + 8 > width
                    ? 0 : font.width(count) + 8;
            if (countWidth > 0) g.drawString(font, count, getX() + width - countWidth, centerY - 4, QUIET, false);
            icon(g, symbol, getX() + 10, centerY - 1, active ? MUTED : QUIET);
            String name = ellipsis(titleFont(), getMessage().getString(), (int) ((width - countWidth - 40) / titleScale()));
            title(g, name, getX() + 22, centerY, active ? TEXT : QUIET);
            g.pose().pushPose();
            g.pose().translate(getX() + 22 + titleWidth(name) + 8, centerY - .5f, 0); g.pose().scale(.7f, .7f, 1);
            icon(g, Icon.RIGHT, 0, 0, fg);
            g.pose().popPose();
        }
    }

    private final class QueueRow extends Button {
        private String song = "", artist = "";
        private int number;
        private boolean selected;
        private float hover;
        private final long[] clock = new long[1];
        private QueueRow(int x, int y, int w, Runnable action) {
            super(x, y, w, 28, Component.empty(), ignored -> action.run(), DEFAULT_NARRATION);
        }
        @ParametersAreNonnullByDefault @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            hover = approach(hover, active && isHoveredOrFocused() ? 1 : 0, clock);
            int x = getX(), y = getY();
            if (skin() != null) {
                row(g, x, y, width, height, selected, hover);
                if (isFocused()) focusRing(g, x, y, width, height, 0);
            } else if (blocky()) {
                if (selected) { g.fill(x, y, x + width, y + height, alpha(ORE_GREEN, .45)); g.fill(x, y, x + 2, y + height, ORE_GREEN_HI); }
                if (hover > .01f) g.fill(x, y, x + width, y + height, alpha(0xFFFFFFFF, .1 * hover));
                if (isFocused()) frame(g, x, y, width, height, 0xFFFFFFFF);
            } else {
                if (selected || hover > .01f) Shapes.rect(g, x, y, width, height, 8,
                        alpha(selected ? ACCENT : OVERLAY, (selected ? (light() ? .1 : .13) : 0) + .06 * hover));
                if (isFocused()) focusRing(g, x, y, width, height, 8);
            }
            if (selected) equalizer(g, x + 8, y + 9, player.state() == PlaybackController.State.PLAYING, player.audio().positionMs(), ACCENT);
            else {
                String index = Integer.toString(number);
                g.drawString(font, index, x + 13 - font.width(index) / 2, y + 10, QUIET, false);
            }
            label(g, song, x + 28, y + 4, width - 34, selected ? ACCENT : TEXT, false);
            label(g, artist, x + 28, y + 16, width - 34, MUTED, false);
        }
    }

    private abstract static class ThinSlider extends AbstractSliderButton {
        private float hover;
        private final long[] clock = new long[1];
        private ThinSlider(int x, int y, int w, int h, double value) { super(x, y, w, h, Component.empty(), value); }
        @ParametersAreNonnullByDefault @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            hover = approach(hover, active && isHoveredOrFocused() ? 1 : 0, clock);
            if (isFocused()) focusRing(g, getX() + 2, getY() + height / 2 - 5, width - 4, 10, 5);
            ThemedSlider.track(g, getX() + 4, getY() + height / 2, width - 8, value, active, hover);
        }
    }

    private final class VolumeSlider extends ThinSlider {
        private VolumeSlider(int x, int y, int w) { super(x, y, w, 20, player.volume()); updateMessage(); }
        @Override protected void updateMessage() {
            setMessage(Component.translatable("modernmusic.volume", Math.round(value * 100)));
            setTooltip(Tooltip.create(getMessage()));
        }
        @Override protected void applyValue() { player.volume((float) value); }
    }

    /** The seek rail; its times are drawn beside it by the stage. */
    private final class ProgressSlider extends ThinSlider {
        private boolean dragging;
        private ProgressSlider(int x, int y, int w) { super(x, y, w, 14, 0); }
        private double value() { return value; }
        private void refresh() {
            var audio = player.audio();
            active = player.state() == PlaybackController.State.PLAYING || player.state() == PlaybackController.State.PAUSED;
            if (!dragging) value = audio.durationMs() == 0 ? 0 : Math.clamp((double) audio.positionMs() / audio.durationMs(), 0, 1);
            updateMessage();
        }
        @Override protected void updateMessage() {
            long duration = player.audio().durationMs();
            setMessage(Component.translatable("modernmusic.ui.progress", time((long) (value * duration)), time(duration)));
        }
        @Override protected void applyValue() { if (!dragging) player.seek((long) (value * player.audio().durationMs())); }
        @Override public void onClick(double x, double y) { dragging = true; super.onClick(x, y); }
        @Override public void onRelease(double x, double y) {
            dragging = false; player.seek((long) (value * player.audio().durationMs())); super.onRelease(x, y);
        }
    }

    private static String time(long milliseconds) {
        return MusicHud.time(milliseconds);
    }
}
