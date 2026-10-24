package dev.modernmusic.server;

import dev.modernmusic.provider.MusicError;
import dev.modernmusic.provider.MusicException;
import java.util.*;

/** Server-thread admission. Queued downloads hold no file bytes and enter active slots in arrival order. */
public final class AudioTransferQueue {
    public static final int MAX_ACTIVE = 4, MAX_UPLOADS = 2, MAX_WAITING = 64;
    public static final int CHUNKS_PER_TICK = 4;
    private static final class Ticket {
        final UUID owner;
        final boolean upload;
        boolean active;
        Ticket(UUID owner, boolean upload, boolean active) { this.owner = owner; this.upload = upload; this.active = active; }
    }
    private final Map<UUID, Ticket> tickets = new LinkedHashMap<>();
    private int active;

    /** Repeated requests retain their original slot or position; the caller also verifies the song identity. */
    public boolean download(UUID owner, UUID request) {
        Ticket previous = tickets.get(request);
        if (previous != null) {
            if (!previous.owner.equals(owner) || previous.upload) throw new MusicException(MusicError.INVALID_RESPONSE);
            return previous.active;
        }
        checkPlayer(owner);
        boolean start = active < MAX_ACTIVE && waitingCount() == 0;
        if (!start && waitingCount() >= MAX_WAITING) throw new MusicException(MusicError.RATE_LIMITED);
        tickets.put(request, new Ticket(owner, false, start));
        if (start) active++;
        return start;
    }

    /** Uploads need immediate storage and cannot jump ahead of waiting downloads. */
    public void upload(UUID owner, UUID request) {
        if (tickets.containsKey(request)) throw new MusicException(MusicError.INVALID_RESPONSE);
        checkPlayer(owner);
        if (active >= MAX_ACTIVE || waitingCount() != 0
                || tickets.values().stream().filter(ticket -> ticket.upload).count() >= MAX_UPLOADS
                || tickets.values().stream().anyMatch(ticket -> ticket.upload && ticket.owner.equals(owner)))
            throw new MusicException(MusicError.RATE_LIMITED);
        tickets.put(request, new Ticket(owner, true, true)); active++;
    }

    private void checkPlayer(UUID owner) {
        if (tickets.values().stream().filter(ticket -> ticket.owner.equals(owner)).count() >= 2)
            throw new MusicException(MusicError.RATE_LIMITED);
    }
    public boolean waiting(UUID request) { Ticket ticket = tickets.get(request); return ticket != null && !ticket.active; }
    private int waitingCount() { return tickets.size() - active; }

    public List<UUID> startWaiting() {
        var started = new ArrayList<UUID>();
        for (var entry : tickets.entrySet()) {
            if (active == MAX_ACTIVE) break;
            Ticket ticket = entry.getValue();
            if (!ticket.active) { ticket.active = true; active++; started.add(entry.getKey()); }
        }
        return started;
    }
    public void remove(UUID request) {
        Ticket ticket = tickets.remove(request);
        if (ticket != null && ticket.active) active--;
    }
    public void clear() { tickets.clear(); active = 0; }
}
