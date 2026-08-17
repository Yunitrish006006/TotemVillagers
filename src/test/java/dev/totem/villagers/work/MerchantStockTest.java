package dev.totem.villagers.work;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantStockTest {
    private static final WorkOrder WORKSHOP_BREAD = new WorkOrder(
            "totem:farmer_bread",
            "minecraft:farmer",
            new ItemAmount("minecraft:bread", 3),
            List.of(new ItemAmount("minecraft:wheat", 9)),
            Set.of(WorkSource.WORKSHOP),
            "",
            40,
            5
    );

    @Test
    void completedWorkAddsOnlyBoundedStockAndTradeDebitsAtomically() {
        MerchantStock stock = new MerchantStock();

        assertEquals(3, stock.recordCompletedWork(WORKSHOP_BREAD));
        assertEquals(2, stock.recordCompletedWork(WORKSHOP_BREAD));
        assertEquals(5, stock.available("minecraft:bread"));
        assertTrue(stock.debitForTrade(new ItemAmount("minecraft:bread", 3)));
        assertEquals(2, stock.available("minecraft:bread"));
        assertFalse(stock.debitForTrade(new ItemAmount("minecraft:bread", 3)));
        assertEquals(2, stock.available("minecraft:bread"));
    }

    @Test
    void workshopOrderCannotExistWithoutConsumedInputs() {
        try {
            new WorkOrder(
                    "totem:free_bread", "minecraft:farmer", new ItemAmount("minecraft:bread", 1),
                    List.of(), Set.of(WorkSource.WORKSHOP), "", 1, 1
            );
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("consume raw inputs"));
            return;
        }
        throw new AssertionError("A no-input workshop order was accepted");
    }
}
