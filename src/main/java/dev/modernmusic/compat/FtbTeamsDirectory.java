package dev.modernmusic.compat;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.modernmusic.room.TeamDirectory;
import java.util.Optional;
import java.util.UUID;

/** Loaded only after ModList confirms FTB Teams. No client manager or implementation classes. */
public final class FtbTeamsDirectory implements TeamDirectory {
    @Override public State state() {
        var api = FTBTeamsAPI.api();
        return api != null && api.isManagerLoaded() ? State.READY : State.UNAVAILABLE;
    }

    @Override public Optional<Party> party(UUID player) {
        if (state() != State.READY) return Optional.empty();
        return FTBTeamsAPI.api().getManager().getTeamForPlayerID(player)
                .filter(team -> team.isPartyTeam())
                .flatMap(team -> {
                    var rank = team.getRankForPlayer(player);
                    return rank.isMemberOrBetter()
                            ? Optional.of(new Party(team.getId(), team.getName().getString(), team.getOwner()))
                            : Optional.empty();
                });
    }
}
