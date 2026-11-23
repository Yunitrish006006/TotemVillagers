package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.world.NaturalOakTimber;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.HashSet;
import java.util.List;

/** Two-household settlement scenarios driven by server ticks, vanilla brains, growth and real inventories. */
public final class RiverHouseholdGameTest {
    @GameTest(environment = "totem:river_households", structure = "totem:river_households", maxTicks = 48000, padding = 20)
    public void twoResidentsFishAgainAfterLosingTheirFirstRod(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(1, 4, 1));
        var residents = settle(h, origin, true);
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        Runnable cleanup = () -> {
            settings.setMode(previous);
            residents.forEach(Villager::discard);
        };
        h.runBeforeTestEnd(cleanup);
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        boolean[] lostRod = {false};
        var stocked = new HashSet<java.util.UUID>();
        h.succeedWhen(() -> {
            for (var resident : residents) {
                var inventory = inventories.inventory(resident.getUUID());
                if (!lostRod[0] && inventory.takeFirstMatchingItem("minecraft:fishing_rod", 1).isPresent()) lostRod[0] = true;
            }
            h.assertTrue(lostRod[0], "Neither household crafted the first rod");
            for (var resident : residents) {
                var inventory = inventories.inventory(resident.getUUID());
                if (resident.isAlive() && VillagerNutrition.foodLevel(resident) > 8
                        && inventory.snapshot().stream().anyMatch(s -> s.is(Items.FISHING_ROD) && s.getDamageValue() > 0)
                        && inventory.snapshot().stream().mapToInt(s -> s.getCount() * VillagerNutrition.nutrition(s)).sum() >= 20) stocked.add(resident.getUUID());
            }
            for (var resident : residents) {
                var inventory = inventories.inventory(resident.getUUID());
                h.assertTrue(resident.isAlive() && VillagerNutrition.foodLevel(resident) > 8 && stocked.contains(resident.getUUID()),
                        h.getTick() < 47990 ? "Waiting for both households" : "Unrecovered household at " + resident.blockPosition().subtract(origin)
                                + " health=" + resident.getHealth() + " hunger=" + VillagerNutrition.foodLevel(resident)
                                + " status=" + dev.totem.villagers.runtime.VillagerHeadStatus.current(resident)
                                + " stock=" + inventory.snapshot().stream().filter(s -> !s.isEmpty()).toList());
            }
            cleanup.run();
        });
    }

    @GameTest(environment = "totem:inland_households", structure = "totem:river_households", maxTicks = 160000, padding = 20)
    public void twoHouseholdsBakeBreadFromNaturallyGrownWheat(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(1, 4, 1));
        var residents = settle(h, origin, false);
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        for (var v : residents) {
            h.assertTrue(inventories.inventory(v.getUUID()).insertExact(new ItemStack(Items.WHEAT_SEEDS)),
                    "Could not provide the household's starting seed");
        }
        var witness = h.makeMockServerPlayerInLevel();
        var observation = origin.offset(0, 0, -1);
        witness.snapTo(observation.getX() + .5, observation.getY() + 1.5, observation.getZ() + .5);
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        Runnable cleanup = () -> {
            settings.setMode(previous);
            residents.forEach(Villager::discard);
            witness.connection.disconnect(net.minecraft.network.chat.Component.literal("Settlement observation finished"));
        };
        var firstBake = new java.util.LinkedHashMap<java.util.UUID, Long>();
        var firstPlant = new java.util.LinkedHashMap<java.util.UUID, Long>();
        var firstHarvest = new java.util.LinkedHashMap<java.util.UUID, Long>();
        var parcels = dev.totem.villagers.worker.ProductionParcel.get(level.getServer());
        var previousWheat = new java.util.HashMap<java.util.UUID, Integer>();
        var previousHunger = new java.util.HashMap<java.util.UUID, Integer>();
        var deadline = new java.util.concurrent.atomic.AtomicReference<String>();
        h.succeedWhen(() -> {
            if (deadline.get() != null) h.fail(deadline.get());
            for (var v : residents) {
                var stock = inventories.inventory(v.getUUID());
                var id = v.getUUID();
                int wheat = stock.countMatchingItem(new ItemStack(Items.WHEAT));
                int hunger = VillagerNutrition.foodLevel(v);
                if (!firstPlant.containsKey(id) && parcels.claim(id).filter(c -> c.role().equals("starter")).isPresent())
                    firstPlant.put(id, h.getTick());
                if (wheat > 0) firstHarvest.putIfAbsent(id, h.getTick());
                boolean usedWheat = previousWheat.getOrDefault(id, wheat) - wheat == 3;
                if (usedWheat && (stock.countMatchingItem(new ItemStack(Items.BREAD)) > 0
                        || hunger > previousHunger.getOrDefault(id, hunger))) firstBake.putIfAbsent(id, h.getTick());
                previousWheat.put(id, wheat);
                previousHunger.put(id, hunger);
            }
            if (firstBake.size() == residents.size() && residents.stream().allMatch(Villager::isAlive)) {
                com.mojang.logging.LogUtils.getLogger().info("Household first planting {}, first wheat {}, first bread ticks {}", firstPlant, firstHarvest, firstBake);
                cleanup.run();
                return;
            }
            if (h.getTick() >= 160000 || residents.stream().anyMatch(v -> !v.isAlive())) {
                var evidence = residents.stream().map(v -> {
                    var id = v.getUUID();
                    var claim = dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).claim(id);
                    return "house=" + id + " firstPlant=" + firstPlant.get(id) + " firstHarvest=" + firstHarvest.get(id) + " firstBake=" + firstBake.get(id) + " alive=" + v.isAlive()
                            + " pos=" + v.blockPosition().subtract(origin) + " food=" + VillagerNutrition.foodLevel(v)
                            + " home=" + v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME)
                            + " field=" + claim.map(c -> c.cells().stream().map(pos -> level.getBlockState(pos.above())).toList())
                            + " stock=" + inventories.inventory(id).snapshot().stream().filter(stack -> !stack.isEmpty()).toList();
                }).toList();
                deadline.set("Household baking deadline: " + evidence);
                com.mojang.logging.LogUtils.getLogger().info("{}", deadline.get());
                cleanup.run();
                h.fail(deadline.get());
            }
            h.fail("Waiting for both households");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Villager> settle(GameTestHelper h, BlockPos origin, boolean river) {
        var level = h.getLevel();
        for (int x = -1; x <= 13; x++) for (int z = -1; z <= 9; z++) {
            var floor = origin.offset(x, -1, z);
            level.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(floor, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            if (x == -1 || x == 13 || z == -1 || z == 9) level.setBlockAndUpdate(floor.above(), Blocks.OAK_FENCE.defaultBlockState());
            else if (river && (z == 7 || z == 8)) level.setBlock(floor, Blocks.WATER.defaultBlockState(), 2);
            else if (river && z == 6) level.setBlock(floor, Blocks.GRAVEL.defaultBlockState(), 2);
            else if (z <= 2 || river && (z == 3 || (x + z) % 2 == 0)) level.setBlock(floor.above(), Blocks.SHORT_GRASS.defaultBlockState(), 2);
        }
        var bed = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        for (int x : List.of(2, 9)) {
            if (!river) {
                var irrigation = origin.offset(x, -1, 6);
                level.setBlock(irrigation, Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(irrigation.above(), Blocks.OAK_SLAB.defaultBlockState(), 2);
            }
            var foot = origin.offset(x, 0, 4);
            level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
            var trunk = origin.offset(x + 1, 0, 2);
            var placed = new HashSet<BlockPos>();
            for (int y = 0; y < 5; y++) {
                level.setBlock(trunk.above(y), Blocks.OAK_LOG.defaultBlockState(), 2);
                placed.add(trunk.above(y));
            }
            level.setBlock(trunk.above(5), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1), 2);
            NaturalOakTimber.record(level.getChunkAt(trunk), trunk, placed, java.util.Set.of(trunk.above(5)));
        }
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        return List.of(h.spawn(type, new BlockPos(3, 4, 6)), h.spawn(type, new BlockPos(10, 4, 6)));
    }
}
