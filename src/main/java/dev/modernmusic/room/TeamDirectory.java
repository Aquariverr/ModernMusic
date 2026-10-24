package dev.modernmusic.room;

import java.util.Optional;
import java.util.UUID;

/** Server-only authority. Never populated from a client packet. */
public interface TeamDirectory {
    enum State { NOT_INSTALLED, UNAVAILABLE, READY }
    record Party(UUID id, String name, UUID owner) {
        public Party {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(owner);
            name = name == null || name.isBlank() ? "FTB Teams" : name.strip();
            if (name.length() > 40) name = name.substring(0, 40);
        }
    }
    State state();
    Optional<Party> party(UUID player);

    TeamDirectory ABSENT = new TeamDirectory() {
        @Override public State state() { return State.NOT_INSTALLED; }
        @Override public Optional<Party> party(UUID player) { return Optional.empty(); }
    };
}
