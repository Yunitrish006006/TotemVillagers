package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.NaturalOakTimber;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class HouseholdForestryGameTest {
    @GameTest(environment = "totem:smith_trials", structure = "totem:river_households")
    @SuppressWarnings("unchecked")
    public void forestTrialRequiresAPaidNeedAndReplantsOnlyRecordedTrees(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var mode = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = mode.settings().mode();
        mode.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 4));
        var worker = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 4));
        var accounts = VillagerWorkInventorySavedData.forServer(server);
        var stock = accounts.inventory(worker.getUUID());
        var wallet = accounts.inventory(buyer.getUUID());
        var jobs = WorkerAssignmentSavedData.forServer(server);
        var tree = worker.blockPosition().east();
        var root = new BlockCoordinate(tree.getX(), tree.getY(), tree.getZ());
        var manual = jobs.createZone("totem:builder", new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(), root, root));
        try {
            for (var resident : List.of(buyer, worker)) {
                var bed = resident.blockPosition().south(4);
                level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                resident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(resident, 20);
                accounts.inventory(resident.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            var home = worker.getBrain().getMemory(MemoryModuleType.HOME);
            var parcels = ProductionParcel.get(server);
            var workerSoil = worker.blockPosition().south().below();
            level.setBlockAndUpdate(workerSoil, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(workerSoil.above(), Blocks.AIR.defaultBlockState());
            level.setBlock(workerSoil.east(), Blocks.WATER.defaultBlockState(), 2);
            level.setBlockAndUpdate(worker.blockPosition().west(), Blocks.CRAFTING_TABLE.defaultBlockState());
            var starterTree = worker.blockPosition().north();
            recordedOak(level, starterTree);
            stock.insertExact(new ItemStack(Items.WHEAT_SEEDS));
            for (int step = 0; step < 16 && parcels.claim(worker.getUUID()).isEmpty(); step++)
                StarterWheatField.work(level, worker, home.orElseThrow().pos(), accounts, stock);
            h.assertTrue(StarterWheatField.owns(level, worker, workerSoil.above()) && level.getBlockState(starterTree).isAir()
                    && dev.totem.villagers.world.FarmerWorldWorkAction.bestHoe(stock).isPresent(),
                    "Second resident failed to cut wood, make a hoe and sow wheat");
            int householdLogs = stock.countMatchingItem(new ItemStack(Items.OAK_LOG));
            var station = GlobalPos.of(level.dimension(), buyer.blockPosition().west());
            level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
            var soil = buyer.blockPosition().north().below();
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            var wheat = (net.minecraft.world.level.block.CropBlock) Blocks.WHEAT;
            level.setBlockAndUpdate(soil.above(), wheat.getStateForAge(wheat.getMaxAge()));
            var buyerHome = buyer.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
            h.assertTrue(parcels.recordStarter(level, buyer, buyerHome, soil), "Missing first household field");
            var hoe = new ItemStack(Items.WOODEN_HOE);
            hoe.setDamageValue(hoe.getMaxDamage() - 5);
            wallet.insertExact(hoe);
            h.assertTrue(!dev.totem.villagers.world.FarmerWorldWorkAction.needsReplacementHoe(wallet), "Farmer ordered a spare early");
            wallet.insertExact(new ItemStack(Items.WHEAT_SEEDS));
            StarterWheatField.work(level, buyer, buyerHome.pos(), accounts, wallet);
            h.assertTrue(buyer.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(station::equals).isPresent()
                    && wallet.countMatchingItem(new ItemStack(Items.WHEAT)) > 0
                    && dev.totem.villagers.world.FarmerWorldWorkAction.needsReplacementHoe(wallet),
                    "First harvest failed to establish a farmer with a worn hoe");
            wallet.insertExact(new ItemStack(Items.EMERALD, 3));
            stock.insertExact(new ItemStack(Items.STONE_AXE));
            var logs = recordedOak(level, tree);
            var crown = tree.above(4);
            mode.setMode(WorkBackedTradingMode.ENFORCED);
            for (int t = 0; t <= 48000; t += 100) HouseholdForestry.request(level, buyer, accounts, t);
            h.assertTrue(jobs.getAssignment(worker.getUUID()).isEmpty(), "Unrenewable trial appointed a lumberjack");
            stock.insertExact(new ItemStack(Items.OAK_SAPLING));
            HouseholdForestry.request(level, buyer, accounts, 48100);
            h.assertTrue(jobs.getAssignment(worker.getUUID()).isEmpty(), "Forest trial crossed a registered zone");
            jobs.removeZone(manual.id());
            while (wallet.insertExact(new ItemStack(Items.COBBLESTONE, 64))) {}
            h.assertTrue(!HouseholdForestry.request(level, buyer, accounts, 48200) && jobs.getAssignment(worker.getUUID()).isEmpty()
                    && StarterWheatField.owns(level, worker, workerSoil.above())
                    && level.getBlockState(tree).is(Blocks.OAK_LOG), "Full buyer commissioned an undeliverable log");
            wallet.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE, 64)).orElseThrow();
            h.assertTrue(HouseholdForestry.request(level, buyer, accounts, 48300), "Paid shortage did not open its first forest plot");
            var assigned = jobs.getAssignment(worker.getUUID()).orElseThrow();
            var record = jobs.getZone(assigned.workZoneId().orElseThrow()).orElseThrow();
            h.assertTrue(parcels.claim(worker.getUUID()).isEmpty() && level.getBlockState(workerSoil.above()).is(Blocks.WHEAT)
                    && assigned.home().equals(home) && record.zone().naturalTimber() && level.getBlockState(tree).is(Blocks.OAK_SAPLING)
                    && stock.countMatchingItem(new ItemStack(Items.OAK_LOG)) == householdLogs + 3, "Forest handover lost home, replant or logs");
            h.assertTrue(wallet.countMatchingItem(new ItemStack(Items.OAK_LOG)) == 1 && wallet.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                    && stock.countMatchingItem(new ItemStack(Items.EMERALD)) == 2, "First log never reached its paying household");
            for (var pos : logs) level.setBlockAndUpdate(pos, Blocks.OAK_LOG.defaultBlockState());
            level.setBlockAndUpdate(crown, Blocks.OAK_LEAVES.defaultBlockState());
            var order = WorkOrderDefinitions.catalog().require("totem:lumberjack_oak_logs");
            var tag = TagKey.create(Registries.BLOCK, Identifier.parse(order.worldTargetTag()));
            h.assertTrue(!LumberjackWorldWorkAction.isEligibleBase(level, worker, record.zone(), tree, tag, order),
                    "Player-replaced logs retained the right to fell a tree");
            for (var pos : logs) level.removeBlock(pos, false);
            level.removeBlock(crown, false);
            var oak = level.registryAccess().lookupOrThrow(Registries.FEATURE)
                    .getOrThrow(net.minecraft.data.worldgen.features.TreeFeatures.OAK).value();
            h.assertTrue(oak.place(level, level.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(10), tree),
                    "Vanilla oak failed to grow on the personal plot");
            stock.insertExact(new ItemStack(Items.OAK_SAPLING));
            h.assertTrue(new LumberjackWorldWorkAction().complete(level, worker, record.zone(), tree, tag, order, stock)
                    && level.getBlockState(tree).is(Blocks.OAK_SAPLING), "Regrown oak could not be harvested and replanted");
            h.assertTrue(NaturalOakTimber.viableForestRoot(level, tree), "Replanted oak lost its parcel");
            level.setBlockAndUpdate(tree, Blocks.OAK_LOG.defaultBlockState());
            VillagerResourceWorkforceRuntime.reconcileResourceHome(level, worker, jobs);
            h.assertTrue(jobs.getZone(record.id()).isEmpty() && jobs.getAssignment(worker.getUUID()).orElseThrow().home().equals(home),
                    "Unsupported trunk retained the parcel or evicted its worker");
            worker.getBrain().eraseMemory(MemoryModuleType.HOME);
            VillagerResourceWorkforceRuntime.reconcileResourceHome(level, worker, jobs);
            h.assertTrue(jobs.getAssignment(worker.getUUID()).orElseThrow().home().isEmpty(), "Moved logger retained the old home");
            h.succeed();
        } finally {
            jobs.removeZone(manual.id());
            jobs.removeAssignment(worker.getUUID());
            mode.setMode(previous);
            worker.discard();
            buyer.discard();
        }
    }
    private static Set<BlockPos> recordedOak(net.minecraft.server.level.ServerLevel level, BlockPos tree) {
        Set<BlockPos> logs = new HashSet<>();
        level.setBlockAndUpdate(tree.below(), Blocks.DIRT.defaultBlockState());
        for (int y = 0; y < 4; y++) {
            logs.add(tree.above(y));
            level.setBlockAndUpdate(tree.above(y), Blocks.OAK_LOG.defaultBlockState());
        }
        var crown = tree.above(4);
        level.setBlockAndUpdate(crown, Blocks.OAK_LEAVES.defaultBlockState());
        NaturalOakTimber.record(level.getChunkAt(tree), tree, logs, Set.of(crown));
        return logs;
    }

}
