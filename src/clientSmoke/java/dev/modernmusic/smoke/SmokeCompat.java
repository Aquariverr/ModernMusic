package dev.modernmusic.smoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;

final class SmokeCompat {
    static int right(AbstractWidget widget) { return widget.getX() + widget.getWidth(); }
    static int bottom(AbstractWidget widget) { return widget.getY() + widget.getHeight(); }
    static <T> List<T> reversed(List<T> values) {
        var copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }
}
