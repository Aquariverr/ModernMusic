package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.modernmusic.auth.*;
import dev.modernmusic.client.SharedAdminController;
import dev.modernmusic.shared.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import static dev.modernmusic.client.ui.PlayerTheme.*;
import static dev.modernmusic.shared.SharedAdminAction.*;

/** Server account settings and binding, separate from the personal account screen. */
public final class SharedAdminScreen extends MusicScreen {
    private final Screen parent;
    private final SharedAdminController admin;
    private int left, top, panelWidth, panelHeight, layout;
    private int controlsY, contentY, statusY;
    private SharedSafetyNotice safety;
    private boolean opened, cookieTab, confirmForget, verificationLayout;
    private SecretEditBox cookie;
    private Button enabled, access;
    private final List<Button> accountActions = new ArrayList<>();
    private String localError, renderedKey = "";
    private BitMatrix matrix;
    public SharedAdminScreen(Screen parent, SharedAdminController admin) {
        super(Component.translatable(admin.platform().key("admin.title"))); this.parent = parent; this.admin = admin; cookieTab = cookieOnly();
    }
    private boolean cookieOnly() { return admin.platform().sharedCookieOnly(); }
    private int currentLayout(SharedAdminSnapshot state) {
        return !dev.modernmusic.client.SharedAccountClient.adminAvailable() || state == null || !state.editing() || !admin.allowed()
                ? 0 : state.bound() ? 3 : state.qrActive() ? 2 : cookieTab ? 1 : 4;
    }
    @Override protected void initMusic() {
        font = UiFont.get();
        if (!opened) { opened = true; admin.open(); }
        if (cookie != null) cookie.setValue(""); cookie = null; enabled = access = null; accountActions.clear();
        panelWidth = Math.min(560, width - 24);
        safety = new SharedSafetyNotice(font, panelWidth - 32, "modernmusic.admin.security_notice", height < 260);
        panelHeight = Math.min(288 + safety.height(), height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        controlsY = top + 38 + safety.height() + 4;
        contentY = controlsY + (height < 260 ? 22 : 24);
        statusY = top + panelHeight - 14;
        var state = admin.snapshot();
        layout = currentLayout(state); verificationLayout = state != null && state.verificationRequired();
        addRenderableWidget(new IconButton(left + panelWidth - 76, top + 6, 60, 20, Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
        if (layout == 0 || state == null) {
            accountActions.add(button("modernmusic.admin.retry", left + 16, top + panelHeight - 64, panelWidth - 32, true, () -> send(OPEN)));
        } else {
            int half = (panelWidth - 40) / 2;
            enabled = button("modernmusic.admin.enable", left + 16, controlsY, half, false,
                    () -> {
                        var current = admin.snapshot();
                        if (current != null) send(current.enabled() ? DISABLE : ENABLE);
                    });
            access = button("modernmusic.admin.all", left + 24 + half, controlsY, half, false,
                    () -> {
                        var current = admin.snapshot();
                        if (current != null) send(current.operatorsOnly() ? ACCESS_ALL : ACCESS_OPS);
                    });
            if (layout == 3) {
                accountActions.add(button("modernmusic.admin.save", left + 16, contentY + 44, half, true, () -> send(SAVE)));
                accountActions.add(button(confirmForget ? "modernmusic.admin.confirm_forget" : "modernmusic.admin.forget",
                        left + 24 + half, contentY + 44, half, false, () -> {
                            if (confirmForget) { confirmForget = false; send(FORGET); }
                            else { confirmForget = true; rebuildWidgets(); }
                        }));
            } else if (layout == 1 || layout == 4) {
                if (!cookieOnly()) accountActions.add(button("modernmusic.admin.qr", left + 16, contentY, half, layout == 4, () -> { cookieTab = false; send(QR_START); }));
                accountActions.add(button("modernmusic.admin.cookie", cookieOnly() ? left + 16 : left + 24 + half, contentY, cookieOnly() ? panelWidth - 32 : half, layout == 1,
                        () -> { cookieTab = true; localError = null; rebuildWidgets(); }));
                if (layout == 1) {
                    cookie = addRenderableWidget(new CookieEditBox(font, left + 16, contentY + 34, panelWidth - 32,
                            Component.translatable(admin.platform().key("auth.cookie_label")), admin.platform(), error -> localError = error));
                    accountActions.add(button("modernmusic.admin.bind_cookie", left + 16, contentY + 58, panelWidth - 32, true, () -> {
                        String value = cookie.getValue(); cookie.setValue("");
                        try { admin.request(COOKIE_LOGIN, value); localError = null; }
                        catch (AuthException invalid) { localError = invalid.translationKey(); }
                    }));
                } else accountActions.add(button("modernmusic.admin.restore", left + 16, statusY - 28, panelWidth - 32, false, () -> send(RESTORE)));
            } else if (layout == 2) {
                int qrSpace = qrSpace(), x = left + qrSpace + 30, w = panelWidth - qrSpace - 46;
                if (verificationLayout && !state.verificationUrl().isEmpty()) {
                    accountActions.add(button("modernmusic.auth.qr_open_verification", x, contentY + 24, w, true,
                            this::openVerification));
                    accountActions.add(button("modernmusic.auth.qr_resume", x, contentY + 46, w, false, () -> send(QR_RESUME)));
                }
                button("gui.cancel", x, contentY + 68, w, false, () -> send(CANCEL_LOGIN));
            }
        }
        tick();
    }
    private void send(SharedAdminAction action) { localError = null; admin.request(action, ""); }
    private void openVerification() {
        var state = admin.snapshot();
        if (state == null || !state.editing() || !state.verificationRequired()) return;
        var uri = SharedAdminSnapshot.verificationUri(state.verificationUrl());
        if (uri != null) net.minecraft.Util.getPlatform().openUri(uri);
    }
    private Button button(String key, int x, int y, int w, boolean primary, Runnable action) {
        return addRenderableWidget(new ThemedButton(x, y, w, 20, Component.translatable(key), primary, action));
    }
    @Override public void tick() {
        var state = admin.snapshot();
        if (layout != currentLayout(state) || verificationLayout != (state != null && state.verificationRequired())) { rebuildWidgets(); return; }
        boolean supported = dev.modernmusic.client.SharedAccountClient.adminAvailable();
        boolean ready = supported && admin.allowed() && !admin.waiting();
        for (Button action : accountActions) action.active = ready && (state == null || !state.busy());
        if (enabled != null && state != null) {
            enabled.active = access.active = ready;
            enabled.setMessage(Component.translatable(state.enabled() ? "modernmusic.admin.disable" : "modernmusic.admin.enable"));
            enabled.setTooltip(ThemedTooltips.create(Component.translatable(state.enabled()
                    ? "modernmusic.admin.sharing" : "modernmusic.admin.not_sharing")));
            access.setMessage(Component.translatable(state.operatorsOnly() ? "modernmusic.admin.ops" : "modernmusic.admin.all"));
        }
        for (Button action : accountActions) {
            String key = ((net.minecraft.network.chat.contents.TranslatableContents) action.getMessage().getContents()).getKey();
            action.setTooltip(Tooltip.create(Component.translatable(supported ? key : "modernmusic.shared.unavailable")));
            if (key.equals("modernmusic.admin.cookie") || key.equals("modernmusic.admin.bind_cookie")) action.active &= state != null && state.cookieUpload();
            if (key.equals("modernmusic.admin.restore")) action.active &= state != null && state.saved();
            if (key.equals("modernmusic.admin.save")) action.active &= state != null && !state.saved();
        }
        if (cookie != null) cookie.setEditable(ready && state != null && !state.busy() && state.cookieUpload());
        String key = state != null && state.editing() ? state.qrKey() : "";
        if (!key.equals(renderedKey)) {
            renderedKey = key; matrix = null;
            if (!key.isEmpty()) try {
                matrix = new QRCodeWriter().encode("https://music.163.com/login?codekey=" + key, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 4));
            } catch (Exception invalid) { localError = "modernmusic.auth.qr_error"; }
        }
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g) {
        if (client().level == null) renderDirtBackground(g);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        header(g, left, top, panelWidth, 32);
    }
    @Override protected void renderMusic(GuiGraphics g, int x, int y, float partial) {
        tick(); super.renderMusic(g, x, y, partial); var state = admin.snapshot();
        icon(g, Icon.GROUP, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        safety.render(g, left + 16, top + 38);
        if (layout == 0 || state == null) wrapped(g, Component.translatable(statusKey()), left + 16, contentY, panelWidth - 32,
                Math.max(0, (top + panelHeight - 72 - contentY) / 11));
        else if (layout == 3) {
            g.drawString(font, Component.translatable(admin.platform().key("shared.account_name")), left + 16, contentY, ACCENT, false);
            wrapped(g, Component.translatable(state.saved() ? "modernmusic.admin.saved" : "modernmusic.admin.unsaved"), left + 16, contentY + 16, panelWidth - 32, 2);
            wrapped(g, Component.translatable(confirmForget ? "modernmusic.admin.forget_help" : "modernmusic.admin.bound_help"), left + 16, contentY + 72, panelWidth - 32, 3);
        } else if (layout == 1) {
            g.drawString(font, Component.translatable(admin.platform().key("auth.cookie_label")), left + 16, contentY + 22, MUTED, false);
            wrapped(g, Component.translatable(cookieOnly() ? admin.platform().key("auth.cookie_help") : "modernmusic.admin.cookie_help"), left + 16, contentY + 86, panelWidth - 32, 4);
        } else if (layout == 4) {
            wrapped(g, Component.translatable("modernmusic.admin.bind_help"), left + 16, contentY + 30, panelWidth - 32,
                    Math.max(0, (statusY - 34 - contentY - 30) / 11));
        } else if (layout == 2) {
            int qrSpace = qrSpace();
            if (matrix != null) {
                QrDrawing.draw(g, matrix, left + 16, contentY, qrSpace);
            }
            wrapped(g, Component.translatable(verificationLayout ? "modernmusic.auth.qr_security_verification" : "modernmusic.admin.qr_help"),
                    left + qrSpace + 30, contentY, panelWidth - qrSpace - 46, verificationLayout ? 2 : 5);
        }
        var statusText = Component.translatable(statusKey());
        g.drawString(font, ellipsis(font, statusText.getString(), panelWidth - 32), left + 16, statusY, MUTED, false);
        if (x >= left + 16 && x < left + panelWidth - 16 && y >= statusY - 2 && y < statusY + 11)
            g.renderTooltip(font, font.split(statusText, panelWidth - 32), x, y);
    }
    private String statusKey() {
        if (!dev.modernmusic.client.SharedAccountClient.adminAvailable()) return "modernmusic.shared.unavailable";
        var state = admin.snapshot();
        return localError != null ? localError : state == null ? "modernmusic.admin.loading" : state.status();
    }
    private int qrSpace() { return Math.max(45, Math.min(140, statusY - contentY - 12)); }
    private void wrapped(GuiGraphics g, Component text, int x, int y, int w, int count) {
        var lines = font.split(text, w);
        int bottom = statusY - 6;
        for (int i = 0; i < Math.min(count, lines.size()) && y + i * 11 + font.lineHeight <= bottom; i++)
            g.drawString(font, lines.get(i), x, y + i * 11, MUTED, false);
    }
    @Override public void removed() {
        if (cookie != null) cookie.setValue(""); matrix = null; renderedKey = ""; admin.close();
    }
    @Override public void onClose() { client().setScreen(parent); }
}
