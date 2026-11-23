package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SettlementReloadGameTest {
    @GameTest(environment="totem:settlement_reload",maxTicks=5000)
    public void anUnloadedWorkforceKeepsItsZonesUntilARealVacancy(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(BlockPos.ZERO);
        int chunkX = (origin.getX() >> 4) + 64;
        int chunkZ = origin.getZ() >> 4;
        var base = new BlockPos((chunkX << 4)+2,100,(chunkZ << 4)+2);
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        level.setChunkForced(chunkX,chunkZ,true);
        level.getChunkAt(base);
        BlockPos.betweenClosedStream(base.below(), base.offset(11,-1,10))
                .forEach(p -> level.setBlock(p,Blocks.DIRT.defaultBlockState(),2));
        var miner = BuilderSettlementGameTest.resident(h,"totem:miner",base.east(),base.south(5));
        var logger = BuilderSettlementGameTest.resident(h,"totem:lumberjack",base.east(5),base.offset(5,0,5));
        var spare = BuilderSettlementGameTest.resident(h,"minecraft:unemployed",base.east(3),base.offset(3,0,7));
        var residents = List.of(miner.getUUID(), logger.getUUID(), spare.getUUID());
        var jobs = WorkerAssignmentSavedData.forServer(level.getServer());
        var owner = UUID.randomUUID();
        var dim = level.dimension().identifier().toString();
        var mine = jobs.createZone("totem:miner",BuilderSettlementGameTest.zone(owner,dim,base,base.offset(2,3,2)));
        var forest = jobs.createZone("totem:lumberjack",BuilderSettlementGameTest.zone(owner,dim,base.east(5),base.offset(8,3,2)));
        var furnace = base.south();
        level.setBlockAndUpdate(furnace,Blocks.FURNACE.defaultBlockState());
        miner.getBrain().setMemory(MemoryModuleType.JOB_SITE,net.minecraft.core.GlobalPos.of(level.dimension(),furnace));
        var minerJob = new WorkerAssignment(miner.getUUID(),"totem:miner",Optional.of(mine.id()),Optional.empty(),true,miner.getBrain().getMemory(MemoryModuleType.HOME));
        var loggerJob = new WorkerAssignment(logger.getUUID(),"totem:lumberjack",Optional.of(forest.id()),Optional.empty(),true,logger.getBrain().getMemory(MemoryModuleType.HOME));
        jobs.putAssignment(minerJob);
        jobs.putAssignment(loggerJob);
        var spareHome = spare.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        h.runBeforeTestEnd(() -> {
            settings.setMode(previous);
            for (var id : residents) {
                var resident = level.getEntity(id);
                if (resident != null) resident.discard();
                jobs.removeAssignment(id);
            }
            jobs.removeZone(mine.id());
            jobs.removeZone(forest.id());
            level.setChunkForced(chunkX,chunkZ,false);
        });
        boolean[] unloading = {false};
        int[] unloaded = {0};
        boolean[] reloaded = {false};
        boolean[] released = {false};
        boolean[] changedWhileAway = {false};
        h.succeedWhen(() -> {
            if (!unloading[0]) {
                h.assertTrue(level.areEntitiesActuallyLoadedAndTicking(new net.minecraft.world.level.ChunkPos(chunkX,chunkZ))
                        && residents.stream().allMatch(id -> level.getEntity(id) instanceof Villager),
                        "Waiting for the initial three residents to be tracked in their ticking chunk");
                unloading[0] = true;
                level.setChunkForced(chunkX,chunkZ,false);
            }
            if (!reloaded[0]) {
                h.assertTrue(!level.isLoaded(base) && residents.stream().noneMatch(id -> level.getEntity(id)!=null),"Waiting for real chunk unload");
                if (!jobs.getAssignment(miner.getUUID()).equals(Optional.of(minerJob))
                        || !jobs.getAssignment(logger.getUUID()).equals(Optional.of(loggerJob))
                        || jobs.getAssignment(spare.getUUID()).isPresent()) changedWhileAway[0]=true;
                if (++unloaded[0] == 200) {
                    reloaded[0]=true;
                    level.setChunkForced(chunkX,chunkZ,true);
                    level.getChunkAt(base);
                }
                throw h.assertionException("Waiting for 200 unloaded ticks and reload");
            }
            if (!released[0]) {
                var current = level.getEntity(miner.getUUID());
                var forester = level.getEntity(logger.getUUID());
                h.assertTrue(current instanceof Villager && forester instanceof Villager && level.getEntity(spare.getUUID())!=null,
                        "Workers did not reload");
                h.assertTrue(!changedWhileAway[0] && current != miner && forester != logger
                        && jobs.getAssignment(miner.getUUID()).equals(Optional.of(minerJob))
                        && jobs.getAssignment(logger.getUUID()).equals(Optional.of(loggerJob))
                        && ((Villager)current).getBrain().getMemory(MemoryModuleType.HOME).equals(minerJob.home())
                        && ((Villager)forester).getBrain().getMemory(MemoryModuleType.HOME).equals(loggerJob.home()),"Unloading lost an owner or original home");
                current.discard();
                released[0]=true;
            }
            var replacement = jobs.getAssignment(spare.getUUID()).orElse(null);
            h.assertTrue(replacement!=null && replacement.workZoneId().equals(Optional.of(mine.id()))
                    && replacement.home().equals(Optional.of(spareHome)) && jobs.getAssignment(miner.getUUID()).isEmpty()
                    && jobs.getAssignment(logger.getUUID()).equals(Optional.of(loggerJob)),"Real vacancy was not filled");
        });
    }
}
