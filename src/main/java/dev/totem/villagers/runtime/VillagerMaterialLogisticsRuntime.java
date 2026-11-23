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
                if (recipient.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.FLETCHER)
                        || dev.totem.villagers.worker.TotemVillagerProfessions.GUARD_ID.equals(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(recipient.getVillagerData().profession().value())))
                    VillageWorkshopApprenticeship.observeArrowDemand(level, recipient, demands.get(recipient.getUUID()).orElse(null), level.getGameTime());
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
        if (!VillagerWorkNeeds.canWork(recipient) || !VillageWorkshopApprenticeship.available(level, recipient)) {
            VillagerHeadStatus.clear(recipient, VillagerHeadStatus.Kind.MATERIAL);
            return;
        }
        Optional<WorkOrder> demand = demands.getOrDefault(recipient.getUUID(), Optional.empty());
        if (demand.isEmpty()) {
            VillagerHeadStatus.clear(recipient, VillagerHeadStatus.Kind.MATERIAL);
            return;
        }
        VillagerWorkInventory recipientInventory = inventories.inventory(recipient.getUUID());
        var order = demand.orElseThrow();
        var ingredients = VillagerWorkshopRuntime.exactIngredients(order, recipient.getOffers(), level);
        Optional<ItemAmount> missing = firstMissingIngredient(recipientInventory, order, ingredients);
        if (missing.isEmpty()) {
            VillagerHeadStatus.clear(recipient, VillagerHeadStatus.Kind.MATERIAL);
            return;
        }
        var exact = ingredients.get(missing.get().itemId());
        Optional<MaterialPurchase> purchase = purchaseFor(recipient, missing.orElseThrow(), exact,
                recipientInventory.countMatchingItem(new ItemStack(Items.EMERALD)));
        if (purchase.isEmpty()) {
            reportShortage(level, recipient, missing.orElseThrow(), exact);
            return;
        }
        var rate = purchase.orElseThrow();
        var payment = new ItemStack(Items.EMERALD, rate.emeraldPrice());
        var suppliers = villagers.stream()
                .filter(candidate -> candidate != recipient && VillagerWorkNeeds.canWork(candidate)
                        && VillageWorkshopApprenticeship.available(level, candidate))
                .filter(candidate -> recipient.distanceToSqr(candidate) <= SEARCH_RANGE_SQUARED)
                .map(candidate -> supplierFor(level, candidate, inventories, missing.orElseThrow(), rate,
                        demands.getOrDefault(candidate.getUUID(), Optional.empty())))
                .flatMap(Optional::stream)
                .sorted(java.util.Comparator.comparingDouble(entry -> recipient.distanceToSqr(entry.villager()))).toList();
        for (var supplier : suppliers) {
            if (recipient.distanceToSqr(supplier.villager()) <= HANDOFF_RANGE_SQUARED && recipient.hasLineOfSight(supplier.villager())) {
                if (!WorkshopLocalMarket.exchange(inventories, supplier.inventory(), recipientInventory, rate.material(), payment)) continue;
            } else {
                if (!recipientInventory.canInsertExact(rate.material()) || !supplier.inventory().canInsertExact(payment)) {
                    var copy = inventories.copyAccounts(List.of(recipient.getUUID(), supplier.villager().getUUID()));
                    if (!WorkshopLocalMarket.exchange(copy, copy.inventory(supplier.villager().getUUID()),
                            copy.inventory(recipient.getUUID()), rate.material(), payment)) continue;
                }
                var path = recipient.getNavigation().createPath(supplier.villager(), 0);
                if (path == null || !path.canReach() || !recipient.getNavigation().moveTo(path, .5)) continue;
            }
            VillagerHeadStatus.clear(recipient, VillagerHeadStatus.Kind.MATERIAL);
            return;
        }
        reportShortage(level, recipient, missing.orElseThrow(), exact);
    }

    private static Optional<ItemAmount> firstMissingIngredient(VillagerWorkInventory inventory, WorkOrder order, Map<String, ItemStack> exact) {
        for (var entry : ItemAmount.totals(order.requiredInputs()).entrySet()) {
            int held = exact.containsKey(entry.getKey()) ? inventory.countMatchingItem(exact.get(entry.getKey())) : count(inventory, entry.getKey());
            int missing = entry.getValue() - held;
            if (missing > 0) {
                return Optional.of(new ItemAmount(entry.getKey(), missing));
            }
        }
        return Optional.empty();
    }

    private static Optional<Supplier> supplierFor(
            ServerLevel level, Villager supplier,
            VillagerWorkInventorySavedData inventories,
            ItemAmount need,
            MaterialPurchase purchase,
            Optional<WorkOrder> supplierDemand
    ) {
        VillagerWorkInventory inventory = inventories.inventory(supplier.getUUID());
        int matching = inventory.countMatchingItem(purchase.material());
        if (matching < purchase.material().getCount()) return Optional.empty();
        int ownReservation = supplierDemand.map(order -> {
            int required = requiredCount(order, need.itemId());
            if (required == 0) return 0;
            var ownExact = VillagerWorkshopRuntime.exactIngredients(order, supplier.getOffers(), level).get(need.itemId());
            if (ownExact != null) return ItemStack.isSameItemSameComponents(ownExact, purchase.material()) ? required : 0;
            int otherVariants = count(inventory, need.itemId()) - matching;
            return Math.max(0, required - otherVariants);
        }).orElse(0);
        int transferable = matching - ownReservation;
        return transferable >= purchase.material().getCount()
                ? Optional.of(new Supplier(supplier, inventory))
                : Optional.empty();
    }

    /**
     * The recipient's live vanilla buy offer is the sole material price authority.
     * Only a single material cost paid out as pure emeralds is safe for an
     * autonomous transaction; side inputs stay player-facing rather than being
     * silently invented or selected by the runtime.
     */
    private static Optional<MaterialPurchase> purchaseFor(Villager recipient, ItemAmount need, ItemStack exact, int buyerEmeralds) {
        if (buyerEmeralds < 1) {
            return Optional.empty();
        }
        return recipient.getOffers().stream()
                .filter(offer -> !offer.isOutOfStock())
                .map(offer -> materialPurchase(offer, exact))
                .flatMap(Optional::stream)
                .filter(purchase -> purchase.materialId().equals(need.itemId())
                        && (exact == null || ItemStack.isSameItemSameComponents(exact, purchase.material())))
                .filter(purchase -> purchase.emeraldPrice() <= buyerEmeralds)
                .min(java.util.Comparator.comparingInt(MaterialPurchase::emeraldPrice)
                        .thenComparingInt(purchase -> purchase.material().getCount()));
    }

    private static Optional<MaterialPurchase> materialPurchase(MerchantOffer offer, ItemStack exact) {
        ItemStack material = offer.getCostA();
        ItemStack extraCost = offer.getCostB();
        ItemStack payment = offer.getResult();
        if (material.isEmpty() || !extraCost.isEmpty() || !payment.is(Items.EMERALD) || payment.getCount() < 1) {
            return Optional.empty();
        }
        if (exact != null) {
            if (!offer.getItemCostA().test(exact)) return Optional.empty();
            material = exact.copyWithCount(material.getCount());
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

    private static void reportShortage(ServerLevel level, Villager worker, ItemAmount missing, ItemStack exact) {
        if (dev.totem.villagers.work.VillagerWorkSavedData.forServer(level.getServer()).get(worker.getUUID())
                .flatMap(state -> state.activeWork()).isPresent()) {
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.MATERIAL);
        } else {
            VillagerHeadStatus.missingMaterial(worker, missing, exact);
        }
    }

    private record MaterialPurchase(String materialId, ItemStack material, int emeraldPrice) {
    }

    private record Supplier(Villager villager, VillagerWorkInventory inventory) {
    }
}
