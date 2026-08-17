package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.runtime.VillagerMaterialLogisticsRuntime;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

/** Proves that nearby villagers buy physical recipe materials at their live vanilla emerald rates. */
public final class VillagerMaterialLogisticsGameTest {
    @GameTest(maxTicks = 80)
    public void nearbyVillageMaterialsSupplyARecipeBackedFarmerTrade(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        Villager baker = spawnVillager(helper, relativeJobSite.above(), "minecraft:farmer");
        Villager grower = spawnVillager(helper, relativeJobSite.above().east(), "minecraft:unemployed");
        try {
            baker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), jobSite));
            VillagerNutrition.setFoodLevel(baker, 20);
            VillagerNutrition.setFoodLevel(grower, 20);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 3), new ItemStack(Items.EMERALD), 12, 1, .05F));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) baker).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory bakerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(baker.getUUID());
            VillagerWorkInventory growerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(grower.getUUID());
            require(helper, growerInventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not seed the nearby grower's physical wheat");
            require(helper, bakerInventory.insertExact(new ItemStack(Items.EMERALD)), "Could not fund material buyer");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(bakerInventory, Items.WHEAT) == 3,
                    "Village logistics did not deliver the Farmer's exact live-recipe wheat inputs");
            require(helper, count(growerInventory, Items.WHEAT) == 0,
                    "Village logistics duplicated or retained delivered wheat at its source");
            require(helper, count(bakerInventory, Items.EMERALD) == 0 && count(growerInventory, Items.EMERALD) == 1,
                    "Direct material purchase did not transfer its exact one-emerald price");

            WorkOrder bread = WorkOrderDefinitions.catalog().require("totem:farmer_bread");
            for (int tick = 0; tick <= bread.workTicks(); tick++) {
                VillagerWorkshopRuntime.tickForGameTest(server);
            }
            int stock = count(bakerInventory, Items.BREAD);
            require(helper, stock == 1,
                    "Delivered wheat did not become work-backed bread stock: " + stock);
            require(helper, count(bakerInventory, Items.WHEAT) == 0,
                    "The Baker did not consume the delivered recipe materials");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(baker.getUUID());
            states.remove(grower.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(baker.getUUID());
            inventories.drain(grower.getUUID());
            baker.discard();
            grower.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void supplierRetainsIngredientsForItsOwnNextLiveRecipe(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos recipientJobSite = new BlockPos(3, 2, 3);
        BlockPos supplierJobSite = new BlockPos(6, 2, 3);
        helper.setBlock(recipientJobSite, Blocks.COMPOSTER);
        helper.setBlock(supplierJobSite, Blocks.COMPOSTER);
        Villager recipient = spawnVillager(helper, recipientJobSite.above(), "minecraft:farmer");
        Villager supplier = spawnVillager(helper, supplierJobSite.above(), "minecraft:farmer");
        try {
            prepareBreadSeller(helper, recipient, helper.absolutePos(recipientJobSite));
            prepareBreadSeller(helper, supplier, helper.absolutePos(supplierJobSite));
            VillagerWorkInventory recipientInventory = VillagerWorkInventorySavedData.forServer(server).inventory(recipient.getUUID());
            VillagerWorkInventory supplierInventory = VillagerWorkInventorySavedData.forServer(server).inventory(supplier.getUUID());
            require(helper, supplierInventory.insertExact(new ItemStack(Items.WHEAT, 4)),
                    "Could not seed the supplier's own recipe inputs and surplus");
            require(helper, recipientInventory.insertExact(new ItemStack(Items.EMERALD)), "Could not fund requester");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(recipientInventory, Items.WHEAT) == 1,
                    "The requester did not receive the supplier's one-wheat surplus");
            require(helper, count(supplierInventory, Items.WHEAT) == 3,
                    "The supplier did not retain its own next bread recipe inputs");
            require(helper, count(recipientInventory, Items.EMERALD) == 0 && count(supplierInventory, Items.EMERALD) == 1,
                    "The supplier did not receive the live material-offer emerald payment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(recipient.getUUID());
            states.remove(supplier.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(recipient.getUUID());
            inventories.drain(supplier.getUUID());
            recipient.discard();
            supplier.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void materialPurchaseRequiresBuyerEmeralds(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos jobSite = new BlockPos(3, 2, 3);
        helper.setBlock(jobSite, Blocks.COMPOSTER);
        Villager buyer = spawnVillager(helper, jobSite.above(), "minecraft:farmer");
        Villager supplier = spawnVillager(helper, jobSite.above().east(), "minecraft:unemployed");
        try {
            buyer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(jobSite)));
            VillagerNutrition.setFoodLevel(buyer, 20);
            VillagerNutrition.setFoodLevel(supplier, 20);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 3), new ItemStack(Items.EMERALD), 12, 1, .05F));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) buyer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory buyerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(buyer.getUUID());
            VillagerWorkInventory supplierInventory = VillagerWorkInventorySavedData.forServer(server).inventory(supplier.getUUID());
            require(helper, supplierInventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not seed physical wheat for the unfunded purchase test");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(buyerInventory, Items.WHEAT) == 0 && count(supplierInventory, Items.WHEAT) == 3,
                    "An unfunded villager material purchase moved physical wheat");
            require(helper, count(buyerInventory, Items.EMERALD) == 0,
                    "An unfunded material purchase created emeralds");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(buyer.getUUID());
            states.remove(supplier.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(buyer.getUUID());
            inventories.drain(supplier.getUUID());
            buyer.discard();
            supplier.discard();
        }
    }

    private static void prepareBreadSeller(GameTestHelper helper, Villager villager, BlockPos jobSite) {
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), jobSite));
        VillagerNutrition.setFoodLevel(villager, 20);
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 1), new ItemStack(Items.EMERALD), 12, 1, .05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
        ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing villager profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
