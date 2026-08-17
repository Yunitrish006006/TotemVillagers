package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.trade.OfferStockDecision;
import dev.totem.villagers.trade.TradeStockPolicy;
import dev.totem.villagers.work.FletcherTippedArrowOrders;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.workshop.FletcherTippedArrowWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Covers component-bound Fletcher tipped-arrow production and stock gating. */
public final class FletcherTippedArrowGameTest {
    @GameTest(maxTicks = 40)
    public void fletcherConsumesOnlyTheMatchingLingeringPotionForTippedArrows(GameTestHelper helper) {
        ItemStack offeredArrows = PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.SWIFTNESS).copyWithCount(5);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 5), offeredArrows, 12, 1, .2F);
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer);
        WorkOrder order = FletcherTippedArrowOrders.orderFor(offer, helper.getLevel()).orElseThrow();
        StockVariantKey offeredKey = StockVariantKey.fromStack(offeredArrows, helper.getLevel().registryAccess());
        require(helper, order.outputKey().equals(offeredKey) && order.output().count() == 8,
                "Tipped-arrow order did not retain the live potion variant or vanilla batch size");

        WorkOrderCatalog catalog = FletcherTippedArrowOrders.extend(new WorkOrderCatalog(List.of()), offers, helper.getLevel());
        VillagerWorkState stocked = new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-000000000b01"), Map.of(), Map.of(offeredKey, 8),
                Optional.empty(), Optional.empty(), Optional.empty());
        require(helper, new TradeStockPolicy().decide(WorkBackedTradingMode.ENFORCED, catalog, stocked,
                offeredArrows, helper.getLevel().registryAccess()) == OfferStockDecision.AVAILABLE,
                "Exact tipped-arrow variant was not sellable from component-bound stock");
        ItemStack otherArrows = PotionContents.createItemStack(Items.TIPPED_ARROW, Potions.STRONG_SWIFTNESS).copyWithCount(5);
        require(helper, new TradeStockPolicy().decide(WorkBackedTradingMode.ENFORCED, catalog, stocked,
                otherArrows, helper.getLevel().registryAccess()) == OfferStockDecision.UNMAPPED,
                "Different tipped-arrow potion variant reused the offered stock");

        BlockPos relativeJobSite = new BlockPos(2, 2, 2);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.FLETCHING_TABLE);
        Villager fletcher = spawnVillager(helper, relativeJobSite.above());
        try {
            setFletcher(fletcher);
            ItemStack matchingPotion = FletcherTippedArrowOrders.lingeringPotionFor(offer).orElseThrow();
            ItemStack wrongPotion = PotionContents.createItemStack(Items.LINGERING_POTION, Potions.STRONG_SWIFTNESS);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(fletcher.getUUID());
            require(helper, inventory.insertExact(wrongPotion) && inventory.insertExact(matchingPotion)
                            && inventory.insertExact(new ItemStack(Items.ARROW, 8)),
                    "Could not seed Fletcher personal inventory for tipped-arrow work");
            require(helper, FletcherTippedArrowOrders.canReserve(order, inventory, offers, helper.getLevel()),
                    "Fletcher could not identify the exact lingering potion and eight arrows");
            FletcherTippedArrowWorkshopAction action = new FletcherTippedArrowWorkshopAction(
                    helper.getLevel(), fletcher, jobSite, order, offers
            );
            require(helper, FletcherTippedArrowWorkshopAction.supports(order, helper.getLevel(), jobSite, offers),
                    "Tipped-arrow order was not accepted at a loaded Fletching Table");
            MerchantStock stock = new MerchantStock();
            WorkshopCommitResult result = new WorkshopCommitService().complete(
                    FletcherTippedArrowOrders.reserve(order, inventory, offers, helper.getLevel()).orElseThrow(),
                    order, stock, action
            );
            require(helper, result == WorkshopCommitResult.COMPLETED,
                    "Fletcher tipped-arrow work did not complete: " + result);
            require(helper, stock.available(order.outputKey()) == 8,
                    "Tipped-arrow work did not credit exactly the vanilla eight-arrow output");
            require(helper, inventory.snapshot().stream()
                            .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, wrongPotion)),
                    "Fletcher consumed a lingering potion with the wrong effect");
            require(helper, inventory.snapshot().stream()
                            .noneMatch(stack -> ItemStack.isSameItemSameComponents(stack, matchingPotion)
                                    || stack.is(Items.ARROW)),
                    "Fletcher did not consume precisely the matching potion and eight arrows");
            helper.succeed();
        } finally {
            fletcher.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setFletcher(Villager villager) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "fletcher")
        );
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:fletcher profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
