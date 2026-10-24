package dev.modernmusic.probe;

import dev.modernmusic.client.audio.*;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Silent, opt-in device smoke test. Does not claim that subjective listening was verified. */
public final class AudioProbe {
    public static void main(String[] args) throws Exception {
        try (var output = new JavaSoundOutput()) {
            output.volume(0);
            output.load(new PcmAudio(new byte[48000 * 4 * 3], 48000, 2));
            await(() -> output.snapshot().state() == AudioOutput.State.PLAYING && output.snapshot().positionMs() >= 200);
            output.pause(true);
            await(() -> output.snapshot().state() == AudioOutput.State.PAUSED);
            long paused = output.snapshot().positionMs();
            Thread.sleep(100);
            if (Math.abs(output.snapshot().positionMs() - paused) > 50) throw new IllegalStateException("Pause advanced");
            output.seek(1500);
            await(() -> output.snapshot().state() == AudioOutput.State.PAUSED && output.snapshot().positionMs() >= 1490);
            output.pause(false);
            await(() -> output.snapshot().state() == AudioOutput.State.PLAYING && output.snapshot().positionMs() >= 1700);
            output.seek(2900);
            await(() -> output.snapshot().state() == AudioOutput.State.FINISHED);
            output.stop();
            if (output.snapshot().state() != AudioOutput.State.STOPPED) throw new IllegalStateException("Stop failed");
            System.out.println("Silent audio-device probe passed: open, progress, pause, seek, resume, finish, stop.");
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Audio-device probe timed out");
            Thread.sleep(10);
        }
    }
}
