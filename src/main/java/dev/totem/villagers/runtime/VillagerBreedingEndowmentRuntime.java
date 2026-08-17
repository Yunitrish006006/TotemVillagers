package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.ItemAmount;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Transfers a bred villager's finite starting assets from both physical parent inventories. */
public final class VillagerBreedingEndowmentRuntime {
    public static final int PARENT_EMERALDS = VillagerStarterSupplyRuntime.STARTING_EMERALDS / 2;
    public static final int PARENT_BREAD = VillagerStarterSupplyRuntime.STARTING_BREAD / 2;
    private static final List<ItemAmount> PARENT_SHARE = List.of(
            new ItemAmount("minecraft:emerald", PARENT_EMERALDS),
            new ItemAmount("minecraft:bread", PARENT_BREAD));
    private static final Map<UUID, PendingBirth> PENDING_BIRTHS = new ConcurrentHashMap<>();

    private VillagerBreedingEndowmentRuntime() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager child) {
                settlePendingBirth(level, child);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING_BIRTHS.clear());
    }

    /** Called by the breeding mixin before vanilla attempts to add the child to the world. */
    public static void rememberBirth(Villager firstParent, Villager secondParent, Villager child) {
        if (firstParent == null || secondParent == null || child == null
                || firstParent.getUUID().equals(secondParent.getUUID())
                || child.getUUID().equals(firstParent.getUUID())
                || child.getUUID().equals(secondParent.getUUID())) {
            return;
        }
        PENDING_BIRTHS.put(child.getUUID(), new PendingBirth(firstParent.getUUID(), secondParent.getUUID()));
    }

    private static void settlePendingBirth(ServerLevel level, Villager child) {
        PendingBirth birth = PENDING_BIRTHS.remove(child.getUUID());
        if (birth == null) {
            return;
        }
        VillagerStarterSupplySavedData ledger = VillagerStarterSupplySavedData.forServer(level.getServer());
        if (ledger.isBred(child.getUUID())) {
            return;
        }
        // Persist the provenance even when the parents cannot pay. A bred child
        // must never become eligible for a later world-generation grant.
        ledger.markBred(child.getUUID());
        if (WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode()
                != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        if (!(level.getEntity(birth.firstParent()) instanceof Villager firstParent)
                || !(level.getEntity(birth.secondParent()) instanceof Villager secondParent)) {
            return;
        }
        transferCompleteEndowment(level, firstParent, secondParent, child, ledger);
    }

    private static boolean transferCompleteEndowment(ServerLevel level, Villager firstParent, Villager secondParent,
                                                      Villager child, VillagerStarterSupplySavedData ledger) {
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        synchronized (inventories) {
            VillagerWorkInventory firstInventory = inventories.inventory(firstParent.getUUID());
            VillagerWorkInventory secondInventory = inventories.inventory(secondParent.getUUID());
            VillagerWorkInventory childInventory = inventories.inventory(child.getUUID());
            List<ItemStack> childEndowment = List.of(
                    new ItemStack(Items.EMERALD, VillagerStarterSupplyRuntime.STARTING_EMERALDS),
                    new ItemStack(Items.BREAD, VillagerStarterSupplyRuntime.STARTING_BREAD));
            if (!firstInventory.canReserveExact(PARENT_SHARE)
                    || !secondInventory.canReserveExact(PARENT_SHARE)
                    || !childInventory.canInsertAllExact(childEndowment)) {
                return false;
            }
            var firstReservation = firstInventory.reserveExact(PARENT_SHARE).orElseThrow();
            var secondReservation = secondInventory.reserveExact(PARENT_SHARE).orElse(null);
            if (secondReservation == null) {
                firstReservation.rollback();
                return false;
            }
            if (!childInventory.insertAllExact(childEndowment)) {
                secondReservation.rollback();
                firstReservation.rollback();
                return false;
            }
            firstReservation.commit();
            secondReservation.commit();
            ledger.markBase(child.getUUID());
            return true;
        }
    }

    private record PendingBirth(UUID firstParent, UUID secondParent) {
    }
}
