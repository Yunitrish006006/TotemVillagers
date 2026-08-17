package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Loaded, local material purchases between villagers. A buyer must pay the
 * exact emerald result of its own current vanilla purchase offer, while the
 * supplier must physically hold that offer's full, unreserved input batch.
 * This never mints material or currency and a data-pack offer change takes
 * effect immediately.
 */
public final class VillagerMaterialLogisticsRuntime {
    private static final long DELIVERY_INTERVAL_TICKS = 20L;
    private static final double SEARCH_RANGE_SQUARED = 32.0D * 32.0D;
    private static final double HANDOFF_RANGE_SQUARED = 4.0D * 4.0D;

    private VillagerMaterialLogisticsRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerMaterialLogisticsRuntime::tick);
    }

    /** Public only for the end-to-end GameTest; production invokes this every second. */
    public static void tickForGameTest(MinecraftServer server) {
        transfer(server);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        if (server.overworld().getGameTime() % DELIVERY_INTERVAL_TICKS != 0L) {
            return;
        }
        transfer(server);
    }

    private static void transfer(MinecraftServer server) {
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            List<? extends Villager> villagers = LoadedVillagerCache.loaded(level);
            Map<UUID, Optional<WorkOrder>> demands = new LinkedHashMap<>();
            for (Villager villager : villagers) {
                demands.put(villager.getUUID(), VillagerWorkshopRuntime.materialDemandFor(level, villager));
            }
            for (Villager recipient : villagers) {
                transferOneMissingIngredient(level, recipient, villagers, inventories, demands);
            }
        }
    }

    private static void transferOneMissingIngredient(
            ServerLevel level,
            Villager recipient,
            List<? extends Villager> villagers,
            VillagerWorkInventorySavedData inventories,
            Map<UUID, Optional<WorkOrder>> demands
    ) {
        if (!VillagerWorkNeeds.canWork(recipient)) {
            return;
        }
        Optional<WorkOrder> demand = demands.getOrDefault(recipient.getUUID(), Optional.empty());
        if (demand.isEmpty()) {
            return;
        }
        VillagerWorkInventory recipientInventory = inventories.inventory(recipient.getUUID());
        Optional<MaterialNeed> missing = firstMissingIngredient(recipientInventory, demand.orElseThrow());
        if (missing.isEmpty()) {
            return;
        }
        Optional<MaterialPurchase> purchase = purchaseFor(recipient, missing.orElseThrow(),
                recipientInventory.countMatchingItem(new ItemStack(Items.EMERALD)));
        if (purchase.isEmpty()) {
            return;
        }
        Optional<Supplier> supplier = villagers.stream()
                .filter(candidate -> candidate != recipient && VillagerWorkNeeds.canWork(candidate))
                .filter(candidate -> recipient.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .map(candidate -> supplierFor(candidate, inventories, missing.orElseThrow(), purchase.orElseThrow(),
                        demands.getOrDefault(candidate.getUUID(), Optional.empty())))
                .flatMap(Optional::stream)
                .min(java.util.Comparator.comparingDouble(entry -> recipient.distanceToSqr(entry.villager())));
        if (supplier.isEmpty()) {
            return;
        }
        Supplier selected = supplier.orElseThrow();
        if (recipient.distanceToSqr(selected.villager()) > HANDOFF_RANGE_SQUARED) {
            recipient.getNavigation().moveTo(selected.villager(), .5D);
            return;
        }
        ItemStack purchasedMaterial = selected.purchase().material().copy();
        ItemStack payment = new ItemStack(Items.EMERALD, selected.purchase().emeraldPrice());
        if (!recipientInventory.canInsertExact(purchasedMaterial)
                || !selected.inventory().canInsertExact(payment)
                || recipientInventory.takeExactMatchingItem(payment).isEmpty()) {
            return;
        }
        ItemStack delivered = selected.inventory().takeExactMatchingItem(purchasedMaterial).orElse(null);
        if (delivered == null) {
            recipientInventory.insertExact(payment);
            return;
        }
        if (!recipientInventory.insertExact(delivered)) {
            if (!selected.inventory().insertExact(delivered)) {
                selected.villager().spawnAtLocation(level, delivered);
            }
            recipientInventory.insertExact(payment);
            return;
        }
        if (!selected.inventory().insertExact(payment)) {
            throw new IllegalStateException("Supplier inventory changed during same-tick emerald transfer");
        }
    }

    private static Optional<MaterialNeed> firstMissingIngredient(VillagerWorkInventory inventory, WorkOrder order) {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (ItemAmount input : order.requiredInputs()) {
            required.merge(input.itemId(), input.count(), Math::addExact);
        }
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int missing = entry.getValue() - count(inventory, entry.getKey());
            if (missing > 0) {
                return Optional.of(new MaterialNeed(entry.getKey(), missing));
            }
        }
        return Optional.empty();
    }

    private static Optional<Supplier> supplierFor(
            Villager supplier,
            VillagerWorkInventorySavedData inventories,
            MaterialNeed need,
            MaterialPurchase purchase,
            Optional<WorkOrder> supplierDemand
    ) {
        VillagerWorkInventory inventory = inventories.inventory(supplier.getUUID());
        int ownReservation = supplierDemand
                .map(order -> requiredCount(order, need.itemId()))
                .orElse(0);
        int transferable = inventory.countMatchingItem(purchase.material()) - ownReservation;
        return transferable >= purchase.material().getCount()
                ? Optional.of(new Supplier(supplier, inventory, purchase))
                : Optional.empty();
    }

    /**
     * The recipient's live vanilla buy offer is the sole material price authority.
     * Only a single material cost paid out as pure emeralds is safe for an
     * autonomous transaction; side inputs stay player-facing rather than being
     * silently invented or selected by the runtime.
     */
    private static Optional<MaterialPurchase> purchaseFor(Villager recipient, MaterialNeed need, int buyerEmeralds) {
        if (buyerEmeralds < 1) {
            return Optional.empty();
        }
        return recipient.getOffers().stream()
                .filter(offer -> !offer.isOutOfStock())
                .map(VillagerMaterialLogisticsRuntime::materialPurchase)
                .flatMap(Optional::stream)
                .filter(purchase -> purchase.materialId().equals(need.itemId()))
                .filter(purchase -> purchase.emeraldPrice() <= buyerEmeralds)
                .min(java.util.Comparator.comparingInt(MaterialPurchase::emeraldPrice)
                        .thenComparingInt(purchase -> purchase.material().getCount()));
    }

    private static Optional<MaterialPurchase> materialPurchase(MerchantOffer offer) {
        ItemStack material = offer.getCostA();
        ItemStack extraCost = offer.getCostB();
        ItemStack payment = offer.getResult();
        if (material.isEmpty() || !extraCost.isEmpty() || !payment.is(Items.EMERALD) || payment.getCount() < 1) {
            return Optional.empty();
        }
        return Optional.of(new MaterialPurchase(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(material.getItem()).toString(),
                material.copy(), payment.getCount()));
    }

    private static int requiredCount(WorkOrder order, String itemId) {
        return order.requiredInputs().stream()
                .filter(input -> input.itemId().equals(itemId))
                .mapToInt(ItemAmount::count)
                .sum();
    }

    private static int count(VillagerWorkInventory inventory, String itemId) {
        return inventory.snapshot().stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private record MaterialNeed(String itemId, int count) {
    }

    private record MaterialPurchase(String materialId, ItemStack material, int emeraldPrice) {
    }

    private record Supplier(Villager villager, VillagerWorkInventory inventory, MaterialPurchase purchase) {
    }
}
