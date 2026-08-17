package dev.totem.villagers.world;

import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldWorkZoneEligibilityTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000001001");
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000001002");
    private static final UUID ZONE = UUID.fromString("00000000-0000-0000-0000-000000001003");

    @Test
    void requiresMatchingAssignedRoleDimensionAndBounds() {
        WorkZoneRecord mine = new WorkZoneRecord(ZONE, "totem:miner", new WorkZone(OWNER, "minecraft:overworld",
                new BlockCoordinate(0, 40, 0), new BlockCoordinate(8, 72, 8)));
        WorkerAssignment miner = new WorkerAssignment(WORKER, "totem:miner", Optional.of(ZONE), Optional.empty());
        WorldWorkZoneEligibility eligibility = new WorldWorkZoneEligibility();

        assertTrue(eligibility.permits(WORKER, "totem:miner", "minecraft:overworld", new BlockCoordinate(8, 60, 8),
                Map.of(ZONE, mine), Optional.of(miner)));
        assertFalse(eligibility.permits(WORKER, "totem:lumberjack", "minecraft:overworld", new BlockCoordinate(4, 60, 4),
                Map.of(ZONE, mine), Optional.of(miner)));
        assertFalse(eligibility.permits(WORKER, "totem:miner", "minecraft:the_nether", new BlockCoordinate(4, 60, 4),
                Map.of(ZONE, mine), Optional.of(miner)));
        assertFalse(eligibility.permits(WORKER, "totem:miner", "minecraft:overworld", new BlockCoordinate(9, 60, 4),
                Map.of(ZONE, mine), Optional.of(miner)));
        assertFalse(eligibility.permits(WORKER, "totem:miner", "minecraft:overworld", new BlockCoordinate(4, 60, 4),
                Map.of(ZONE, mine), Optional.empty()));
    }
}
