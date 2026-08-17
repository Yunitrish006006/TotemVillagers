package dev.totem.villagers.world;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Shared physical-equipment rules for autonomous Fishermen and Toolsmith demand. */
public final class FishingRodUse {
    private FishingRodUse() {
    }

    /** Selects the rod with the most remaining durability from one villager's real inventory. */
    public static Optional<ItemStack> bestAvailable(List<ItemStack> inventory) {
        return inventory.stream()
                .filter(FishingRodUse::isUsable)
                .map(stack -> stack.copyWithCount(1))
                .max(Comparator.comparingInt(FishingRodUse::remainingDurability));
    }

    /** Uses the most worn serviceable rod first so a pre-ordered spare never strands old durability. */
    public static Optional<ItemStack> nextForWork(List<ItemStack> inventory) {
        return inventory.stream()
                .filter(FishingRodUse::isUsable)
                .map(stack -> stack.copyWithCount(1))
                .min(Comparator.comparingInt(FishingRodUse::remainingDurability));
    }

    public static long usableCount(List<ItemStack> inventory) {
        return inventory.stream().filter(FishingRodUse::isUsable).mapToLong(ItemStack::getCount).sum();
    }

    public static boolean isUsable(ItemStack stack) {
        return stack != null && stack.is(Items.FISHING_ROD)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    /** Returns the physical rod after one successful catch, or empty when that use breaks it. */
    public static ItemStack wearOnce(ItemStack rod) {
        if (!isUsable(rod)) {
            return ItemStack.EMPTY;
        }
        ItemStack worn = rod.copyWithCount(1);
        if (!worn.isDamageableItem()) {
            return worn;
        }
        if (worn.getDamageValue() + 1 >= worn.getMaxDamage()) {
            return ItemStack.EMPTY;
        }
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn;
    }

    public static int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }
}
