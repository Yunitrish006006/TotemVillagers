package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One physical stock authority shared by every renewable world producer.
 * World work used to consult the legacy merchant-stock counter, even though
 * modern production is stored only in the villager's 27 real slots. That made
 * a worker continue until the inventory filled and its tool broke. This class
 * makes production stop at a finite reserve and restart only after stock was
 * eaten, sold, or consumed by another profession.
 */
public final class VillageProductionStockPolicy {
    public static final int MARKET_RADIUS = 32;
    /**
     * Internal buyers request at least 25 nutrition, but a ration must contain
     * whole items. Across vanilla foods the largest rounded bundle is 32
     * nutrition (four 8-nutrition foods), so reserving 32 per consumer keeps a
     * producer able to satisfy simultaneous buyers without unbounded stock.
     */
    public static final int FOOD_RATION_RESERVE_NUTRITION = 32;
    public static final int MAX_RESERVED_RATIONS = 8;
    public static final int FISHING_STRING_RESERVE = 16;
    public static final int FISHING_ROD_RESERVE = 2;
    public static final int INCIDENTAL_ITEM_RESERVE = 64;
    public static final int LUMBERJACK_SAPLING_RESERVE = 8;

    private VillageProductionStockPolicy() {
    }

    /** Returns true only when one more physical output is both useful and storable. */
    public static boolean needsWorldWork(ServerLevel level, Villager worker,
                                         VillagerWorkInventory inventory, WorkOrder order) {
        if (!order.outputComponentPatch().isBlank()) {
            return false;
        }
        Item output = item(order.output().itemId());
        if (output == null) {
            return false;
        }
        ItemStack produced = new ItemStack(output, order.output().count());
        if (!inventory.canInsertExact(produced) || !belowOrderCap(inventory, order, output)) {
            return false;
        }
        int nutrition = VillagerNutrition.nutrition(produced.copyWithCount(1));
        return nutrition < 1 || storedNutrition(inventory) < foodTargetNutrition(nearbyAdults(level, worker));
    }

    /**
     * One producer carries a post-meal reserve plus one rounded ration for every nearby adult, including itself.
     * The self ration matters because the producer may eat at the start of the same market pulse before buyers run.
     */
    public static int foodTargetNutrition(int nearbyAdults) {
        int consumers = Math.max(0, Math.min(MAX_RESERVED_RATIONS, nearbyAdults));
        return VillagerNutrition.MAX_FOOD_LEVEL + consumers * FOOD_RATION_RESERVE_NUTRITION;
    }

    public static int storedNutrition(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream()
                .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount())
                .sum();
    }

    /**
     * Fishing keeps food for the bounded food market, at most two usable spare
     * rods, and a small string batch that the Toolsmith can buy. Other junk is
     * still rolled by the live loot table but is deliberately left at the water.
     */
    public static boolean mayRetainFishingBycatch(ServerLevel level, Villager fisherman,
                                                   VillagerWorkInventory inventory, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (VillagerNutrition.nutrition(stack) > 0) {
            return storedNutrition(inventory) < foodTargetNutrition(nearbyAdults(level, fisherman));
        }
        if (stack.is(Items.FISHING_ROD)) {
            return countItem(inventory, Items.FISHING_ROD) < FISHING_ROD_RESERVE;
        }
        return stack.is(Items.STRING) && countItem(inventory, Items.STRING) < FISHING_STRING_RESERVE;
    }

    /** Caps chance-generated mine products that are not what prompted the stone job. */
    public static ItemStack boundedIncidentalDrop(VillagerWorkInventory inventory, ItemStack drop) {
        if (drop == null || drop.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int room = INCIDENTAL_ITEM_RESERVE - countItem(inventory, drop.getItem());
        return room < 1 ? ItemStack.EMPTY : drop.copyWithCount(Math.min(room, drop.getCount()));
    }

    /** Uses the first matching live drop for replanting when present and retains only a finite nursery reserve. */
    public static java.util.List<ItemStack> boundedLumberjackReturns(VillagerWorkInventory inventory,
                                                                     java.util.List<ItemStack> returns,
                                                                     Item replacementItem) {
        java.util.List<ItemStack> bounded = new java.util.ArrayList<>();
        int saplingRoom = Math.max(0, LUMBERJACK_SAPLING_RESERVE - countItem(inventory, replacementItem));
        boolean paidReplant = false;
        for (ItemStack stack : returns) {
            if (!stack.is(replacementItem)) {
                bounded.add(stack.copy());
                continue;
            }
            int retained = stack.getCount();
            if (!paidReplant && retained > 0) {
                retained--;
                paidReplant = true;
            }
            retained = Math.min(retained, saplingRoom);
            if (retained > 0) {
                bounded.add(stack.copyWithCount(retained));
                saplingRoom -= retained;
            }
        }
        return java.util.List.copyOf(bounded);
    }

    private static int nearbyAdults(ServerLevel level, Villager worker) {
        double rangeSquared = (double) MARKET_RADIUS * MARKET_RADIUS;
        return (int) LoadedVillagerCache.loaded(level).stream()
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby()
                        && candidate.distanceToSqr(worker) <= rangeSquared)
                .count();
    }

    private static boolean belowOrderCap(VillagerWorkInventory inventory, WorkOrder order, Item output) {
        int current = countItem(inventory, output);
        return current <= order.stockCap() - order.output().count();
    }

    public static int countItem(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        Item item = identifier == null ? null : BuiltInRegistries.ITEM.getValue(identifier);
        return item == Items.AIR ? null : item;
    }
}
