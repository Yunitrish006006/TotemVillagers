package dev.totem.villagers.trade;

import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

/** Identifies the direction of vanilla villager trades from their result stack. */
public final class VillagerOfferSides {
    private VillagerOfferSides() {
    }

    /**
     * Vanilla profession offers that return emeralds are villager purchase orders:
     * the player supplies the displayed cost and the villager buys it. Every other
     * generated offer is a villager sell order and must be backed by produced stock.
     */
    public static boolean isVillagerSellOffer(MerchantOffer offer) {
        return !offer.getResult().is(Items.EMERALD);
    }
}
