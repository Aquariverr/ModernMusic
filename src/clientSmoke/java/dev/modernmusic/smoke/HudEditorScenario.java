package dev.modernmusic.smoke;

import dev.modernmusic.client.HudSettings;
import dev.modernmusic.client.PlaybackController;
import dev.modernmusic.client.ui.HudEditorScreen;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.lwjgl.glfw.GLFW;

/** Real screen input checks for the HUD gallery; no providers, accounts or audio devices. */
final class HudEditorScenario {
    static void verify(Minecraft game, PlaybackController player, Path file) {
        var hud = new HudSettings(file); hud.reset();
        int width = game.getWindow().getGuiScaledWidth(), height = game.getWindow().getGuiScaledHeight();
        hud.ensureLayout(width, height); hud.component(HudSettings.Part.COVER).scale(.75);
        hud.component(HudSettings.Part.LYRICS).enabled(false); hud.component(HudSettings.Part.INFO).opacity(.4);
        var original = hud.snapshot(width, height);
        game.setScreen(new HudEditorScreen(null, player, hud));
        widget(game, "modernmusic.hud.all_scale");
        var box = hud.bounds(width, height);
        game.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        close(box.left() + 1, hud.bounds(width, height).left(), "default whole selection allows arrow nudging");
        game.screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, GLFW.GLFW_MOD_SHIFT);
        close(box.top() + 10, hud.bounds(width, height).top(), "Shift nudges all parts");
        game.screen.keyPressed(GLFW.GLFW_KEY_H, 0, 0);
        box = hud.bounds(width, height);
        double x = box.left() + 8, y = box.top() + 8;
        require(game.screen.mouseClicked(x, y, 0), "group drag click");
        game.screen.mouseDragged(x + 40, y + 30, 0, 40, 30); game.screen.mouseReleased(x + 40, y + 30, 0);
        close(box.left() + 40, hud.bounds(width, height).left(), "whole drag x");
        close(box.top() + 30, hud.bounds(width, height).top(), "whole drag y");
        close(box.width(), hud.bounds(width, height).width(), "whole drag preserves width");
        box = hud.bounds(width, height);
        game.screen.mouseScrolled(box.left() + 8, box.top() + 8, 0, 2);
        close(.825, hud.component(HudSettings.Part.COVER).scale(), "whole scroll cover scale");
        close(1.1, hud.component(HudSettings.Part.INFO).scale(), "whole scroll info scale");
        box = hud.bounds(width, height); x = box.left() + box.width(); y = box.top() + box.height();
        require(game.screen.mouseClicked(x, y, 0), "group resize handle click");
        game.screen.mouseDragged(box.left() + box.width() * 1.1, box.top() + box.height() * 1.1, 0, box.width() * .1, box.height() * .1);
        game.screen.mouseReleased(x, y, 0);
        close(1.21, hud.component(HudSettings.Part.INFO).scale(), "whole resize handle");
        game.screen.keyPressed(GLFW.GLFW_KEY_H, 0, 0);
        var slider = widget(game, "modernmusic.hud.all_scale");
        x = slider.getX() + 4 + (slider.getWidth() - 8) * ((1.3 - .25) / 2.75); y = slider.getY() + 20;
        game.screen.mouseClicked(x, y, 0); game.screen.mouseReleased(x, y, 0);
        close(1.3, hud.component(HudSettings.Part.INFO).scale(), "whole size slider");
        game.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        close(1.31, hud.component(HudSettings.Part.INFO).scale(), "focused slider changes scale, not position");
        press(game, "modernmusic.hud.align_center"); box = hud.bounds(width, height);
        close(width / 2.0, box.left() + box.width() / 2, "whole center alignment");
        require(!hud.component(HudSettings.Part.LYRICS).enabled(), "hidden part stays hidden");
        close(.4, hud.component(HudSettings.Part.INFO).opacity(), "opacity stays unchanged");
        press(game, "gui.cancel"); require(original.equals(hud.snapshot(width, height)), "cancel restores whole edit");

        game.setScreen(new HudEditorScreen(null, player, hud)); press(game, "modernmusic.hud.all");
        game.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, GLFW.GLFW_MOD_SHIFT);
        var saved = hud.snapshot(width, height); press(game, "gui.done");
        require(saved.equals(new HudSettings(file).snapshot(width, height)), "save and reload whole edit");
        game.setScreen(new HudEditorScreen(null, player, hud)); press(game, "modernmusic.hud.all");
        press(game, "modernmusic.hud.part.cover");
        int infoX = hud.component(HudSettings.Part.INFO).left(width, height);
        game.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        require(infoX == hud.component(HudSettings.Part.INFO).left(width, height), "single part selection still works");
        press(game, "modernmusic.hud.all"); checkWidgets(game);
    }

    static void expand(Minecraft game) {
        if (game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .anyMatch(widget -> key(widget, "modernmusic.hud.expand"))) press(game, "modernmusic.hud.expand");
    }
    static void press(Minecraft game, String key) {
        var widget = widget(game, key);
        double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
        var screen = game.screen;
        require(screen.mouseClicked(x, y, 0), "button click " + key); screen.mouseReleased(x, y, 0);
    }
    static void checkWidgets(Minecraft game) {
        var widgets = game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        for (int i = 0; i < widgets.size(); i++) {
            var a = widgets.get(i); if (!a.visible) continue;
            require(a.getX() >= 0 && a.getY() >= 0 && a.getRight() <= game.screen.width && a.getBottom() <= game.screen.height,
                    "widget outside viewport: " + a.getMessage().getString());
            for (int j = i + 1; j < widgets.size(); j++) {
                var b = widgets.get(j); if (!b.visible) continue;
                require(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
                        "overlapping widgets: " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
        }
    }
    private static AbstractWidget widget(Minecraft game, String key) {
        return game.screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> key(widget, key)).findFirst().orElseThrow(() -> new AssertionError("Missing widget " + key));
    }
    private static boolean key(AbstractWidget widget, String key) {
        return widget.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals(key);
    }
    private static void close(double expected, double actual, String label) { require(Math.abs(expected - actual) < .00001, label + ": " + expected + " != " + actual); }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
