package dev.modernmusic.room;

import dev.modernmusic.domain.*;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/** Server-thread-owned room authority, with a controllable monotonic clock for tests. */
public final class RoomService {
    public static final int MAX_ROOMS = 64, MAX_MEMBERS = 16, DEFAULT_ROOM_LIMIT = 2;
    /** Stable server-owned identity, independent of any player's membership or creation quota. */
    public static final UUID PUBLIC_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID SERVER_OWNER = new UUID(0, 0);
    private static final long INVITE_TTL = 60_000, START_DELAY = 2_000, RESUME_DELAY = 200;
    private final UUID epoch = UUID.randomUUID();
    private final TeamDirectory teams;
    private final IntSupplier roomLimit;
    private final Predicate<UUID> administrators;
    private final Map<UUID, TeamQueue> teamQueues = new LinkedHashMap<>();
    private long teamQueueRevision;
    private final Map<UUID, PersistentRoom> persistentRooms = new LinkedHashMap<>();
    private long persistenceRevision;
    /** Saved membership grants and playlist; online membership and playback clocks are never restored. */
    public record PersistentRoom(UUID id, String name, UUID owner, List<RoomSnapshot.Member> members,
                                 List<SongId> songs, int selected, PlaybackMode mode) {
        public PersistentRoom {
            Objects.requireNonNull(id); Objects.requireNonNull(owner);
            if (name == null || name.isBlank() || name.length() > 40) throw new IllegalArgumentException("room_name");
            members = List.copyOf(members); songs = List.copyOf(songs);
            if (songs.stream().anyMatch(SongId::isClientLocal)) throw new IllegalArgumentException("local_audio");
            var ids = new HashSet<UUID>();
            for (var member : members) {
                if (member.id() == null || !ids.add(member.id()) || member.name() == null || member.name().isBlank()
                        || member.name().length() > 16 || member.dj() && member.requester() || member.administrator())
                    throw new IllegalArgumentException("room_member");
            }
            if (PUBLIC_ROOM_ID.equals(id) ? !SERVER_OWNER.equals(owner) : !ids.contains(owner))
                throw new IllegalArgumentException("room_owner");
            new PlaybackQueue().restore(songs, selected, mode);
        }
    }
    public List<PersistentRoom> persistentRooms() { return List.copyOf(persistentRooms.values()); }
    public long persistenceRevision() { return persistenceRevision; }
    public void restoreRooms(List<PersistentRoom> saved) {
        if (!memberships.isEmpty() || rooms.values().stream().anyMatch(room -> room.teamId == null))
            throw new IllegalStateException("Rooms already active");
        if (saved.stream().filter(value -> !PUBLIC_ROOM_ID.equals(value.id())).count() + privateRoomCount() > MAX_ROOMS)
            throw new IllegalArgumentException("room_limit");
        var restored = new LinkedHashMap<UUID, Room>();
        for (var value : saved) {
            Objects.requireNonNull(value);
            if (rooms.containsKey(value.id()) || restored.putIfAbsent(value.id(), new Room(value)) != null)
                throw new IllegalArgumentException("duplicate_room");
        }
        rooms.putAll(restored);
        persistentRooms.clear();
        for (var value : saved) persistentRooms.put(value.id(), value);
    }
    /** Persistent playlist only; room membership and the playback clock are session-local. */
    public record TeamQueue(UUID team, List<SongId> songs, int selected, PlaybackMode mode) {
        public TeamQueue {
            Objects.requireNonNull(team); songs = List.copyOf(songs);
            if (songs.stream().anyMatch(SongId::isClientLocal)) throw new IllegalArgumentException("local_audio");
            new PlaybackQueue().restore(songs, selected, mode);
        }
    }
    public List<TeamQueue> teamQueues() { return List.copyOf(teamQueues.values()); }
    public long teamQueueRevision() { return teamQueueRevision; }
    public void restoreTeamQueues(List<TeamQueue> saved) {
        if (!memberships.isEmpty() || rooms.values().stream().anyMatch(room -> room.teamId != null))
            throw new IllegalStateException("Rooms already active");
        teamQueues.clear();
        for (var queue : saved) teamQueues.put(queue.team(), queue);
    }
    private void remember(Room room) {
        if (room.teamId == null) {
            var saved = new PersistentRoom(room.id, room.name, room.owner, new ArrayList<>(room.grants.values()),
                    room.queue.entries(), room.queue.index(), room.queue.mode());
            if (!saved.equals(persistentRooms.put(room.id, saved))) persistenceRevision++;
            return;
        }
        var saved = new TeamQueue(room.teamId, room.queue.entries(), room.queue.index(), room.queue.mode());
        if (!saved.equals(teamQueues.put(room.teamId, saved))) teamQueueRevision++;
    }
    private final Map<UUID, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberships = new HashMap<>();
    private final Map<UUID, Rate> rates = new HashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, RoomImportResult>> imports = new HashMap<>();
    private record Rate(long window, int count) {}
    private static final class Room {
        final UUID id;
        String name;
        final UUID teamId;
        UUID owner;
        final LinkedHashMap<UUID, RoomSnapshot.Member> members = new LinkedHashMap<>();
        final LinkedHashMap<UUID, RoomSnapshot.Member> grants = new LinkedHashMap<>();
        final Map<UUID, Long> invites = new HashMap<>();
        final PlaybackQueue queue = new PlaybackQueue();
        long revision, queueRevision, playbackRevision, position, anchor, duration;
        RoomSnapshot cachedSnapshot;
        RoomSnapshot.State state = RoomSnapshot.State.STOPPED;
        Room(UUID owner, String name, String playerName) {
            id = UUID.randomUUID(); this.owner = owner; this.name = name;
            teamId = null;
            var creator = new RoomSnapshot.Member(owner, playerName, true);
            members.put(owner, creator); grants.put(owner, creator);
        }
        Room(PersistentRoom saved) {
            id = saved.id(); name = saved.name(); owner = saved.owner(); teamId = null;
            for (var member : saved.members()) grants.put(member.id(), member);
            queue.restore(saved.songs(), saved.selected(), saved.mode());
        }
        Room(TeamDirectory.Party party) {
            id = UUID.randomUUID(); teamId = party.id(); owner = party.owner(); name = party.name();
        }
    }
    public RoomService() { this(TeamDirectory.ABSENT); }
    public RoomService(TeamDirectory teams) { this(teams, () -> DEFAULT_ROOM_LIMIT); }
    public RoomService(IntSupplier roomLimit) { this(TeamDirectory.ABSENT, roomLimit); }
    public RoomService(TeamDirectory teams, IntSupplier roomLimit) {
        this(teams, roomLimit, player -> false);
    }
    public RoomService(TeamDirectory teams, IntSupplier roomLimit, Predicate<UUID> administrators) {
        this.teams = Objects.requireNonNull(teams); this.roomLimit = Objects.requireNonNull(roomLimit);
        this.administrators = Objects.requireNonNull(administrators);
    }
    /** Called once after loading saved rooms at server startup; safe if already restored. */
    public void openPublicRoom() {
        if (rooms.containsKey(PUBLIC_ROOM_ID)) return;
        Room room = new Room(new PersistentRoom(PUBLIC_ROOM_ID, "Server Public Room", SERVER_OWNER,
                List.of(), List.of(), -1, PlaybackMode.SEQUENTIAL));
        rooms.put(room.id, room); remember(room);
    }
    private int privateRoomCount() { return rooms.size() - (rooms.containsKey(PUBLIC_ROOM_ID) ? 1 : 0); }
    private boolean reconcileAuthority() {
        boolean changed = reconcileTeams();
        Room room = rooms.get(PUBLIC_ROOM_ID);
        if (room == null) return changed;
        boolean permissionsChanged = false;
        for (var entry : room.members.entrySet()) {
            var member = entry.getValue(); boolean administrator = administrators.test(member.id());
            if (member.administrator() == administrator) continue;
            entry.setValue(new RoomSnapshot.Member(member.id(), member.name(), member.dj(), member.requester(), administrator));
            permissionsChanged = true;
        }
        if (permissionsChanged) room.revision++;
        return changed || permissionsChanged;
    }
    private int roomLimit() { return Math.clamp(roomLimit.getAsInt(), 0, MAX_ROOMS); }
    public static final class Rejected extends RuntimeException {
        public final String key;
        public Rejected(String reason) { super(reason); key = "modernmusic.room.error." + reason; }
    }
    private static void require(boolean value, String reason) { if (!value) throw new Rejected(reason); }
    public TeamStatus teamStatus(UUID player) {
        return switch (teams.state()) {
            case NOT_INSTALLED -> TeamStatus.ABSENT;
            case UNAVAILABLE -> new TeamStatus(TeamStatus.State.UNAVAILABLE, "");
            case READY -> teams.party(player).map(p -> new TeamStatus(TeamStatus.State.READY, p.name()))
                    .orElseGet(() -> new TeamStatus(TeamStatus.State.NO_PARTY, ""));
        };
    }
    /** Recheck before reads/writes as well as every tick: cached membership never grants access. */
    public boolean reconcileTeams() {
        boolean changed = false;
        for (Room room : List.copyOf(rooms.values())) {
            if (room.teamId == null) continue;
            boolean roomChanged = false;
            for (var member : List.copyOf(room.members.values())) {
                var party = teams.state() == TeamDirectory.State.READY ? teams.party(member.id()).orElse(null) : null;
                if (party == null || !room.teamId.equals(party.id())) {
                    leave(member.id()); roomChanged = true;
                } else {
                    if (!room.owner.equals(party.owner()) || !room.name.equals(party.name())) {
                        room.owner = party.owner(); room.name = party.name(); roomChanged = true;
                    }
                }
            }
            if (roomChanged) { room.revision++; changed = true; }
        }
        return changed;
    }
    public RoomSnapshot current(UUID player) {
        reconcileAuthority();
        Room room = rooms.get(memberships.get(player));
        return room == null ? null : snapshot(room);
    }
    public List<RoomSnapshot> snapshots() { reconcileAuthority(); return rooms.values().stream().map(this::snapshot).toList(); }
    /** One authority check and one invitation scan per delivery batch, regardless of recipient count. */
    public Map<UUID, RoomUpdate> updatesFor(Collection<UUID> players, long now) {
        reconcileAuthority();
        var invitations = new HashMap<UUID, List<RoomUpdate.Invitation>>();
        for (Room room : rooms.values()) {
            room.invites.values().removeIf(expiry -> expiry <= now);
            if (room.invites.isEmpty()) continue;
            var invitation = new RoomUpdate.Invitation(room.id, epoch, room.name);
            for (UUID player : room.invites.keySet())
                invitations.computeIfAbsent(player, id -> new ArrayList<>()).add(invitation);
        }
        var updates = new LinkedHashMap<UUID, RoomUpdate>();
        int limit = roomLimit();
        for (UUID player : players) {
            Room room = rooms.get(memberships.get(player));
            var saved = rooms.values().stream().filter(value -> value.teamId == null
                            && (PUBLIC_ROOM_ID.equals(value.id) || value.owner.equals(player) || value.grants.containsKey(player)))
                    .sorted(Comparator.comparing((Room value) -> !PUBLIC_ROOM_ID.equals(value.id))
                            .thenComparing(value -> !value.owner.equals(player)))
                    .map(value -> new RoomUpdate.SavedRoom(value.id, epoch, value.name, value.owner,
                            value.members.size(), value.queue.entries().size())).toList();
            updates.put(player, new RoomUpdate(0, room == null ? null : snapshot(room),
                    invitations.getOrDefault(player, List.of()), "", teamStatus(player), saved, limit));
        }
        return updates;
    }
    public Set<UUID> invited(UUID roomId, long now) {
        Room room = rooms.get(roomId);
        if (room == null) return Set.of();
        room.invites.values().removeIf(expiry -> expiry <= now);
        return Set.copyOf(room.invites.keySet());
    }
    public void execute(UUID sender, String senderName, RoomCommand command, UUID target, long now) {
        rate(sender, now);
        reconcileAuthority();
        executeCommand(sender, senderName, command, target, now);
    }
    private void rate(UUID sender, long now) {
        Rate rate = rates.get(sender);
        if (rate == null || now - rate.window >= 1_000) rate = new Rate(now, 0);
        require(rate.count < 12, "rate"); rates.put(sender, new Rate(rate.window, rate.count + 1));
    }
    /** One atomic append and one queue revision, regardless of playlist length. */
    public RoomImportResult append(UUID sender, RoomImportRequest request, long now) {
        rate(sender, now);
        reconcileAuthority();
        Room room = rooms.get(request.room()); require(room != null, "missing");
        require(epoch.equals(request.epoch()), "stale");
        require(room.id.equals(memberships.get(sender)), "member");
        require(snapshot(room).canRequest(sender), "request_permission");
        var receipts = imports.computeIfAbsent(sender, id -> new LinkedHashMap<>());
        var previous = receipts.get(request.request());
        if (previous != null) return previous;
        var songs = request.songs().stream().map(SongId::fromWire).toList();
        require(songs.stream().noneMatch(SongId::isClientLocal), "local_audio");
        var appended = room.queue.append(songs);
        int count = appended.added();
        if (count > 0) { room.queueRevision++; room.revision++; remember(room); }
        var result = new RoomImportResult(request.request(), count, request.total(), count == 0
                ? appended.processed() == request.songs().size() ? "modernmusic.status.duplicate" : "modernmusic.status.queue_full" : "",
                appended.processed());
        if (receipts.size() == 32) receipts.remove(receipts.keySet().iterator().next());
        receipts.put(request.request(), result); return result;
    }
    private void executeCommand(UUID sender, String senderName, RoomCommand command, UUID target, long now) {
        if (command.action() == RoomCommand.Action.JOIN_TEAM) {
            require(teams.state() != TeamDirectory.State.NOT_INSTALLED, "team_not_installed");
            require(teams.state() == TeamDirectory.State.READY, "team_unavailable");
            var party = teams.party(sender).orElseThrow(() -> new Rejected("team_required"));
            require(!memberships.containsKey(sender), "already_joined");
            Room room = rooms.values().stream().filter(r -> party.id().equals(r.teamId)).findFirst().orElse(null);
            if (room == null) {
                require(privateRoomCount() < MAX_ROOMS, "full");
                room = new Room(party); rooms.put(room.id, room);
                var saved = teamQueues.get(party.id());
                if (saved != null) room.queue.restore(saved.songs(), saved.selected(), saved.mode());
            }
            require(room.members.size() < MAX_MEMBERS, "full");
            room.members.put(sender, new RoomSnapshot.Member(sender, senderName, false));
            memberships.put(sender, room.id); room.revision++; return;
        }
        if (command.action() == RoomCommand.Action.CREATE) {
            require(!memberships.containsKey(sender), "already_joined"); require(privateRoomCount() < MAX_ROOMS, "full");
            long owned = rooms.values().stream().filter(room -> room.teamId == null && !PUBLIC_ROOM_ID.equals(room.id) && room.owner.equals(sender)).count();
            require(owned < roomLimit(), "owned_limit");
            String name = command.text().strip(); require(!name.isEmpty() && name.length() <= 40, "input");
            Room room = new Room(sender, name, senderName); rooms.put(room.id, room); memberships.put(sender, room.id); remember(room); return;
        }
        Room room = rooms.get(command.room()); require(room != null, "missing");
        require(epoch.equals(command.epoch()), "stale");
        if (command.action() == RoomCommand.Action.DISSOLVE) {
            require(!PUBLIC_ROOM_ID.equals(room.id), "public_managed");
            require(room.teamId == null, "team_managed");
            require(room.owner.equals(sender), "owner_permission");
            // Lobby entries have no live revision, but an in-room confirmation still guards stale controls.
            require(command.revision() == -1 || command.revision() == room.revision, "stale");
            for (UUID member : room.members.keySet()) memberships.remove(member, room.id);
            rooms.remove(room.id);
            if (persistentRooms.remove(room.id) != null) persistenceRevision++;
            return;
        }
        if (command.action() == RoomCommand.Action.JOIN) {
            require(room.teamId == null, "team_managed");
            require(!memberships.containsKey(sender), "already_joined");
            boolean publicRoom = PUBLIC_ROOM_ID.equals(room.id);
            require(publicRoom || room.owner.equals(sender) || room.grants.containsKey(sender) || room.invites.getOrDefault(sender, 0L) > now, "invite");
            require(publicRoom || room.members.size() < MAX_MEMBERS, "full");
            var grant = room.grants.get(sender);
            var member = new RoomSnapshot.Member(sender, senderName, grant != null && grant.dj(), grant != null && grant.requester());
            room.invites.remove(sender); room.grants.put(sender, member);
            room.members.put(sender, new RoomSnapshot.Member(sender, senderName, member.dj(), member.requester(),
                    publicRoom && administrators.test(sender)));
            memberships.put(sender, room.id); room.revision++; remember(room); return;
        }
        require(room.id.equals(memberships.get(sender)), "member");
        if (command.action() == RoomCommand.Action.SNAPSHOT) return;
        if (command.action() == RoomCommand.Action.LEAVE) { leave(sender); return; }
        if (PUBLIC_ROOM_ID.equals(room.id)) require(command.action() != RoomCommand.Action.INVITE, "public_managed");
        if (room.teamId != null) require(command.action() != RoomCommand.Action.INVITE
                && command.action() != RoomCommand.Action.KICK, "team_managed");
        boolean additive = command.action() == RoomCommand.Action.ADD || command.action() == RoomCommand.Action.INVITE;
        require(snapshot(room).allows(sender, command.action()), command.action().permissionError());
        if (!additive) require(command.revision() == (command.action() == RoomCommand.Action.DURATION
                ? room.playbackRevision : room.revision), "stale");
        switch (command.action()) {
            case INVITE -> {
                require(target != null && !room.members.containsKey(target), "target");
                require(room.invites.size() < 32 || room.invites.containsKey(target), "full");
                room.invites.put(target, now + INVITE_TTL);
            }
            case DJ, REQUESTER, KICK -> {
                require(target != null && !target.equals(sender) && room.members.containsKey(target), "target");
                require(!snapshot(room).canManage(target), "target");
                if (command.action() == RoomCommand.Action.KICK) {
                    room.grants.remove(target); room.invites.remove(target); leave(target);
                }
                else {
                    require(command.value() == 0 || command.value() == 1, "input");
                    var member = room.members.get(target);
                    var grant = new RoomSnapshot.Member(target, member.name(),
                            command.action() == RoomCommand.Action.DJ && command.value() == 1,
                            command.action() == RoomCommand.Action.REQUESTER && command.value() == 1);
                    room.members.put(target, grant);
                    if (room.teamId == null) room.grants.put(target, grant);
                }
            }
            case ADD -> {
                require(command.value() != 0 && command.value() != Long.MIN_VALUE, "input");
                SongId song = SongId.fromWire(command.value()); require(!room.queue.contains(song), "duplicate");
                require(room.queue.add(song), "full"); room.queueRevision++;
            }
            case REMOVE -> {
                require(command.value() >= 0 && command.value() < room.queue.entries().size(), "input");
                boolean current = room.queue.index() == command.value(); room.queue.remove((int) command.value()); room.queueRevision++;
                if (current) reset(room, now, room.state == RoomSnapshot.State.PLAYING && !room.queue.entries().isEmpty());
            }
            case CLEAR -> { room.queue.clear(); room.queueRevision++; reset(room, now, false); }
            case PLAY -> {
                require(command.value() >= 0 && command.value() < room.queue.entries().size(), "input");
                room.queue.select((int) command.value()); reset(room, now, true);
            }
            case PAUSE -> {
                require(room.queue.current().isPresent() && room.state != RoomSnapshot.State.STOPPED, "input");
                room.position = position(room, now); room.state = room.state == RoomSnapshot.State.PLAYING ? RoomSnapshot.State.PAUSED : RoomSnapshot.State.PLAYING;
                // Resuming uses buffered audio; only leave a short window for delivering the new timeline.
                room.anchor = now + (room.state == RoomSnapshot.State.PLAYING ? RESUME_DELAY : 0);
            }
            case STOP -> reset(room, now, false);
            case SEEK -> {
                require(room.state != RoomSnapshot.State.STOPPED && command.value() >= 0
                        && command.value() <= dev.modernmusic.audio.AudioFiles.MAX_DURATION_MS, "input");
                room.position = room.duration > 0 ? Math.min(command.value(), room.duration) : command.value();
                room.anchor = now + (room.state == RoomSnapshot.State.PLAYING ? START_DELAY : 0);
            }
            case NEXT -> advance(room, now, false);
            case MODE -> { room.queue.mode(room.queue.mode().next()); room.queueRevision++; }
            case DURATION -> {
                require(room.state != RoomSnapshot.State.STOPPED && command.value() > 0
                        && command.value() <= dev.modernmusic.audio.AudioFiles.MAX_DURATION_MS, "input");
                require(room.queue.current().map(song -> song.toString().equals(command.text())).orElse(false), "stale");
                // Any listening member can supply missing timing after the last DJ leaves or mutes.
                // Reports are pinned to this playback; later reports cannot change an established clock.
                if (room.duration > 0) return;
                room.duration = command.value();
            }
            default -> throw new Rejected("input");
        }
        room.revision++;
        remember(room);
    }
    public boolean tick(long now) {
        boolean changed = reconcileAuthority();
        for (Room room : rooms.values()) {
            changed |= room.invites.values().removeIf(expiry -> expiry <= now);
            if (room.state == RoomSnapshot.State.PLAYING && room.duration > 0 && position(room, now) >= room.duration) {
                advance(room, now, true); room.revision++; remember(room); changed = true;
            }
        }
        return changed;
    }
    public void disconnect(UUID player) { leave(player); rates.remove(player); imports.remove(player); rooms.values().forEach(r -> r.invites.remove(player)); }
    public void leave(UUID player) {
        UUID id = memberships.remove(player); Room room = rooms.get(id); if (room == null) return;
        room.members.remove(player);
        if (room.members.isEmpty()) {
            if (room.teamId != null) { rooms.remove(id); return; }
            reset(room, 0, false);
        }
        room.revision++;
    }
    private void advance(Room room, long now, boolean automatic) {
        int next = room.queue.following(automatic);
        if (next >= 0) room.queue.select(next);
        reset(room, now, next >= 0);
    }
    private void reset(Room room, long now, boolean playing) {
        room.position = 0; room.duration = 0; room.playbackRevision++;
        room.state = playing ? RoomSnapshot.State.PLAYING : RoomSnapshot.State.STOPPED;
        room.anchor = now + (playing ? START_DELAY : 0);
    }
    private long position(Room room, long now) {
        long position = room.position + (room.state == RoomSnapshot.State.PLAYING ? Math.max(0, now - room.anchor) : 0);
        return room.duration > 0 ? Math.min(position, room.duration) : position;
    }
    private RoomSnapshot snapshot(Room room) {
        if (room.cachedSnapshot != null && room.cachedSnapshot.revision() == room.revision) return room.cachedSnapshot;
        return room.cachedSnapshot = new RoomSnapshot(room.id, epoch, room.name, room.revision, room.queueRevision, room.playbackRevision,
                room.owner, new ArrayList<>(room.members.values()), room.queue.entries().stream().map(SongId::toString).toList(),
                room.queue.index(), room.queue.mode(), room.state, room.position, room.anchor, room.duration, room.teamId);
    }
}
