package dev.modernmusic.client.audio;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSoundOutputTest {
    static final PcmAudio PCM = new PcmAudio(new byte[176400], 44100, 2);
    static final class Device {
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), active = new AtomicInteger();
        boolean fail;
        SourceDataLine create(AudioFormat format) {
            return (SourceDataLine) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SourceDataLine.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "open" -> {
                        if (fail) throw new LineUnavailableException();
                        assertEquals(1, active.incrementAndGet(), "Device opened before releasing previous line");
                        opens.incrementAndGet(); yield null;
                    }
                    case "close" -> { if (!fail) active.decrementAndGet(); closes.incrementAndGet(); yield null; }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestSourceDataLine";
                    case "getLongFramePosition" -> 0L;
                    case "available" -> 8192;
                    case "write" -> args[2];
                    default -> null;
                };
            });
        }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }
    static Object requested(JavaSoundOutput output) throws Exception {
        var field = JavaSoundOutput.class.getDeclaredField("requestedAudio"); field.setAccessible(true); return field.get(output);
    }
    static Thread worker(JavaSoundOutput output) throws Exception {
        var field = JavaSoundOutput.class.getDeclaredField("worker"); field.setAccessible(true); return (Thread) field.get(output);
    }
    @Test void idleAndPausedDevicesSleepUntilACommandAndResumePromptly() throws Exception {
        Device device = new Device();
        try (var output = new JavaSoundOutput(device::create)) {
            assertNull(worker(output), "Unused output must not start a thread");
            output.load(PCM, 0, true);
            Thread worker = worker(output);
            await(() -> output.snapshot().state() == AudioOutput.State.PAUSED && worker.getState() == Thread.State.WAITING);
            output.pause(false);
            await(() -> output.snapshot().state() == AudioOutput.State.PLAYING);
            output.stop();
            await(() -> device.active.get() == 0 && worker.getState() == Thread.State.WAITING);
            output.load(PCM, 500, true);
            await(() -> output.snapshot().state() == AudioOutput.State.PAUSED && output.snapshot().positionMs() == 500);
        }
    }
    @Test void closeBeforeFirstPlaybackNeverStartsAWorker() throws Exception {
        Device device = new Device();
        var output = new JavaSoundOutput(device::create);
        output.volume(.5f); output.pause(true); output.seek(100); output.stop();
        assertNull(worker(output));
        output.close(); output.close(); output.load(PCM);
        assertNull(worker(output)); assertEquals(0, device.opens.get());
        assertEquals(AudioOutput.State.STOPPED, output.snapshot().state());
    }
    @Test void repeatedLoadSeekStopReleasesEveryDeviceAndPcm() throws Exception {
        Device device = new Device();
        try (var output = new JavaSoundOutput(device::create)) {
            for (int i = 0; i < 100; i++) {
                output.load(PCM, 0, true); await(() -> output.snapshot().state() == AudioOutput.State.PAUSED);
                output.seek(500); await(() -> output.snapshot().positionMs() == 500);
                output.stop(); await(() -> device.active.get() == 0);
                assertNull(requested(output));
            }
            assertEquals(100, device.opens.get()); assertEquals(device.opens.get(), device.closes.get());
        }
    }
    @Test void pauseAlignmentAndResumeReuseTheOpenDevice() throws Exception {
        Device device = new Device();
        try (var output = new JavaSoundOutput(device::create)) {
            output.load(PCM);
            await(() -> output.snapshot().state() == AudioOutput.State.PLAYING);
            for (int i = 1; i <= 20; i++) {
                long position = i * 20L;
                output.pause(true); output.seek(position);
                await(() -> output.snapshot().state() == AudioOutput.State.PAUSED && output.snapshot().positionMs() == position);
                output.pause(false);
                await(() -> output.snapshot().state() == AudioOutput.State.PLAYING);
                assertEquals(1, device.opens.get(), "Room alignment must not reopen the audio device");
                assertEquals(0, device.closes.get());
            }
            output.stop(); await(() -> device.active.get() == 0);
        }
    }
    @Test void closeRejectsLateLoadsAndReleasesActiveLine() throws Exception {
        Device device = new Device(); var output = new JavaSoundOutput(device::create);
        try {
            output.load(PCM); await(() -> output.snapshot().state() == AudioOutput.State.PLAYING);
        } finally { output.close(); }
        output.close(); output.load(PCM); output.seek(400);
        await(() -> device.closes.get() == 1);
        assertEquals(AudioOutput.State.STOPPED, output.snapshot().state()); assertNull(requested(output));
        var field = JavaSoundOutput.class.getDeclaredField("worker"); field.setAccessible(true);
        Thread worker = (Thread) field.get(output); worker.join(1000); assertFalse(worker.isAlive());
    }
    @Test void failedOpenClosesPartialDeviceAndDropsPcm() throws Exception {
        Device device = new Device(); device.fail = true;
        try (var output = new JavaSoundOutput(device::create)) {
            output.load(PCM); await(() -> output.snapshot().state() == AudioOutput.State.ERROR);
            assertEquals(1, device.closes.get()); assertNull(requested(output));
        }
    }
}
