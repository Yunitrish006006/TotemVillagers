package dev.totem.villagers.runtime;

import dev.totem.villagers.builder.*;
import dev.totem.villagers.config.*;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An inhabited village repairs two missing foundation blocks after a newly approved project ages in real time. */
public final class BuilderSettlementGameTest {
    @GameTest(environment = "totem:builder_mine", structure = "totem:river_households", maxTicks = 54000, padding = 32)
    public void minersSupplyTheirOwnBuildingProject(GameTestHelper h) { settlement(h, true); }

    @GameTest(environment = "totem:builder_forest", structure = "totem:river_households", maxTicks = 54000, padding = 32)
    public void forestersSupplyTheirOwnBuildingProject(GameTestHelper h) { settlement(h, false); }

    @GameTest(structure = "totem:river_households", padding = 48)
    public void buyOnlyMatchingSurplusFromLocalForester(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var sites = BuilderSiteSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var anchor = h.absolutePos(new BlockPos(4, 10, 4));
        var template = Identifier.parse("minecraft:village/plains/houses/plains_small_house_1");
        var blocks = VanillaVillageBlueprints.resolve(server,template,anchor).orElseThrow().blocks();
        var next = blocks.stream().filter(b -> b.state().is(Blocks.OAK_PLANKS)).findFirst().orElseThrow();
        var target = next.position();
        var stand = target.west();
        level.setBlockAndUpdate(stand.below(), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(stand.south().below(), Blocks.DIRT.defaultBlockState());
        var buyer = resident(h, "totem:builder", stand, stand.west(34));
        var forester = resident(h, "totem:lumberjack", stand.south(), stand.west(15));
        var owner = UUID.randomUUID();
        var dim = level.dimension().identifier().toString();
        var job = assignments.createZone("totem:builder",zone(owner,dim,anchor,anchor.offset(7,8,7)));
        var local = assignments.createZone("totem:lumberjack",zone(owner,dim,anchor.west(2),anchor.west()));
        var foreign = assignments.createZone("totem:lumberjack",zone(UUID.randomUUID(),dim,anchor.north(2),anchor.north()));
        var site = new BuilderSite(UUID.randomUUID(),owner,buyer.getUUID(),template.toString(),dim,anchor.asLong(),blocks.indexOf(next));
        sites.registerOrReplace(site,owner);
        assignments.putAssignment(new WorkerAssignment(buyer.getUUID(),"totem:builder",Optional.of(job.id()),Optional.empty()));
        var purse = inventories.inventory(buyer.getUUID());
        var stock = inventories.inventory(forester.getUUID());
        var coin = new ItemStack(Items.EMERALD);
        var plank = new ItemStack(Items.OAK_PLANKS);
        purse.insertExact(coin.copy());
        stock.insertExact(new ItemStack(Items.BIRCH_LOG));
        try {
            assignments.putAssignment(new WorkerAssignment(forester.getUUID(),"totem:lumberjack",Optional.of(local.id()),Optional.empty()));
            BuilderMaterialOrders.request(level,buyer,site,next,purse,100);
            h.assertTrue(purse.countMatchingItem(coin)==1 && stock.countMatchingItem(coin)==0,"Paid for the wrong wood species");
            stock.insertExact(plank.copy());
            assignments.putAssignment(new WorkerAssignment(forester.getUUID(),"totem:lumberjack",Optional.of(foreign.id()),Optional.empty()));
            BuilderMaterialOrders.request(level,buyer,site,next,purse,200);
            h.assertTrue(purse.countMatchingItem(coin)==1 && stock.countMatchingItem(plank)==1,"Bought another owner's stock");
            assignments.putAssignment(new WorkerAssignment(forester.getUUID(),"totem:lumberjack",Optional.of(local.id()),Optional.empty()));
            BuilderMaterialOrders.request(level,buyer,site,next,purse,300);
            var states = dev.totem.villagers.work.VillagerWorkSavedData.forServer(server);
            buyer.snapTo(target.getX()+.5,target.getY(),target.getZ()+.5,0,0);
            VillagerBuilderRuntime.tickBuilder(server,level,buyer,site,sites,states,assignments,inventories);
            h.assertTrue(level.getBlockState(target).isAir() && purse.countMatchingItem(plank)==1,"Placed inside the builder");
            buyer.snapTo(stand.getX()+.5,stand.getY(),stand.getZ()+.5,0,0);
            forester.snapTo(target.getX()+.5,target.getY(),target.getZ()+.5,0,0);
            VillagerBuilderRuntime.tickBuilder(server,level,buyer,site,sites,states,assignments,inventories);
            h.assertTrue(level.getBlockState(target).isAir() && purse.countMatchingItem(plank)==1,"Placed inside the supplier");
            forester.snapTo(stand.getX()+.5,stand.getY(),stand.getZ()+1.5,0,0);
            VillagerBuilderRuntime.tickBuilder(server,level,buyer,site,sites,states,assignments,inventories);
            h.assertTrue(level.getBlockState(target).equals(next.state()) && purse.countMatchingItem(coin)==0
                    && stock.countMatchingItem(coin)==1 && stock.countMatchingItem(plank)==0 && purse.countMatchingItem(plank)==0,
                    "Paid plank was not installed at the worksite");
            purse.insertExact(new ItemStack(Items.OAK_LOG));
            BuilderMaterialOrders.request(level,buyer,site,next,purse,350);
            h.assertTrue(purse.countMatchingItem(plank)==4 && purse.countMatchingItem(new ItemStack(Items.OAK_LOG))==0,
                    "One oak log did not yield the four matching boards");
            purse.takeExactMatchingItem(plank.copyWithCount(4)).orElseThrow();
            // If converting stocked logs cannot return four planks, repeated requests must preserve the wallet.
            stock.insertExact(new ItemStack(Items.OAK_LOG, 2));
            purse.insertExact(new ItemStack(Items.EMERALD, 8));
            purse.insertExact(new ItemStack(Items.OAK_LOG, 2));
            int slots = purse.snapshot().size();
            for (int i=0;i<slots;i++) purse.insertExact(new ItemStack(Items.WOODEN_SHOVEL));
            BuilderMaterialOrders.request(level,buyer,site,next,purse,400);
            BuilderMaterialOrders.request(level,buyer,site,next,purse,500);
            h.assertTrue(purse.countMatchingItem(coin)==8 && purse.countMatchingItem(new ItemStack(Items.OAK_LOG))==2,
                    "Repeated orders paid for extra logs without room for planks");
            h.succeed();
        } finally {
            sites.removeByBuilder(buyer.getUUID(),owner);
            for (var area : List.of(job,local,foreign)) assignments.removeZone(area.id());
            buyer.discard();
            forester.discard();
        }
    }

    private static void settlement(GameTestHelper h, boolean mine) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var sites = BuilderSiteSavedData.forServer(server);
        var anchor = h.absolutePos(new BlockPos(6, 10, 6));
        var template = Identifier.parse("minecraft:village/plains/houses/plains_small_house_" + (mine ? "1" : "3"));
        var blueprint = VanillaVillageBlueprints.resolve(server, template, anchor).orElseThrow();
        var first = blueprint.blocks().getFirst();
        var second = blueprint.blocks().stream().filter(b -> b.state().is(mine ? Blocks.COBBLESTONE : Blocks.OAK_LOG))
                .filter(b -> mine || b.position().equals(anchor.offset(1,1,1))).findFirst().orElseThrow();
        BlockPos.betweenClosedStream(anchor.offset(-6,-1,-5),anchor.offset(9,-1,9))
                .forEach(p -> level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2));
        for (var b : blueprint.blocks()) {
            if (b != first && b != second) level.setBlock(b.position(), b.state(), 2);
        }
        var role = mine ? "totem:miner" : "totem:lumberjack";
        var candidate = resident(h, role, anchor.offset(-1,0,2), anchor.offset(-4,0,2));
        var supplier = resident(h, role, anchor.offset(-2,0,1), anchor.offset(-4,0,4));
        var farmer = resident(h, "minecraft:farmer", anchor.offset(0,0,-3), anchor.offset(-4,0,-2));
        var residents = List.of(candidate, supplier, farmer);
        var owner = UUID.randomUUID();
        var dimension = level.dimension().identifier().toString();
        var work = assignments.createZone("totem:builder", zone(owner,dimension,anchor,anchor.offset(7,8,7)));
        var donorStation = anchor.offset(-1,0,-3);
        var donor = assignments.createZone(role, zone(owner,dimension,donorStation,donorStation.above(3)));
        var source = anchor.offset(-2,0,0);
        var reserve = source.north(3);
        var backup = assignments.createZone(role, zone(owner,dimension,reserve.west(),source.above(4)));
        for (var v : List.of(candidate,supplier)) {
            var area = v == candidate ? donor : backup;
            assignments.putAssignment(new WorkerAssignment(v.getUUID(),role,Optional.of(area.id()), Optional.empty(),
                    v == candidate,v.getBrain().getMemory(MemoryModuleType.HOME)));
        }
        var wallet = inventories.inventory(candidate.getUUID());
        var stock = inventories.inventory(supplier.getUUID());
        var tool = mine ? Items.STONE_PICKAXE : Items.STONE_AXE;
        stock.insertExact(new ItemStack(tool));
        stock.insertExact(new ItemStack(Items.OAK_SAPLING, 8));
        wallet.insertExact(new ItemStack(Items.EMERALD, 8));
        wallet.insertExact(new ItemStack(first.state().getBlock().asItem()));
        for (var v : residents) inventories.inventory(v.getUUID()).insertExact(new ItemStack(Items.BREAD,64));
        for (var pos : List.of(source, reserve)) {
            if (mine) level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
            else {
                for (int y=0;y<3;y++) level.setBlockAndUpdate(pos.above(y),Blocks.OAK_LOG.defaultBlockState());
                level.setBlockAndUpdate(pos.above(3),Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(LeavesBlock.PERSISTENT,true));
            }
        }
        if (mine) {
            for (var v : List.of(candidate,supplier)) {
                var pos = v == candidate ? donorStation : reserve.west();
                level.setBlockAndUpdate(pos,Blocks.FURNACE.defaultBlockState());
                v.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),pos));
            }
        }
        var soil = anchor.offset(1,-1,-3);
        var station = soil.east().above();
        level.setBlockAndUpdate(station,Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(soil.north(),Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(soil.above(),Blocks.CARROTS.defaultBlockState());
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),station));
        inventories.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.DIAMOND_HOE));
        var farm = ProductionParcel.get(server);
        h.assertTrue(farm.acquire(level,farmer,GlobalPos.of(level.dimension(),station),soil,true),"Missing farm");
        var project = new BuilderProject(work.id(),owner,dimension,template.toString(),anchor.asLong(),0);
        h.assertTrue(sites.submit(project,owner),"Project registration failed");
        h.runBeforeTestEnd(() -> {
            sites.cancelProject(work.id(),owner);
            sites.removeByBuilder(candidate.getUUID(),owner);
            for (var area : List.of(work, donor, backup)) assignments.removeZone(area.id());
            residents.forEach(Villager::discard);
            settings.setMode(previous);
        });
        var originalHome = candidate.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
        var initial = level.getGameTime();
        boolean[] early = {false};
        boolean[] harvested = {false};
        h.succeedWhen(() -> {
            if (!harvested[0] && !level.getBlockState(source).is(mine ? Blocks.STONE : Blocks.OAK_LOG)
                    && stock.countMatchingItem(new ItemStack(mine ? Items.COBBLESTONE : Items.OAK_LOG)) > 0) harvested[0] = true;
            var site = sites.getByBuilder(candidate.getUUID()).orElse(null);
            if (site != null && level.getGameTime()-initial < BuilderProject.DEMAND_TICKS) early[0] = true;
            String status = h.getTick() < 53900 ? "Waiting for paid handover and repair"
                    : "site=" + site + " pending=" + sites.projects() + " stock=" + stock.snapshot().stream().filter(s -> !s.isEmpty()).toList()
                    + " buyer=" + wallet.snapshot().stream().filter(s -> !s.isEmpty()).toList();
            h.assertTrue(!early[0] && site != null && site.nextBlockIndex() == blueprint.blocks().size(),status);
            h.assertTrue(blueprint.blocks().stream().allMatch(b -> level.getBlockState(b.position()).is(b.state().getBlock())),
                    "Completed site still has a missing block");
            h.assertTrue(stock.countMatchingItem(new ItemStack(Items.EMERALD)) >= (mine ? 1 : 2)
                    && stock.snapshot().stream().anyMatch(s -> s.is(tool) && s.getDamageValue()>0)
                    && harvested[0],"Missing paid harvest");
            h.assertTrue(assignments.getAssignment(candidate.getUUID()).orElseThrow().home().equals(Optional.of(originalHome))
                    && candidate.getBrain().getMemory(MemoryModuleType.HOME).equals(Optional.of(originalHome))
                    && assignments.getAssignment(supplier.getUUID()).orElseThrow().workZoneId().equals(Optional.of(backup.id())), "Lost home or supplier");
        });
    }

    static WorkZone zone(UUID owner,String dimension,BlockPos low,BlockPos high) {
        return new WorkZone(owner,dimension,new BlockCoordinate(low.getX(),low.getY(),low.getZ()),
                new BlockCoordinate(high.getX(),high.getY(),high.getZ()));
    }

    static Villager resident(GameTestHelper h, String role,BlockPos stand,BlockPos home) {
        return resident(h, role, stand, home, true);
    }

    @SuppressWarnings("unchecked")
    static Villager resident(GameTestHelper h, String role, BlockPos stand, BlockPos home, boolean fixed) {
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var v = fixed ? h.spawnWithNoFreeWill(type,h.relativePos(stand)) : h.spawn(type,h.relativePos(stand));
        v.setNoAi(fixed);
        v.snapTo(stand.getX()+.5, stand.getY(), stand.getZ()+.5,0,0);
        v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(role)))));
        v.setVillagerDataFinalized(true);
        var bed = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH);
        level.setBlock(home.south(),bed.setValue(BedBlock.PART,BedPart.FOOT),2);
        level.setBlock(home,bed.setValue(BedBlock.PART,BedPart.HEAD),2);
        v.getBrain().setMemory(MemoryModuleType.HOME,GlobalPos.of(level.dimension(),home));
        VillagerNutrition.setFoodLevel(v,20);
        VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(v.getUUID());
        return v;
    }
}
