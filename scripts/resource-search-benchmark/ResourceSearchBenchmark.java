package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.worker.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;
import static dev.totem.villagers.world.ResourceSearchCounters.*;

/** Optional isolated dedicated-server workload. Compiled by run.py, never shipped in the mod. */
public final class ResourceSearchBenchmark {
    // The runner adds counters to copies of production classes in the throwaway JVM.
    private record Worker(Villager entity, VillagerWorkInventory stock, BlockPos face, UUID zone, boolean mine) {}
    private static final int WARM = 600, SAMPLE = 800;
    private static final String[] SCENES = {"normal", "empty", "blocked", "furnace"};
    private final List<Worker> workers = new ArrayList<>();
    private final double[] times = new double[SAMPLE], workerTimes = new double[SAMPLE];
    private final Set<UUID> producers = new HashSet<>();
    private int round, ticks, harvests;
    private long start, firstCells, firstPaths, firstFurnaces;
    private boolean running;

    @GameTest(environment="totem:smith_trials", structure="totem:river_households", maxTicks=20000)
    public void measure(GameTestHelper h) {
        var level = h.getLevel();
        WorkBackedTradingSettingsSavedData.forServer(level.getServer()).setMode(WorkBackedTradingMode.ENFORCED);
        ServerTickEvents.START_SERVER_TICK.register(server -> { if (running) start = System.nanoTime(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!running) return;
            if (ticks > WARM) {
                times[ticks-WARM-1] = (System.nanoTime()-start)/1e6;
                workerTimes[ticks-WARM-1] = workNanos/1e6;
            }
            if (ticks == WARM) {
                firstCells=cells; firstPaths=paths; firstFurnaces=furnaces; harvests=0; producers.clear();
            }
            if (ticks == WARM+SAMPLE) {
                for (var w : workers) {
                    if (level.getEntity(w.entity().getUUID()) != w.entity() || !w.entity().isAlive())
                        throw new IllegalStateException("Benchmark worker unloaded");
                }
                if ((round-1)%SCENES.length==0 && producers.size()!=workers.size())
                    throw new IllegalStateException("Not every normal worker harvested: " + producers.size() + "/" + workers.size());
                int scene=(round-1)%SCENES.length;
                if (scene==3 && (furnaces<=firstFurnaces || producers.size()!=workers.size()))
                    throw new IllegalStateException("Furnace search never confirmed all replacement stations");
                for (var w : workers) if (scene!=0) {
                    if (w.stock().countMatchingItem(new ItemStack(w.mine()?Items.COBBLESTONE:Items.OAK_LOG))!=0)
                        throw new IllegalStateException("Worker produced without a reachable source");
                    if (scene==2 && !level.getBlockState(w.face()).is(w.mine()?Blocks.STONE:Blocks.OAK_LOG))
                        throw new IllegalStateException("Sealed resource was harvested");
                }
                Arrays.sort(times);
                Arrays.sort(workerTimes);
                System.out.printf(Locale.ROOT,"BENCH scene=%s workers=%d p95=%.4f cells/t=%.3f paths/t=%.4f furnace/t=%.3f harvests=%d worker_p95=%.4f%n",
                        SCENES[(round-1)%SCENES.length], workers.size(), times[(95*SAMPLE/100)-1],
                        (cells-firstCells)/(double)SAMPLE, (paths-firstPaths)/(double)SAMPLE,
                        (furnaces-firstFurnaces)/(double)SAMPLE, harvests, workerTimes[95*SAMPLE/100-1]);
                running=false;
            }
        });
        h.onEachTick(() -> {
            if (!running) {
                for (var w : workers) {
                    WorkerAssignmentSavedData.forServer(level.getServer()).removeZone(w.zone());
                    w.entity().discard();
                }
                workers.clear();
                if (round == SCENES.length*3) { h.succeed(); return; }
                setup(h, round);
                round++;
                ticks=0; running=true;
            }
            ticks++;
            if ((round-1)%SCENES.length==3) for (var w : workers) {
                var site=w.entity().blockPosition().west(3);
                if (ticks%100==1) {
                    level.removeBlock(w.entity().blockPosition().west(),false);
                    level.removeBlock(site,false);
                    w.entity().getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
                }
                if (ticks%100==51) level.setBlockAndUpdate(site,Blocks.FURNACE.defaultBlockState());
                if (ticks%100==95 && w.entity().getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .filter(job->job.pos().equals(site)).isPresent()) producers.add(w.entity().getUUID());
            }
            if ((round-1)%SCENES.length==0) for (var w : workers) {
                if (!level.getBlockState(w.face()).is(w.mine()?Blocks.STONE:Blocks.OAK_LOG)) {
                    int expected=w.mine()?1:4;
                    var product=new ItemStack(w.mine()?Items.COBBLESTONE:Items.OAK_LOG,expected);
                    w.stock().takeExactMatchingItem(product).orElseThrow(() ->
                            new IllegalStateException("Harvested block did not reach its worker's inventory"));
                    harvests++;
                    producers.add(w.entity().getUUID());
                    plant(level,w);
                    if (!w.mine()) w.stock().insertExact(new ItemStack(Items.OAK_SAPLING));
                }
            }
        });
    }

    private void setup(GameTestHelper h, int index) {
        var level=h.getLevel();
        int sizeGroup = index/SCENES.length;
        int total = new int[]{1,20,50}[Boolean.getBoolean("totem.bench.reverse") ? 2-sizeGroup : sizeGroup];
        for (int i=0;i<total;i++) {
            var origin=new BlockPos(16+(i%5)*28, 180+(i/5)*8, 16);
            for (int cx=(origin.getX()-12)>>4; cx<=(origin.getX()+12)>>4; cx++)
                for (int cz=(origin.getZ()-12)>>4; cz<=(origin.getZ()+12)>>4; cz++)
                    level.setChunkForced(cx,cz,true);
            var target=origin.east(index%SCENES.length==2?5:1);
            boolean mine=index%SCENES.length==3 || (i%2)==0;
            String role=mine?"totem:miner":"totem:lumberjack";
            for (var pos : BlockPos.betweenClosed(origin.offset(-12,-1,-12),origin.offset(12,-1,12)))
                level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
            level.removeBlock(origin.west(3),false);
            for (var pos : BlockPos.betweenClosed(origin,origin.offset(7,5,1))) level.removeBlock(pos,false);
            for (var pos : BlockPos.betweenClosed(origin.offset(3,0,-2),origin.offset(7,5,2))) level.removeBlock(pos,false);
            var worker=(Villager)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager")).create(level, EntitySpawnReason.COMMAND);
            worker.setUUID(new UUID(167+index, 98760+i));
            worker.setNoAi(true);
            worker.snapTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5);
            worker.setVillagerData(worker.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(role)))));
            worker.setVillagerDataFinalized(true);
            level.addFreshEntity(worker);
            VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(worker.getUUID());
            var stock=VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(worker.getUUID());
            stock.insertExact(new ItemStack(Items.BREAD,64));
            stock.insertExact(new ItemStack(mine?Items.DIAMOND_PICKAXE:Items.DIAMOND_AXE));
            stock.insertExact(new ItemStack(Items.OAK_SAPLING,32));
            var jobs=WorkerAssignmentSavedData.forServer(level.getServer());
            var zone=jobs.createZone(role,new WorkZone(worker.getUUID(),level.dimension().identifier().toString(),
                    new BlockCoordinate(origin.getX()-12,origin.getY(),origin.getZ()-12),
                    new BlockCoordinate(origin.getX()+12,origin.getY()+4,origin.getZ()+12)));
            jobs.putAssignment(new WorkerAssignment(worker.getUUID(),role,Optional.of(zone.id()),Optional.empty()));
            if(mine) {
                level.setBlockAndUpdate(origin.west(),Blocks.FURNACE.defaultBlockState());
                worker.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),origin.west()));
            }
            var w=new Worker(worker,stock,target,zone.id(),mine);
            workers.add(w);
            if(index%SCENES.length == 0 || index%SCENES.length == 2) plant(level,w);
            if(index%SCENES.length == 2) {
                // A hollow sealed room has walkable interior stances, but no path from outside.
                for(var p:BlockPos.betweenClosed(target.offset(-2,0,-2),target.offset(2,5,2)))
                    if (Math.abs(p.getX()-target.getX())==2 || Math.abs(p.getZ()-target.getZ())==2 || p.getY()==target.getY()+5)
                        level.setBlock(p,Blocks.BRICKS.defaultBlockState(),2);
            }
        }
    }
    private void plant(ServerLevel level, Worker w) {
        if(w.mine()) level.setBlock(w.face(),Blocks.STONE.defaultBlockState(),2);
        else {
            for(int y=0;y<4;y++)level.setBlock(w.face().above(y),Blocks.OAK_LOG.defaultBlockState(),2);
            level.setBlock(w.face().above(4),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),2);
        }
    }
}
