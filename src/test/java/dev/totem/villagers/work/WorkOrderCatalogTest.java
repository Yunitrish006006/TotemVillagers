package dev.totem.villagers.work;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkOrderCatalogTest {
    private static final WorkOrder BREAD = new WorkOrder(
            "totem:farmer_bread", "minecraft:farmer", new ItemAmount("minecraft:bread", 1),
            List.of(new ItemAmount("minecraft:wheat", 3)), Set.of(WorkSource.WORKSHOP), "", 20, 16
    );

    @Test
    void coverageRejectsAnUnmappedSellOrder() {
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(BREAD));

        assertDoesNotThrow(() -> catalog.requireCoverage(Set.of("totem:farmer_bread")));
        assertThrows(IllegalStateException.class, () -> catalog.requireCoverage(Set.of(
                "totem:farmer_bread", "totem:librarian_bookshelf"
        )));
    }
}
