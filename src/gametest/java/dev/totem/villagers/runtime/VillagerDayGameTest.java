package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class VillagerDayGameTest {
    @GameTest(environment = "totem:villager_day", structure = "totem:river_households", maxTicks = 1300)
    public void farmerWalksToBellAndBed(GameTestHelper h) {
        var level = h.getLevel();
        var mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = mode.settings().mode();
        mode.setMode(WorkBackedTradingMode.ENFORCED);
        h.setTime(8500);
        var start = h.absolutePos(new BlockPos(3, 6, 3));
        for (var pos : BlockPos.betweenClosed(start.offset(-4,-1,-4), start.offset(16,2,8))) {
            level.setBlock(pos, (pos.getY() < start.getY() ? Blocks.DIRT : Blocks.AIR).defaultBlockState(), 2);
        }
        var bed = start.south(5);
        var station = start.west();
        var bell = start.east(12);
        level.setBlockAndUpdate(station, Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
        var farmer = BuilderSettlementGameTest.resident(h, "minecraft:farmer", start, bed, false);
        farmer.refreshBrain(level);
        farmer.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), station));
        farmer.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        stock.insertExact(new ItemStack(Items.WHEAT, 12));
        stock.insertExact(new ItemStack(Items.BREAD, 4));
        boolean[] arrived = {false};
        boolean[] worked = {false};
        boolean[] slept = {false};
        h.runBeforeTestEnd(() -> { farmer.discard(); mode.setMode(previous); });
        h.onEachTick(() -> {
            if (farmer.getBrain().isActive(Activity.WORK)) worked[0] = true;
            if (h.getTick() == 110) h.setTime(9001);
            if (!arrived[0] && farmer.getBrain().isActive(Activity.MEET) && farmer.blockPosition().distSqr(bell) <= 36) {
                arrived[0] = true;
                h.setTime(12001);
            }
            if (!slept[0] && arrived[0] && farmer.isSleeping() && farmer.getSleepingPos().filter(bed::equals).isPresent()) {
                slept[0] = true;
                level.setBlockAndUpdate(station.north(2), Blocks.CRAFTING_TABLE.defaultBlockState());
                h.setTime(2500);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(worked[0], "Farmer never entered work activity");
            h.assertTrue(arrived[0], "Farmer did not walk from the work area to the bell");
            h.assertTrue(slept[0], "Farmer did not sleep in its claimed bed");
            h.assertTrue(stock.countMatchingItem(new ItemStack(Items.WHEAT)) < 12
                    && stock.countMatchingItem(new ItemStack(Items.BREAD)) > 4, "Work did not resume at the new table after sleeping");
        });
    }

    @GameTest(environment = "totem:smith_trials")
    public void breadNeedsAdjacentLiveTable(GameTestHelper h) {
        var level = h.getLevel();
        var start = h.absolutePos(new BlockPos(2, 3, 2));
        var station = start.west();
        var table = start.east();
        level.setBlockAndUpdate(station, Blocks.COMPOSTER.defaultBlockState());
        var farmer = BuilderSettlementGameTest.resident(h, "minecraft:farmer", start, start.north(2));
        var order = WorkOrderDefinitions.catalog().require("totem:farmer_bread");
        var action = new RecipeBackedWorkshopAction(level, farmer, station, order);
        h.assertTrue(RecipeBackedWorkshopAction.needsCraftingTable(level, order), "Bread must use a three-wide row");
        h.assertTrue(!action.complete(), "Crafted without table");
        level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        h.assertTrue(action.complete(), "Valid table rejected");
        level.removeBlock(table, false);
        var wallet = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        var wheat = new ItemStack(Items.WHEAT, 3);
        var bread = new ItemStack(Items.BREAD);
        wallet.insertExact(wheat);
        var commits = new dev.totem.villagers.workshop.WorkshopCommitService();
        int before = wallet.countMatchingItem(wheat);
        int loaves = wallet.countMatchingItem(bread);
        var result = commits.completePhysical(wallet, order, action, ignored -> bread);
        h.assertTrue(result == dev.totem.villagers.workshop.WorkshopCommitResult.JOB_SITE_REJECTED
                && wallet.countMatchingItem(wheat) == before && wallet.countMatchingItem(bread) == loaves,
                "Lost wheat or credited bread after table removal");
        farmer.discard();
        h.succeed();
    }

    @GameTest(environment = "totem:smith_trials")
    public void observedResourcesAndSheepSurviveEntityReload(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(2, 3, 2));
        var keeper = BuilderSettlementGameTest.resident(h, "minecraft:shepherd", origin, origin.north(2));
        var other = BuilderSettlementGameTest.resident(h, "minecraft:shepherd", origin.east(2), origin.east(3));
        var sheepType = (net.minecraft.world.entity.EntityType<net.minecraft.world.entity.animal.sheep.Sheep>)
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("sheep"));
        var sheep = h.spawnWithNoFreeWill(sheepType, h.relativePos(origin.south()));
        sheep.snapTo(origin.getX() + .5, origin.getY(), origin.getZ() + 1.5);
        var site = origin.west().above();
        level.setBlockAndUpdate(site, Blocks.COAL_ORE.defaultBlockState());
        VillagerWorkMemory.observeBlock(level, keeper, site);
        VillagerWorkMemory.claimAfterShearing(keeper, sheep);
        var records = VillagerWorkMemory.sites(keeper);
        h.assertTrue(records.size() == 2, "Expected resource and individually observed sheep, got " + records + ", sees sheep=" + keeper.hasLineOfSight(sheep));
        var keeperTag = save(keeper);
        var sheepTag = save(sheep);
        keeper.discard();
        sheep.discard();
        var restored = (net.minecraft.world.entity.npc.villager.Villager) keeper.getType().create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
        var restoredSheep = sheepType.create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD);
        restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), keeperTag));
        restoredSheep.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), sheepTag));
        h.assertTrue(VillagerWorkMemory.sites(restored).equals(records), "Last known coordinates changed after reload");
        h.assertTrue(VillagerWorkMemory.canTend(restored, restoredSheep) && !VillagerWorkMemory.canTend(other, restoredSheep),
                "Sheep lost caretaker on reload");
        other.discard();
        h.succeed();
    }

    private static net.minecraft.nbt.CompoundTag save(net.minecraft.world.entity.Entity entity) {
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.level().registryAccess());
        entity.saveWithoutId(out);
        return out.buildResult();
    }
}
