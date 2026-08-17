package dev.totem.villagers.trade;

import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

/**
 * Dynamic librarian offers are distinguished from generated vanilla offers by
 * their single stock-backed use and zero demand multiplier. The result's full
 * component key is the only stock identity and its deterministic price source.
 */
public final class LibrarianEnchantedBookTrades {
    public static final int MAX_ACTIVE_OFFERS = 12;
    private static final int MAX_USES_PER_PRODUCED_BOOK = 1;
    private static final int VILLAGER_XP_PER_SALE = 10;

    private LibrarianEnchantedBookTrades() {
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return offer != null
                && offer.getResult().is(Items.ENCHANTED_BOOK)
                && offer.getBaseCostA().is(Items.EMERALD)
                && offer.getCostB().is(Items.BOOK)
                && offer.getMaxUses() == MAX_USES_PER_PRODUCED_BOOK
                && offer.getPriceMultiplier() == 0.0F;
    }

    /** Enforced mode removes pre-generated vanilla enchanted-book offers: only real outputs may be sold. */
    public static void removeLegacyOffers(MerchantOffers offers) {
        offers.removeIf(offer -> offer.getResult().is(Items.ENCHANTED_BOOK) && !isManagedOffer(offer));
    }

    /** Removes rows whose matching physical work-backed stock has been sold. */
    public static void pruneEmptyOffers(MerchantOffers offers, MerchantStock stock, HolderLookup.Provider registries) {
        offers.removeIf(offer -> isManagedOffer(offer)
                && stock.available(StockVariantKey.fromStack(offer.getResult(), registries)) < offer.getResult().getCount());
    }

    public static boolean hasCapacity(MerchantOffers offers, MerchantStock stock, HolderLookup.Provider registries) {
        pruneEmptyOffers(offers, stock, registries);
        return offers.stream().filter(LibrarianEnchantedBookTrades::isManagedOffer).count() < MAX_ACTIVE_OFFERS;
    }

    /** Registers the exact book only after its table action has validated it. */
    public static boolean registerProducedOffer(
            MerchantOffers offers, MerchantStock stock, ItemStack book, HolderLookup.Provider registries
    ) {
        if (book.isEmpty() || !book.is(Items.ENCHANTED_BOOK)) {
            return false;
        }
        pruneEmptyOffers(offers, stock, registries);
        if (offers.stream().filter(LibrarianEnchantedBookTrades::isManagedOffer)
                .anyMatch(existing -> ItemStack.isSameItemSameComponents(existing.getResult(), book))) {
            return true;
        }
        if (offers.stream().filter(LibrarianEnchantedBookTrades::isManagedOffer).count() >= MAX_ACTIVE_OFFERS) {
            return false;
        }
        offers.add(new MerchantOffer(
                new ItemCost(Items.EMERALD, LibrarianEnchantingRules.emeraldPrice(book)),
                Optional.of(new ItemCost(Items.BOOK, 1)), book.copyWithCount(1),
                MAX_USES_PER_PRODUCED_BOOK, VILLAGER_XP_PER_SALE, 0.0F
        ));
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
