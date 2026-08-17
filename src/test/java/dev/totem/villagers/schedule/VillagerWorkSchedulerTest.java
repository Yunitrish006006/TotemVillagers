package dev.totem.villagers.schedule;

import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWorkSchedulerTest {
    private static final UUID VILLAGER = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final WorkOrder BREAD = new WorkOrder("totem:farmer_bread", "minecraft:farmer",
            new ItemAmount("minecraft:bread", 1), List.of(new ItemAmount("minecraft:wheat", 3)),
            Set.of(WorkSource.WORKSHOP), "", 2, 8);

    @Test
    void startsAllowedSourceThenMakesItReadyWithoutCommittingStock() {
        VillagerWorkScheduler scheduler = new VillagerWorkScheduler();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));
        WorkScheduleInput safe = input(false, List.of(new WorkCandidate("totem:farmer_bread", WorkSource.WORKSHOP, 1)));

        var started = scheduler.tick(catalog, VillagerWorkState.empty(VILLAGER), safe);
        var progressed = scheduler.tick(catalog, started.state(), input(false, safe.candidates()));
        var ready = scheduler.tick(catalog, progressed.state(), input(false, safe.candidates()));

        assertFalse(started.readyToCommit().isPresent());
        assertTrue(ready.readyToCommit().isPresent());
        assertEquals(0, ready.state().merchantStock().getOrDefault("minecraft:bread", 0));
    }

    @Test
    void dangerCancelsActiveWorkBeforeAnyCommit() {
        VillagerWorkScheduler scheduler = new VillagerWorkScheduler();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));
        var started = scheduler.tick(catalog, VillagerWorkState.empty(VILLAGER),
                input(false, List.of(new WorkCandidate("totem:farmer_bread", WorkSource.WORKSHOP, 1))));

        var cancelled = scheduler.tick(catalog, started.state(), input(true, List.of()));

        assertTrue(cancelled.state().activeWork().isEmpty());
        assertEquals("danger", cancelled.state().diagnostic().orElseThrow().blockedReason());
        assertTrue(cancelled.readyToCommit().isEmpty());
    }

    @Test
    void travellingToTheLinkedJobSiteDoesNotAdvanceWork() {
        VillagerWorkScheduler scheduler = new VillagerWorkScheduler();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));
        var started = scheduler.tick(catalog, VillagerWorkState.empty(VILLAGER),
                input(false, true, List.of(new WorkCandidate("totem:farmer_bread", WorkSource.WORKSHOP, 1))));

        var travelling = scheduler.tick(catalog, started.state(),
                input(false, false, List.of(new WorkCandidate("totem:farmer_bread", WorkSource.WORKSHOP, 1))));

        assertEquals(0, travelling.state().activeWork().orElseThrow().elapsedTicks());
        assertEquals("travelling to job site", travelling.state().diagnostic().orElseThrow().blockedReason());
        assertTrue(travelling.readyToCommit().isEmpty());
    }

    @Test
    void changingThePersistentWorldTargetCancelsInsteadOfRetargetingWork() {
        WorkOrder stone = new WorkOrder("totem:miner_stone", "totem:miner", new ItemAmount("minecraft:cobblestone", 1),
                List.of(), Set.of(WorkSource.WORLD), "totem:miner_targets", 2, 8);
        VillagerWorkScheduler scheduler = new VillagerWorkScheduler();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(stone));
        WorkCandidate first = new WorkCandidate("totem:miner_stone", WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget("minecraft:overworld", 1L)));
        WorkCandidate changed = new WorkCandidate("totem:miner_stone", WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget("minecraft:overworld", 2L)));
        WorkScheduleInput initial = new WorkScheduleInput(VILLAGER, "totem:miner", 100L, true, true,
                false, false, false, true, true, List.of(first));
        var started = scheduler.tick(catalog, VillagerWorkState.empty(VILLAGER), initial);
        WorkScheduleInput retargeted = new WorkScheduleInput(VILLAGER, "totem:miner", 101L, true, true,
                false, false, false, true, true, List.of(changed));

        var cancelled = scheduler.tick(catalog, started.state(), retargeted);

        assertTrue(cancelled.state().activeWork().isEmpty());
        assertEquals("source changed", cancelled.state().diagnostic().orElseThrow().blockedReason());
    }

    private static WorkScheduleInput input(boolean danger, List<WorkCandidate> candidates) {
        return input(danger, true, candidates);
    }

    private static WorkScheduleInput input(boolean danger, boolean atWorkLocation, List<WorkCandidate> candidates) {
        return new WorkScheduleInput(VILLAGER, "minecraft:farmer", 100L, true, true, danger, false, false, true,
                atWorkLocation, candidates);
    }
}
