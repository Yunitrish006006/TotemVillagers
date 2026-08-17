package dev.totem.villagers.guard;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardDefencePlannerTest {
    @Test
    void defaultOrderUsesVanillaIronGolemMaterialsAndOnlyOneReservation() {
        ManagedVillageState village = new ManagedVillageState(
                new GuardPost(UUID.fromString("00000000-0000-0000-0000-000000000501"),
                        UUID.fromString("00000000-0000-0000-0000-000000000502"),
                        "minecraft:overworld", 9L),
                UUID.fromString("00000000-0000-0000-0000-000000000503"), Set.of(), Optional.empty());
        GuardDefencePlanner planner = new GuardDefencePlanner();

        GuardConstructionState first = planner.beginIfNeeded(village, 1, GuardDefenceOrder.VANILLA_IRON_GOLEM).orElseThrow();
        ManagedVillageState reserved = village.withConstruction(Optional.of(first));

        assertTrue(GuardDefenceOrder.VANILLA_IRON_GOLEM.matchesVanillaIronGolemMaterials());
        assertEquals(5, GuardDefenceOrder.VANILLA_IRON_GOLEM.placements().size());
        assertFalse(planner.beginIfNeeded(reserved, 2, GuardDefenceOrder.VANILLA_IRON_GOLEM).isPresent());
    }
}
