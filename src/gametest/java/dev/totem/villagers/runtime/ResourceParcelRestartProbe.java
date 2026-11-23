package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.worker.*;
import dev.totem.villagers.world.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class ResourceParcelRestartProbe implements ModInitializer {
    private final Path record = Path.of("resource-record");
    private String phase;
    private BlockPos face;
    private UUID minerId, loggerId, mineId, forestId, librarianId;
    private boolean done;

    public void onInitialize() {
        if (!Boolean.getBoolean("totem.villagers.resourceRestart")) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                phase = Files.readString(Path.of("resource-phase")).trim();
                var level = server.overworld();
                WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.DISABLED);
                if (phase.equals("prepare")) {
                    face = generatedFace(level);
                    prepare(level);
                    Files.write(record, List.of(Long.toString(face.asLong()), minerId.toString(), loggerId.toString(), mineId.toString(), forestId.toString(), librarianId.toString()));
                } else {
                    var lines = Files.readAllLines(record);
                    face = BlockPos.of(Long.parseLong(lines.get(0)));
                    minerId = UUID.fromString(lines.get(1)); loggerId = UUID.fromString(lines.get(2));
                    mineId = UUID.fromString(lines.get(3)); forestId = UUID.fromString(lines.get(4)); librarianId = UUID.fromString(lines.get(5));
                }
                for (var p : List.of(face, face.west(), root(), root().west(), root().offset(-7,5,-3))) {
                    level.setChunkForced(p.getX()>>4, p.getZ()>>4, true);
                    level.getChunkAt(p);
                }
            } catch (IOException e) { throw new IllegalStateException(e); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done || face == null) return;
            var level = server.overworld();
            if (!(level.getEntity(minerId) instanceof Villager miner) || !(level.getEntity(loggerId) instanceof Villager logger) || !(level.getEntity(librarianId) instanceof Villager librarian)) {
                if (server.getTickCount() > 400) throw new IllegalStateException("Resource workers failed to reload");
                return;
            }
            var library = root().offset(-6,5,-3);
            require(librarian.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.LIBRARIAN)
                    && librarian.getBrain().getMemory(MemoryModuleType.HOME).equals(Optional.of(GlobalPos.of(level.dimension(),library)))
                    && librarian.getBrain().getMemory(MemoryModuleType.JOB_SITE).equals(Optional.of(GlobalPos.of(level.dimension(),library.east())))
                    && librarian.getVillagerXp()==0 && !VillagerCareerHistory.mayChange(librarian), "Traded librarian lost home, lectern or career lock");
            var offers = librarian.getOffers();
            require(offers.size()==1 && offers.getFirst().getUses()==1 && offers.getFirst().getBaseCostA().is(Items.PAPER)
                    && offers.getFirst().getBaseCostA().getCount()==3 && offers.getFirst().getResult().is(Items.EMERALD),"Trade was refreshed on reload");
            var jobs = WorkerAssignmentSavedData.forServer(server);
            var mine = verifyAssignment(level, jobs, miner, mineId, face, true);
            var forest = verifyAssignment(level, jobs, logger, forestId, root(), false);
            var stocks = VillagerWorkInventorySavedData.forServer(server);
            var mineStock = stocks.inventory(minerId);
            var forestStock = stocks.inventory(loggerId);
            if (!phase.equals("verify")) {
                require(NaturalStoneFaces.usable(level, miner, mine, face), "Terrain provenance missing at " + face);
                var catalog = WorkOrderDefinitions.catalog();
                var wood = catalog.require("totem:lumberjack_oak_logs");
                var woodTag = TagKey.create(Registries.BLOCK,Identifier.parse(wood.worldTargetTag()));
                require(LumberjackWorldWorkAction.isEligibleBase(level,logger,forest,root(),woodTag,wood),"Personal oak provenance missing");
                if (phase.equals("resume")) {
                    var stone = catalog.require("totem:miner_stone");
                    require(new MinerWorldWorkAction().completeForOrder(level,miner,face,TagKey.create(Registries.BLOCK,Identifier.parse(stone.worldTargetTag())),
                            stone,mineStock,new ItemStack(Items.COBBLESTONE)),"Reloaded miner failed to mine");
                    require(new LumberjackWorldWorkAction().complete(level,logger,forest,root(),woodTag,wood,forestStock),"Reloaded logger failed to replant");
                    level.setBlockAndUpdate(face,Blocks.STONE.defaultBlockState());
                }
            } else {
                require(!NaturalStoneFaces.usable(level,miner,mine,face) && mineStock.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 1,
                        "Replaced stone regained provenance or harvested goods were lost");
                require(level.getBlockState(root()).is(Blocks.OAK_SAPLING) && forestStock.countMatchingItem(new ItemStack(Items.OAK_LOG)) >= 4,
                        "Reload lost sapling or harvested oak");
            }
            server.saveEverything(false,true,false);
            try { Files.writeString(Path.of("resource-"+phase+"-passed"),face.toString()); }
            catch (IOException e) { throw new IllegalStateException(e); }
            done = true;
            server.halt(false);
        });
    }

    private BlockPos root() { return face.east(8).above(16); }

    private static BlockPos generatedFace(ServerLevel level) {
        for (int x=-3;x<=3;x++) for (int z=-3;z<=3;z++) {
            level.getChunk(x,z);
            var center = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE,new BlockPos(x*16+8,0,z*16+8));
            var faces = NaturalStoneFaces.nearby(level,center,center);
            for (var p : faces) {
                if ((p.getX() & 15) >= 3 && (p.getX() & 15) <= 12 && (p.getZ() & 15) >= 3 && (p.getZ() & 15) <= 12
                        && level.getBlockState(p.above()).isAir() && level.canSeeSky(p.above())
                        && !level.structureManager().getStructureWithPieceAt(p,h -> true).isValid()) return p;
            }
        }
        throw new IllegalStateException("No recorded stony terrain in seeded world");
    }

    private void prepare(ServerLevel level) {
        // The mine cell itself comes from terrain generation. Only prepare its adjacent stance.
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var near = face.relative(direction);
            level.removeBlock(near,false);
        }
        var miner = spawn(level,face,"totem:miner");
        var logger = spawn(level,root(),"totem:lumberjack");
        minerId = miner.getUUID(); loggerId = logger.getUUID();
        var library = root().offset(-6,5,-3);
        var librarian = spawn(level,library,"minecraft:librarian");
        librarianId = librarian.getUUID();
        level.setBlock(library,BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(),2);
        level.setBlockAndUpdate(library.east(),Blocks.LECTERN.defaultBlockState());
        level.setBlockAndUpdate(library.north(),Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        librarian.getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),library));
        librarian.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),library.east()));
        var offer = new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.PAPER,3),
                new ItemStack(Items.EMERALD),8,0,0F);
        librarian.getOffers().clear();
        librarian.getOffers().add(offer);
        librarian.notifyTrade(offer);
        var jobs = WorkerAssignmentSavedData.forServer(level.getServer());
        var furnace = face.east(2);
        level.setBlockAndUpdate(furnace,Blocks.FURNACE.defaultBlockState());
        var plot = HouseholdQuarry.plot(level,miner,furnace);
        mineId = assign(level,jobs,miner,plot,face,"totem:miner");
        miner.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),furnace));
        level.setBlockAndUpdate(root().below(),Blocks.DIRT.defaultBlockState());
        forestId = assign(level,jobs,logger,new WorkZone(loggerId,level.dimension().identifier().toString(),at(root()),at(root().above(7)),true),root(),"totem:lumberjack");
        var oak = level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(net.minecraft.data.worldgen.features.TreeFeatures.OAK).value();
        require(oak.place(level,level.getChunkSource().getGenerator(),net.minecraft.util.RandomSource.create(10),root()),"Oak did not grow");
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer());
        stock.inventory(minerId).insertExact(new ItemStack(Items.WOODEN_PICKAXE));
        stock.inventory(loggerId).insertExact(new ItemStack(Items.STONE_AXE));
        stock.inventory(loggerId).insertExact(new ItemStack(Items.OAK_SAPLING));
    }

    private static Villager spawn(ServerLevel level,BlockPos target,String role) {
        var villager = (Villager) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager")).create(level, EntitySpawnReason.COMMAND);
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(role)))));
        villager.setVillagerDataFinalized(true);
        var stance = target.west();
        level.setBlockAndUpdate(stance.below(),Blocks.DIRT.defaultBlockState());
        level.removeBlock(stance.above(),false);
        villager.snapTo(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        require(level.addFreshEntity(villager),"Could not add worker");
        return villager;
    }

    private static UUID assign(ServerLevel level,WorkerAssignmentSavedData jobs,Villager worker,WorkZone zone,BlockPos target,String role) {
        var bed = target.south(4);
        level.setBlock(bed,BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(),2);
        var home = GlobalPos.of(level.dimension(),bed);
        worker.getBrain().setMemory(MemoryModuleType.HOME,home);
        var id = jobs.createZone(role,zone).id();
        jobs.putAssignment(new WorkerAssignment(worker.getUUID(),role,Optional.of(id),Optional.empty(),true,Optional.of(home)));
        return id;
    }

    private static WorkZone verifyAssignment(ServerLevel level,WorkerAssignmentSavedData jobs,Villager worker,UUID id,BlockPos target,boolean miner) {
        var a = jobs.getAssignment(worker.getUUID()).orElseThrow();
        var zone = jobs.getZone(id).orElseThrow().zone();
        require(a.workZoneId().filter(id::equals).isPresent() && zone.ownerId().equals(worker.getUUID())
                && zone.personal() && zone.naturalQuarry() == miner && a.home().equals(Optional.of(GlobalPos.of(level.dimension(),target.south(4))))
                && a.home().equals(worker.getBrain().getMemory(MemoryModuleType.HOME)),"Lost personal parcel or home");
        return zone;
    }
    private static BlockCoordinate at(BlockPos p) { return new BlockCoordinate(p.getX(), p.getY(), p.getZ()); }
    private static void require(boolean test, String message) { if (!test) throw new IllegalStateException(message); }
}
