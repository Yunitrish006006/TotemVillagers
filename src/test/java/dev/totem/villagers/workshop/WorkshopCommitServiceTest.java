package dev.totem.villagers.workshop;

import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkshopCommitServiceTest {
    private static final WorkOrder BREAD = new WorkOrder(
            "totem:farmer_bread", "minecraft:farmer", new ItemAmount("minecraft:bread", 3),
            List.of(new ItemAmount("minecraft:wheat", 9)), Set.of(WorkSource.WORKSHOP), "", 20, 16
    );

    @Test
    void successfulWorkshopCommitConsumesRawInputsAndCreditsOnlyOrderOutput() {
        MapWorkChestInventory inventory = new MapWorkChestInventory(Map.of("minecraft:wheat", 9));
        MerchantStock stock = new MerchantStock();
        WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, BREAD, stock, () -> true);

        assertEquals(WorkshopCommitResult.COMPLETED, result);
        assertEquals(Map.of(), inventory.snapshot());
        assertEquals(3, stock.available("minecraft:bread"));
        assertEquals(0, stock.available("minecraft:wheat"));
    }

    @Test
    void rejectedJobSiteRestoresReservedInputsAndDoesNotCreateStock() {
        MapWorkChestInventory inventory = new MapWorkChestInventory(Map.of("minecraft:wheat", 9));
        MerchantStock stock = new MerchantStock();

        WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, BREAD, stock, () -> false);

        assertEquals(WorkshopCommitResult.JOB_SITE_REJECTED, result);
        assertEquals(Map.of("minecraft:wheat", 9), inventory.snapshot());
        assertEquals(0, stock.available("minecraft:bread"));
    }

    @Test
    void personalInventoryNeedsNoSeparateLinkToSpendInputs() {
        MapWorkChestInventory inventory = new MapWorkChestInventory(Map.of("minecraft:wheat", 9));
        MerchantStock stock = new MerchantStock();

        WorkshopCommitResult result = new WorkshopCommitService().complete(inventory, BREAD, stock, () -> true);

        assertEquals(WorkshopCommitResult.COMPLETED, result);
        assertEquals(Map.of(), inventory.snapshot());
        assertEquals(3, stock.available("minecraft:bread"));
    }
}
