package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.modernmusic.auth.AccountController;
import dev.modernmusic.auth.AccountProvider;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.modernmusic.client.ui.PlayerTheme.*;

/** Local account screen: QR contents stay in memory and are drawn without allocating a game texture. */
public final class AccountScreen extends MusicScreen {
    private final Screen parent;
    private final AccountController account;
    private final List<Button> actions = new ArrayList<>();
    private SecretEditBox cookie, smsCode;
    private EditBox country, phone;
    private Button smsSend, smsLogin, sharedSource;
    private String countryDraft = "86", phoneDraft = "";
    private int left, top, panelWidth, panelHeight, layout, tab;
    private AccountProvider.QrChallenge renderedChallenge;
    private BitMatrix matrix;
    private String localError;
    private boolean verificationLayout;

    public AccountScreen(Screen parent, AccountController account) {
        super(Component.translatable(account.platform().key("auth.title"))); this.parent = parent; this.account = account;
    }
    private boolean supportsSms() { return account.platform() == dev.modernmusic.domain.MusicPlatform.NETEASE; }
    private int currentLayout() { return account.session() != null ? 2 : account.qrActive() ? 1 : 0; }
    @Override protected void initMusic() {
        font = UiFont.get();
        eraseFields(); cookie = null; smsCode = null; country = null; phone = null;
        smsSend = null; smsLogin = null; sharedSource = null; actions.clear();
        panelWidth = Math.min(560, width - 24); panelHeight = Math.min(280, height - 20);
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2;
        layout = currentLayout();
        verificationLayout = account.qrNeedsVerification();
        addRenderableWidget(new IconButton(left + panelWidth - 76, top + 6, 60, 20, Component.translatable("gui.back"), Icon.LEFT, false, true, this::onClose));
        if (dev.modernmusic.client.SharedAccountClient.source() != null)
            sharedSource = addRenderableWidget(new IconButton(left + panelWidth - 162, top + 6, 80, 20, Component.translatable("modernmusic.shared.short_title"), Icon.GROUP, false, true,
                    () -> {
                        var source = dev.modernmusic.client.SharedAccountClient.source();
                        if (source != null) client().setScreen(new SharedSourceScreen(this, source));
                    }));
        if (layout == 1) {
            button("gui.cancel", left + panelWidth - 108, top + panelHeight - 67, 92, account::cancelQr, false);
            if (verificationLayout) {
                int available = Math.min(140, panelHeight - 102);
                int x = left + available + 30, w = panelWidth - available - 46;
                if (account.qrVerificationUri() != null) {
                    button("modernmusic.auth.qr_open_verification", x, top + 105, w,
                            () -> {
                                var uri = account.qrVerificationUri();
                                if (uri != null) net.minecraft.Util.getPlatform().openUri(uri);
                            }, true);
                    button("modernmusic.auth.qr_resume", x, top + 129, w, account::resumeQrAfterVerification, false);
                }
                button("modernmusic.auth.qr_use_sms", left + 16, top + panelHeight - 67, panelWidth - 132,
                        () -> { account.cancelQr(); switchTab(1); }, true);
            }
        } else if (layout == 0) {
            int count = 2 + account.qrMethods().size() + (supportsSms() ? 1 : 0);
            int tabWidth = (panelWidth - 32 - (count - 1) * 8) / count, index = 1;
            actions.add(tab(button("modernmusic.auth.tab_cookie", left + 16, top + 42, tabWidth, () -> switchTab(0), false), tab == 0));
            if (supportsSms()) actions.add(tab(button("modernmusic.auth.tab_phone", left + 16 + (tabWidth + 8) * index++, top + 42,
                    tabWidth, () -> switchTab(1), false), tab == 1));
            for (var method : account.qrMethods()) {
                String label = switch (method) {
                    case APP -> "modernmusic.auth.qr_login";
                    case QQ -> "modernmusic.qq.auth.qr_qq";
                    case WECHAT -> "modernmusic.qq.auth.qr_wechat";
                };
                actions.add(tab(button(label, left + 16 + (tabWidth + 8) * index++, top + 42, tabWidth,
                        () -> account.beginQr(method), false), false));
            }
            actions.add(tab(button("modernmusic.auth.tab_saved", left + 16 + (tabWidth + 8) * index, top + 42,
                    tabWidth, () -> switchTab(2), false), tab == 2));
            if (tab == 0) {
                cookie = addRenderableWidget(new CookieEditBox(font, left + 16, top + 92, panelWidth - 32,
                        Component.translatable(account.platform().key("auth.cookie_label")), account.platform(), error -> localError = error));
                actions.add(button("modernmusic.auth.cookie_login", left + 16, top + 120, panelWidth - 32, () -> {
                    String value = cookie.getValue(); cookie.setValue(""); account.loginCookie(value);
                }, true));
            } else if (tab == 1) {
                country = addRenderableWidget(new ThemedEditBox(font, left + 16, top + 88, 52, 20, Component.translatable("modernmusic.auth.country_label")));
                country.setMaxLength(5); country.setValue(countryDraft); country.setResponder(value -> countryDraft = value);
                phone = addRenderableWidget(new ThemedEditBox(font, left + 76, top + 88, panelWidth - 92, 20, Component.translatable("modernmusic.auth.phone_label")));
                phone.setMaxLength(24); phone.setValue(phoneDraft); phone.setResponder(value -> phoneDraft = value);
                smsCode = addRenderableWidget(new SecretEditBox(font, left + 16, top + 136, panelWidth - 144,
                        Component.translatable("modernmusic.auth.code_label"), 8));
                smsSend = button("modernmusic.auth.sms_send", left + panelWidth - 120, top + 136, 104,
                        () -> account.sendSms(country.getValue(), phone.getValue()), false);
                actions.add(smsSend);
                smsLogin = button("modernmusic.auth.sms_login", left + 16, top + 162, panelWidth - 32, this::submitSms, true);
                actions.add(smsLogin);
            } else initRemember(120);
        } else {
            phoneDraft = "";
            initRemember(132);
            actions.add(button("modernmusic.auth.logout", left + 16, top + 158, panelWidth - 32, account::logout, false));
        }
        if (cookie != null) setInitialFocus(cookie); else if (phone != null) setInitialFocus(phone);
        tick();
    }
    private void initRemember(int buttonY) {
            int half = (panelWidth - 40) / 2;
            actions.add(button(layout == 2 ? "modernmusic.auth.remember" : "modernmusic.auth.unlock", left + 16, top + buttonY, half, () -> {
                if (layout == 2) account.remember(); else account.restore();
            }, true));
            if (layout == 2) dev.modernmusic.compat.Java17.last(actions).setTooltip(Tooltip.create(Component.translatable("modernmusic.auth.storage_help")));
            actions.add(button("modernmusic.auth.forget", left + 24 + half,
                    top + buttonY, half, account::forgetSaved, false));
    }
    private void switchTab(int selected) { tab = selected; rebuildWidgets(); }
    private static Button tab(Button button, boolean selected) { ((ThemedButton) button).selected(selected); return button; }
    private void submitSms() {
        String value = smsCode.getValue(); smsCode.setValue(""); account.loginSms(country.getValue(), phone.getValue(), value);
    }
    private Button button(String key, int x, int y, int w, Runnable action, boolean primary) {
        Button button = new ThemedButton(x, y, w, 20, Component.translatable(key), primary, () -> { localError = null; action.run(); });
        return addRenderableWidget(button);
    }
    @Override public void tick() {
        if (layout != currentLayout() || verificationLayout != account.qrNeedsVerification()) { rebuildWidgets(); return; }
        if (sharedSource != null) {
            sharedSource.active = dev.modernmusic.client.SharedAccountClient.available();
            sharedSource.setTooltip(Tooltip.create(Component.translatable(sharedSource.active
                    ? "modernmusic.shared.short_title" : "modernmusic.shared.unavailable")));
        }
        for (Button action : actions) {
            action.active = !account.busy();
            String key = ((net.minecraft.network.chat.contents.TranslatableContents) action.getMessage().getContents()).getKey();
            if ((key.equals("modernmusic.auth.unlock") || key.equals("modernmusic.auth.forget")) && !account.hasSaved()) action.active = false;
            if (key.equals("modernmusic.auth.remember") && account.hasSaved()) action.active = false;
        }
        if (cookie != null) cookie.setEditable(!account.busy());
        if (country != null) country.setEditable(!account.busy());
        if (phone != null) phone.setEditable(!account.busy());
        if (smsCode != null) smsCode.setEditable(!account.busy());
        if (smsSend != null) {
            int seconds = account.smsCooldownSeconds();
            smsSend.active = !account.busy() && seconds == 0;
            smsSend.setMessage(seconds > 0 ? Component.translatable("modernmusic.auth.sms_resend_in", seconds) : Component.translatable("modernmusic.auth.sms_send"));
            smsSend.setTooltip(Tooltip.create(smsSend.getMessage()));
            smsLogin.active = !account.busy() && account.smsLoginCooldownSeconds() == 0;
        }
        if (renderedChallenge != account.qr()) {
            renderedChallenge = account.qr(); matrix = null;
            if (renderedChallenge != null) {
                try {
                    matrix = new QRCodeWriter().encode(renderedChallenge.content(),
                            BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
                } catch (Exception error) { account.cancelQr(); localError = "modernmusic.auth.qr_error"; }
            }
        }
    }
    @ParametersAreNonnullByDefault @Override public void renderBackground(GuiGraphics g) {
        if (client().level == null) renderDirtBackground(g);
        scrim(g, width, height);
        panel(g, left, top, panelWidth, panelHeight, 12);
        PlayerTheme.header(g, left, top, panelWidth, 32);
        if (layout == 0) {
            int tabWidth = (panelWidth - 56) / 4;
            tabStrip(g, left + 12, top + 38, tabWidth * 4 + 32, 28, 14);
        }
        divider(g, left + 16, top + panelHeight - 38, panelWidth - 32);
    }
    @Override protected void renderMusic(GuiGraphics g, int mx, int my, float partial) {
        // A click or queued account callback can change the session after Screen.tick but before this frame.
        if (layout != currentLayout() || verificationLayout != account.qrNeedsVerification()) rebuildWidgets();
        super.renderMusic(g, mx, my, partial);
        icon(g, Icon.ACCOUNT, left + 21, top + 15, HEAD_ACCENT);
        heading(g, title.getString(), left + 34, top + 16, HEAD_TEXT);
        if (layout == 1) {
            int available = Math.min(140, panelHeight - 102);
            if (matrix != null) {
                QrDrawing.draw(g, matrix, left + 16, top + 47, available);
                boolean verification = account.qrNeedsVerification();
                String help = !verification ? qrHelp() : account.qrVerificationUri() == null
                        ? "modernmusic.auth.qr_security_no_link" : "modernmusic.auth.qr_security_help";
                wrapped(g, Component.translatable(help), left + available + 30, top + 51,
                        panelWidth - available - 46, verification ? ACCENT : MUTED, 5);
            }
            else wrapped(g, Component.translatable(account.message()), left + 24, top + 70, panelWidth - 48, ACCENT, 5);
        } else {
            if (layout == 0) {
                if (tab == 0) {
                    g.drawString(font, Component.translatable(account.platform().key("auth.cookie_label")), left + 16, top + 78, MUTED, false);
                    wrapped(g, Component.translatable(account.platform().key("auth.cookie_help")), left + 16, top + 152, panelWidth - 32, MUTED, 3);
                } else if (tab == 1) {
                    g.drawString(font, Component.translatable("modernmusic.auth.country_label"), left + 16, top + 75, MUTED, false);
                    g.drawString(font, Component.translatable("modernmusic.auth.phone_label"), left + 76, top + 75, MUTED, false);
                    g.drawString(font, Component.translatable("modernmusic.auth.code_label"), left + 16, top + 122, MUTED, false);
                } else {
                    g.drawString(font, Component.translatable("modernmusic.auth.tab_saved"), left + 16, top + 78, MUTED, false);
                    wrapped(g, Component.translatable("modernmusic.auth.unlock_help"), left + 16, top + 152, panelWidth - 32, MUTED, 3);
                }
            }
            else {
                dot(g, left + 35, top + 65, 22, alpha(ACCENT, .25));
                ArtworkCache.get().draw(g, account.session().avatar(), left + 16, top + 46, 38, true);
                String nickname = ellipsis(titleFont(), account.session().nickname(), (int) ((panelWidth - 90) / titleScale()));
                title(g, nickname, left + 66, top + 56, TEXT);
                g.drawString(font, "ID " + account.session().userId(), left + 66, top + 68, MUTED, false);
                wrapped(g, Component.translatable(account.hasSaved() ? "modernmusic.auth.saved_help" : "modernmusic.auth.memory_help"),
                        left + 16, top + 94, panelWidth - 32, MUTED, 3);
            }
        }
        String status = localError == null ? account.message() : localError;
        wrapped(g, Component.translatable(status), left + 16, top + panelHeight - 31, panelWidth - 32, MUTED, 2);
        if (my >= top + panelHeight - 34 && my < top + panelHeight && mx >= left && mx < left + panelWidth)
            g.renderTooltip(font, font.split(Component.translatable(status), panelWidth - 32), mx, my);
    }
    private void wrapped(GuiGraphics g, Component text, int x, int y, int width, int color, int lines) {
        var split = font.split(text, width);
        for (int i = 0; i < Math.min(lines, split.size()); i++) g.drawString(font, split.get(i), x, y + i * 11, color, false);
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && cookie != null && cookie.isFocused() && !account.busy()) {
            String value = cookie.getValue(); cookie.setValue(""); account.loginCookie(value); return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && smsCode != null && smsCode.isFocused() && smsLogin.active) {
            submitSms(); return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    private void eraseFields() {
        if (cookie != null) cookie.setValue(""); if (smsCode != null) smsCode.setValue("");
    }
    private String qrHelp() {
        if (renderedChallenge != null && renderedChallenge.method() == AccountProvider.QrMethod.WECHAT)
            return "modernmusic.qq.auth.qr_wechat_help";
        return account.platform().key("auth.qr_help");
    }
    @Override public void removed() {
        eraseFields(); if (phone != null) phone.setValue(""); phoneDraft = "";
        account.cancelQr(); renderedChallenge = null; matrix = null;
    }
    @Override public void onClose() { client().setScreen(parent); }
}
