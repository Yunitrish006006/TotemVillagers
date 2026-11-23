package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.Set;

/** Funded village commissions buy common raw materials in lots of four for one emerald. */
final class WorkshopLocalMarket {
    private static final Set<String> RAW = Set.of("minecraft:cobblestone", "minecraft:stone", "minecraft:andesite",
            "minecraft:glowstone_dust", "minecraft:paper", "minecraft:compass", "minecraft:book", "minecraft:oak_planks", "minecraft:leather", "minecraft:iron_ingot", "minecraft:diorite", "minecraft:granite", "minecraft:sandstone", "minecraft:feather", "minecraft:flint", "minecraft:stick");

    static boolean restock(ServerLevel level, Villager apprentice, WorkOrder order, VillagerWorkInventory stock) {
        return restock(level, apprentice, order, stock, 1);
    }

    static boolean restock(ServerLevel level, Villager apprentice, WorkOrder order, VillagerWorkInventory stock, int batches) {
        if (!(order.professionId().equals("minecraft:cleric") || order.professionId().equals("minecraft:cartographer") || order.professionId().equals("minecraft:librarian") || order.professionId().equals("minecraft:leatherworker") || order.professionId().equals("minecraft:mason") || order.professionId().equals("minecraft:fletcher")
                || order.professionId().equals("minecraft:armorer") || order.professionId().equals("minecraft:weaponsmith"))) return false;
        var price = new ItemStack(Items.EMERALD);
        var home = apprentice.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
        if (home == null || stock.countMatchingItem(price) <= 1) return false;
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        for (var input : order.requiredInputs()) {
            if (!RAW.contains(input.itemId())) continue;
            int count = switch (input.itemId()) {
                case "minecraft:compass", "minecraft:iron_ingot" -> 1;
                case "minecraft:book" -> 3;
                default -> 4;
            };
            var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(input.itemId()));
            int missing = input.count() * batches - stock.countMatchingItem(new ItemStack(item));
            if (missing <= 0) continue;
            var lot = new ItemStack(item, order.professionId().equals("minecraft:librarian") ? Math.min(count, missing) : count);
            var neighbors = level.getEntitiesOfClass(Villager.class, apprentice.getBoundingBox().inflate(16),
                    v -> v != apprentice && v.isAlive() && !v.isBaby() && VillageWorkshopApprenticeship.available(level, v)
                            && v.getBrain().getMemory(MemoryModuleType.HOME).filter(b -> b.dimension().equals(level.dimension())
                            && b.pos().distSqr(home.pos()) <= 32 * 32 && level.isLoaded(b.pos())
                            && level.getBlockState(b.pos()).is(BlockTags.BEDS)).isPresent());
            neighbors.sort(Comparator.comparingDouble(apprentice::distanceToSqr));
            for (var supplier : neighbors) {
                var seller = inventories.inventory(supplier.getUUID());
                if (!hasSurplus(level, supplier, seller, lot)) continue;
                if (apprentice.distanceToSqr(supplier) > 16 || !apprentice.hasLineOfSight(supplier)) {
                    var path = apprentice.getNavigation().createPath(supplier, 1);
                    if (path != null && path.canReach() && apprentice.getNavigation().moveTo(path, .5)) return true;
                    continue;
                }
                if (exchange(inventories, seller, stock, lot, price)) return true;
            }
        }
        return false;
    }

    static boolean hasSurplus(ServerLevel level, Villager supplier, VillagerWorkInventory seller, ItemStack lot) {
        return hasSurplus(level, supplier, seller, lot, false);
    }

    static boolean hasSurplus(ServerLevel level, Villager supplier, VillagerWorkInventory seller, ItemStack lot,
                              boolean allowGatheredBuildingMaterial) {
        int count = seller.countMatchingItem(lot);
        if (count < lot.getCount()) return false;
        var key = StockVariantKey.fromStack(lot, level.registryAccess());
        var state = VillagerWorkSavedData.forServer(level.getServer()).get(supplier.getUUID()).orElse(null);
        if (state != null) {
            if (state.activeWork().isPresent()) {
                var active = state.activeWork().get();
                if (!allowGatheredBuildingMaterial || active.source() != dev.totem.villagers.work.WorkSource.WORLD) return false;
                var order = dev.totem.villagers.work.WorkOrderDefinitions.catalog().snapshot().get(active.orderId());
                if (order == null || order.requiredInputs().stream().anyMatch(input -> input.itemId().equals(key.itemId()))) return false;
            }
            count -= state.merchantStock().getOrDefault(key.itemId(), 0) + state.variantMerchantStock().getOrDefault(key, 0);
        }
        var own = VillagerWorkshopRuntime.materialDemandFor(level, supplier).orElse(null);
        if (own != null) count -= own.requiredInputs().stream().filter(i -> i.itemId().equals(key.itemId())).mapToInt(i -> i.count()).sum();
        return count >= lot.getCount();
    }

    static boolean exchange(VillagerWorkInventorySavedData inventories, VillagerWorkInventory seller,
                            VillagerWorkInventory buyer, ItemStack goods, ItemStack price) {
        synchronized (inventories) {
            var reserved = seller.reserveExactMatchingItem(goods).orElse(null);
            if (reserved == null) return false;
            var money = buyer.reserveExactMatchingItem(price).orElse(null);
            if (money == null || !buyer.canInsertExact(goods) || !seller.canInsertExact(price)) {
                reserved.rollback();
                if (money != null) money.rollback();
                return false;
            }
            reserved.commitWithReturn(price);
            money.commitWithReturn(goods);
            return true;
        }
    }

    private WorkshopLocalMarket() {}
}
