package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.RoomClient;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Explicit confirmation keeps room deletion separate from leaving the listening session. */
public final class DissolveRoomScreen extends MusicScreen {
    private final Screen parent;
    private final RoomClient rooms;
    private final UUID room, epoch;
    private final String name;
    private ThemedButton confirm, cancel;
    private int left, top, panelWidth, panelHeight;
    public DissolveRoomScreen(Screen parent, RoomClient rooms, UUID room, UUID epoch, String name) {
        super(Component.translatable("modernmusic.room.dissolve"));
        this.parent = parent; this.rooms = rooms; this.room = room; this.epoch = epoch; this.name = name;
    }
    @Override protected void initMusic() {
        font = UiFont.get(); panelWidth = Math.min(430, width - 24); panelHeight = Math.min(208, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        int half = (panelWidth - 42) / 2, y = top + panelHeight - 40;
        cancel = addRenderableWidget(new ThemedButton(left + 16, y, half, 24, Component.translatable("gui.cancel"), false, this::onClose));
        confirm = addRenderableWidget(new ThemedButton(left + 26 + half, y, half, 24, title, true, () -> {
            if (rooms.canDissolve(room, epoch)) rooms.dissolve(room, epoch);
            onClose();
        }));
        confirm.active = rooms.canDissolve(room, epoch); setInitialFocus(cancel);
    }
    @Override public void tick() { confirm.active = rooms.canDissolve(room, epoch); }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {
        if (client().level == null) renderPanorama(g, delta);
        renderBlurredBackground(delta); scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12); header(g, left, top, panelWidth, 38);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float delta) {
        super.renderMusic(g, x, y, delta);
        heading(g, title.getString(), left + 16, top + 21, HEAD_TEXT);
        g.drawString(font, ellipsis(font, name, panelWidth - 32), left + 16, top + 52, TEXT, false);
        int line = top + 75;
        for (var text : font.split(Component.translatable("modernmusic.room.dissolve_confirm"), panelWidth - 32)) {
            g.drawString(font, text, left + 16, line, MUTED, false); line += font.lineHeight + 3;
        }
    }
    @Override public void onClose() { client().setScreen(parent); }
}
