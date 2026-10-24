package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.RoomClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Memory-only maintenance. Persistent queues, layout and credentials are not cache. */
public final class CacheScreen extends MusicScreen {
    private final Screen parent;
    private final PlaybackController player;
    private int left, top, panelWidth, panelHeight;
    private boolean cleared;

    public CacheScreen(Screen parent, PlaybackController player) {
        super(Component.translatable("modernmusic.cache.title")); this.parent = parent; this.player = player;
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        panelWidth = Math.min(430, width - 24); panelHeight = Math.min(224, height - 16);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        addRenderableWidget(new IconButton(left + 16, top + panelHeight - 62, panelWidth - 32, 22,
                Component.translatable("modernmusic.cache.clear"), Icon.CLEAR, true, true, () -> {
                    var rooms = RoomClient.instance();
                    if (player.shared() && rooms != null && rooms.listening()) rooms.toggleListening();
                    player.clearTemporaryCache(); ArtworkCache.clearIfCreated(); cleared = true;
                }));
        addRenderableWidget(new IconButton(left + 16, top + panelHeight - 34, panelWidth - 32, 22,
                Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g, int x, int y, float partial) {
        if (client().level == null) renderPanorama(g, partial);
        renderBlurredBackground(partial);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
        card(g, left + 12, top + 40, panelWidth - 24, 30, 10);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float partial) {
        super.renderMusic(g, x, y, partial);
        icon(g, Icon.CLEAR, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        long mib = (player.bufferedBytes() + ArtworkCache.textureCount() * ArtworkCache.textureBytes() + 1_048_575) / 1_048_576;
        g.drawString(font, Component.translatable("modernmusic.cache.size", mib), left + 24, top + 51, TEXT, false);
        int row = top + 80;
        for (var line : font.split(Component.translatable(cleared ? "modernmusic.cache.done" : "modernmusic.cache.help"), panelWidth - 32)) {
            if (row + font.lineHeight > top + panelHeight - 70) break;
            g.drawString(font, line, left + 16, row, MUTED, false); row += 12;
        }
    }
    @Override public void onClose() { client().setScreen(parent); }
}
