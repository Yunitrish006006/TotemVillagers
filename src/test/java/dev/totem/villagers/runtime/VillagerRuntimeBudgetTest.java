package dev.totem.villagers.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VillagerRuntimeBudgetTest {
    private static final UUID VILLAGER = UUID.fromString("12345678-1234-5678-9abc-def012345678");

    @Test
    void idleDiscoveryRunsExactlyOncePerTwentyTickWindow() {
        long pulses = LongStream.range(0, VillagerRuntimeBudget.IDLE_SCAN_INTERVAL_TICKS)
                .filter(tick -> VillagerRuntimeBudget.due(
                        tick, VILLAGER, VillagerRuntimeBudget.IDLE_SCAN_INTERVAL_TICKS))
                .count();

        assertEquals(1L, pulses);
    }

    @Test
    void navigationRetriesRemainBoundedAcrossOneSecond() {
        long retries = LongStream.range(0, VillagerRuntimeBudget.IDLE_SCAN_INTERVAL_TICKS)
                .filter(tick -> VillagerRuntimeBudget.due(
                        tick, VILLAGER, VillagerRuntimeBudget.NAVIGATION_RETRY_INTERVAL_TICKS))
                .count();

        assertEquals(2L, retries);
    }

    @Test
    void invalidIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VillagerRuntimeBudget.due(0L, VILLAGER, 0));
    }
}
