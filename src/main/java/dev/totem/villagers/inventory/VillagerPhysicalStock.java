package dev.totem.villagers.inventory;

import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Views the villager's real 27-slot inventory as its only merchant stock ledger. */
public final class VillagerPhysicalStock {
    private VillagerPhysicalStock() {
    }

    public static MerchantStock snapshot(VillagerWorkInventory inventory, HolderLookup.Provider registries) {
        MerchantStock result = new MerchantStock();
        for (ItemStack stack : inventory.snapshot()) {
            if (stack.isEmpty()) {
                continue;
            }
            StockVariantKey key = StockVariantKey.fromStack(stack, registries);
            result.credit(key, stack.getCount());
        }
        return result;
    }

    public static int available(VillagerWorkInventory inventory, ItemStack stack) {
        return inventory.countMatchingItem(stack);
    }
}
