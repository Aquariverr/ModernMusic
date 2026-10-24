package dev.modernmusic.client.audio;

public interface AudioOutput extends AutoCloseable {
    enum State { STOPPED, STARTING, PLAYING, PAUSED, FINISHED, ERROR }
    record Snapshot(State state, long positionMs, long durationMs) {}

    void load(PcmAudio audio);
    default void load(PcmAudio audio, long positionMs, boolean paused) {
        load(audio); pause(paused); seek(positionMs);
    }
    void pause(boolean paused);
    void seek(long positionMs);
    void volume(float value);
    void stop();
    Snapshot snapshot();
    @Override void close();
}
