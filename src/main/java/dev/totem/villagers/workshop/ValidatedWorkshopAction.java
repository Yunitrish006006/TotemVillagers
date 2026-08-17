package dev.totem.villagers.workshop;

import dev.totem.villagers.work.WorkOrder;
import net.minecraft.world.item.ItemStack;

/** A job-site action revalidated by the server immediately before input commit. */
@FunctionalInterface
public interface ValidatedWorkshopAction {
    /** Returns true only after the profession-appropriate job-site action completed. */
    boolean complete();

    /**
     * Most actions credit the scheduled order unchanged. A deterministic generator
     * may bind the just-created component variant after it has validated the work.
     */
    default WorkOrder completedOrder(WorkOrder scheduledOrder) {
        return scheduledOrder;
    }

    /**
     * Returns the one compatible crafting remainder that must be restored to the
     * personal work inventory when the input reservation commits. Most job-site actions have
     * no such remainder. The commit service owns the actual insertion so a
     * failed action cannot mint containers.
     */
    default ItemStack returnedItem() {
        return ItemStack.EMPTY;
    }
}
