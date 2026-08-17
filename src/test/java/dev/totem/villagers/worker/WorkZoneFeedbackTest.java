package dev.totem.villagers.worker;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneFeedbackTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000002001");
    private static final UUID VILLAGER = UUID.fromString("00000000-0000-0000-0000-000000002002");
    private static final UUID ZONE = UUID.fromString("00000000-0000-0000-0000-000000002003");
    private static final WorkZoneRecord MINE = new WorkZoneRecord(ZONE, "totem:miner",
            new WorkZone(OWNER, "minecraft:overworld", new BlockCoordinate(0, 60, 0), new BlockCoordinate(8, 80, 8)));
    private static final WorkerAssignment MINER = new WorkerAssignment(VILLAGER, "totem:miner", Optional.of(ZONE), Optional.empty());

    @Test
    void reportsTheConfirmedBoundaryAndWhetherTheLoadedWorkerIsInsideIt() {
        WorkZoneFeedback inside = feedback("totem:miner", "minecraft:overworld", new BlockCoordinate(8, 70, 8),
                Optional.of(MINER), Map.of(ZONE, MINE)).orElseThrow();
        WorkZoneFeedback outside = feedback("totem:miner", "minecraft:overworld", new BlockCoordinate(9, 70, 8),
                Optional.of(MINER), Map.of(ZONE, MINE)).orElseThrow();

        assertEquals(WorkZoneFeedback.State.INSIDE, inside.state());
        assertEquals(Optional.of(ZONE), inside.zoneId());
        assertEquals(Optional.of(MINE.zone()), inside.zone());
        assertEquals(WorkZoneFeedback.State.OUTSIDE, outside.state());
        assertEquals(Optional.of(MINE.zone()), outside.zone());
    }

    @Test
    void explainsUnassignedMissingAndStaleRoleStatesWithoutInventingABoundary() {
        WorkZoneFeedback unassigned = feedback("totem:builder", "minecraft:overworld", new BlockCoordinate(0, 70, 0),
                Optional.empty(), Map.of()).orElseThrow();
        WorkZoneFeedback missing = feedback("totem:miner", "minecraft:overworld", new BlockCoordinate(0, 70, 0),
                Optional.of(MINER), Map.of()).orElseThrow();
        WorkZoneFeedback staleRole = feedback("minecraft:farmer", "minecraft:overworld", new BlockCoordinate(0, 70, 0),
                Optional.of(MINER), Map.of(ZONE, MINE)).orElseThrow();

        assertEquals(WorkZoneFeedback.State.UNASSIGNED, unassigned.state());
        assertTrue(unassigned.zoneId().isEmpty());
        assertEquals(WorkZoneFeedback.State.MISSING, missing.state());
        assertEquals(Optional.of(ZONE), missing.zoneId());
        assertTrue(missing.zone().isEmpty());
        assertEquals(WorkZoneFeedback.State.ASSIGNMENT_MISMATCH, staleRole.state());
        assertEquals(Optional.of(MINE.zone()), staleRole.zone());
        assertFalse(feedback("totem:guard", "minecraft:overworld", new BlockCoordinate(0, 70, 0),
                Optional.empty(), Map.of()).isPresent());
    }

    private static Optional<WorkZoneFeedback> feedback(String role, String dimension, BlockCoordinate position,
                                                        Optional<WorkerAssignment> assignment,
                                                        Map<UUID, WorkZoneRecord> zones) {
        return WorkZoneFeedback.evaluate(VILLAGER, role, dimension, position, assignment, zones);
    }
}
