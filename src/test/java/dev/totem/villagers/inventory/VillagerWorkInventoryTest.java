package dev.totem.villagers.inventory;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillagerWorkInventoryTest {
    @Test
    void startsWithExactlyTwentySevenProtectedEmptySlots() {
        VillagerWorkInventory inventory = new VillagerWorkInventorySavedData().inventory(UUID.randomUUID());

        assertEquals(VillagerWorkInventorySavedData.SLOT_COUNT, inventory.snapshot().size());
        assertTrue(inventory.snapshot().stream().allMatch(ItemStack::isEmpty));
    }
}
