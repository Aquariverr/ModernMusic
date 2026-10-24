package dev.modernmusic.client.ui;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Shared viewport for music menus; the HUD editor keeps the game's coordinates for its live preview. */
public abstract class MusicScreen extends Screen {
    private double guiScale = 1;
    private List<FormattedCharSequence> tooltip;
    private ClientTooltipPositioner tooltipPositioner;

    protected MusicScreen(Component title) { super(title); }

    /** Screen.minecraft is assigned by Screen.init before any widget or render callback. */
    protected final net.minecraft.client.Minecraft client() {
        return java.util.Objects.requireNonNull(minecraft, "Screen has not been initialized");
    }

    @Override protected final void init() {
        var window = client().getWindow();
        var layout = MusicScreenLayout.fit(window.getWidth(), window.getHeight());
        width = layout.width(); height = layout.height();
        guiScale = layout.pixelsPerUnit() / window.getGuiScale();
        double previous = UiFont.layoutScale(guiScale);
        try { initMusic(); }
        finally { UiFont.layoutScale(previous); }
    }

    @Override public boolean isPauseScreen() { return false; }

    protected abstract void initMusic();

    @Override public final void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        // Tooltips use guiWidth/guiHeight internally, so they need the same virtual viewport as widgets.
        var local = new GuiGraphics(client(), graphics.bufferSource()) {
            @Override public int guiWidth() { return width; }
            @Override public int guiHeight() { return height; }
            @Override public void enableScissor(int left, int top, int right, int bottom) {
                // In 1.21.1 scissor bounds bypass the pose stack and use native GUI coordinates.
                flush();
                var pose = pose().last().pose();
                super.enableScissor((int) Math.floor(left * pose.m00() + pose.m30()),
                        (int) Math.floor(top * pose.m11() + pose.m31()),
                        (int) Math.ceil(right * pose.m00() + pose.m30()),
                        (int) Math.ceil(bottom * pose.m11() + pose.m31()));
            }
            @Override public void disableScissor() { flush(); super.disableScissor(); }
            @Override public boolean containsPointInScissor(int x, int y) {
                var pose = pose().last().pose();
                return super.containsPointInScissor((int) Math.floor(x * pose.m00() + pose.m30()),
                        (int) Math.floor(y * pose.m11() + pose.m31()));
            }
        };
        graphics.flush();
        local.pose().last().pose().set(graphics.pose().last().pose());
        local.pose().scale((float) guiScale, (float) guiScale, 1);
        int x = (int) Math.floor(mouseX / guiScale), y = (int) Math.floor(mouseY / guiScale);
        double previous = UiFont.layoutScale(guiScale);
        try {
            renderMusic(local, x, y, delta);
            if (tooltip != null) local.renderTooltip(font, tooltip, tooltipPositioner, x, y);
            local.flush();
        } finally {
            tooltip = null; tooltipPositioner = null;
            UiFont.layoutScale(previous);
        }
    }

    protected void renderMusic(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
    }

    @ParametersAreNonnullByDefault @Override public final void setTooltipForNextRenderPass(List<FormattedCharSequence> lines,
            ClientTooltipPositioner positioner, boolean override) {
        if (tooltip == null || override) { tooltip = lines; tooltipPositioner = positioner; }
    }

    @ParametersAreNonnullByDefault @Override public final void setTooltipForNextRenderPass(Tooltip tooltip,
            ClientTooltipPositioner positioner, boolean override) {
        setTooltipForNextRenderPass(ThemedTooltips.split(tooltip, font, width), positioner, override);
    }

    @Override protected final void clearTooltipForNextRenderPass() {
        super.clearTooltipForNextRenderPass();
        tooltip = null; tooltipPositioner = null;
    }

    /** Converts a music widget coordinate to the vanilla GUI coordinate used by mouse events. */
    public final double toGuiCoordinate(double coordinate) { return coordinate * guiScale; }

    @Override public final void mouseMoved(double x, double y) { super.mouseMoved(x / guiScale, y / guiScale); }
    @Override public final boolean mouseClicked(double x, double y, int button) {
        return clickMusic(x / guiScale, y / guiScale, button);
    }
    /** Pointer interception in the same virtual coordinates as music widgets. */
    protected boolean clickMusic(double x, double y, int button) {
        return super.mouseClicked(x, y, button);
    }
    @Override public final boolean mouseReleased(double x, double y, int button) {
        return super.mouseReleased(x / guiScale, y / guiScale, button);
    }
    @Override public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return super.mouseDragged(x / guiScale, y / guiScale, button, dx / guiScale, dy / guiScale);
    }
    @Override public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return scrollMusic(x / guiScale, y / guiScale, horizontal, vertical);
    }
    protected boolean scrollMusic(double x, double y, double horizontal, double vertical) {
        return super.mouseScrolled(x, y, horizontal, vertical);
    }
}
