package dev.totem.villagers.inventory;

import dev.totem.villagers.work.ItemAmount;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Transactional material inventory used by Totem work. Implementations may be
 * personal villager storage or a legacy adapter, but work code never accesses
 * arbitrary world containers.
 */
public interface WorkInventory {
    boolean canReserveExact(List<ItemAmount> requiredInputs);

    Optional<Reservation> reserveExact(List<ItemAmount> requiredInputs);

    boolean canInsertExact(ItemStack stack);

    boolean insertExact(ItemStack stack);

    /**
     * Checks whether every stack in a gathered yield can fit together. World
     * actions use this before changing their source blocks, so a secondary
     * yield can never be lost after a primary one was accepted.
     */
    boolean canInsertAllExact(List<ItemStack> stacks);

    /** Atomically inserts one complete gathered yield, or inserts none of it. */
    boolean insertAllExact(List<ItemStack> stacks);

    interface Reservation {
        List<ItemAmount> reservedInputs();

        void commit();

        boolean commitWithReturn(ItemStack returnedItem);

        /**
         * Atomically finishes this reservation while returning every supplied
         * stack to the work inventory. Implementations that only support one
         * return retain their existing behaviour; personal inventories can
         * use this for a processed tool and its gathered output together.
         */
        default boolean commitWithReturns(List<ItemStack> returnedItems) {
            if (returnedItems == null || returnedItems.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
                throw new IllegalArgumentException("Returned stacks must be non-null and non-empty");
            }
            if (returnedItems.isEmpty()) {
                commit();
                return true;
            }
            if (returnedItems.size() == 1) {
                return commitWithReturn(returnedItems.getFirst());
            }
            throw new UnsupportedOperationException("This work inventory supports one returned stack per reservation");
        }

        void rollback();
    }
}
