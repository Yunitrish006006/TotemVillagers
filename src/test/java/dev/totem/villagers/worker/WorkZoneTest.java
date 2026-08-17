package dev.totem.villagers.worker;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkZoneTest {
    @Test
    void zoneDoesNotPermitCrossDimensionOrOutOfBoundsWork() {
        WorkZone zone = new WorkZone(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "minecraft:overworld", new BlockCoordinate(-8, 64, -8), new BlockCoordinate(8, 80, 8));

        assertTrue(zone.contains("minecraft:overworld", new BlockCoordinate(0, 70, 0)));
        assertFalse(zone.contains("minecraft:the_nether", new BlockCoordinate(0, 70, 0)));
        assertFalse(zone.contains("minecraft:overworld", new BlockCoordinate(9, 70, 0)));
    }
}
