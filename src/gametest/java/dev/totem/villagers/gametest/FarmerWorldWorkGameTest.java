package dev.totem.villagers.gametest;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.trade.TradeSnapshotSender;
import dev.totem.villagers.runtime.VillagerFarmerWorkRuntime;
import dev.totem.villagers.needs.VillagerNutrition;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.world.FarmerWorldWorkAction;
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
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import java.util.List;
import java.util.Set;

/** Verifies a mature field supplies the farmer's personal inventory and replants the crop. */
public final class FarmerWorldWorkGameTest {
    @GameTest(structure = "totem:river_households", padding = 40, maxTicks = 40)
    public void farmerReplacesMissingHoeFromTwoLogsAndResumesHarvest(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        BlockPos composter = helper.absolutePos(new BlockPos(7, 2, 7));
        Villager farmer = spawnVillager(helper, new BlockPos(7, 2, 8));
        Villager lumberjack = spawnVillager(helper, new BlockPos(6, 2, 8));
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var timber = inventories.inventory(lumberjack.getUUID());
        var inventory = inventories.inventory(farmer.getUUID());
        try {
            setFarmer(farmer);
            var profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("totem:lumberjack"));
            lumberjack.setVillagerData(lumberjack.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
            VillagerNutrition.setFoodLevel(lumberjack, 20);
            VillagerNutrition.setFoodLevel(farmer, 20);
            level.setBlockAndUpdate(composter, Blocks.COMPOSTER.defaultBlockState());
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), composter));
            BlockPos site = composter.east();
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                level.setBlockAndUpdate(composter.relative(direction).below(), Blocks.STONE.defaultBlockState());
            }
            level.setBlockAndUpdate(site, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(site.below(), Blocks.DIRT.defaultBlockState());
            require(helper, timber.insertExact(new ItemStack(Items.OAK_LOG, 2))
                    && inventory.insertExact(new ItemStack(Items.EMERALD, 5)), "Could not seed wood market");
            for (int pass = 0; pass < 12; pass++) VillagerFarmerWorkRuntime.tickForGameTest(server);
            require(helper, level.getBlockState(site).is(Blocks.CRAFTING_TABLE), "No physical table at farm; stock=" + inventory.snapshot().stream()
                    .filter(stack -> !stack.isEmpty()).toList() + ", site=" + level.getBlockState(site)
                    + ", allowed=" + dev.totem.villagers.world.WorldWorkPermissions.mayWork(level, farmer, site)
                    + ", workstation=" + farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    + ", position=" + farmer.blockPosition() + ", target=" + site);
            require(helper, inventory.countMatchingItem(new ItemStack(Items.WOODEN_HOE)) == 1
                    && inventory.countMatchingItem(new ItemStack(Items.STICK)) == 2
                    && inventory.countMatchingItem(new ItemStack(Items.OAK_LOG)) == 0
                    && inventory.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 0
                    && inventory.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                    && timber.countMatchingItem(new ItemStack(Items.EMERALD)) == 4
                    && timber.countMatchingItem(new ItemStack(Items.OAK_LOG)) == 0,
                    "Farmer did not commit exact wood, handle and hoe recipes once");
            harvestAndAssertReplanted(helper, new FarmerWorldWorkAction(), farmer, inventory,
                    new BlockPos(8, 2, 8), (CropBlock) Blocks.CARROTS, "totem:farmer_carrot",
                    "minecraft:carrot", "totem:farmer_mature_carrots");
            var current = FarmerWorldWorkAction.bestHoe(inventory).orElseThrow();
            inventory.takeExactMatchingItem(current).orElseThrow();
            current.setDamageValue(current.getMaxDamage() - 4);
            inventory.insertExact(current);
            inventory.insertExact(new ItemStack(Items.OAK_PLANKS, 2));
            level.removeBlock(site, false);
            var wall = farmer.blockPosition().east();
            var behindWall = wall.east();
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(behindWall, Blocks.CRAFTING_TABLE.defaultBlockState());
            VillagerFarmerWorkRuntime.tickForGameTest(server);
            require(helper, inventory.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 2
                    && inventory.countMatchingItem(new ItemStack(Items.WOODEN_HOE)) == 0, "Farmer crafted through a wall");
            level.removeBlock(wall, false);
            level.removeBlock(wall.above(), false);
            VillagerFarmerWorkRuntime.tickForGameTest(server);
            require(helper, inventory.countMatchingItem(current) == 1
                    && inventory.countMatchingItem(new ItemStack(Items.WOODEN_HOE)) == 1
                    && inventory.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 0, "Farmer failed to replace a worn hoe at the accessible table");
            helper.succeed();
        } finally {
            inventories.drain(farmer.getUUID());
            inventories.drain(lumberjack.getUUID());
            lumberjack.discard();
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void matureWheatSuppliesThePersonalInventoryAndIsReplanted(GameTestHelper helper) {
        BlockPos crop = helper.absolutePos(new BlockPos(8, 3, 8));
        Villager farmer = spawnVillager(helper, new BlockPos(7, 2, 7));
        try {
            setFarmer(farmer);
            helper.getLevel().setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            CropBlock wheat = (CropBlock) Blocks.WHEAT;
            helper.getLevel().setBlock(crop, wheat.getStateForAge(wheat.getMaxAge()), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(farmer.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_HOE)),
                    "Could not give the Farmer its physical work hoe");
            WorkOrder order = new WorkOrder("totem:farmer_wheat", "minecraft:farmer", new ItemAmount("minecraft:wheat", 1),
                    List.of(), Set.of(WorkSource.WORLD), "totem:farmer_mature_wheat", 40, 64);

            require(helper, new FarmerWorldWorkAction().complete(helper.getLevel(), farmer, crop, order, inventory),
                    "Mature wheat was not committed to the farmer's personal inventory");
            require(helper, wheatCount(inventory.snapshot()) == 1,
                    "Harvested wheat was not placed exactly once in the personal inventory");
            require(helper, TradeSnapshotSender.snapshot(helper.getLevel(), farmer, new MerchantOffers(), 1).workInventory()
                            .stream().anyMatch(slot -> slot.itemId().equals("minecraft:wheat") && slot.count() == 1),
                    "Harvested wheat was not visible in the Farmer work-inventory snapshot");
            require(helper, helper.getLevel().getBlockState(crop).is(Blocks.WHEAT)
                            && wheat.getAge(helper.getLevel().getBlockState(crop)) == 0,
                    "Farmer harvest did not immediately replant the wheat crop");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void matureCarrotsPotatoesAndBeetrootsAreHarvestedAndReplanted(GameTestHelper helper) {
        Villager farmer = spawnVillager(helper, new BlockPos(7, 2, 7));
        try {
            setFarmer(farmer);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(farmer.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_HOE)),
                    "Could not give the Farmer its physical work hoe");
            FarmerWorldWorkAction action = new FarmerWorldWorkAction();
            harvestAndAssertReplanted(helper, action, farmer, inventory, new BlockPos(8, 3, 8),
                    (CropBlock) Blocks.CARROTS, "totem:farmer_carrot", "minecraft:carrot", "totem:farmer_mature_carrots");
            harvestAndAssertReplanted(helper, action, farmer, inventory, new BlockPos(10, 3, 8),
                    (CropBlock) Blocks.POTATOES, "totem:farmer_potato", "minecraft:potato", "totem:farmer_mature_potatoes");
            harvestAndAssertReplanted(helper, action, farmer, inventory, new BlockPos(12, 3, 8),
                    (CropBlock) Blocks.BEETROOTS, "totem:farmer_beetroot", "minecraft:beetroot", "totem:farmer_mature_beetroots");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    private static void harvestAndAssertReplanted(GameTestHelper helper, FarmerWorldWorkAction action, Villager farmer,
                                                   VillagerWorkInventory inventory, BlockPos crop, CropBlock cropBlock,
                                                   String orderId, String outputId, String targetTag) {
        helper.getLevel().setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        helper.getLevel().setBlock(crop, cropBlock.getStateForAge(cropBlock.getMaxAge()), 3);
        WorkOrder order = new WorkOrder(orderId, "minecraft:farmer", new ItemAmount(outputId, 1), List.of(),
                Set.of(WorkSource.WORLD), targetTag, 40, 64);
        require(helper, action.complete(helper.getLevel(), farmer, crop, order, inventory),
                "Mature " + outputId + " was not harvested");
        require(helper, helper.getLevel().getBlockState(crop).is(cropBlock)
                        && cropBlock.getAge(helper.getLevel().getBlockState(crop)) == 0,
                "Farmer did not replant " + outputId);
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setFarmer(Villager farmer) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer")
        );
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:farmer profession");
        }
        farmer.setVillagerData(farmer.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static int wheatCount(List<ItemStack> inventory) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (stack.is(Items.WHEAT)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
