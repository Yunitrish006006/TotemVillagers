package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.needs.VillagerNutritionSavedData;
import dev.totem.villagers.runtime.ToolsmithVillageEconomyRuntime;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;

import java.util.List;
import java.util.UUID;

/** End-to-end physical emerald, material, recipe processing and replacement-tool circulation. */
public final class ToolsmithVillageEconomyGameTest {
    @GameTest(maxTicks = 40)
    public void toolsmithCraftsAndSellsFishermanAPhysicalFishingRod(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.SMITHING_TABLE);
        Villager toolsmith = spawn(helper, new BlockPos(4, 3, 3), "minecraft:toolsmith");
        Villager fisherman = spawn(helper, new BlockPos(5, 3, 3), "minecraft:fisherman");
        toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(4, 2, 3))));
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWorkInventory smithStock = inventories.inventory(toolsmith.getUUID());
        VillagerWorkInventory fisherStock = inventories.inventory(fisherman.getUUID());
        try {
            VillagerNutrition.setFoodLevel(toolsmith, 20);
            VillagerNutrition.setFoodLevel(fisherman, 20);
            require(helper, smithStock.insertAllExact(List.of(
                            new ItemStack(Items.EMERALD, 8), new ItemStack(Items.STICK, 3),
                            new ItemStack(Items.STRING, 2))),
                    "Could not seed the Toolsmith's physical fishing-rod materials");
            require(helper, fisherStock.insertExact(new ItemStack(Items.EMERALD, 8)),
                    "Could not seed the Fisherman's physical emerald payment");

            ToolsmithVillageEconomyRuntime.tickForGameTest(server);
            require(helper, count(smithStock, Items.FISHING_ROD) == 1
                            && count(smithStock, Items.STICK) == 0 && count(smithStock, Items.STRING) == 0,
                    "Toolsmith did not consume the live vanilla recipe inputs to craft one fishing rod");
            ToolsmithVillageEconomyRuntime.tickForGameTest(server);
            require(helper, count(fisherStock, Items.FISHING_ROD) == 1 && count(fisherStock, Items.EMERALD) == 5,
                    "Fisherman did not buy the physical fishing rod for three emeralds");
            require(helper, count(smithStock, Items.FISHING_ROD) == 0 && count(smithStock, Items.EMERALD) == 11,
                    "Toolsmith did not receive and retain the Fisherman's physical payment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : List.of(toolsmith, fisherman)) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithCutsPlantsCraftsLiveStringAndSuppliesFishingRod(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.SMITHING_TABLE);
        Villager toolsmith = spawn(helper, new BlockPos(4, 3, 3), "minecraft:toolsmith");
        Villager fisherman = spawn(helper, new BlockPos(5, 3, 3), "minecraft:fisherman");
        toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(4, 2, 3))));
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWorkInventory smithStock = inventories.inventory(toolsmith.getUUID());
        VillagerWorkInventory fisherStock = inventories.inventory(fisherman.getUUID());
        List<BlockPos> relativePlants = List.of(new BlockPos(2, 2, 3), new BlockPos(3, 2, 4),
                new BlockPos(4, 2, 4), new BlockPos(5, 2, 4), new BlockPos(3, 2, 5), new BlockPos(4, 2, 5));
        try {
            VillagerNutrition.setFoodLevel(toolsmith, 20);
            VillagerNutrition.setFoodLevel(fisherman, 20);
            require(helper, smithStock.insertAllExact(List.of(
                            new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STICK, 3))),
                    "Could not seed the Toolsmith's live shears and rod recipe materials");
            require(helper, fisherStock.insertExact(new ItemStack(Items.EMERALD, 8)),
                    "Could not seed the Fisherman's physical emerald payment");
            for (int index = 0; index < relativePlants.size(); index++) {
                BlockPos plant = relativePlants.get(index);
                helper.setBlock(plant.below(), Blocks.GRASS_BLOCK);
                helper.setBlock(plant, index % 2 == 0 ? Blocks.SHORT_GRASS : Blocks.FERN);
            }

            for (int pass = 0; pass < 11; pass++) {
                ToolsmithVillageEconomyRuntime.tickForGameTest(server);
            }

            ItemStack usedShears = smithStock.snapshot().stream().filter(stack -> stack.is(Items.SHEARS))
                    .findFirst().orElse(null);
            require(helper, usedShears != null && usedShears.getDamageValue() == relativePlants.size(),
                    "Toolsmith did not consume one real shears durability for every harvested plant");
            require(helper, relativePlants.stream().map(helper::absolutePos)
                            .allMatch(position -> level.getBlockState(position).isAir()),
                    "A credited plant fibre was not physically removed from the world");
            require(helper, count(smithStock, Items.SHORT_GRASS) == 0 && count(smithStock, Items.FERN) == 0
                            && count(smithStock, Items.STRING) == 0 && count(smithStock, Items.IRON_INGOT) == 0,
                    "Toolsmith did not consume two live three-fibre recipes and one live shears recipe exactly");
            require(helper, count(fisherStock, Items.FISHING_ROD) == 1 && count(fisherStock, Items.EMERALD) == 5,
                    "Renewable plant fibre did not reach the Fisherman as a paid physical fishing rod");
            require(helper, count(smithStock, Items.EMERALD) == 3,
                    "Toolsmith did not retain the Fisherman's three physical emerald payment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : List.of(toolsmith, fisherman)) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithRepeatedlyClipsGeneratedVineTrellisWithoutDepletingIt(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.SMITHING_TABLE);
        BlockPos lowerVine = helper.absolutePos(new BlockPos(3, 2, 5));
        BlockPos motherVine = lowerVine.above();
        BlockPos support = lowerVine.east();
        level.setBlock(support, Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
        level.setBlock(support.above(), Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
        var attachedVine = Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
        level.setBlock(lowerVine, attachedVine, 3);
        level.setBlock(motherVine, attachedVine, 3);
        Villager toolsmith = spawn(helper, new BlockPos(4, 3, 3), "minecraft:toolsmith");
        Villager fisherman = spawn(helper, new BlockPos(5, 3, 3), "minecraft:fisherman");
        toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(4, 2, 3))));
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWorkInventory smithStock = inventories.inventory(toolsmith.getUUID());
        VillagerWorkInventory fisherStock = inventories.inventory(fisherman.getUUID());
        try {
            VillagerNutrition.setFoodLevel(toolsmith, 20);
            VillagerNutrition.setFoodLevel(fisherman, 20);
            require(helper, smithStock.insertAllExact(List.of(
                            new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.STICK, 3))),
                    "Could not seed the trellis cycle's live shears and rod recipe materials");
            require(helper, fisherStock.insertExact(new ItemStack(Items.EMERALD, 8)),
                    "Could not seed the Fisherman's physical rod payment");

            for (int pass = 0; pass < 11; pass++) {
                ToolsmithVillageEconomyRuntime.tickForGameTest(server);
            }

            ItemStack usedShears = smithStock.snapshot().stream().filter(stack -> stack.is(Items.SHEARS))
                    .findFirst().orElse(null);
            require(helper, usedShears != null && usedShears.getDamageValue() == 6,
                    "Two string recipes did not consume six physical vine clippings and six shears durability");
            require(helper, level.getBlockState(lowerVine).is(Blocks.VINE)
                            && level.getBlockState(motherVine).is(Blocks.VINE),
                    "Renewable clipping depleted the generated vine trellis");
            require(helper, count(smithStock, Items.VINE) == 0 && count(smithStock, Items.STRING) == 0
                            && count(smithStock, Items.IRON_INGOT) == 0,
                    "Trellis fibre, string, or shears inputs were not consumed exactly");
            require(helper, count(fisherStock, Items.FISHING_ROD) == 1 && count(fisherStock, Items.EMERALD) == 5,
                    "The trellis cycle did not deliver a paid physical fishing rod");
            require(helper, count(smithStock, Items.EMERALD) == 3,
                    "Toolsmith did not receive the Fisherman's three physical emeralds");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : List.of(toolsmith, fisherman)) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithBuysWoodAndSmeltedIronThenSellsFarmerAWorkingHoe(GameTestHelper helper) {
        Economy economy = prepare(helper, true);
        try {
            runSupplyChain(helper, economy, 8);

            VillagerWorkInventory farmer = economy.inventory(economy.farmer());
            VillagerWorkInventory toolsmith = economy.inventory(economy.toolsmith());
            VillagerWorkInventory miner = economy.inventory(economy.miner());
            VillagerWorkInventory lumberjack = economy.inventory(economy.lumberjack());
            require(helper, count(farmer, Items.IRON_HOE) == 1 && count(farmer, Items.EMERALD) == 4,
                    "Farmer did not pay four physical emeralds for the forged iron hoe");
            require(helper, count(toolsmith, Items.EMERALD) == 7 && count(toolsmith, Items.IRON_INGOT) == 0
                            && count(toolsmith, Items.STICK) == 2,
                    "Toolsmith did not retain the exact material/payment remainder after forging");
            require(helper, count(miner, Items.RAW_IRON) == 0 && count(miner, Items.COAL) == 0
                            && count(miner, Items.IRON_INGOT) == 2 && count(miner, Items.EMERALD) == 11,
                    "Miner did not smelt four and sell exactly two physical iron ingots");
            require(helper, count(lumberjack, Items.OAK_LOG) == 7 && count(lumberjack, Items.EMERALD) == 10,
                    "Lumberjack did not sell exactly one physical log for two emeralds");

            BlockPos crop = helper.absolutePos(new BlockPos(7, 2, 3));
            helper.setBlock(new BlockPos(7, 1, 3), Blocks.FARMLAND);
            CropBlock wheat = (CropBlock) Blocks.WHEAT;
            helper.getLevel().setBlock(crop, wheat.getStateForAge(wheat.getMaxAge()), 3);
            require(helper, new FarmerWorldWorkAction().complete(helper.getLevel(), economy.farmer(), crop,
                            WorkOrderDefinitions.catalog().require("totem:farmer_wheat"), farmer),
                    "Farmer could not work with the purchased iron hoe");
            ItemStack usedHoe = farmer.snapshot().stream().filter(stack -> stack.is(Items.IRON_HOE)).findFirst().orElse(null);
            require(helper, usedHoe != null && usedHoe.getDamageValue() == 1,
                    "Purchased Farmer hoe did not consume real durability during harvest");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithUsesLiveSmeltAndCraftingRecipesForCopperReplacementTools(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        VillagerWorkInventory miner = economy.inventory(economy.miner());
        try {
            require(helper, miner.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE, 16)).isPresent()
                            && miner.insertAllExact(List.of(
                            new ItemStack(Items.RAW_COPPER, 4), new ItemStack(Items.COAL))),
                    "Could not replace the stone fixture with physical copper smelting inputs");

            runSupplyChain(helper, economy, 8);

            VillagerWorkInventory farmer = economy.inventory(economy.farmer());
            VillagerWorkInventory toolsmith = economy.inventory(economy.toolsmith());
            require(helper, count(farmer, Items.COPPER_HOE) == 1 && count(farmer, Items.EMERALD) == 4,
                    "Farmer did not receive the recipe-backed copper hoe");
            require(helper, count(miner, Items.RAW_COPPER) == 0 && count(miner, Items.COAL) == 0
                            && count(miner, Items.COPPER_INGOT) == 2 && count(miner, Items.EMERALD) == 11,
                    "Miner did not smelt four raw copper and sell the exact two-ingot tool share");
            require(helper, count(toolsmith, Items.COPPER_INGOT) == 0 && count(toolsmith, Items.EMERALD) == 7,
                    "Toolsmith's copper material and emerald accounting was not conserved");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerSmeltsTwoRawIronWhenThatIsEnoughForReplacementShears(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        VillagerWorkInventory miner = economy.inventory(economy.miner());
        try {
            require(helper, miner.insertAllExact(List.of(
                            new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.CHARCOAL))),
                    "Could not seed the Miner's minimum useful iron-smelting batch");

            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());

            require(helper, count(miner, Items.RAW_IRON) == 0 && count(miner, Items.CHARCOAL) == 0
                            && count(miner, Items.IRON_INGOT) == 2,
                    "Miner waited for four raw iron instead of releasing the two-ingot shears batch");
            int totalEmeralds = economy.villagers().stream()
                    .mapToInt(villager -> count(economy.inventory(villager), Items.EMERALD)).sum();
            require(helper, totalEmeralds == 32,
                    "Minimum useful smelting created or destroyed village emeralds");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithBuysOnlyOneMissingStringForNextFishingRod(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.SMITHING_TABLE);
        Villager toolsmith = spawn(helper, new BlockPos(4, 3, 3), "minecraft:toolsmith");
        Villager fisherman = spawn(helper, new BlockPos(5, 3, 3), "minecraft:fisherman");
        toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(4, 2, 3))));
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        VillagerWorkInventory smithStock = inventories.inventory(toolsmith.getUUID());
        VillagerWorkInventory fisherStock = inventories.inventory(fisherman.getUUID());
        try {
            VillagerNutrition.setFoodLevel(toolsmith, 20);
            VillagerNutrition.setFoodLevel(fisherman, 20);
            require(helper, smithStock.insertAllExact(List.of(
                            new ItemStack(Items.STICK, 3), new ItemStack(Items.STRING),
                            new ItemStack(Items.EMERALD))),
                    "Could not seed a rod order missing exactly one string");
            require(helper, fisherStock.insertExact(new ItemStack(Items.STRING)),
                    "Could not seed the Fisherman's one-string sale lot");

            ToolsmithVillageEconomyRuntime.tickForGameTest(server);

            require(helper, count(smithStock, Items.STRING) == 2 && count(smithStock, Items.EMERALD) == 0,
                    "Toolsmith did not buy exactly the one missing physical string");
            require(helper, count(fisherStock, Items.STRING) == 0 && count(fisherStock, Items.EMERALD) == 1,
                    "Fisherman did not receive the exact one-emerald string payment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : List.of(toolsmith, fisherman)) {
                VillagerWorkSavedData.forServer(server).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(server).remove(villager.getUUID());
                villager.discard();
            }
        }
    }

    @GameTest(maxTicks = 40)
    public void fragmentedPaymentRequiresNearbySponsorsAndRemainsConserved(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        VillagerWorkInventory farmer = economy.inventory(economy.farmer());
        VillagerWorkInventory toolsmith = economy.inventory(economy.toolsmith());
        VillagerWorkInventory miner = economy.inventory(economy.miner());
        VillagerWorkInventory lumberjack = economy.inventory(economy.lumberjack());
        try {
            for (Villager villager : economy.villagers()) {
                require(helper, economy.inventory(villager)
                                .takeExactMatchingItem(new ItemStack(Items.EMERALD, 8)).isPresent(),
                        "Could not clear founding emeralds for fragmented-payment test");
            }
            require(helper, farmer.insertExact(new ItemStack(Items.EMERALD))
                            && miner.insertExact(new ItemStack(Items.EMERALD))
                            && lumberjack.insertExact(new ItemStack(Items.EMERALD))
                            && toolsmith.insertExact(new ItemStack(Items.STONE_HOE)),
                    "Could not seed the three-way physical payment and finished hoe");
            economy.lumberjack().setPos(economy.toolsmith().getX() + 40.0D,
                    economy.toolsmith().getY(), economy.toolsmith().getZ());

            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());
            require(helper, count(toolsmith, Items.STONE_HOE) == 1 && count(farmer, Items.STONE_HOE) == 0,
                    "A villager more than 32 blocks away funded a remote work order");
            require(helper, count(farmer, Items.EMERALD) == 1 && count(miner, Items.EMERALD) == 1
                            && count(lumberjack, Items.EMERALD) == 1 && count(toolsmith, Items.EMERALD) == 0,
                    "Rejected remote sponsorship mutated physical wallets");

            economy.lumberjack().setPos(economy.toolsmith().getX() + 1.0D,
                    economy.toolsmith().getY(), economy.toolsmith().getZ());
            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());
            require(helper, count(farmer, Items.STONE_HOE) == 1 && count(toolsmith, Items.STONE_HOE) == 0,
                    "Three nearby one-emerald wallets could not jointly buy the replacement hoe");
            require(helper, count(farmer, Items.EMERALD) == 0 && count(miner, Items.EMERALD) == 0
                            && count(lumberjack, Items.EMERALD) == 0 && count(toolsmith, Items.EMERALD) == 3,
                    "Pooled work-order payment was not transferred exactly to the seller");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithFallsBackToMinerStoneWhenNoSmeltedMetalExists(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        try {
            runSupplyChain(helper, economy, 8);
            VillagerWorkInventory farmer = economy.inventory(economy.farmer());
            require(helper, count(farmer, Items.STONE_HOE) == 1 && count(farmer, Items.EMERALD) == 5,
                    "Toolsmith did not forge and sell the physical stone fallback hoe");
            require(helper, count(economy.inventory(economy.miner()), Items.COBBLESTONE) == 14
                            && count(economy.inventory(economy.miner()), Items.EMERALD) == 10,
                    "Toolsmith did not buy the Miner's exact two-stone input");
            require(helper, count(economy.inventory(economy.lumberjack()), Items.SPRUCE_LOG) == 7
                            && count(economy.inventory(economy.lumberjack()), Items.EMERALD) == 10,
                    "Stone-tool path did not pay its Lumberjack supplier");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithPrebuysTwoToolBatchesAndPreservesOperatingCash(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        VillagerWorkInventory farmer = economy.inventory(economy.farmer());
        VillagerWorkInventory miner = economy.inventory(economy.miner());
        VillagerWorkInventory toolsmith = economy.inventory(economy.toolsmith());
        try {
            require(helper, farmer.insertExact(new ItemStack(Items.IRON_HOE)),
                    "Could not satisfy every immediate replacement-tool request");
            require(helper, miner.insertExact(new ItemStack(Items.IRON_INGOT, 6)),
                    "Could not seed two full Miner iron batches");
            require(helper, toolsmith.insertExact(new ItemStack(Items.EMERALD, 6)),
                    "Could not seed the Toolsmith's earned reserve-purchase funds");

            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());
            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());
            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());

            require(helper, count(toolsmith, Items.IRON_INGOT) == 6 && count(toolsmith, Items.EMERALD) == 6,
                    "Toolsmith did not stop after two three-ingot batches or spent its six-emerald operating reserve");
            require(helper, count(miner, Items.IRON_INGOT) == 0 && count(miner, Items.EMERALD) == 16,
                    "Miner did not receive both four-emerald physical prepayments");
            int totalEmeralds = economy.villagers().stream()
                    .mapToInt(villager -> count(economy.inventory(villager), Items.EMERALD)).sum();
            require(helper, totalEmeralds == 38,
                    "Mineral reserve purchases created or destroyed physical emeralds");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    @GameTest(maxTicks = 40)
    public void toolsmithReplacesAndWorkersConsumeMinerPickaxeAndLumberjackAxe(GameTestHelper helper) {
        Economy economy = prepare(helper, false);
        VillagerWorkInventory farmer = economy.inventory(economy.farmer());
        VillagerWorkInventory miner = economy.inventory(economy.miner());
        VillagerWorkInventory lumberjack = economy.inventory(economy.lumberjack());
        try {
            require(helper, farmer.insertExact(new ItemStack(Items.IRON_HOE)),
                    "Could not keep the Farmer out of the resource-tool replacement queue");
            require(helper, miner.takeExactMatchingItem(new ItemStack(Items.IRON_PICKAXE)).isPresent(),
                    "Could not exhaust the Miner's founding pickaxe");
            require(helper, lumberjack.takeExactMatchingItem(new ItemStack(Items.IRON_AXE)).isPresent(),
                    "Could not exhaust the Lumberjack's founding axe");

            runSupplyChain(helper, economy, 9);
            require(helper, count(miner, Items.STONE_PICKAXE) == 1 && count(miner, Items.EMERALD) == 10,
                    "Miner did not buy the Toolsmith's physical stone pickaxe for four emeralds");
            require(helper, count(lumberjack, Items.STONE_AXE) == 1 && count(lumberjack, Items.EMERALD) == 6,
                    "Lumberjack did not buy the Toolsmith's physical stone axe after supplying its wood");
            require(helper, count(economy.inventory(economy.toolsmith()), Items.EMERALD) == 8,
                    "Toolsmith replacement-tool payments did not remain physically conserved");

            var level = helper.getLevel();
            BlockPos stone = helper.absolutePos(new BlockPos(2, 2, 4));
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            TagKey<Block> minerTargets = TagKey.create(Registries.BLOCK,
                    Identifier.fromNamespaceAndPath("totem", "miner_targets"));
            require(helper, new MinerWorldWorkAction().complete(level, economy.miner(), stone, minerTargets,
                            WorkOrderDefinitions.catalog().require("totem:miner_stone"), miner),
                    "Miner could not use the purchased replacement pickaxe");

            BlockPos treeBase = helper.absolutePos(new BlockPos(6, 2, 5));
            level.setBlock(treeBase.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int height = 0; height < 4; height++) {
                level.setBlock(treeBase.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
            level.setBlock(treeBase.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), 3);
            require(helper, lumberjack.insertExact(new ItemStack(Items.OAK_SAPLING)),
                    "Could not give the manual-zone Lumberjack a physical replanting sapling");
            WorkZone lumberyard = new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                    new BlockCoordinate(treeBase.getX() - 2, treeBase.getY() - 1, treeBase.getZ() - 2),
                    new BlockCoordinate(treeBase.getX() + 2, treeBase.getY() + 6, treeBase.getZ() + 2));
            TagKey<Block> lumberjackLogs = TagKey.create(Registries.BLOCK,
                    Identifier.fromNamespaceAndPath("totem", "lumberjack_oak_logs"));
            require(helper, new LumberjackWorldWorkAction().complete(level, economy.lumberjack(), lumberyard,
                            treeBase, lumberjackLogs,
                            WorkOrderDefinitions.catalog().require("totem:lumberjack_oak_logs"), lumberjack),
                    "Lumberjack could not use the purchased replacement axe");

            ItemStack usedPickaxe = miner.snapshot().stream().filter(stack -> stack.is(Items.STONE_PICKAXE))
                    .findFirst().orElse(null);
            ItemStack usedAxe = lumberjack.snapshot().stream().filter(stack -> stack.is(Items.STONE_AXE))
                    .findFirst().orElse(null);
            require(helper, usedPickaxe != null && usedPickaxe.getDamageValue() == 1
                            && usedAxe != null && usedAxe.getDamageValue() == 1,
                    "Purchased resource tools did not consume real durability during world work");
            helper.succeed();
        } finally {
            economy.close();
        }
    }

    private static Economy prepare(GameTestHelper helper, boolean iron) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.FURNACE);
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.SMITHING_TABLE);
        helper.setBlock(new BlockPos(5, 2, 3), Blocks.COMPOSTER);
        Villager miner = spawn(helper, new BlockPos(3, 3, 3), "totem:miner");
        Villager toolsmith = spawn(helper, new BlockPos(4, 3, 3), "minecraft:toolsmith");
        Villager farmer = spawn(helper, new BlockPos(5, 3, 3), "minecraft:farmer");
        Villager lumberjack = spawn(helper, new BlockPos(4, 3, 4), "totem:lumberjack");
        miner.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(3, 2, 3))));
        toolsmith.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(4, 2, 3))));
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                GlobalPos.of(level.dimension(), helper.absolutePos(new BlockPos(5, 2, 3))));
        Economy economy = new Economy(helper, settings, farmer, toolsmith, miner, lumberjack,
                VillagerWorkInventorySavedData.forServer(server));
        for (Villager villager : economy.villagers()) {
            VillagerNutrition.setFoodLevel(villager, 20);
            require(helper, economy.inventory(villager).insertExact(new ItemStack(Items.EMERALD, 8)),
                    "Could not seed physical founding emeralds");
        }
        require(helper, economy.inventory(miner).insertExact(new ItemStack(Items.IRON_PICKAXE)),
                "Could not seed the Miner's existing physical pickaxe");
        require(helper, economy.inventory(lumberjack).insertExact(new ItemStack(Items.IRON_AXE)),
                "Could not seed the Lumberjack's existing physical axe");
        require(helper, economy.inventory(lumberjack).insertExact(new ItemStack(
                        iron ? Items.OAK_LOG : Items.SPRUCE_LOG, 8)),
                "Could not seed Lumberjack wood stock");
        if (iron) {
            require(helper, economy.inventory(miner).insertAllExact(List.of(
                            new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.COAL))),
                    "Could not seed Miner smelting inputs");
        } else {
            require(helper, economy.inventory(miner).insertExact(new ItemStack(Items.COBBLESTONE, 16)),
                    "Could not seed Miner stone stock");
        }
        return economy;
    }

    private static void runSupplyChain(GameTestHelper helper, Economy economy, int passes) {
        for (int pass = 0; pass < passes; pass++) {
            ToolsmithVillageEconomyRuntime.tickForGameTest(helper.getLevel().getServer());
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        require(helper, profession != null, "Missing profession " + professionId);
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private record Economy(GameTestHelper helper, WorkBackedTradingSettingsSavedData settings, Villager farmer,
                           Villager toolsmith, Villager miner, Villager lumberjack,
                           VillagerWorkInventorySavedData inventories) {
        private List<Villager> villagers() {
            return List.of(farmer, toolsmith, miner, lumberjack);
        }

        private VillagerWorkInventory inventory(Villager villager) {
            return inventories.inventory(villager.getUUID());
        }

        private void close() {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            for (Villager villager : villagers()) {
                VillagerWorkSavedData.forServer(helper.getLevel().getServer()).remove(villager.getUUID());
                inventories.drain(villager.getUUID());
                VillagerNutritionSavedData.forServer(helper.getLevel().getServer()).remove(villager.getUUID());
                villager.discard();
            }
        }
    }
}
