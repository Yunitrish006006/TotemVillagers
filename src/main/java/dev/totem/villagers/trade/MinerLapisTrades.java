package dev.totem.villagers.trade;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** The always-visible lapis anchor among a Miner's dynamic physical material rows. */
public final class MinerLapisTrades {
    public static final int EMERALD_PRICE = 1;
    public static final int LAPIS_PER_SALE = 3;
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP_PER_SALE = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;

    private MinerLapisTrades() {
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return offer != null
                && offer.getResult().is(Items.LAPIS_LAZULI)
                && offer.getResult().getCount() == LAPIS_PER_SALE
                && offer.getBaseCostA().is(Items.EMERALD)
                && offer.getBaseCostA().getCount() == EMERALD_PRICE
                && offer.getItemCostB().isEmpty()
                && offer.getMaxUses() == MAX_USES
                && offer.getPriceMultiplier() == PRICE_MULTIPLIER;
    }

    public static void ensureOffer(MerchantOffers offers) {
        if (offers.stream().noneMatch(MinerLapisTrades::isManagedOffer)) {
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, EMERALD_PRICE), result(), MAX_USES,
                    VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER));
        }
    }

    public static OfferStockDecision decision(MerchantOffer offer, VillagerWorkInventory inventory) {
        if (!isManagedOffer(offer)) {
            return OfferStockDecision.UNMAPPED;
        }
        return inventory.countMatchingItem(result()) >= LAPIS_PER_SALE
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }

    public static boolean debit(VillagerWorkInventory inventory) {
        return inventory.takeExactMatchingItem(result()).isPresent();
    }

    public static ItemStack result() {
        return new ItemStack(Items.LAPIS_LAZULI, LAPIS_PER_SALE);
    }
}
