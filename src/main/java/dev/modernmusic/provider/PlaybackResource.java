package dev.modernmusic.provider;

import javax.annotation.Nonnull;
import dev.modernmusic.domain.SongId;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record PlaybackResource(SongId id, URI uri, Instant expiresAt) {
    public PlaybackResource {
        Objects.requireNonNull(id);
        Objects.requireNonNull(uri);
        Objects.requireNonNull(expiresAt);
    }

    @Override
    @Nonnull public String toString() {
        return "PlaybackResource[id=" + id + ", uri=<redacted>]";
    }
}
