package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.domain.QueueLibrary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Local queue selection and naming. Editing a name never changes the active playback. */
public final class QueueManagerScreen extends MusicScreen {
    private final Screen parent;
    private final PlaybackController player;
    private EditBox name;
    private Button create, rename;
    private int page, left, top, panelWidth, panelHeight, pageSize;
    private String draft, error;
    private boolean lastReady;
    public QueueManagerScreen(Screen parent, PlaybackController player) {
        super(Component.translatable("modernmusic.queues.title")); this.parent = parent; this.player = player;
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        lastReady = player.queuesReady();
        panelWidth = Math.min(440, width - 24); panelHeight = Math.min(330, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        pageSize = Math.max(1, (panelHeight - 156) / 30);
        page = Math.clamp(page, 0, (player.library().queues().size() - 1) / pageSize);
        addRenderableWidget(new IconButton(left + panelWidth - 76, top + 6, 60, 20, Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
        var queues = player.library().queues();
        for (int i = 0; i < pageSize && page * pageSize + i < queues.size(); i++) {
            var entry = queues.get(page * pageSize + i);
            String label = (entry == player.library().active() ? "● " : "") + entry.name() + "  (" + entry.queue().entries().size() + "/400)";
            var button = addRenderableWidget(new QueueEntry(left + 16, top + 46 + i * 30, panelWidth - 32, Component.literal(label),
                    entry.name(), entry.queue().entries().size(), () -> { player.switchQueue(entry.id()); draft = entry.name(); error = null; rebuildWidgets(); }));
            button.selected(entry == player.library().active());
            button.active = player.queuesReady(); button.setTooltip(Tooltip.create(Component.literal(label)));
        }
        int bottom = top + panelHeight;
        var previous = addRenderableWidget(new IconButton(left + 16, bottom - 102, 24, 20, Component.literal("‹"), Icon.LEFT, false, false, () -> { draft = name.getValue(); page--; rebuildWidgets(); }));
        var next = addRenderableWidget(new IconButton(left + panelWidth - 40, bottom - 102, 24, 20, Component.literal("›"), Icon.RIGHT, false, false, () -> { draft = name.getValue(); page++; rebuildWidgets(); }));
        previous.setTooltip(Tooltip.create(Component.translatable("modernmusic.previous_page")));
        next.setTooltip(Tooltip.create(Component.translatable("modernmusic.next_page")));
        previous.active = page > 0; next.active = (page + 1) * pageSize < queues.size();
        previous.bare(); next.bare();
        name = addRenderableWidget(new ThemedEditBox(font, left + 16, bottom - 64, panelWidth - 32, 22, Component.translatable("modernmusic.queues.name")));
        name.setMaxLength(QueueLibrary.MAX_NAME); name.setValue(draft == null ? player.library().active().name() : draft);
        name.setResponder(value -> draft = value);
        int half = (panelWidth - 40) / 2;
        create = addRenderableWidget(new IconButton(left + 16, bottom - 36, half, 22, Component.translatable("modernmusic.queues.create"), Icon.PLUS, true, true, () -> change(true)));
        rename = button("modernmusic.queues.rename", left + 24 + half, bottom - 36, half, 22, false, () -> change(false));
        tick();
    }
    private Button button(String key, int x, int y, int w, int h, boolean primary, Runnable action) {
        return addRenderableWidget(new ThemedButton(x, y, w, h, Component.translatable(key), primary, action));
    }
    private void change(boolean creating) {
        try {
            String value = QueueLibrary.cleanName(name.getValue());
            if (creating) { if (!player.createQueue(value)) { error = "modernmusic.queues.limit"; return; } page = (player.library().queues().size() - 1) / pageSize; }
            else player.renameQueue(value);
            draft = value; error = null; rebuildWidgets();
        } catch (IllegalArgumentException ignored) { error = "modernmusic.queues.invalid_name"; }
    }
    @Override public void tick() {
        if (lastReady != player.queuesReady()) { rebuildWidgets(); return; }
        boolean valid;
        try { QueueLibrary.cleanName(name.getValue()); valid = true; } catch (IllegalArgumentException ignored) { valid = false; }
        create.active = valid && player.queuesReady() && player.library().queues().size() < QueueLibrary.MAX_QUEUES;
        rename.active = valid && player.queuesReady();
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        if (client().level == null) renderPanorama(g, delta);
        renderBlurredBackground(delta);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
        well(g, left + 10, top + 40, panelWidth - 20, panelHeight - 150, 10);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        super.renderMusic(g, x, y, delta);
        icon(g, Icon.QUEUE, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        String pages = (page + 1) + " / " + ((player.library().queues().size() + pageSize - 1) / pageSize);
        centered(g, pages, width / 2, top + panelHeight - 96, QUIET);
        g.drawString(font, Component.translatable("modernmusic.queues.name"), left + 16, top + panelHeight - 78, MUTED, false);
        String status = error != null ? error : player.queueStorageMessage();
        if (status != null) g.drawCenteredString(font, ellipsis(font, Component.translatable(status).getString(), width - 16), width / 2, height - 9, ERROR);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && name.isFocused() && rename.active) { change(false); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { client().setScreen(parent); }

    /** A queue row: name on the left, track count badge on the right; its message stays the full label. */
    private final class QueueEntry extends ThemedButton {
        private final String queueName, count;
        private QueueEntry(int x, int y, int width, Component label, String name, int size, Runnable action) {
            super(x, y, width, 26, label, false, action);
            queueName = name; count = size + " / 400";
        }
        @Override protected void drawContent(GuiGraphics g, int color) {
            int centerY = getY() + height / 2;
            if (selected()) equalizer(g, getX() + 9, centerY - 5, false, 0, ACCENT);
            else icon(g, Icon.QUEUE, getX() + 14, centerY - 1, QUIET);
            int badge = font.width(count) + 14;
            badge(g, getX() + width - badge - 6, centerY - 7, badge, 14, selected() ? alpha(ACCENT, .2) : alpha(OVERLAY, .07));
            g.drawString(font, count, getX() + width - badge + 1, centerY - 4, selected() ? ACCENT : MUTED, false);
            g.drawString(font, ellipsis(font, queueName, width - badge - 40), getX() + 28, centerY - 4, color, false);
        }
    }
}
