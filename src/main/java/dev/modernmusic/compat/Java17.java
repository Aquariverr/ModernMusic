package dev.modernmusic.compat;

import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Small replacements for JDK 21 APIs; the Forge 1.20.1 runtime is Java 17. */
public final class Java17 {
    private Java17() {}
    public static int clamp(long value, int min, int max) { return (int) Math.max(min, Math.min(value, max)); }
    public static long clamp(long value, long min, long max) { return Math.max(min, Math.min(value, max)); }
    public static float clamp(float value, float min, float max) { return Math.max(min, Math.min(value, max)); }
    public static double clamp(double value, double min, double max) { return Math.max(min, Math.min(value, max)); }
    public static <T> T last(List<T> list) { return list.get(list.size() - 1); }
    public static ThreadFactory daemonFactory(String name) {
        var sequence = new AtomicInteger();
        return task -> {
            var thread = new Thread(task, name + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
    public static Thread startDaemon(String name, Runnable task) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
