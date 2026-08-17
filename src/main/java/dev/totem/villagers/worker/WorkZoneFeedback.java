package dev.totem.villagers.worker;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side explanation of a specialist's Work Zone state.  It is deliberately
 * based only on persisted assignments, persisted zones, and the villager's
 * current loaded position; callers never need to probe or load another chunk.
 */
public record WorkZoneFeedback(
        String roleId,
        State state,
        Optional<UUID> zoneId,
        Optional<WorkZone> zone
) {
    public enum State {
        UNASSIGNED("unassigned"),
        ASSIGNMENT_MISMATCH("assignment_mismatch"),
        MISSING("missing"),
        ZONE_ROLE_MISMATCH("zone_role_mismatch"),
        OTHER_DIMENSION("other_dimension"),
        INSIDE("inside"),
        OUTSIDE("outside");

        private final String id;

        State(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public WorkZoneFeedback {
        WorkerProfessionRegistry.require(roleId);
        Objects.requireNonNull(state, "state");
        zoneId = zoneId == null ? Optional.empty() : zoneId;
        zone = zone == null ? Optional.empty() : zone;
        if (zone.isPresent() && zoneId.isEmpty()) {
            throw new IllegalArgumentException("A Work Zone boundary requires a zone id");
        }
    }

    /**
     * Returns no feedback for roles that never use a generic Work Zone.  A stale
     * zone-role assignment remains visible so an administrator can repair it.
     */
    public static Optional<WorkZoneFeedback> evaluate(
            UUID villagerId,
            String currentRoleId,
            String currentDimensionId,
            BlockCoordinate currentPosition,
            Optional<WorkerAssignment> assignment,
            Map<UUID, WorkZoneRecord> zones
    ) {
        Objects.requireNonNull(villagerId, "villagerId");
        Objects.requireNonNull(currentRoleId, "currentRoleId");
        Objects.requireNonNull(currentDimensionId, "currentDimensionId");
        Objects.requireNonNull(currentPosition, "currentPosition");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(zones, "zones");

        Optional<WorkerAssignment> matchingVillager = assignment.filter(value -> value.villagerId().equals(villagerId));
        boolean currentRoleUsesZone = usesGenericWorkZone(currentRoleId);
        boolean savedRoleUsesZone = matchingVillager.map(WorkerAssignment::roleId)
                .map(WorkZoneFeedback::usesGenericWorkZone).orElse(false);
        if (!currentRoleUsesZone && !savedRoleUsesZone) {
            return Optional.empty();
        }

        String displayedRole = currentRoleUsesZone ? currentRoleId
                : matchingVillager.orElseThrow().roleId();
        if (matchingVillager.isEmpty()) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.UNASSIGNED, Optional.empty(), Optional.empty()));
        }
        WorkerAssignment saved = matchingVillager.orElseThrow();
        if (!saved.roleId().equals(currentRoleId)) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.ASSIGNMENT_MISMATCH,
                    saved.workZoneId(), saved.workZoneId().map(zones::get).filter(Objects::nonNull).map(WorkZoneRecord::zone)));
        }
        if (saved.workZoneId().isEmpty()) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.UNASSIGNED, Optional.empty(), Optional.empty()));
        }
        UUID assignedZoneId = saved.workZoneId().orElseThrow();
        WorkZoneRecord record = zones.get(assignedZoneId);
        if (record == null) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.MISSING, Optional.of(assignedZoneId), Optional.empty()));
        }
        WorkZone workZone = record.zone();
        if (!record.roleId().equals(saved.roleId())) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.ZONE_ROLE_MISMATCH,
                    Optional.of(assignedZoneId), Optional.of(workZone)));
        }
        if (!workZone.dimensionId().equals(currentDimensionId)) {
            return Optional.of(new WorkZoneFeedback(displayedRole, State.OTHER_DIMENSION,
                    Optional.of(assignedZoneId), Optional.of(workZone)));
        }
        State state = workZone.contains(currentDimensionId, currentPosition) ? State.INSIDE : State.OUTSIDE;
        return Optional.of(new WorkZoneFeedback(displayedRole, state, Optional.of(assignedZoneId), Optional.of(workZone)));
    }

    private static boolean usesGenericWorkZone(String roleId) {
        return WorkerProfessionRegistry.MINER.id().equals(roleId)
                || WorkerProfessionRegistry.LUMBERJACK.id().equals(roleId)
                || WorkerProfessionRegistry.BUILDER.id().equals(roleId);
    }
}
