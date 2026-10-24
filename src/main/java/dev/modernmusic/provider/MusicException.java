package dev.modernmusic.provider;

/** Safe to display/log: never retains a response body, URL, Cookie or HTTP exception cause. */
public final class MusicException extends RuntimeException {
    private final MusicError error;

    public MusicException(MusicError error) {
        super(error.name());
        this.error = error;
    }

    public MusicError error() { return error; }
}
