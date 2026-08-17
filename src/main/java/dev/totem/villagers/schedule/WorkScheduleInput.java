package dev.totem.villagers.schedule;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-observed safety state; no client input can mark an unsafe work task valid. */
public record WorkScheduleInput(
        UUID villagerId,
        String professionId,
        long gameTick,
        boolean alive,
        boolean chunkLoaded,
        boolean inDanger,
        boolean sleeping,
        boolean raidActive,
        boolean jobSiteValid,
        boolean atWorkLocation,
        List<WorkCandidate> candidates
) {
    public WorkScheduleInput {
        Objects.requireNonNull(villagerId, "villagerId");
        if (professionId == null || !professionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("professionId must be a namespaced identifier");
        }
        if (gameTick < 0) {
            throw new IllegalArgumentException("gameTick cannot be negative");
        }
        candidates = List.copyOf(candidates);
    }
}
