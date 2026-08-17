package dev.totem.villagers.worker;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerAssignmentSavedDataTest {
    @Test
    void zoneCanOnlyBeAssignedByItsOwnerToTheMatchingSpecialistRole() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000701");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000702");
        WorkerAssignmentSavedData data = new WorkerAssignmentSavedData();
        WorkZoneRecord mine = data.createZone("totem:miner", new WorkZone(owner, "minecraft:overworld",
                new BlockCoordinate(0, 60, 0), new BlockCoordinate(8, 80, 8)));
        WorkerAssignment miner = new WorkerAssignment(UUID.fromString("00000000-0000-0000-0000-000000000703"),
                "totem:miner", Optional.empty(), Optional.empty());

        assertFalse(data.assignZone(other, miner, mine.id()));
        assertTrue(data.assignZone(owner, miner, mine.id()));
        assertTrue(data.getAssignment(miner.villagerId()).orElseThrow().workZoneId().isPresent());
    }

    @Test
    void builderUsesTheSameOwnerBoundWorkZoneAssignmentModel() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000711");
        WorkerAssignmentSavedData data = new WorkerAssignmentSavedData();
        WorkZoneRecord construction = data.createZone("totem:builder", new WorkZone(owner, "minecraft:overworld",
                new BlockCoordinate(0, 60, 0), new BlockCoordinate(8, 80, 8)));
        WorkerAssignment builder = new WorkerAssignment(UUID.fromString("00000000-0000-0000-0000-000000000712"),
                "totem:builder", Optional.empty(), Optional.empty());

        assertTrue(data.assignZone(owner, builder, construction.id()));
        assertTrue(data.getAssignment(builder.villagerId()).orElseThrow().workZoneId().isPresent());
    }
}
