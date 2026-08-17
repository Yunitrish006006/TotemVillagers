package dev.totem.villagers.trade;

import dev.totem.villagers.work.RemnantBackpackOrders;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Optional;

/** Player-facing purchase rows that make optional Remnant backpack work reachable through vanilla trading only. */
public final class ToolsmithBackpackMaterialTrades {
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;
    private static final List<MaterialPurchase> PURCHASES = List.of(
            new MaterialPurchase("minecraft:bundle", 2, 1),
            new MaterialPurchase("minecraft:leather", 4, 1),
            new MaterialPurchase("minecraft:netherite_upgrade_smithing_template", 1, 8),
            new MaterialPurchase("minecraft:netherite_ingot", 1, 8)
    );

    private ToolsmithBackpackMaterialTrades() {
    }

    public static void syncOffers(MerchantOffers offers, ServerLevel level) {
        boolean installed = WorkOrderCatalogs.effectiveFor(
                WorkOrderDefinitions.catalog(), "minecraft:toolsmith", offers, level)
                .snapshot().values().stream().anyMatch(RemnantBackpackOrders::isBackpackOrder);
        if (!installed) {
            return;
        }
        for (MaterialPurchase purchase : PURCHASES) {
            Item item = item(purchase.itemId());
            if (item != null && offers.stream().noneMatch(offer -> purchase.matches(offer, item))) {
                offers.add(purchase.offer(item));
            }
        }
    }

    public static List<MerchantOffer> catalogOffers(ServerLevel level) {
        boolean installed = WorkOrderCatalogs.effectiveFor(
                WorkOrderDefinitions.catalog(), "minecraft:toolsmith", null, level)
                .snapshot().values().stream().anyMatch(RemnantBackpackOrders::isBackpackOrder);
        if (!installed) {
            return List.of();
        }
        return PURCHASES.stream().map(purchase -> {
            Item item = item(purchase.itemId());
            return item == null ? null : purchase.offer(item);
        }).filter(java.util.Objects::nonNull).toList();
    }

    private static Item item(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        return item == null || item == Items.AIR ? null : item;
    }

    private record MaterialPurchase(String itemId, int inputCount, int emeraldPayout) {
        private MerchantOffer offer(Item item) {
            int maximumStack = new ItemStack(item).getMaxStackSize();
            if (inputCount > maximumStack * 2) {
                throw new IllegalStateException("Material purchase exceeds both merchant input slots: " + itemId);
            }
            int firstCount = Math.min(inputCount, maximumStack);
            Optional<ItemCost> secondCost = inputCount > firstCount
                    ? Optional.of(new ItemCost(item, inputCount - firstCount))
                    : Optional.empty();
            return new MerchantOffer(new ItemCost(item, firstCount), secondCost,
                    new ItemStack(Items.EMERALD, emeraldPayout), MAX_USES, VILLAGER_XP, PRICE_MULTIPLIER);
        }

        private boolean matches(MerchantOffer offer, Item item) {
            return offer != null && offer.getResult().is(Items.EMERALD)
                    && offer.getResult().getCount() == emeraldPayout
                    && offer.getBaseCostA().is(item)
                    && (offer.getCostB().isEmpty() || offer.getCostB().is(item))
                    && offer.getBaseCostA().getCount() + offer.getCostB().getCount() == inputCount;
        }
    }
}
