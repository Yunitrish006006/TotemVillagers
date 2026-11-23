package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.world.ShepherdWorldWorkAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class ShepherdEquipmentGameTest {
    @SuppressWarnings("unchecked")
    @GameTest(environment = "totem:smith_trials")
    public void shepherdBuysSpareShearsAndUsesThemOnTheFlock(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var shepherd = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var smith = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var sheep = h.spawnWithNoFreeWill((EntityType<Sheep>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:sheep")),
                new BlockPos(3, 3, 4));
        try {
            shepherd.setVillagerData(shepherd.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:shepherd")))));
            smith.setVillagerData(smith.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:toolsmith")))));
            var loom = shepherd.blockPosition().west();
            var bed = loom.north();
            level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
            shepherd.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
            for (int i = 1; i <= 3; i++) level.setBlockAndUpdate(shepherd.blockPosition().south(i).below(), Blocks.GRASS_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(loom, Blocks.LOOM.defaultBlockState());
            shepherd.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), loom));
            VillagerNutrition.setFoodLevel(shepherd, 20);
            sheep.setColor(DyeColor.WHITE);
            var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
            var buyer = inventories.inventory(shepherd.getUUID());
            var seller = inventories.inventory(smith.getUUID());
            var shears = new ItemStack(Items.SHEARS);
            var emerald = new ItemStack(Items.EMERALD);
            var wool = new ItemStack(Blocks.WOOL.pick(DyeColor.WHITE));
            buyer.insertExact(emerald.copyWithCount(4));
            var oldPair = shears.copy();
            oldPair.setDamageValue(9);
            seller.insertExact(oldPair);
            var order = WorkOrderDefinitions.catalog().snapshot().get("totem:shepherd_white_wool");
            var action = new ShepherdWorldWorkAction();
            h.assertTrue(!action.complete(level, shepherd, sheep, order, buyer) && sheep.readyForShearing(), "Bare hands sheared sheep");
            VillagerShepherdWorkRuntime.tickForGameTest(level.getServer());
            h.assertTrue(buyer.countMatchingItem(emerald) == 4 && seller.countMatchingItem(oldPair) == 1, "Sold working shears");
            seller.insertExact(shears);
            for (int i = 0; i < 30; i++) VillagerShepherdWorkRuntime.tickForGameTest(level.getServer());
            var worn = shears.copy();
            worn.setDamageValue(1);
            h.assertTrue(!sheep.readyForShearing() && buyer.countMatchingItem(wool) == 1 && buyer.countMatchingItem(worn) == 1
                    && seller.countMatchingItem(oldPair) == 1 && seller.countMatchingItem(emerald) == 3 && buyer.countMatchingItem(emerald) == 1,
                    "Missing physical wool, wear or payment");
            buyer.takeExactMatchingItem(worn).orElseThrow();
            worn.setDamageValue(worn.getMaxDamage() - 1);
            buyer.insertExact(worn);
            sheep.setSheared(false);
            sheep.snapTo(shepherd.getX(), shepherd.getY(), shepherd.getZ() + 3);
            var wall = shepherd.blockPosition().south();
            level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            h.assertTrue(!action.complete(level, shepherd, sheep, order, buyer)
                    && sheep.readyForShearing() && buyer.countMatchingItem(worn) == 1, "Sheared through a wall");
            level.setBlockAndUpdate(wall.above(), Blocks.AIR.defaultBlockState());
            h.assertTrue(action.complete(level, shepherd, sheep, order, buyer) && buyer.countMatchingItem(wool) == 2
                    && buyer.countMatchingItem(worn) == 0 && !action.complete(level, shepherd, sheep, order, buyer), "Last use duplicated shears or wool");
            h.succeed();
        } finally {
            shepherd.discard();
            smith.discard();
            sheep.discard();
        }
    }
}
