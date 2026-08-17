package dev.totem.villagers.trade;

import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Exact, stock-backed Librarian rows for real enchanting-table equipment output. */
public final class LibrarianEnchantedEquipmentTrades {
    public static final int MAX_ACTIVE_OFFERS = 12;
    private static final int MAX_USES_PER_PRODUCED_ITEM = 1;
    private static final int VILLAGER_XP_PER_SALE = 10;

    private LibrarianEnchantedEquipmentTrades() {
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return offer != null
                && LibrarianEnchantingEquipmentRules.definitionFor(offer.getResult()).isPresent()
                && offer.getResult().isEnchanted()
                && offer.getBaseCostA().is(Items.EMERALD)
                && offer.getItemCostB().isEmpty()
                && offer.getMaxUses() == MAX_USES_PER_PRODUCED_ITEM
                && offer.getPriceMultiplier() == 0.0F;
    }

    /** Removes the randomized vanilla rows from their old professions and from Librarians. */
    public static void removeLegacyOffers(MerchantOffers offers) {
        offers.removeIf(offer -> LibrarianEnchantingEquipmentRules.definitionFor(offer.getResult()).isPresent()
                && offer.getResult().isEnchanted() && !isManagedOffer(offer));
    }

    public static void pruneEmptyOffers(MerchantOffers offers, MerchantStock stock, HolderLookup.Provider registries) {
        offers.removeIf(offer -> isManagedOffer(offer)
                && stock.available(StockVariantKey.fromStack(offer.getResult(), registries)) < offer.getResult().getCount());
    }

    public static boolean hasCapacity(MerchantOffers offers, MerchantStock stock, HolderLookup.Provider registries) {
        pruneEmptyOffers(offers, stock, registries);
        return offers.stream().filter(LibrarianEnchantedEquipmentTrades::isManagedOffer).count() < MAX_ACTIVE_OFFERS;
    }

    public static boolean registerProducedOffer(
            MerchantOffers offers, MerchantStock stock, ItemStack equipment, HolderLookup.Provider registries
    ) {
        if (!equipment.isEnchanted() || LibrarianEnchantingEquipmentRules.definitionFor(equipment).isEmpty()) {
            return false;
        }
        pruneEmptyOffers(offers, stock, registries);
        if (offers.stream().filter(LibrarianEnchantedEquipmentTrades::isManagedOffer)
                .anyMatch(existing -> ItemStack.isSameItemSameComponents(existing.getResult(), equipment))) {
            return true;
        }
        if (offers.stream().filter(LibrarianEnchantedEquipmentTrades::isManagedOffer).count() >= MAX_ACTIVE_OFFERS) {
            return false;
        }
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, LibrarianEnchantingEquipmentRules.emeraldPrice(equipment)),
                equipment.copyWithCount(1), MAX_USES_PER_PRODUCED_ITEM, VILLAGER_XP_PER_SALE, 0.0F));
        return true;
    }

    public static OfferStockDecision decision(MerchantOffer offer, VillagerWorkState state, HolderLookup.Provider registries) {
        if (!isManagedOffer(offer)) {
            return OfferStockDecision.UNMAPPED;
        }
        StockVariantKey key = StockVariantKey.fromStack(offer.getResult(), registries);
        int available = key.isBaseItem()
                ? state.merchantStock().getOrDefault(key.itemId(), 0)
                : state.variantMerchantStock().getOrDefault(key, 0);
        return available >= offer.getResult().getCount()
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }
}
