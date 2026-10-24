package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.client.SharedPlaybackSource;
import dev.modernmusic.client.SharedAccountClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Listening source is independent from room membership and personal login. */
public final class SharedSourceScreen extends MusicScreen {
    private final Screen parent;
    private final SharedPlaybackSource source;
    private int left, top, panelWidth, panelHeight;
    private Button toggle, manage, remember;
    private boolean saveFailed;
    private Component toggleTooltip;
    private SharedSafetyNotice safety;
    public SharedSourceScreen(Screen parent, SharedPlaybackSource source) {
        super(Component.translatable(source.platform().key("shared.title"))); this.parent = parent; this.source = source;
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        panelWidth = Math.min(450, width - 24);
        safety = new SharedSafetyNotice(font, panelWidth - 32, "modernmusic.shared.security_notice");
        panelHeight = Math.min(262 + safety.height(), height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        manage = addRenderableWidget(new ThemedButton(left + panelWidth - 112, top + 6, 96, 20,
                Component.translatable("modernmusic.admin.manage"), false,
                () -> {
                    var admin = dev.modernmusic.client.SharedAccountClient.admin();
                    if (admin != null && admin.allowed()) client().setScreen(new SharedAdminScreen(this, admin));
                }));
        toggle = addRenderableWidget(new ThemedButton(left + 16, top + panelHeight - 88, panelWidth - 32, 20,
                Component.empty(), true, () -> source.select(!source.selected())));
        remember = addRenderableWidget(new RememberButton(left + 16, top + panelHeight - 62, panelWidth - 32));
        addRenderableWidget(new IconButton(left + 16, top + panelHeight - 34, panelWidth - 32, 22,
                Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose)); tick();
    }
    @Override public void tick() {
        var admin = dev.modernmusic.client.SharedAccountClient.admin();
        manage.visible = manage.active = dev.modernmusic.client.SharedAccountClient.adminAvailable() && admin != null && admin.allowed();
        toggle.active = source.selected() || dev.modernmusic.client.SharedAccountClient.available() && source.available();
        toggle.setMessage(Component.translatable(source.selected() ? "modernmusic.shared.use_personal" : "modernmusic.shared.use_server"));
        remember.active = SharedAccountClient.canRememberLogin();
        var tooltip = toggle.active ? toggle.getMessage() : Component.translatable(source.statusKey());
        if (!tooltip.equals(toggleTooltip) || toggle.getTooltip() == null) {
            toggleTooltip = tooltip; toggle.setTooltip(ThemedTooltips.create(tooltip));
        }
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g) {
        if (client().level == null) renderDirtBackground(g);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float partial) {
        super.renderMusic(g, x, y, partial);
        icon(g, Icon.GROUP, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        safety.render(g, left + 16, top + 38);
        int row = top + 38 + safety.height() + 6;
        for (var line : UiText.wrap(font, Component.translatable(source.selected() ? "modernmusic.shared.selected_server" : "modernmusic.shared.selected_personal"), panelWidth - 32)) {
            g.drawString(font, line, left + 16, row, ACCENT, false); row += 11;
        }
        row += 4;
        if (source.available()) {
            ArtworkCache.get().draw(g, null, left + 16, row - 3, 16, true);
            String name = Component.translatable(source.platform().key("shared.account_name")).getString();
            g.drawString(font, ellipsis(font, name, panelWidth - 54), left + 38, row + 1, TEXT, false); row += 22;
        }
        for (var line : UiText.wrap(font, Component.translatable(source.statusKey()), panelWidth - 32)) {
            if (row > top + panelHeight - 106) break;
            g.drawString(font, line, left + 16, row, MUTED, false); row += 11;
        }
        row += 6;
        for (var line : UiText.wrap(font, Component.translatable(saveFailed ? "modernmusic.hud.save_failed" : "modernmusic.shared.help"), panelWidth - 32)) {
            if (row > top + panelHeight - 106) break;
            g.drawString(font, line, left + 16, row, MUTED, false); row += 11;
        }
    }
    private final class RememberButton extends ThemedButton {
        RememberButton(int x, int y, int w) {
            super(x, y, w, 20, Component.translatable("modernmusic.shared.keep_login"), false, () -> {
                saveFailed = !SharedAccountClient.keepLogin(source.platform(), !SharedAccountClient.keepLogin(source.platform()));
                remember.setTooltip(ThemedTooltips.create(Component.translatable(saveFailed
                        ? "modernmusic.hud.save_failed" : "modernmusic.shared.keep_login_help")));
            });
            plain(); setTooltip(ThemedTooltips.create(Component.translatable("modernmusic.shared.keep_login_help")));
        }
        @Override protected void drawContent(GuiGraphics g, int color) {
            boolean checked = SharedAccountClient.keepLogin(source.platform());
            int x = getX() + 2, y = getY() + 2, ink = active ? TEXT : QUIET;
            if (skin() != null) checkbox(g, x, y, checked, active && isHoveredOrFocused());
            else {
                surface(g, x, y, 16, 16, 3, checked ? ACCENT : alpha(OVERLAY, .08 + .08 * hover()));
                if (checked) icon(g, Icon.CHECK, x + 8, y + 8, INK);
                else frame(g, x, y, 16, 16, active && isHoveredOrFocused() ? ACCENT : MUTED);
            }
            label(g, ellipsis(PlayerTheme.font(), getMessage().getString(), width - 28), x + 24, getY() + 6, ink);
        }
        @Override public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage().copy().append(": ")
                    .append(Component.translatable(SharedAccountClient.keepLogin(source.platform()) ? "options.on" : "options.off")));
            if (active) output.add(net.minecraft.client.gui.narration.NarratedElementType.USAGE, Component.translatable("narration.button.usage.focused"));
        }
    }
    @Override public void onClose() { client().setScreen(parent); }
}
