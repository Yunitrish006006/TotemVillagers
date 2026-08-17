package dev.totem.villagers.schedule;

import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkshopCandidatePlannerTest {
    private static final WorkOrder BREAD = new WorkOrder("totem:farmer_bread", "minecraft:farmer",
            new ItemAmount("minecraft:bread", 1), List.of(new ItemAmount("minecraft:wheat", 3)),
            Set.of(WorkSource.WORKSHOP), "", 20, 8);

    @Test
    void offersWorkshopOrdersAtTheNativeJobSiteWithoutAContainerLink() {
        WorkshopCandidatePlanner planner = new WorkshopCandidatePlanner();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));

        assertEquals(List.of(new WorkCandidate("totem:farmer_bread", WorkSource.WORKSHOP, 100)),
                planner.candidates(catalog, "minecraft:farmer", true));
        assertEquals(List.of(), planner.candidates(catalog, "minecraft:farmer", false));
    }
}
