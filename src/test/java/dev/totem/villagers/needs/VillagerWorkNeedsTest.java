package dev.totem.villagers.needs;

import dev.totem.villagers.work.ActiveWork;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWorkNeedsTest {
    @Test
    void hungerThresholdAllowsWorkOnlyAboveTheFoodPurchaseThreshold() {
        assertFalse(VillagerWorkNeeds.canWork(8));
        assertTrue(VillagerWorkNeeds.canWork(9));
    }

    @Test
    void hungerCancelsAnInFlightJobBeforeItCanCommit() {
        UUID worker = UUID.fromString("00000000-0000-0000-0000-000000000911");
        VillagerWorkState active = VillagerWorkState.empty(worker).withActiveWork(
                Optional.of(new ActiveWork("totem:farmer_bread", WorkSource.WORKSHOP, 100L, 20)), Optional.empty());

        VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(active);
        assertTrue(paused.activeWork().isEmpty());
        assertEquals("totem:needs_food", paused.diagnostic().orElseThrow().orderId());
        assertEquals("needs food", paused.diagnostic().orElseThrow().blockedReason());
    }
}
