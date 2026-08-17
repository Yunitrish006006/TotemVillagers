package dev.totem.villagers.trade;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.needs.VillagerNutrition;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Optional;

/**
 * Farm produce is sold only from the Farmer's physical work inventory. The
 * batches keep basic crops inexpensive without turning a single harvest into
 * an unlimited emerald source.
 */
public final class FarmerGatheredCropTrades {
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP_PER_SALE = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;
    private static final List<CropSale> SALES = List.of(
            sale(Items.WHEAT, 20), sale(Items.WHEAT_SEEDS, 32), sale(Items.CARROT, 20),
            sale(Items.POTATO, 20), sale(Items.POISONOUS_POTATO, 16), sale(Items.BEETROOT, 15),
            sale(Items.BEETROOT_SEEDS, 32)
    );

    private FarmerGatheredCropTrades() {
    }

    /** Adds stocked crop rows and removes only this module's depleted rows. */
    public static void syncOffers(MerchantOffers offers, VillagerWorkInventory inventory) {
        offers.removeIf(offer -> matchingSale(offer).filter(sale -> {
            boolean depleted = saleableCount(inventory, sale.result()) < sale.count();
            if (depleted) {
                offer.setToOutOfStock();
            }
            return depleted;
        }).isPresent());
        for (CropSale sale : SALES) {
            if (saleableCount(inventory, sale.result()) >= sale.count()
                    && offers.stream().noneMatch(sale::matches)) {
                offers.add(sale.offer());
            }
        }
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return matchingSale(offer).isPresent();
    }

    public static boolean isManagedResult(ItemStack result) {
        return result != null && !result.isEmpty() && SALES.stream().anyMatch(sale -> result.is(sale.item()));
    }

    public static OfferStockDecision decision(MerchantOffer offer, VillagerWorkInventory inventory) {
        return matchingSale(offer)
                .map(sale -> saleableCount(inventory, sale.result()) >= sale.count()
                        ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK)
                .orElse(OfferStockDecision.UNMAPPED);
    }

    public static boolean debit(MerchantOffer offer, VillagerWorkInventory inventory) {
        return matchingSale(offer).map(CropSale::result).flatMap(inventory::takeExactMatchingItem).isPresent();
    }

    public static List<MerchantOffer> catalogOffers() {
        return SALES.stream().map(CropSale::offer).toList();
    }

    private static Optional<CropSale> matchingSale(MerchantOffer offer) {
        return SALES.stream().filter(sale -> sale.matches(offer)).findFirst();
    }

    private static CropSale sale(Item item, int count) {
        return new CropSale(item, count);
    }

    private static int saleableCount(VillagerWorkInventory inventory, ItemStack result) {
        int available = inventory.countMatchingItem(result);
        int nutrition = VillagerNutrition.nutrition(result);
        if (nutrition < 1) {
            return available;
        }
        int totalNutrition = inventory.snapshot().stream()
                .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount())
                .sum();
        return Math.min(available,
                Math.max(0, totalNutrition - VillagerNutrition.MAX_FOOD_LEVEL) / nutrition);
    }

    private record CropSale(Item item, int count) {
        private ItemStack result() {
            return new ItemStack(item, count);
        }

        private MerchantOffer offer() {
            return new MerchantOffer(new ItemCost(Items.EMERALD, 1), result(), MAX_USES,
                    VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER);
        }

        private boolean matches(MerchantOffer offer) {
            return offer != null
                    && offer.getResult().is(item)
                    && offer.getResult().getCount() == count
                    && offer.getBaseCostA().is(Items.EMERALD)
                    && offer.getBaseCostA().getCount() == 1
                    && offer.getItemCostB().isEmpty()
                    && offer.getMaxUses() == MAX_USES
                    && offer.getPriceMultiplier() == PRICE_MULTIPLIER;
        }
    }
}
