package dev.totem.villagers.trade;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** The fixed empty-bucket row supplied only by a Toolsmith's real work stock. */
public final class ToolsmithBucketTrades {
    public static final int EMERALD_PRICE = 2;
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP_PER_SALE = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;

    private ToolsmithBucketTrades() {
    }

    public static boolean isManagedOffer(MerchantOffer offer) {
        return offer != null
                && offer.getResult().is(Items.BUCKET)
                && offer.getResult().getCount() == 1
                && offer.getBaseCostA().is(Items.EMERALD)
                && offer.getBaseCostA().getCount() == EMERALD_PRICE
                && offer.getItemCostB().isEmpty()
                && offer.getMaxUses() == MAX_USES
                && offer.getPriceMultiplier() == PRICE_MULTIPLIER;
    }

    /** Adds the fixed row only while a physical bucket exists, and removes it immediately when depleted. */
    public static void syncOffer(MerchantOffers offers, VillagerWorkInventory inventory) {
        offers.removeIf(offer -> isManagedOffer(offer)
                && inventory.countMatchingItem(new ItemStack(Items.BUCKET)) < 1);
        if (inventory.countMatchingItem(new ItemStack(Items.BUCKET)) >= 1
                && offers.stream().noneMatch(ToolsmithBucketTrades::isManagedOffer)) {
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, EMERALD_PRICE), new ItemStack(Items.BUCKET),
                    MAX_USES, VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER));
        }
    }

    public static MerchantOffer catalogOffer() {
        return new MerchantOffer(new ItemCost(Items.EMERALD, EMERALD_PRICE), new ItemStack(Items.BUCKET),
                MAX_USES, VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER);
    }
}
