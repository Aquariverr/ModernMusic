package dev.modernmusic.smoke;

import dev.modernmusic.auth.AccountController;
import dev.modernmusic.client.*;
import dev.modernmusic.client.ui.*;
import dev.modernmusic.domain.MusicPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Actual player widgets, account tooltip, source switching and in-world HUD. */
final class SharedDisplayScenario {
    private int step, frames;
    static void installAvatar(Minecraft game) throws Exception {
        // Synthetic avatar in the production texture cache, avoiding any external image request.
        var cache = ArtworkCache.get();
        var entries = ArtworkCache.class.getDeclaredField("entries"); entries.setAccessible(true);
        Class<?> type = Class.forName("dev.modernmusic.client.ui.ArtworkCache$Entry");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); Object entry = constructor.newInstance();
        var pixels = new com.mojang.blaze3d.platform.NativeImage(256, 256, false);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            boolean head = (x - 128) * (x - 128) + (y - 85) * (y - 85) < 35 * 35;
            boolean body = y > 138 && y < 223 && (x - 128) * (x - 128) + (y - 221) * (y - 221) < 83 * 83;
            pixels.setPixelRGBA(x, y, head || body ? 0xFFF7F3EA : 0xFFAB6548);
        }
        var texture = new net.minecraft.client.renderer.texture.DynamicTexture(pixels);
        var location = game.getTextureManager().register("shared-profile-fixture", texture);
        var textureField = type.getDeclaredField("texture"); textureField.setAccessible(true); textureField.set(entry, location);
        @SuppressWarnings("unchecked") var map = (java.util.Map<java.net.URI, Object>) entries.get(cache);
        map.put(SharedAccountFixture.AVATAR, entry);
    }
    boolean tick(Minecraft game, PlaybackController player, AccountController account) throws Exception {
        var source = player.sharedSource();
        switch (step) {
            case 0 -> {
                if (!source.available()) return false;
                if (!source.profile().nickname().isEmpty() || source.profile().avatarUri() != null) throw new AssertionError("Shared identity was disclosed");
                game.setScreen(new PlayerScreen(null, player, account)); focusAccount(game); frames = 0; step++;
            }
            case 1 -> {
                if (++frames < 15) return false;
                checkShared(game, player, account); capture(game, "shared-player-tooltip.png");
                game.getWindow().setWindowed(720, 520); game.resizeDisplay(); focusAccount(game); frames = 0; step++;
            }
            case 2 -> {
                if (++frames < 15) return false;
                checkShared(game, player, account); capture(game, "shared-player-tooltip-compact.png");
                accountButton(game).onPress();
                if (!(game.screen instanceof SharedSourceScreen)) throw new AssertionError("Shared icon opened personal login");
                frames = 0; step++;
            }
            case 3 -> {
                if (++frames < 10) return false;
                capture(game, "shared-profile-source.png");
                SharedAdminScenario.press(game, "modernmusic.shared.use_personal"); game.screen.onClose(); focusAccount(game); frames = 0; step++;
            }
            case 4 -> {
                if (++frames < 10) return false;
                String tooltip = accountTooltip(game);
                if (tooltip.contains("Server fixture") || !tooltip.contains("本机账号")) throw new AssertionError("Local tooltip not restored");
                if (PlaybackAccountView.of(null, account.message(), source).server()) throw new AssertionError("Local source still shows shared identity");
                capture(game, "shared-return-local.png");
                accountButton(game).onPress();
                if (!(game.screen instanceof AccountScreen)) throw new AssertionError("Local icon does not open local login");
                source.select(true); player.togglePause(); game.getWindow().setWindowed(1100, 720); game.resizeDisplay(); game.setScreen(null); frames = 0; step++;
            }
            case 5 -> {
                if (player.state() != PlaybackController.State.PLAYING || ++frames < 20) return false;
                if (!MusicHud.status(player).contains("服务器共享")) throw new AssertionError("HUD missing selected source");
                capture(game, "shared-playback-hud.png"); game.setScreen(new SharedSourceScreen(null, source)); step++;
            }
            default -> { return true; }
        }
        return false;
    }
    private static void checkShared(Minecraft game, PlaybackController player, AccountController account) {
        checkSharedTooltip(game, player.platform());
        var view = PlaybackAccountView.of(account.session(), account.message(), player.sharedSource());
        if (!view.server() || !view.ready() || view.avatar() != null || !view.nickname().isEmpty()) throw new AssertionError("Shared identity was disclosed");
    }
    static void checkSharedTooltip(Minecraft game, MusicPlatform platform) {
        // The platform prefix is public context; the generic shared label must still contain no account identity.
        String expected = Component.translatable(platform.translationKey()).getString() + " · "
                + Component.translatable("modernmusic.shared.account_name").getString();
        String actual = accountTooltip(game).strip();
        if (!actual.equals(expected))
            throw new AssertionError("Shared tooltip mismatch: expected [" + expected + "], actual [" + actual + "]");
    }
    static Button accountButton(Minecraft game) {
        return game.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals("modernmusic.auth.title"))
                .findFirst().orElseThrow();
    }
    static String accountTooltip(Minecraft game) {
        var tooltip = accountButton(game).getTooltip();
        if (tooltip == null) throw new AssertionError("Account tooltip is missing");
        StringBuilder text = new StringBuilder();
        for (var line : tooltip.toCharSequence(game)) {
            line.accept((index, style, codepoint) -> { text.appendCodePoint(codepoint); return true; });
            text.append('\n');
        }
        return text.toString();
    }
    static void focusAccount(Minecraft game) {
        game.screen.tick(); game.setLastInputType(net.minecraft.client.InputType.KEYBOARD_TAB); game.screen.setFocused(accountButton(game));
    }
    private static void capture(Minecraft game, String name) { net.minecraft.client.Screenshot.grab(game.gameDirectory, name, game.getMainRenderTarget(), message -> {}); }
}
