package dev.totem.villagers.world;

import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure permission boundary for autonomous work. A missing, mismatched, foreign
 * dimension, or out-of-bounds Zone is a denial; callers must never fall back to
 * scanning arbitrary world blocks.
 */
public final class WorldWorkZoneEligibility {
    public Optional<WorkZoneRecord> assignedZone(
            UUID villagerId,
            String professionId,
            String dimensionId,
            Map<UUID, WorkZoneRecord> zones,
            Optional<WorkerAssignment> assignment
    ) {
        Objects.requireNonNull(villagerId, "villagerId");
        Objects.requireNonNull(professionId, "professionId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(assignment, "assignment");
        return assignment
                .filter(value -> value.villagerId().equals(villagerId))
                .filter(value -> value.roleId().equals(professionId))
                .flatMap(WorkerAssignment::workZoneId)
                .map(zones::get)
                .filter(Objects::nonNull)
                .filter(zone -> zone.roleId().equals(professionId))
                .filter(zone -> zone.zone().dimensionId().equals(dimensionId));
    }

    public boolean permits(
            UUID villagerId,
            String professionId,
            String dimensionId,
            BlockCoordinate candidate,
            Map<UUID, WorkZoneRecord> zones,
            Optional<WorkerAssignment> assignment
    ) {
        Objects.requireNonNull(candidate, "candidate");
        return assignedZone(villagerId, professionId, dimensionId, zones, assignment)
                .map(zone -> zone.zone().contains(dimensionId, candidate))
                .orElse(false);
    }
}
