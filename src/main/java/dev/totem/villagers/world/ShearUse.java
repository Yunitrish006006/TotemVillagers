package dev.totem.villagers.world;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ShearUse {
    private ShearUse() {}

    public static boolean usable(ItemStack stack) {
        return stack.is(Items.SHEARS) && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    public static Optional<ItemStack> best(List<ItemStack> inventory) {
        return inventory.stream().filter(ShearUse::usable)
                .map(stack -> stack.copyWithCount(1))
                .min(Comparator.comparingInt(ItemStack::getDamageValue));
    }

    /** One successful use consumes one durability point. */
    static ItemStack wearOnce(ItemStack shears) {
        ItemStack worn = shears.copy();
        if (!worn.isDamageableItem()) {
            return worn;
        }
        if (worn.getDamageValue() + 1 >= worn.getMaxDamage()) {
            return ItemStack.EMPTY;
        }
        worn.setDamageValue(worn.getDamageValue() + 1);
        return worn;
    }
}
