package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.NaturalWorkTrees;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Set;

public final class WildResourceGameTest {
    @GameTest(environment = "totem:wild_resource_load", structure = "totem:river_households", maxTicks = 650)
    @SuppressWarnings("unchecked")
    public void twentyWorkersShareDiscoveryBudget(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var workers = new java.util.ArrayList<Villager>();
        var center = h.absolutePos(new BlockPos(5, 24, 5));
        var bed = center.west(2);
        level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
        for (var pos : BlockPos.betweenClosed(center.offset(-2,-1,-2), center.offset(8,-1,8)))
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
        for (int i = 0; i < 20; i++) {
            var worker = h.spawnWithNoFreeWill(type, new BlockPos(5,24,5));
            worker.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
            workers.add(worker);
        }
        for (int x = 2; x < 9; x += 2) for (int z = 0; z < 9; z += 2) {
            var base = center.offset(x,0,z);
            level.setBlock(base, Blocks.OAK_LOG.defaultBlockState(), 2);
            level.setBlock(base.above(), Blocks.OAK_LOG.defaultBlockState(), 2);
            level.setBlock(base.above(2), Blocks.OAK_LEAVES.defaultBlockState(), 2);
            NaturalWorkTrees.record(level.getChunkAt(base), Set.of(base,base.above()), Set.of(base.above(2)));
        }
        var order = WorkOrderDefinitions.catalog().require("totem:lumberjack_oak_logs");
        var queue = WildResourceSearch.get(level);
        long[] previous = {queue.nextPathTick()};
        int[] attempts = {0}, ticks = {0};
        var matched = new java.util.HashSet<java.util.UUID>();
        h.onEachTick(() -> {
            for (var worker : workers) {
                if (!matched.contains(worker.getUUID()) && queue.request(level, worker, List.of(order)) != null)
                    matched.add(worker.getUUID());
            }
            queue.tick(level);
            long next = queue.nextPathTick();
            if (next != previous[0]) {
                h.assertTrue(next - previous[0] >= 5, "Pathfinding ran twice in a five-tick window");
                previous[0] = next;
                attempts[0]++;
            }
            if (++ticks[0] == 500) {
                h.assertTrue(attempts[0] >= 2 && attempts[0] <= 100, "Expected multiple budgeted probes for 20 workers: " + attempts[0]);
                h.assertTrue(matched.size() >= 2, "Discovery did not serve multiple workers");
                System.out.println("Wild resource discovery: workers=20 ticks=500 probes=" + attempts[0] + " matched=" + matched.size());
                h.succeed();
            }
        });
    }

    @GameTest(environment = "totem:wild_resources", structure = "totem:river_households", maxTicks = 300)
    @SuppressWarnings("unchecked")
    public void naturalWorkersShareOneTreeAndReplant(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var a = h.spawnWithNoFreeWill(type, new BlockPos(5, 16, 5));
        var b = h.spawnWithNoFreeWill(type, new BlockPos(5, 16, 6));
        var base = a.blockPosition().east();
        var chunk = level.getChunkAt(base);
        var wood = Blocks.MANGROVE_LOG.defaultBlockState();
        var leaf = base.above(3);
        level.setBlockAndUpdate(base.below(), Blocks.MUD.defaultBlockState());
        for (var p : List.of(base, base.above(), base.above(2))) level.setBlockAndUpdate(p, wood);
        level.setBlockAndUpdate(leaf, Blocks.MANGROVE_LEAVES.defaultBlockState());
        NaturalWorkTrees.record(chunk, Set.of(base, base.above(), base.above(2)), Set.of(leaf));
        var order = WorkOrderDefinitions.catalog().require("totem:lumberjack_mangrove_logs");
        var queue = WildResourceSearch.get(level);
        for (var worker : List.of(a, b)) {
            var bed = worker.blockPosition().west(2);
            level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
            worker.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
            queue.request(level, worker, List.of(order));
        }
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(a.getUUID());
        stock.insertExact(new ItemStack(Items.IRON_AXE));
        stock.insertExact(new ItemStack(Items.MANGROVE_PROPAGULE));
        h.succeedWhen(() -> {
            queue.tick(level);
            var selected = queue.request(level, a, List.of(order));
            h.assertTrue(selected != null && selected.pos().equals(base), "First worker must find the indexed mangrove");
            h.assertTrue(!queue.reserve(level, b, base), "Second worker must not take the claimed tree");
            h.assertTrue(new LumberjackWorldWorkAction().completeWild(level, a, NaturalWorkTrees.at(level,base), order, stock),
                    "Adjacent worker must harvest and replant");
            h.assertTrue(stock.countMatchingItem(new ItemStack(Items.MANGROVE_LOG)) == 3, "Must receive the three real logs");
            h.assertTrue(NaturalWorkTrees.at(level,base) == null, "Harvest must invalidate the tree");
            h.assertTrue(NaturalWorkTrees.isPlanted(chunk,base) && level.getBlockState(base).is(Blocks.MANGROVE_PROPAGULE),
                    "Replant must retain provenance");
            level.setBlockAndUpdate(base, wood);
            h.assertTrue(!NaturalWorkTrees.isPlanted(chunk,base) && NaturalWorkTrees.at(level,base) == null,
                    "Replacing the sapling with a player log must not create a work target");
        });
    }
}
