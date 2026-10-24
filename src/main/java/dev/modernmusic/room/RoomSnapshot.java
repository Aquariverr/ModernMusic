package dev.modernmusic.room;

import dev.modernmusic.domain.PlaybackMode;
import java.util.*;

/** Immutable wire data: IDs and controls only, never provider resources or credentials. */
public record RoomSnapshot(UUID id, UUID epoch, String name, long revision, long queueRevision,
        long playbackRevision, UUID owner, List<Member> members, List<String> songs, int index,
        PlaybackMode mode, State state, long positionMs, long anchorMs, long durationMs, UUID teamId) {
    public enum State { STOPPED, PLAYING, PAUSED }
    /** Administrator is a live server assertion, never a saved grant or a client command. */
    public record Member(UUID id, String name, boolean dj, boolean requester, boolean administrator) {
        public Member(UUID id, String name, boolean dj, boolean requester) { this(id, name, dj, requester, false); }
        public Member(UUID id, String name, boolean dj) { this(id, name, dj, false); }
    }
    public RoomSnapshot(UUID id, UUID epoch, String name, long revision, long queueRevision,
            long playbackRevision, UUID owner, List<Member> members, List<String> songs, int index,
            PlaybackMode mode, State state, long positionMs, long anchorMs, long durationMs) {
        this(id, epoch, name, revision, queueRevision, playbackRevision, owner, members, songs, index,
                mode, state, positionMs, anchorMs, durationMs, null);
    }
    public RoomSnapshot {
        Objects.requireNonNull(id); Objects.requireNonNull(epoch); Objects.requireNonNull(owner);
        Objects.requireNonNull(mode); Objects.requireNonNull(state);
        members = List.copyOf(members); songs = List.copyOf(songs);
        if (name == null || name.length() > 40
                || (!RoomService.PUBLIC_ROOM_ID.equals(id) && members.size() > RoomService.MAX_MEMBERS) || songs.size() > 400
                || songs.stream().anyMatch(n -> n == null || n.length() > 64)
                || index < -1 || index >= songs.size() || positionMs < 0 || durationMs < 0
                || revision < 0 || queueRevision < 0 || playbackRevision < 0)
            throw new IllegalArgumentException("Invalid room snapshot");
        songs.forEach(dev.modernmusic.domain.SongId::stored);
    }
    public long positionAt(long serverNow) {
        long position = positionMs + (state == State.PLAYING ? Math.max(0, serverNow - anchorMs) : 0);
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
    public boolean canControl(UUID player) {
        return canManage(player) || members.stream().anyMatch(m -> m.id().equals(player) && m.dj());
    }
    public boolean canRequest(UUID player) {
        return canManage(player) || members.stream().anyMatch(m -> m.id().equals(player) && (m.dj() || m.requester()));
    }
    public boolean canManage(UUID player) {
        return members.stream().anyMatch(m -> m.id().equals(player) && (isPublicRoom() ? m.administrator() : owner.equals(player)));
    }
    public boolean allows(UUID player, RoomCommand.Action action) {
        return switch (action) {
            case ADD -> canRequest(player);
            case INVITE, DISSOLVE -> !isTeamRoom() && !isPublicRoom() && canManage(player);
            case KICK -> !isTeamRoom() && canManage(player);
            case DJ, REQUESTER -> canManage(player);
            case LEAVE, SNAPSHOT, DURATION -> members.stream().anyMatch(m -> m.id().equals(player));
            default -> canControl(player);
        };
    }
    public boolean isTeamRoom() { return teamId != null; }
    public boolean isPublicRoom() { return RoomService.PUBLIC_ROOM_ID.equals(id); }
}
