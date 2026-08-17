package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.trade.GatheredMaterialTrades;
import dev.totem.villagers.trade.LumberjackAppleTrades;
import dev.totem.villagers.trade.MinerLapisTrades;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.VillagerWorkSavedData;
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
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Ensures specialist material rows sell only items that their owner physically gathered. */
public final class SpecialistMaterialTradesGameTest {
    @GameTest(maxTicks = 40)
    public void minerLapisAndLumberjackApplesUseTheirPersonalMaterialStores(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        Villager miner = spawn(helper, new BlockPos(3, 2, 3), "totem:miner");
        Villager lumberjack = spawn(helper, new BlockPos(5, 2, 3), "totem:lumberjack");
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            MerchantOffers minerOffers = offers(miner);
            MerchantOffers lumberjackOffers = offers(lumberjack);
            VillagerTradeStockAuthority.refreshOffers(miner, minerOffers);
            VillagerTradeStockAuthority.refreshOffers(lumberjack, lumberjackOffers);
            MerchantOffer lapis = minerOffers.stream().filter(MinerLapisTrades::isManagedOffer).findFirst().orElse(null);
            MerchantOffer apples = lumberjackOffers.stream().filter(LumberjackAppleTrades::isManagedOffer).findFirst().orElse(null);
            require(helper, lapis == null && apples == null,
                    "Complete mode displayed specialist merchandise before physical work supplied it");

            VillagerWorkInventory minerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(miner.getUUID());
            VillagerWorkInventory lumberjackInventory = VillagerWorkInventorySavedData.forServer(server).inventory(lumberjack.getUUID());
            require(helper, minerInventory.insertExact(MinerLapisTrades.result()), "Could not seed Miner lapis material");
            require(helper, lumberjackInventory.insertExact(LumberjackAppleTrades.result()), "Could not seed Lumberjack apple material");
            require(helper, lumberjackInventory.insertExact(new ItemStack(Items.BREAD, 6)),
                    "Could not seed Lumberjack survival food reserve");
            require(helper, minerInventory.insertExact(new ItemStack(Items.COBBLESTONE, 16)),
                    "Could not seed Miner cobblestone material");
            require(helper, minerInventory.insertExact(new ItemStack(Items.DIAMOND)),
                    "Could not seed Miner diamond material");
            require(helper, minerInventory.insertExact(new ItemStack(Items.DRIPSTONE_BLOCK, 20)),
                    "Could not seed Miner fallback stone material");
            require(helper, lumberjackInventory.insertExact(new ItemStack(Items.OAK_LOG, 8)),
                    "Could not seed Lumberjack oak-log material");
            VillagerTradeStockAuthority.refreshOffers(miner, minerOffers);
            VillagerTradeStockAuthority.refreshOffers(lumberjack, lumberjackOffers);
            lapis = minerOffers.stream().filter(MinerLapisTrades::isManagedOffer).findFirst().orElse(null);
            apples = lumberjackOffers.stream().filter(LumberjackAppleTrades::isManagedOffer).findFirst().orElse(null);
            require(helper, lapis != null && apples != null && !lapis.isOutOfStock() && !apples.isOutOfStock(),
                    "Physical specialist materials did not unlock their matching sales");
            MerchantOffer cobblestone = minerOffers.stream().filter(GatheredMaterialTrades::isMinerManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.COBBLESTONE)).findFirst().orElse(null);
            MerchantOffer diamond = minerOffers.stream().filter(GatheredMaterialTrades::isMinerManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.DIAMOND)).findFirst().orElse(null);
            MerchantOffer oakLog = lumberjackOffers.stream().filter(GatheredMaterialTrades::isLumberjackManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.OAK_LOG)).findFirst().orElse(null);
            require(helper, cobblestone != null && cobblestone.getResult().getCount() == 16
                            && cobblestone.getBaseCostA().getCount() == 1,
                    "Miner did not publish the balanced 16-cobblestone-for-one-emerald row");
            require(helper, diamond != null && diamond.getResult().getCount() == 1
                            && diamond.getBaseCostA().getCount() == 6,
                    "Miner did not publish the balanced one-diamond-for-six-emerald row");
            require(helper, oakLog != null && oakLog.getResult().getCount() == 8
                            && oakLog.getBaseCostA().getCount() == 1,
                    "Lumberjack did not publish the balanced eight-oak-logs-for-one-emerald row");
            MerchantOffer fallback = minerOffers.stream().filter(GatheredMaterialTrades::isMinerManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.DRIPSTONE_BLOCK)).findFirst().orElse(null);
            require(helper, fallback != null && fallback.getBaseCostA().getCount() == 1 && fallback.getResult().getCount() == 16,
                    "Miner did not publish a fallback row for dripstone-block");

            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(miner, lapis);
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(lumberjack, apples);
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(miner, diamond);
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(lumberjack, oakLog);
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(miner, fallback);
            require(helper, minerInventory.countMatchingItem(MinerLapisTrades.result()) == 0
                            && lumberjackInventory.countMatchingItem(LumberjackAppleTrades.result()) == 0
                            && count(minerInventory, Items.DIAMOND) == 0 && count(lumberjackInventory, Items.OAK_LOG) == 0
                            && count(minerInventory, Items.DRIPSTONE_BLOCK) == 4,
                    "A specialist sale did not debit exactly its physical material batch");
            require(helper, lapis.isOutOfStock() && apples.isOutOfStock() && fallback.isOutOfStock(),
                    "A depleted specialist material row remained available");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(miner.getUUID());
            VillagerWorkSavedData.forServer(server).remove(lumberjack.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(miner.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(lumberjack.getUUID());
            miner.discard();
            lumberjack.discard();
        }
    }

    private static MerchantOffers offers(Villager villager) {
        MerchantOffers offers = new MerchantOffers();
        ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);
        return offers;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw helper.assertionException("Missing profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
}
