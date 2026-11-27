package dev.totem.villagers.trade;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.HashMap;
import java.util.Map;

/** Ordinary workshops may sell unused plain ingredients after keeping enough for one production batch. */
final class ProductionMaterialTrades {
    private static final int USES = 16;
    private static final float MULTIPLIER = .05F;
    private static WorkOrderCatalog indexed;
    private static final Map<String, java.util.Set<String>> OUTPUTS = new HashMap<>();
    private static final Map<String, Map<String, Integer>> INPUTS = new HashMap<>();

    private static Map<String, Integer> materials(String profession) {
        var catalog = WorkOrderDefinitions.catalog();
        if (indexed != catalog) {
            INPUTS.clear();
            OUTPUTS.clear();
            for (var order : catalog.snapshot().values()) {
                OUTPUTS.computeIfAbsent(order.professionId(), ignored -> new java.util.HashSet<>()).add(order.output().itemId());
                var inputs = INPUTS.computeIfAbsent(order.professionId(), ignored -> new HashMap<>());
                ItemAmount.totals(order.requiredInputs()).forEach((id, count) -> inputs.merge(id, count, Math::max));
            }
            // Livestock and raw catches can be gathered even when no local recipe consumes them.
            var shepherd = INPUTS.computeIfAbsent("minecraft:shepherd", ignored -> new HashMap<>());
            for (String id : new String[]{"mutton", "porkchop", "chicken", "beef", "leather"})
                shepherd.putIfAbsent("minecraft:" + id, 0);
            var fisher = INPUTS.computeIfAbsent("minecraft:fisherman", ignored -> new HashMap<>());
            for (String id : new String[]{"cod", "salmon", "tropical_fish", "pufferfish", "string"})
                fisher.putIfAbsent("minecraft:" + id, 0);
            // Finished goods and field crops already have their own production prices and stock rules.
            for (var order : catalog.snapshot().values())
                INPUTS.get(order.professionId()).remove(order.output().itemId());
            indexed = catalog;
        }
        return INPUTS.getOrDefault(profession, Map.of());
    }

    static void removeRows(MerchantOffers offers) {
        offers.removeIf(offer -> {
            if (!isRow(offer)) return false;
            offer.setToOutOfStock();
            return true;
        });
    }

    static boolean isRow(MerchantOffer offer) {
        return ((ProductionMaterialRow)offer).totemVillagers$isMaterial();
    }

    private static boolean hasSurplus(Villager seller, int keep, int batch, VillagerWorkInventory inventory, ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.EMERALD) || stack.getMaxStackSize() <= 1
                || !ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))) return false;
        if (inventory.countMatchingItem(stack) - batch < keep + dev.totem.villagers.runtime.VillageMeeting.promisedMaterial(seller, stack)) return false;
        int nutrition = VillagerNutrition.nutrition(stack);
        return nutrition <= 0 || inventory.snapshot().stream().mapToInt(s -> VillagerNutrition.nutrition(s) * s.getCount()).sum()
                - nutrition * batch >= VillagerNutrition.MAX_FOOD_LEVEL;
    }

    private static VillagerTradeKnowledge.Lesson quote(Villager v, String profession, ItemStack stack) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (materials(profession).containsKey(id.toString()))
            return new VillagerTradeKnowledge.Lesson(id, 1, stack.is(Items.DIAMOND) ? 6 : 1);
        if (OUTPUTS.getOrDefault(profession, java.util.Set.of()).contains(id.toString())
                || profession.equals("minecraft:farmer") && FarmerGatheredCropTrades.isCrop(stack)
                || profession.equals("totem:miner") && GatheredMaterialTrades.isNativeProduct(v, stack, true)
                || profession.equals("totem:lumberjack") && GatheredMaterialTrades.isNativeProduct(v, stack, false)) return null;
        return VillagerTradeKnowledge.known(v).stream().filter(l -> l.item().equals(id)).findFirst().orElse(null);
    }

    static void sync(Villager v, String profession, MerchantOffers offers, VillagerWorkInventory inventory) {
        var inputs = materials(profession);
        offers.removeIf(offer -> {
            if (!isRow(offer) || decision(v, profession, inventory, offer) == OfferStockDecision.AVAILABLE) return false;
            offer.setToOutOfStock();
            return true;
        });
        if (inputs.isEmpty() && VillagerTradeKnowledge.known(v).isEmpty()) return;
        for (var stack : inventory.snapshot()) {
            if (stack.isEmpty()) continue;
            var terms = quote(v, profession, stack);
            if (terms == null || !hasSurplus(v, inputs.getOrDefault(terms.item().toString(), 0), terms.batch(), inventory, stack)
                    || offers.stream().anyMatch(o -> VillagerOfferSides.isVillagerSellOffer(o)
                    && ItemStack.isSameItemSameComponents(o.getResult(), stack))) continue;
            var row = new MerchantOffer(new ItemCost(Items.EMERALD, terms.price()), stack.copyWithCount(terms.batch()), USES, 1, MULTIPLIER);
            ((ProductionMaterialRow)row).totemVillagers$markMaterial();
            offers.add(row);
        }
    }

    static OfferStockDecision decision(Villager v, String profession, VillagerWorkInventory inventory, MerchantOffer row) {
        var result = row.getResult();
        var terms = quote(v, profession, result);
        if (terms == null || terms.batch() != result.getCount() || terms.price() != row.getBaseCostA().getCount()) return OfferStockDecision.UNMAPPED;
        return hasSurplus(v, materials(profession).getOrDefault(terms.item().toString(), 0), terms.batch(), inventory, result)
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }
}
