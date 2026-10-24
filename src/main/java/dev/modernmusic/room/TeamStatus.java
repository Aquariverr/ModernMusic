package dev.modernmusic.room;

/** Only this player's availability is disclosed, never a directory of other parties. */
public record TeamStatus(State state, String name) {
    public enum State { NOT_INSTALLED, UNAVAILABLE, NO_PARTY, READY }
    public static final TeamStatus ABSENT = new TeamStatus(State.NOT_INSTALLED, "");
    public TeamStatus {
        if (state == null || name == null || name.length() > 40) throw new IllegalArgumentException("Invalid team status");
    }
    public String messageKey() { return "modernmusic.team." + state.name().toLowerCase(java.util.Locale.ROOT); }
}
