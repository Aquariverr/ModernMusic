package dev.modernmusic.client.ui;

import javax.annotation.Nonnull;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Masks both rendering and accessibility narration; never exports a secret back to the clipboard. */
class SecretEditBox extends ThemedEditBox {
    SecretEditBox(Font font, int x, int y, int width, Component label, int limit) {
        super(font, x, y, width, 20, label);
        setMaxLength(limit);
        setFormatter((value, offset) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        setTextColor(PlayerTheme.FIELD_TEXT);
    }
    @Nonnull @Override protected MutableComponent createNarrationMessage() {
        return Component.translatable("modernmusic.auth.secret_narration", getMessage(), getValue().length());
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (Screen.isCopy(key) || Screen.isCut(key)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
}
