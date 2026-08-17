package dev.totem.villagers.workshop;

import dev.totem.villagers.inventory.WorkInventory;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The sole workshop-to-stock commit path.  Inputs are held in a reservation until
 * the job-site action succeeds, then committed exactly once with the stock credit.
 */
public final class WorkshopCommitService {
    /**
     * Completes a job by returning both crafting remainders and the real
     * produced stack to the worker's own inventory. No parallel stock number is
     * credited.
     */
    public WorkshopCommitResult completePhysical(
            WorkInventory inventory,
            WorkOrder order,
            ValidatedWorkshopAction action,
            Function<WorkOrder, ItemStack> outputResolver
    ) {
        Objects.requireNonNull(inventory, "inventory");
        WorkInventory.Reservation reservation = inventory.reserveExact(order.requiredInputs()).orElse(null);
        return reservation == null ? WorkshopCommitResult.INPUTS_UNAVAILABLE
                : completePhysical(reservation, order, action, outputResolver);
    }

    public WorkshopCommitResult completePhysical(
            WorkInventory.Reservation reservation,
            WorkOrder order,
            ValidatedWorkshopAction action,
            Function<WorkOrder, ItemStack> outputResolver
    ) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(outputResolver, "outputResolver");
        if (!order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)
                && !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.ENCHANTING)) {
            reservation.rollback();
            return WorkshopCommitResult.INPUT_NOT_ACCEPTED;
        }
        try {
            if (!action.complete()) {
                reservation.rollback();
                return WorkshopCommitResult.JOB_SITE_REJECTED;
            }
        } catch (RuntimeException exception) {
            reservation.rollback();
            throw exception;
        }
        WorkOrder completedOrder = action.completedOrder(order);
        if (!isSafeCompletedVariant(order, completedOrder)) {
            reservation.rollback();
            return WorkshopCommitResult.JOB_SITE_REJECTED;
        }
        ItemStack produced = outputResolver.apply(completedOrder);
        if (produced == null || produced.isEmpty() || produced.getCount() != completedOrder.output().count()
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(produced.getItem()).toString()
                .equals(completedOrder.output().itemId())) {
            reservation.rollback();
            return WorkshopCommitResult.JOB_SITE_REJECTED;
        }
        List<ItemStack> returned = new ArrayList<>(2);
        ItemStack remainder = action.returnedItem();
        if (remainder != null && !remainder.isEmpty()) {
            returned.add(remainder);
        }
        returned.add(produced.copy());
        if (!reservation.commitWithReturns(List.copyOf(returned))) {
            reservation.rollback();
            return WorkshopCommitResult.RETURN_UNAVAILABLE;
        }
        return WorkshopCommitResult.COMPLETED;
    }

    public WorkshopCommitResult complete(
            WorkInventory inventory,
            WorkOrder order,
            MerchantStock merchantStock,
            ValidatedWorkshopAction action
    ) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(merchantStock, "merchantStock");
        Objects.requireNonNull(action, "action");

        if (!order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)
                && !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.ENCHANTING)) {
            return WorkshopCommitResult.INPUT_NOT_ACCEPTED;
        }

        WorkInventory.Reservation reservation = inventory.reserveExact(order.requiredInputs()).orElse(null);
        if (reservation == null) {
            return WorkshopCommitResult.INPUTS_UNAVAILABLE;
        }
        return completeReservation(reservation, order, merchantStock, action);
    }

    /** Completes work using a caller-selected exact reservation, such as pristine equipment or a component-bound potion. */
    public WorkshopCommitResult complete(
            WorkInventory.Reservation reservation,
            WorkOrder order,
            MerchantStock merchantStock,
            ValidatedWorkshopAction action
    ) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(merchantStock, "merchantStock");
        Objects.requireNonNull(action, "action");
        if (!order.allowedSources().contains(dev.totem.villagers.work.WorkSource.WORKSHOP)
                && !order.allowedSources().contains(dev.totem.villagers.work.WorkSource.ENCHANTING)) {
            return WorkshopCommitResult.INPUT_NOT_ACCEPTED;
        }
        return completeReservation(reservation, order, merchantStock, action);
    }

    private WorkshopCommitResult completeReservation(
            WorkInventory.Reservation reservation,
            WorkOrder order,
            MerchantStock merchantStock,
            ValidatedWorkshopAction action
    ) {
        try {
            if (!action.complete()) {
                reservation.rollback();
                return WorkshopCommitResult.JOB_SITE_REJECTED;
            }
        } catch (RuntimeException exception) {
            reservation.rollback();
            throw exception;
        }
        WorkOrder completedOrder = action.completedOrder(order);
        if (!isSafeCompletedVariant(order, completedOrder)) {
            reservation.rollback();
            return WorkshopCommitResult.JOB_SITE_REJECTED;
        }
        ItemStack returnedItem = action.returnedItem();
        if (returnedItem == null || !reservation.commitWithReturn(returnedItem)) {
            reservation.rollback();
            return WorkshopCommitResult.RETURN_UNAVAILABLE;
        }
        merchantStock.recordCompletedWork(completedOrder);
        return WorkshopCommitResult.COMPLETED;
    }

    private static boolean isSafeCompletedVariant(WorkOrder scheduledOrder, WorkOrder completedOrder) {
        return completedOrder != null
                && scheduledOrder.id().equals(completedOrder.id())
                && scheduledOrder.professionId().equals(completedOrder.professionId())
                && (scheduledOrder.output().equals(completedOrder.output())
                    || CartographerExplorerMapWorkshopAction.isExplorerOutputVariant(scheduledOrder, completedOrder))
                && scheduledOrder.requiredInputs().equals(completedOrder.requiredInputs())
                && scheduledOrder.allowedSources().equals(completedOrder.allowedSources())
                && scheduledOrder.worldTargetTag().equals(completedOrder.worldTargetTag())
                && scheduledOrder.worldTargetEntityType().equals(completedOrder.worldTargetEntityType())
                && scheduledOrder.worldReplantBlockId().equals(completedOrder.worldReplantBlockId())
                && scheduledOrder.workTicks() == completedOrder.workTicks()
                && scheduledOrder.stockCap() == completedOrder.stockCap();
    }
}
