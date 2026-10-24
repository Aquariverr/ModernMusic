package dev.modernmusic.room;

import java.util.UUID;

/** Sender identity comes from the connection. DURATION uses playbackRevision and the current song ID as text. */
public record RoomCommand(Action action, UUID room, UUID epoch, long revision, String text, long value) {
    public enum Action {
        CREATE, INVITE, JOIN, JOIN_TEAM, LEAVE, DJ, KICK, ADD, REMOVE, CLEAR, PLAY, PAUSE, STOP, SEEK, NEXT, MODE, DURATION, SNAPSHOT, REQUESTER, DISSOLVE;

        public String permissionError() {
            return switch (this) {
                case ADD -> "request_permission";
                case INVITE, DJ, REQUESTER, KICK, DISSOLVE -> "owner_permission";
                default -> "permission";
            };
        }
    }
    public RoomCommand {
        if (action == null || text == null || text.length() > 64 || revision < -1)
            throw new IllegalArgumentException("Invalid room command");
    }
}
