package dev.modernmusic.room;

import java.util.*;

public record RoomUpdate(long sequence, RoomSnapshot room, List<Invitation> invitations, String message, TeamStatus team,
                         List<SavedRoom> savedRooms, int roomLimit) {
    public record Invitation(UUID room, UUID epoch, String name) {
        public Invitation {
            Objects.requireNonNull(room); Objects.requireNonNull(epoch);
            if (name == null || name.length() > 40) throw new IllegalArgumentException("Invalid room invitation");
        }
    }
    /** The public room plus rooms this recipient owns or previously joined. */
    public record SavedRoom(UUID room, UUID epoch, String name, UUID owner, int members, int songs) {
        public boolean isPublicRoom() { return RoomService.PUBLIC_ROOM_ID.equals(room); }
        public SavedRoom {
            Objects.requireNonNull(room); Objects.requireNonNull(epoch); Objects.requireNonNull(owner);
            if (name == null || name.isBlank() || name.length() > 40 || members < 0
                    || members > (RoomService.PUBLIC_ROOM_ID.equals(room) ? RoomService.MAX_PUBLIC_MEMBERS : RoomService.MAX_MEMBERS)
                    || songs < 0 || songs > dev.modernmusic.domain.PlaybackQueue.MAX_TRACKS)
                throw new IllegalArgumentException("Invalid saved room");
        }
    }
    public RoomUpdate(long sequence, RoomSnapshot room, List<Invitation> invitations, String message) {
        this(sequence, room, invitations, message, TeamStatus.ABSENT);
    }
    public RoomUpdate(long sequence, RoomSnapshot room, List<Invitation> invitations, String message, TeamStatus team) {
        this(sequence, room, invitations, message, team, List.of(), RoomService.DEFAULT_ROOM_LIMIT);
    }
    public RoomUpdate {
        Objects.requireNonNull(team);
        invitations = List.copyOf(invitations);
        savedRooms = List.copyOf(savedRooms);
        long publicRooms = savedRooms.stream().filter(SavedRoom::isPublicRoom).count();
        if (sequence < 0 || invitations.size() > RoomService.MAX_ROOMS || publicRooms > 1
                || savedRooms.size() - publicRooms > RoomService.MAX_ROOMS
                || message == null || message.length() > 80 || roomLimit < 0 || roomLimit > RoomService.MAX_ROOMS)
            throw new IllegalArgumentException("Invalid room update");
    }
}
