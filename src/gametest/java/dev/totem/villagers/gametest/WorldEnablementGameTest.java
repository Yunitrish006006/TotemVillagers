package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.runtime.WorldEnablementRuntime;
import dev.totem.villagers.runtime.VillagerStarterSupplyRuntime;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
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

/** Covers safe empty-state initialisation and non-destructive vanilla rollback. */
public final class WorldEnablementGameTest {
    @GameTest(maxTicks = 40)
    public void toolsmithReceivesFiniteStringForAutonomousFishingRodBootstrap(GameTestHelper helper) {
        Villager toolsmith = spawnVillager(helper, new BlockPos(3, 2, 3));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        try {
            VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "toolsmith"));
            toolsmith.setVillagerData(toolsmith.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerStarterSupplyRuntime.tickForGameTest(server);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server)
                    .inventory(toolsmith.getUUID());
            require(helper, count(inventory, Items.IRON_PICKAXE) == 1
                            && count(inventory, Items.STRING) == VillagerStarterSupplyRuntime.TOOLSMITH_STARTING_STRING
                            && count(inventory, Items.SHEARS) == VillagerStarterSupplyRuntime.TOOLSMITH_STARTING_SHEARS,
                    "Toolsmith did not receive its finite fishing-rod bootstrap materials");
            VillagerStarterSupplyRuntime.tickForGameTest(server);
            require(helper, count(inventory, Items.STRING) == VillagerStarterSupplyRuntime.TOOLSMITH_STARTING_STRING
                            && count(inventory, Items.SHEARS) == VillagerStarterSupplyRuntime.TOOLSMITH_STARTING_SHEARS,
                    "Toolsmith fishing-rod bootstrap kit was granted more than once");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkInventorySavedData.forServer(server).drain(toolsmith.getUUID());
            toolsmith.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void adultWorkerReceivesOnePhysicalProfessionStarterKit(GameTestHelper helper) {
        Villager farmer = spawnVillager(helper, new BlockPos(2, 2, 2));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        try {
            VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                    Identifier.fromNamespaceAndPath("minecraft", "farmer"));
            farmer.setVillagerData(farmer.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD, 4), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) farmer).totemVillagers$setExistingOffers(offers);
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerStarterSupplyRuntime.tickForGameTest(server);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
            require(helper, count(inventory, Items.EMERALD) == VillagerStarterSupplyRuntime.STARTING_EMERALDS,
                    "Starter emeralds were not physical inventory items");
            require(helper, count(inventory, Items.IRON_HOE) == 1,
                    "Farmer did not receive its profession tool");
            require(helper, count(inventory, Items.BREAD) == VillagerStarterSupplyRuntime.STARTING_BREAD + 6,
                    "Deterministic Farmer bread merchandise and survival food were not stored together");

            VillagerStarterSupplyRuntime.tickForGameTest(server);
            require(helper, count(inventory, Items.EMERALD) == VillagerStarterSupplyRuntime.STARTING_EMERALDS
                            && count(inventory, Items.IRON_HOE) == 1
                            && count(inventory, Items.BREAD) == VillagerStarterSupplyRuntime.STARTING_BREAD + 6,
                    "Starter kit was granted more than once");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkInventorySavedData.forServer(server).drain(farmer.getUUID());
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void enablingInitialisesExistingLoadedVillagerWithNoFreeStock(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(3, 2, 3));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        Villager laterLoaded = null;
        try {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            require(helper, states.get(villager.getUUID()).isEmpty(), "Fresh legacy villager unexpectedly had work state");
            var originalData = villager.getVillagerData();

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            WorldEnablementRuntime.ApplyResult result = WorldEnablementRuntime.apply(server, WorkBackedTradingMode.ENFORCED);
            VillagerWorkState initialised = states.get(villager.getUUID()).orElse(null);
            require(helper, initialised != null && initialised.merchantStock().isEmpty()
                            && initialised.variantMerchantStock().isEmpty() && initialised.activeWork().isEmpty(),
                    "Enablement did not create an empty, safe legacy work state");
            require(helper, villager.getVillagerData().equals(originalData), "Enablement changed the villager profession or level");
            require(helper, result.initialisedVillagers() >= 1,
                    "Enablement did not report initialising the loaded legacy villager");
            laterLoaded = spawnVillager(helper, new BlockPos(4, 2, 3));
            require(helper, states.get(laterLoaded.getUUID()).map(state -> state.merchantStock().isEmpty()
                            && state.variantMerchantStock().isEmpty()).orElse(false),
                    "Villager loaded after enablement did not receive an empty work state");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            villager.discard();
            if (laterLoaded != null) {
                states.remove(laterLoaded.getUUID());
                laterLoaded.discard();
            }
        }
    }

    @GameTest(maxTicks = 20)
    public void rollbackRestoresVanillaOfferUsesWithoutDeletingMerchantStock(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(5, 2, 5));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 0, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(offer);
            offer.setToOutOfStock();
            ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);
            VillagerWorkState stocked = new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION, villager.getUUID(),
                    Map.of("minecraft:bread", 4), Map.of(), Optional.empty(), Optional.empty());
            states.put(stocked);

            settings.setMode(WorkBackedTradingMode.VANILLA_ROLLBACK);
            WorldEnablementRuntime.ApplyResult result = WorldEnablementRuntime.apply(server, WorkBackedTradingMode.VANILLA_ROLLBACK);
            require(helper, !offer.isOutOfStock(), "Rollback did not restore the loaded vanilla offer use counter");
            require(helper, states.get(villager.getUUID()).equals(Optional.of(stocked)),
                    "Rollback deleted or changed accumulated merchant stock");
            require(helper, result.restoredOfferSets() >= 1, "Rollback did not report restoring the loaded offer set");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void enablementBootstrapsVanillaZeroFoodOnlyOnce(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(7, 2, 3));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            VillagerNutrition.setFoodLevel(villager, 0);

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            WorldEnablementRuntime.apply(server, WorkBackedTradingMode.ENFORCED);
            VillagerWorkState initialised = states.get(villager.getUUID()).orElse(null);
            require(helper, initialised != null && initialised.nutritionBootstrapGranted()
                            && VillagerNutrition.foodLevel(villager) == VillagerNutrition.MAX_FOOD_LEVEL,
                    "Enablement did not give a fresh worker its one full starting buffer");

            VillagerNutrition.setFoodLevel(villager, 0);
            WorldEnablementRuntime.apply(server, WorkBackedTradingMode.ENFORCED);
            require(helper, VillagerNutrition.foodLevel(villager) == 0,
                    "A later hunger state incorrectly received a second bootstrap buffer");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            villager.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
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
