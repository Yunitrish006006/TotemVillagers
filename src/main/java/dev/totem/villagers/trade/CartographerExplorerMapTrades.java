package dev.totem.villagers.trade;

import dev.totem.villagers.work.CartographerExplorerMapRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

/** Exact component-backed rows for Cartographer-produced explorer maps. */
public final class CartographerExplorerMapTrades {
    public static final int MAX_ACTIVE_OFFERS = 12;
    private static final int MAX_USES_PER_PRODUCED_MAP = 1;

    private CartographerExplorerMapTrades() {
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return offer != null
                && offer.getResult().is(Items.FILLED_MAP)
                && offer.getResult().get(DataComponents.MAP_ID) != null
                && offer.getBaseCostA().is(Items.EMERALD)
                && CartographerExplorerMapRules.isExplorerPrice(offer.getBaseCostA().getCount())
                && offer.getCostB().is(Items.COMPASS)
                && offer.getMaxUses() == MAX_USES_PER_PRODUCED_MAP
                && offer.getPriceMultiplier() == 0.0F;
    }

    /** Replaces vanilla pre-generated map rows with only maps this Cartographer actually made. */
    public static void removeLegacyOffers(MerchantOffers offers) {
        offers.removeIf(offer -> offer.getResult().is(Items.FILLED_MAP)
                && offer.getResult().get(DataComponents.MAP_ID) != null
                && !isManagedOffer(offer));
    }

    public static void pruneEmptyOffers(MerchantOffers offers, MerchantStock stock, HolderLookup.Provider registries) {
        offers.removeIf(offer -> isManagedOffer(offer)
                && stock.available(StockVariantKey.fromStack(offer.getResult(), registries)) < offer.getResult().getCount());
    }

    public static boolean hasCapacity(MerchantOffers offers) {
        return offers.stream().filter(CartographerExplorerMapTrades::isManagedOffer).count() < MAX_ACTIVE_OFFERS;
    }

    /** Publishes the exact generated map before its matching variant stock is committed. */
    public static boolean registerProducedOffer(
            MerchantOffers offers, ItemStack map, CartographerExplorerMapRules.ExplorerMapDefinition definition
    ) {
        if (map.isEmpty() || !map.is(Items.FILLED_MAP) || map.get(DataComponents.MAP_ID) == null || !hasCapacity(offers)) {
            return false;
        }
        if (offers.stream().filter(CartographerExplorerMapTrades::isManagedOffer)
                .anyMatch(existing -> ItemStack.isSameItemSameComponents(existing.getResult(), map))) {
            return true;
        }
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, definition.emeraldPrice()),
                Optional.of(new ItemCost(Items.COMPASS, 1)), map.copyWithCount(1), MAX_USES_PER_PRODUCED_MAP,
                definition.villagerXp(), 0.0F));
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
