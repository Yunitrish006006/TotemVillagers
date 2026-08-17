package dev.totem.villagers.guard;

import java.util.Optional;
import java.util.UUID;

/** Determines one-at-a-time defence construction demand without claiming player-built golems. */
public final class GuardDefencePlanner {
    public Optional<GuardConstructionState> beginIfNeeded(ManagedVillageState village, int defenceDemand, GuardDefenceOrder order) {
        if (defenceDemand < 0) {
            throw new IllegalArgumentException("defenceDemand cannot be negative");
        }
        if (village.construction().isPresent() || village.managedGolemIds().size() >= defenceDemand) {
            return Optional.empty();
        }
        return Optional.of(new GuardConstructionState(UUID.randomUUID(), order.id(), order.requiredInputs(), 0));
    }

    public ManagedVillageState cancel(ManagedVillageState village) {
        return village.withConstruction(Optional.empty());
    }

    public ManagedVillageState recordManagedGolem(ManagedVillageState village, UUID golemId) {
        java.util.Set<UUID> next = new java.util.LinkedHashSet<>(village.managedGolemIds());
        next.add(golemId);
        return new ManagedVillageState(village.post(), village.guardVillagerId(), next, Optional.empty());
    }
}
