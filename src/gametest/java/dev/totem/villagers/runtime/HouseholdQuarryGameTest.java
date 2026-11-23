package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.NaturalStoneFaces;
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
import java.util.UUID;

public final class HouseholdQuarryGameTest {
    @GameTest(environment = "totem:smith_trials", structure = "totem:river_households")
    @SuppressWarnings("unchecked")
    public void quarryTrialPaysForNaturalStoneAndReleasesAnExhaustedFace(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var mode = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = mode.settings().mode();
        mode.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(3, 16, 4));
        var worker = h.spawnWithNoFreeWill(type, new BlockPos(5, 16, 4));
        var accounts = VillagerWorkInventorySavedData.forServer(server);
        var stock = accounts.inventory(worker.getUUID());
        var wallet = accounts.inventory(buyer.getUUID());
        var jobs = WorkerAssignmentSavedData.forServer(server);
        var face = worker.blockPosition().east();
        var root = new BlockCoordinate(face.getX(), face.getY(), face.getZ());
        var manual = jobs.createZone("totem:builder", new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(), root, root));
        try {
            for (var floor : BlockPos.betweenClosed(face.offset(-4, -1, -3), face.offset(3, -1, 3))) {
                level.setBlockAndUpdate(floor, Blocks.DIRT.defaultBlockState());
            }
            for (var resident : List.of(buyer, worker)) {
                var bed = resident.blockPosition().south(4);
                level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                resident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(resident, 20);
                accounts.inventory(resident.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            var home = worker.getBrain().getMemory(MemoryModuleType.HOME);
            buyer.setVillagerData(buyer.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:toolsmith")))));
            var smithing = buyer.blockPosition().west();
            level.setBlockAndUpdate(smithing, Blocks.SMITHING_TABLE.defaultBlockState());
            buyer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), smithing));
            var farmer = h.spawnWithNoFreeWill(type, new BlockPos(1, 16, 2));
            var farmerBed = farmer.blockPosition().south(4);
            level.setBlock(farmerBed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
            farmer.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), farmerBed));
            farmer.setVillagerData(farmer.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")))));
            var station = GlobalPos.of(level.dimension(), farmer.blockPosition().west());
            level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
            var soil = station.pos().west().below();
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            h.assertTrue(ProductionParcel.get(server).acquire(level, farmer, station, soil, true), "Missing food producer");
            VillagerNutrition.setFoodLevel(farmer, 20);
            var pantry = accounts.inventory(farmer.getUUID());
            pantry.insertExact(new ItemStack(Items.BREAD, 8));
            pantry.insertExact(new ItemStack(Items.WOODEN_HOE));
            wallet.insertExact(new ItemStack(Items.EMERALD, 3));
            wallet.insertExact(new ItemStack(Items.STICK, 2));
            stock.insertExact(new ItemStack(Items.OAK_PLANKS, 3));
            stock.insertExact(new ItemStack(Items.STICK, 2));
            level.setBlockAndUpdate(worker.blockPosition().south(2), Blocks.CRAFTING_TABLE.defaultBlockState());
            var toolCustomer = h.spawnWithNoFreeWill(type, new BlockPos(3, 16, 2));
            toolCustomer.setVillagerData(toolCustomer.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("totem:lumberjack")))));
            var customers = List.of(buyer, toolCustomer);
            var furnace = face.east(2);
            var next = face.north();
            level.setBlockAndUpdate(face, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(next, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
            var chunk = level.getChunkAt(face);
            net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(chunk, Set.of(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG));
            NaturalStoneFaces.recordSurface(chunk);
            if (level.getChunkAt(next) != chunk) NaturalStoneFaces.recordSurface(level.getChunkAt(next));
            mode.setMode(WorkBackedTradingMode.ENFORCED);
            for (int t = 0; t <= 48000; t += 100) ToolsmithVillageEconomyRuntime.serveResourceWorker(level, buyer, customers, accounts, t);
            h.assertTrue(jobs.getAssignment(worker.getUUID()).isEmpty(), "Trial crossed an operator zone");
            jobs.removeZone(manual.id());
            var proposed = HouseholdQuarry.plot(level, worker, furnace);
            h.assertTrue(NaturalStoneFaces.nearby(level, home.orElseThrow().pos(), worker.blockPosition()).contains(face), "No surface record at " + face + ", top=" + chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,face.getX() & 15,face.getZ() & 15));
            h.assertTrue(NaturalStoneFaces.usable(level,worker,proposed,face), "Recorded face is obstructed");
            h.assertTrue(NaturalStoneFaces.furnaces(level,home.orElseThrow().pos(),worker.blockPosition()).contains(furnace), "Missing local furnace");
            h.assertTrue(dev.totem.villagers.world.WorldWorkNavigation.canReach(level,worker,furnace), "No route to furnace");
            ToolsmithVillageEconomyRuntime.serveResourceWorker(level,buyer,customers,accounts,48100);
            h.assertTrue(stock.countMatchingItem(new ItemStack(Items.WOODEN_PICKAXE)) == 1
                    && stock.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 0 && jobs.getAssignment(worker.getUUID()).isEmpty(),
                    "Trial did not craft the first wooden pickaxe at the worktable");
            ToolsmithVillageEconomyRuntime.serveResourceWorker(level,buyer,customers,accounts,48200);
            var assigned = jobs.getAssignment(worker.getUUID()).orElseThrow();
            var plot = jobs.getZone(assigned.workZoneId().orElseThrow()).orElseThrow();
            h.assertTrue(plot.zone().naturalQuarry() && assigned.home().equals(home) && level.getBlockState(face).isAir()
                    && worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow().pos().equals(furnace)
                    && wallet.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 1
                    && wallet.countMatchingItem(new ItemStack(Items.EMERALD)) == 2 && stock.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                    && stock.snapshot().stream().anyMatch(stack -> stack.is(Items.WOODEN_PICKAXE) && stack.getDamageValue() == 1),
                    "First quarry sale lost the home, furnace, tool wear or payment");
            level.setBlockAndUpdate(face, Blocks.STONE.defaultBlockState());
            h.assertTrue(!NaturalStoneFaces.usable(level,worker,plot.zone(),face), "Quarry accepted player replacement");
            h.assertTrue(NaturalStoneFaces.usable(level,worker,plot.zone(),next), "Remaining natural stone lost provenance");
            var kiln = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) level.getBlockEntity(furnace);
            kiln.setItem(0, new ItemStack(Items.RAW_IRON));
            h.assertTrue(dev.totem.villagers.world.MinerFurnaceWorkstation.ensureAssigned(level, worker, plot.zone()).isEmpty()
                    && !dev.totem.villagers.world.MinerFurnaceWorkstation.mayUseAssignedStation(level, worker, furnace),
                    "Assigned quarry reused a player's active furnace");
            kiln.setItem(0, ItemStack.EMPTY);
            level.removeBlock(next, false);
            VillagerResourceWorkforceRuntime.reconcileResourceHome(level,worker,jobs);
            h.assertTrue(jobs.getZone(plot.id()).isEmpty() && jobs.getAssignment(worker.getUUID()).orElseThrow().home().equals(home),
                    "Depleted quarry retained the parcel or evicted its worker");
            farmer.discard();
            toolCustomer.discard();
            h.succeed();
        } finally {
            jobs.removeZone(manual.id());
            jobs.removeAssignment(worker.getUUID());
            mode.setMode(previous);
            worker.discard();
            buyer.discard();
        }
    }
}
