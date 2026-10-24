package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import dev.modernmusic.auth.AuthException;
import dev.modernmusic.auth.SessionCookie;
import dev.modernmusic.domain.MusicPlatform;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Reduce whole JSON pastes before EditBox removes line breaks and truncates long input. */
final class CookieEditBox extends SecretEditBox {
    private final MusicPlatform platform;
    private final Consumer<String> error;

    CookieEditBox(Font font, int x, int y, int width, Component label, MusicPlatform platform, Consumer<String> error) {
        super(font, x, y, width, label, SessionCookie.MAX_LENGTH);
        this.platform = platform;
        this.error = error;
    }

    @ParametersAreNonnullByDefault @Override public void insertText(String text) {
        error.accept(null);
        if (!SessionCookie.isJsonImport(text)) { super.insertText(text); return; }
        setValue("");
        try { setValue(SessionCookie.parse(text, platform).header()); }
        catch (AuthException invalid) { error.accept(invalid.translationKey()); }
    }
}
