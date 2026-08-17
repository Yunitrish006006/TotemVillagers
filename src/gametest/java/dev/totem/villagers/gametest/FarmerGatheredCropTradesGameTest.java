package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.trade.FarmerGatheredCropTrades;
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

/** Ensures farmer crop rows appear only for crops actually harvested into the personal work inventory. */
public final class FarmerGatheredCropTradesGameTest {
    @GameTest(maxTicks = 40)
    public void harvestedCropsBecomeDynamicPhysicalStock(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        Villager farmer = spawnFarmer(helper, new BlockPos(4, 2, 4));
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            MerchantOffers offers = new MerchantOffers();
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.BREAD, 4)),
                    "Could not seed the Farmer survival-food reserve");
            require(helper, inventory.insertExact(new ItemStack(Items.CARROT, 20)),
                    "Could not seed harvested carrots in the Farmer work inventory");
            require(helper, inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS, 32)),
                    "Could not seed harvested wheat seeds in the Farmer work inventory");

            VillagerTradeStockAuthority.refreshOffers(farmer, offers);
            MerchantOffer carrots = offers.stream().filter(FarmerGatheredCropTrades::isManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.CARROT)).findFirst().orElse(null);
            require(helper, carrots != null && carrots.getResult().getCount() == 20
                            && carrots.getBaseCostA().is(Items.EMERALD) && carrots.getBaseCostA().getCount() == 1,
                    "Farmer did not publish the 20-carrots-for-one-emerald physical-stock row");
            MerchantOffer wheatSeeds = offers.stream().filter(FarmerGatheredCropTrades::isManagedOffer)
                    .filter(offer -> offer.getResult().is(Items.WHEAT_SEEDS)).findFirst().orElse(null);
            require(helper, wheatSeeds != null && wheatSeeds.getResult().getCount() == 32
                            && wheatSeeds.getBaseCostA().is(Items.EMERALD) && wheatSeeds.getBaseCostA().getCount() == 1,
                    "Farmer did not publish a physical surplus-seed sale row");

            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(farmer, carrots);
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(farmer, wheatSeeds);
            require(helper, count(inventory, Items.CARROT) == 0 && count(inventory, Items.WHEAT_SEEDS) == 0
                            && count(inventory, Items.BREAD) == 4
                            && !offers.contains(carrots) && !offers.contains(wheatSeeds),
                    "Farmer crop sale did not debit the harvest batch while preserving survival food");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(farmer.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(farmer.getUUID());
            farmer.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnFarmer(GameTestHelper helper, BlockPos position) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        require(helper, profession != null, "Missing minecraft:farmer profession");
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
