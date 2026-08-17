package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerNutritionSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.runtime.VillagerFoodEconomyRuntime;
import dev.totem.villagers.runtime.VillageProductionStockPolicy;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
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
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Verifies the autonomous food market moves physical food/currency and uses independent Totem hunger. */
public final class VillagerFoodEconomyGameTest {
    @GameTest(maxTicks = 20)
    public void foodSafetyStockIncludesProducerAndEveryNearbyAdult(GameTestHelper helper) {
        require(helper, VillageProductionStockPolicy.foodTargetNutrition(0) == VillagerNutrition.MAX_FOOD_LEVEL,
                "An isolated empty market did not retain the producer's post-meal reserve");
        require(helper, VillageProductionStockPolicy.foodTargetNutrition(4) == 148,
                "Four-adult market did not reserve one rounded ration per adult including the producer");
        require(helper, VillageProductionStockPolicy.foodTargetNutrition(99)
                        == VillagerNutrition.MAX_FOOD_LEVEL
                        + VillageProductionStockPolicy.MAX_RESERVED_RATIONS
                        * VillageProductionStockPolicy.FOOD_RATION_RESERVE_NUTRITION,
                "Food reserve did not remain bounded at the configured market cap");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void foodUsesVanillaNutritionAndSaturationValues(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(2, 2, 2));
        var server = helper.getLevel().getServer();
        try {
            VillagerNutritionSavedData data = VillagerNutritionSavedData.forServer(server);
            data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(0, 0.0F, 0.0F, 0));
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.BREAD)), "Could not seed one bread");

            require(helper, VillagerNutrition.consumeStoredFood(villager, inventory, new ItemStack(Items.BREAD)) == 1,
                    "Villager did not eat the physical bread");
            require(helper, VillagerNutrition.foodLevel(villager) == 5,
                    "Bread did not add its vanilla five nutrition points");
            require(helper, Math.abs(VillagerNutrition.saturationLevel(villager) - 5.0F) < 0.001F,
                    "Bread saturation was not capped to the resulting food level like player FoodData");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(server).drain(villager.getUUID());
            VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void exhaustionConsumesSaturationBeforeFood(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(2, 2, 2));
        var data = VillagerNutritionSavedData.forServer(helper.getLevel().getServer());
        try {
            data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(20, 2.0F, 4.01F, 0));
            VillagerNutrition.tick(villager);
            require(helper, VillagerNutrition.foodLevel(villager) == 20,
                    "Player-style metabolism spent food before saturation");
            require(helper, Math.abs(VillagerNutrition.saturationLevel(villager) - 1.0F) < 0.001F,
                    "One exhaustion threshold did not spend exactly one saturation point");

            data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(20, 0.0F, 4.01F, 0));
            VillagerNutrition.tick(villager);
            require(helper, VillagerNutrition.foodLevel(villager) == 19,
                    "Exhaustion did not spend food after saturation reached zero");
            helper.succeed();
        } finally {
            data.remove(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fullFoodRegeneratesHealthAndZeroFoodStarves(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(2, 2, 2));
        var data = VillagerNutritionSavedData.forServer(helper.getLevel().getServer());
        try {
            villager.setHealth(10.0F);
            data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(20, 6.0F, 0.0F, 0));
            for (int tick = 0; tick < 10; tick++) {
                VillagerNutrition.tick(villager);
            }
            require(helper, villager.getHealth() > 10.0F,
                    "Full food and saturation did not use the player's fast natural regeneration rule");
            require(helper, VillagerNutrition.exhaustionLevel(villager) >= 6.0F,
                    "Natural regeneration did not add player-style exhaustion");

            villager.setHealth(20.0F);
            data.setState(villager.getUUID(), new VillagerNutritionSavedData.NutritionState(0, 0.0F, 0.0F, 0));
            for (int tick = 0; tick < 80; tick++) {
                VillagerNutrition.tick(villager);
            }
            require(helper, villager.getHealth() < 20.0F,
                    "Zero food did not apply the player's 80-tick starvation damage");
            helper.succeed();
        } finally {
            data.remove(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void hungryVillagerPaysAFarmerForWorkBackedBread(GameTestHelper helper) {
        Villager buyer = spawnVillager(helper, new BlockPos(2, 2, 2));
        Villager farmer = spawnVillager(helper, new BlockPos(4, 2, 2));
        var server = helper.getLevel().getServer();
        try {
            setFarmer(farmer);
            MerchantOffer cropPurchase = new MerchantOffer(new ItemCost(Items.BEETROOT, 15),
                    new ItemStack(Items.EMERALD), 16, 2, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(cropPurchase);
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory farmerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            VillagerWorkInventory buyerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(buyer.getUUID());
            require(helper, farmerInventory.insertExact(new ItemStack(Items.BREAD, 10)), "Could not seed Farmer bread and reserve");
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.ENFORCED);
            VillagerNutrition.setFoodLevel(buyer, 0);
            require(helper, buyerInventory.insertExact(new ItemStack(Items.EMERALD, 2)), "Could not seed buyer emeralds");

            require(helper, VillagerFoodEconomyRuntime.tryPurchaseFromFarmer(helper.getLevel(), buyer, farmer),
                    "Hungry villager could not purchase Farmer bread with its own wallet");
            require(helper, VillagerNutrition.foodLevel(buyer) >= VillagerNutrition.EAT_UNTIL,
                    "Purchased bread did not replenish the buyer's persistent vanilla FoodLevel");
            require(helper, count(buyerInventory, Items.EMERALD) == 1 && count(farmerInventory, Items.EMERALD) == 1,
                    "Physical emerald price did not transfer from buyer to Farmer");
            require(helper, count(farmerInventory, Items.BREAD) == 5,
                    "Farmer food sale did not preserve its own physical ration");
            require(helper, count(buyerInventory, Items.BREAD) == 1,
                    "Buyer did not retain the uneaten remainder of its five-bread ration pack");
            helper.succeed();
        } finally {
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.DISABLED);
            buyer.discard();
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void hungryMinerBuysPhysicalCookedFishFromFisherman(GameTestHelper helper) {
        Villager buyer = spawnVillager(helper, new BlockPos(2, 2, 2));
        Villager fisherman = spawnVillager(helper, new BlockPos(4, 2, 2));
        var server = helper.getLevel().getServer();
        try {
            setProfession(helper, buyer, Identifier.fromNamespaceAndPath("totem", "miner"));
            setProfession(helper, fisherman, Identifier.fromNamespaceAndPath("minecraft", "fisherman"));
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            VillagerWorkInventory buyerInventory = inventories.inventory(buyer.getUUID());
            VillagerWorkInventory fishermanInventory = inventories.inventory(fisherman.getUUID());
            require(helper, fishermanInventory.insertExact(new ItemStack(Items.COOKED_COD, 9)),
                    "Could not seed the Fisherman's physical catch and personal reserve");
            require(helper, buyerInventory.insertExact(new ItemStack(Items.EMERALD, 2)),
                    "Could not seed the Miner's physical food payment");
            VillagerNutrition.setFoodLevel(buyer, 0);

            require(helper, VillagerFoodEconomyRuntime.tryPurchaseFromFoodProducer(
                            helper.getLevel(), buyer, fisherman),
                    "Hungry Miner could not purchase the Fisherman's physical cooked fish");
            require(helper, VillagerNutrition.foodLevel(buyer) >= VillagerNutrition.EAT_UNTIL,
                    "Miner did not eat the purchased cooked fish");
            require(helper, count(buyerInventory, Items.EMERALD) == 1
                            && count(fishermanInventory, Items.EMERALD) == 1,
                    "Cooked-fish purchase did not transfer one physical emerald");
            require(helper, count(fishermanInventory, Items.COOKED_COD) == 4
                            && count(buyerInventory, Items.COOKED_COD) == 1,
                    "Fisherman sale did not preserve both personal reserve and buyer remainder");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(server).drain(buyer.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(fisherman.getUUID());
            VillagerNutritionSavedData.forServer(server).remove(buyer.getUUID());
            VillagerNutritionSavedData.forServer(server).remove(fisherman.getUUID());
            buyer.discard();
            fisherman.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void nonFarmerEatsItsOwnPhysicalStarterFoodBeforeTrading(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(2, 2, 2));
        var server = helper.getLevel().getServer();
        try {
            setProfession(helper, villager, Identifier.fromNamespaceAndPath("totem", "miner"));
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.BREAD, 4))
                            && inventory.insertExact(new ItemStack(Items.EMERALD, 8)),
                    "Could not seed physical starter food and currency");
            VillagerNutrition.setFoodLevel(villager, 0);

            require(helper, VillagerFoodEconomyRuntime.tryConsumeOwnStoredFood(villager, inventory),
                    "Non-Farmer ignored edible food in its own personal inventory");
            require(helper, VillagerNutrition.foodLevel(villager) == VillagerNutrition.MAX_FOOD_LEVEL,
                    "Own physical bread did not restore independent Totem hunger");
            require(helper, count(inventory, Items.BREAD) == 0 && count(inventory, Items.EMERALD) == 8,
                    "Eating personal food spent currency or left consumed bread behind");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(server).drain(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void loneFarmerRationsItsOwnWorkBackedFoodWithoutMintingEmeralds(GameTestHelper helper) {
        Villager farmer = spawnVillager(helper, new BlockPos(2, 2, 2));
        var server = helper.getLevel().getServer();
        try {
            setFarmer(farmer);
            MerchantOffer bread = new MerchantOffer(new ItemCost(Items.EMERALD, 2), new ItemStack(Items.BREAD, 4), 12, 1, 0.2F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(bread);
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory farmerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            require(helper, farmerInventory.insertExact(new ItemStack(Items.BREAD, 4)), "Could not seed Farmer bread");
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.ENFORCED);
            VillagerNutrition.setFoodLevel(farmer, 0);

            require(helper, VillagerFoodEconomyRuntime.tryConsumeOwnFood(helper.getLevel(), farmer),
                    "Lone Farmer could not ration its own work-backed bread");
            require(helper, VillagerNutrition.foodLevel(farmer) >= VillagerNutrition.EAT_UNTIL,
                    "Farmer ration did not restore its persistent vanilla FoodLevel");
            require(helper, count(farmerInventory, Items.EMERALD) == 0,
                    "Farmer self-ration unexpectedly created emeralds");
            require(helper, count(farmerInventory, Items.BREAD) < 4,
                    "Farmer ration did not consume physical bread");
            helper.succeed();
        } finally {
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.DISABLED);
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 80)
    public void everyRegisteredAdultProfessionCanBuyFoodFromAnotherFarmer(GameTestHelper helper) {
        Villager seller = spawnVillager(helper, new BlockPos(6, 2, 2));
        List<Villager> buyers = new ArrayList<>();
        var server = helper.getLevel().getServer();
        try {
            setFarmer(seller);
            List<Identifier> professions = BuiltInRegistries.VILLAGER_PROFESSION.keySet().stream()
                    .sorted(Comparator.comparing(Identifier::toString))
                    .toList();
            require(helper, !professions.isEmpty(), "No registered villager professions were available for food-market coverage");
            MerchantOffer cropPurchase = new MerchantOffer(new ItemCost(Items.BEETROOT, 15),
                    new ItemStack(Items.EMERALD), 100, 2, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(cropPurchase);
            ((AbstractVillagerOffersAccessor) (Object) seller).totemVillagers$setExistingOffers(offers);
            int breadPerBuyer = 5;
            VillagerWorkInventory sellerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(seller.getUUID());
            require(helper, sellerInventory.insertExact(new ItemStack(Items.BREAD, breadPerBuyer * professions.size() + 4)),
                    "Could not seed universal food stock");
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.ENFORCED);
            for (Identifier professionId : professions) {
                Villager buyer = spawnVillager(helper, new BlockPos(2, 2, 2));
                buyers.add(buyer);
                setProfession(helper, buyer, professionId);
                VillagerNutrition.setFoodLevel(buyer, 0);
                VillagerWorkInventory buyerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(buyer.getUUID());
                require(helper, buyerInventory.insertExact(new ItemStack(Items.EMERALD, 2)), "Could not seed buyer emeralds");
                require(helper, VillagerFoodEconomyRuntime.tryPurchaseFromFarmer(helper.getLevel(), buyer, seller),
                        professionId + " could not buy food from a separate Farmer");
                require(helper, VillagerNutrition.foodLevel(buyer) >= VillagerNutrition.EAT_UNTIL,
                        professionId + " did not consume the bought food");
            }
            require(helper, count(sellerInventory, Items.EMERALD) == professions.size(),
                    "Food seller did not receive every profession's actual emerald payment");
            require(helper, count(sellerInventory, Items.BREAD) == 4,
                    "Food purchases did not preserve the Farmer's own ration");
            helper.succeed();
        } finally {
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.DISABLED);
            buyers.forEach(Villager::discard);
            seller.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setFarmer(Villager farmer) {
        VillagerProfession profession = requireProfession(Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        farmer.setVillagerData(farmer.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static void setProfession(GameTestHelper helper, Villager villager, Identifier professionId) {
        VillagerProfession profession = requireProfession(professionId);
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static VillagerProfession requireProfession(Identifier professionId) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(professionId);
        if (profession == null) {
            throw new IllegalStateException("Missing " + professionId + " profession");
        }
        return profession;
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
