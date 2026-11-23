package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

import java.util.Optional;
import java.util.UUID;

public final class ResourceDiscoveryGameTest {
    @GameTest(environment = "totem:discovery_mine", structure = "totem:river_households", padding = 40, maxTicks = 2000)
    public void minersWalkToStoneBeyondTheFirstPage(GameTestHelper h) { distantHarvest(h, true); }

    @GameTest(environment = "totem:discovery_forest", structure = "totem:river_households", padding = 40, maxTicks = 2000)
    public void forestersWalkToTreesBeyondTheFirstPage(GameTestHelper h) { distantHarvest(h, false); }

    private void distantHarvest(GameTestHelper h, boolean mine) {
        var level = h.getLevel();
        var server = level.getServer();
        var mode = WorkBackedTradingSettingsSavedData.forServer(server);
        var old = mode.settings().mode();
        mode.setMode(WorkBackedTradingMode.ENFORCED);
        var origin = h.absolutePos(new BlockPos(2, 6, 2));
        var target = origin.east(12).above(mine ? 1 : 0);
        for (var pos : BlockPos.betweenClosed(origin.offset(-12,0,-12), origin.offset(12,0,12))) {
            level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), 2);
            level.removeBlock(pos, false);
            level.removeBlock(pos.above(), false);
        }
        String role = mine ? "totem:miner" : "totem:lumberjack";
        var worker = BuilderSettlementGameTest.resident(h, role, origin, origin.west(2), false);
        // Keep random village wandering from moving the search origin inside the old 512-cell window.
        // Release normal movement as soon as the production scheduler independently selects the far block.
        worker.setNoAi(true);
        var jobs = WorkerAssignmentSavedData.forServer(server);
        var zone = jobs.createZone(role, new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(origin.getX()-12, origin.getY(), origin.getZ()-12),
                new BlockCoordinate(origin.getX()+12, origin.getY()+4, origin.getZ()+12)));
        jobs.putAssignment(new WorkerAssignment(worker.getUUID(), role, Optional.of(zone.id()), Optional.empty()));
        var stock = VillagerWorkInventorySavedData.forServer(server).inventory(worker.getUUID());
        stock.insertExact(new ItemStack(Items.BREAD, 8));
        if (mine) {
            level.setBlockAndUpdate(origin.north(), Blocks.FURNACE.defaultBlockState());
            worker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), origin.north()));
            stock.insertExact(new ItemStack(Items.STONE_PICKAXE));
            // The coal order starts six idle scans later, so its miss/backoff phase differs.
            stock.insertExact(new ItemStack(Items.COAL, 64));
            h.runAfterDelay(120, () -> stock.takeExactMatchingItem(new ItemStack(Items.COAL, 64)).orElseThrow());
        } else {
            stock.insertExact(new ItemStack(Items.STONE_AXE));
            stock.insertExact(new ItemStack(Items.OAK_SAPLING));
            for (int y = 0; y < 4; y++) level.setBlockAndUpdate(target.above(y), Blocks.OAK_LOG.defaultBlockState());
            level.setBlockAndUpdate(target.above(4), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        h.runBeforeTestEnd(() -> {
            jobs.removeZone(zone.id());
            worker.discard();
            mode.setMode(old);
        });
        // Recruitment must also eventually prove the same distant supplier can work.
        var states = dev.totem.villagers.work.VillagerWorkSavedData.forServer(server);
        boolean[] supplier = {false};
        boolean[] opened = {!mine};
        boolean[] premature = {false};
        h.succeedWhen(() -> {
            if (!opened[0] && VillagerHeadStatus.current(worker) == VillagerHeadStatus.Kind.NO_MINE_FACE) {
                if (h.getTick() < 480) premature[0] = true;
                level.setBlockAndUpdate(target.below(), Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
                opened[0] = true;
            }
            if (opened[0] && !supplier[0]) supplier[0] = VillagerWorldWorkRuntime.canKeepSupplying(level, worker, zone);
            if (worker.isNoAi() && supplier[0] && states.get(worker.getUUID()).flatMap(s -> s.activeWork())
                    .flatMap(work -> work.worldTarget()).flatMap(pos -> pos.packedBlockPosition())
                    .filter(pos -> pos == target.asLong()).isPresent()) worker.setNoAi(false);

            h.assertTrue(!premature[0] && supplier[0] && stock.countMatchingItem(new ItemStack(mine ? Items.COBBLESTONE : Items.OAK_LOG)) >= (mine ? 1 : 4)
                    && (mine ? level.getBlockState(target).isAir() : level.getBlockState(target).is(Blocks.OAK_SAPLING))
                    && worker.blockPosition().distSqr(origin) >= 100, "Waiting for distant worker to walk, harvest and store the output");
        });
    }
}
