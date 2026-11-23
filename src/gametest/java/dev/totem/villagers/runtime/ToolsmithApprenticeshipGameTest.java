package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ToolsmithApprenticeshipGameTest {
    @GameTest(environment = "totem:smith_trials")
    public void unemployedHouseholdEarnsItsSmithingTable(GameTestHelper h) { checkTrial(h, Trial.UNEMPLOYED_SMITH); }

    @GameTest(environment = "totem:smith_trials")
    public void spareMinerForgesAndDeliversTheFarmersHoe(GameTestHelper h) { checkTrial(h, Trial.MINER_SMITH); }

    @GameTest(environment = "totem:smith_trials")
    public void spareMinerCutsStairsAndSuppliesSubsequentConstruction(GameTestHelper h) { checkTrial(h, Trial.MASON); }

    @GameTest(environment = "totem:smith_trials")
    public void equippedGuardPaysForFourArrowsFromASpareLumberjack(GameTestHelper h) { checkTrial(h, Trial.GUARD_ARROWS); }

    @GameTest(environment = "totem:smith_trials")
    public void tippedArrowWorkshopFundsTwoBatchesFromASpareLumberjack(GameTestHelper h) { checkTrial(h, Trial.WORKSHOP_ARROWS); }

    @GameTest(environment = "totem:smith_trials")
    @SuppressWarnings("unchecked")
    public void foodForecastWaitsForEdibleHarvestAndProtectsHungryResidents(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var neighbor = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var residents = List.of(farmer, neighbor);
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var pantry = inventories.inventory(neighbor.getUUID());
        var farm = inventories.inventory(farmer.getUUID());
        var soil = farmer.blockPosition().north().below();
        var station = GlobalPos.of(level.dimension(), soil.west().above());
        var home = GlobalPos.of(level.dimension(), soil.south(2).above());
        try {
            profession(farmer, "minecraft:farmer");
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
            farmer.getBrain().setMemory(MemoryModuleType.HOME, home);
            neighbor.getBrain().setMemory(MemoryModuleType.HOME, home);
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
            var carrots = (net.minecraft.world.level.block.CropBlock)Blocks.CARROTS;
            level.setBlockAndUpdate(soil.above(), carrots.getStateForAge(0));
            h.assertTrue(ProductionParcel.get(level.getServer()).acquire(level, farmer, station, soil, true), "Could not claim food source");
            residents.forEach(v -> VillagerNutrition.setFoodLevel(v, 20));
            pantry.insertExact(new ItemStack(Items.BREAD, 8));
            farm.insertExact(new ItemStack(Items.WOODEN_HOE));
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories), "Food in a neighbor's private inventory was pooled");
            pantry.takeExactMatchingItem(new ItemStack(Items.BREAD, 4)).orElseThrow();
            farm.insertExact(new ItemStack(Items.BREAD, 4));
            h.assertTrue(VillageFoodForecast.coversNextMeal(level, residents, inventories), "Two healthy adults should be covered by 40 nutrition");
            pantry.takeExactMatchingItem(new ItemStack(Items.BREAD, 4)).orElseThrow();
            farm.insertExact(new ItemStack(Items.BREAD, 5));
            pantry.insertExact(new ItemStack(Items.EMERALD));
            h.assertTrue(VillageFoodForecast.coversNextMeal(level, residents, inventories), "Funded food bundle was ignored");
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories, neighbor, 1, false), "Same emerald funded both orders");
            h.assertTrue(farm.countMatchingItem(new ItemStack(Items.BREAD)) == 9
                    && pantry.countMatchingItem(new ItemStack(Items.EMERALD)) == 1, "Forecast moved real stock");
            farm.takeExactMatchingItem(new ItemStack(Items.BREAD, 5)).orElseThrow();
            pantry.insertExact(new ItemStack(Items.BREAD, 4));
            VillageFoodForecast.observe(level, farmer, 0);
            VillageFoodForecast.observe(level, farmer, 96000);
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories), "Four days without food did not postpone recruitment");
            var action = new dev.totem.villagers.world.FarmerWorldWorkAction();
            var order = dev.totem.villagers.work.WorkOrderDefinitions.catalog().snapshot().get("totem:farmer_carrot");
            h.assertTrue(!action.complete(level, farmer, soil.above(), order, farm)
                    && !VillageFoodForecast.coversNextMeal(level, residents, inventories), "Unripe crop counted as a delivery");
            level.setBlockAndUpdate(soil.above(), carrots.getStateForAge(carrots.getMaxAge()));
            h.assertTrue(action.complete(level, farmer, soil.above(), order, farm)
                    && farm.countMatchingItem(new ItemStack(Items.CARROT)) > 0
                    && VillageFoodForecast.coversNextMeal(level, residents, inventories), "Actual harvest did not restore the food forecast");
            farm.takeExactMatchingItem(new ItemStack(Items.CARROT, farm.countMatchingItem(new ItemStack(Items.CARROT)))).orElseThrow();
            VillagerNutrition.setFoodLevel(neighbor, 5);
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories), "Hungry household lost its recovery ration");
            VillagerNutrition.setFoodLevel(neighbor, 20);
            level.setBlockAndUpdate(soil.above(), Blocks.AIR.defaultBlockState());
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories), "Empty tilled soil counted as food");
            level.setBlockAndUpdate(soil.above(), carrots.getStateForAge(0));
            level.setBlockAndUpdate(station.pos(), Blocks.AIR.defaultBlockState());
            h.assertTrue(!VillageFoodForecast.coversNextMeal(level, residents, inventories), "Destroyed station counted as reliable food");
            h.succeed();
        } finally {
            residents.forEach(Villager::discard);
        }
    }

    @GameTest(environment = "totem:pasture_trials", structure = "totem:river_households", padding = 40)
    public void unemployedShepherdDeliversWhiteWoolForRealBlueprint(GameTestHelper h) { checkTrial(h, Trial.WOOL); }

    @GameTest(environment = "totem:smith_trials", structure = "totem:river_households", padding = 48)
    public void builderPaysLibrarianToSupplyLibraryShelf(GameTestHelper h) { checkTrial(h, Trial.LIBRARY); }

    private static void mapForGuard(GameTestHelper h, Villager librarian, EntityType<Villager> type, UUID owner) {
        var level = h.getLevel();
        var server = level.getServer();
        var guard = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var relief = h.spawnWithNoFreeWill(type, new BlockPos(7, 3, 3));
        var accounts = VillagerWorkInventorySavedData.forServer(server);
        var postId = UUID.randomUUID();
        var pad = guard.blockPosition();
        var post = new dev.totem.villagers.guard.GuardPost(postId, owner, level.dimension().identifier().toString(), pad.asLong());
        var villages = dev.totem.villagers.guard.ManagedVillageSavedData.forServer(server);
        var jobs = WorkerAssignmentSavedData.forServer(server);
        var cartTable = pad.north();
        var source = accounts.inventory(librarian.getUUID());
        var wallet = accounts.inventory(guard.getUUID());
        var coin = new ItemStack(Items.EMERALD);
        var originalHome = librarian.getBrain().getMemory(MemoryModuleType.HOME);
        var originalLectern = librarian.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow();
        try {
            for (var v : List.of(guard, relief)) {
                var bed = v.blockPosition().south(6);
                level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(v, 20);
                accounts.inventory(v.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            profession(guard, "totem:guard");
            profession(relief, "minecraft:librarian");
            var libraryRole = relief.getVillagerData().profession().value();
            var spare = relief.blockPosition().east();
            level.setBlockAndUpdate(spare, Blocks.LECTERN.defaultBlockState());
            VillagerResourceWorkforceRuntime.claimExactJobSite(level, libraryRole, spare).orElseThrow();
            relief.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), spare));
            villages.registerOrUpdate(new dev.totem.villagers.guard.ManagedVillageState(post, guard.getUUID(), java.util.Set.of(), Optional.empty()), owner);
            jobs.putAssignment(new WorkerAssignment(guard.getUUID(), "totem:guard", Optional.empty(), Optional.of(postId)));
            level.setBlockAndUpdate(cartTable, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
            wallet.insertExact(new ItemStack(Items.IRON_SWORD));
            wallet.insertExact(new ItemStack(Items.IRON_HELMET));
            wallet.insertExact(new ItemStack(Items.LEATHER_BOOTS));
            wallet.insertExact(coin.copyWithCount(5));
            source.insertExact(new ItemStack(Items.PAPER, 8));
            source.insertExact(new ItemStack(Items.COMPASS));
            level.setBlockAndUpdate(pad.above(4), Blocks.OAK_PLANKS.defaultBlockState());
            VillagerCareerHistory.observe(librarian, 72000);
            for (long t = 0; t <= 48000; t += 100) GuardEquipmentOrders.observe(level, guard, t);
            h.assertTrue(librarian.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.LIBRARIAN)
                    && wallet.countMatchingItem(coin) == 5, "The only working library closed for a map order");
            accounts.inventory(relief.getUUID()).insertExact(new ItemStack(Items.BOOKSHELF));
            var backupHome = relief.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow();
            var distantBed = backupHome.pos().east(40);
            var oldBlock = level.getBlockState(distantBed);
            level.setBlock(distantBed, level.getBlockState(backupHome.pos()), 2);
            relief.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), distantBed));
            GuardEquipmentOrders.observe(level, guard, 48100);
            h.assertTrue(wallet.countMatchingItem(coin) == 5 && librarian.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    .filter(originalLectern::equals).isPresent(), "An out-of-village supplier displaced the local librarian");
            level.setBlock(distantBed, oldBlock, 2);
            relief.getBrain().setMemory(MemoryModuleType.HOME, backupHome);
            GuardEquipmentOrders.observe(level, guard, 48200);
            h.assertTrue(wallet.countMatchingItem(new ItemStack(Items.MAP)) == 1 && wallet.countMatchingItem(coin) == 0
                    && source.countMatchingItem(new ItemStack(Items.PAPER)) == 0 && source.countMatchingItem(new ItemStack(Items.COMPASS)) == 0
                    && source.countMatchingItem(coin) == 10 && librarian.getBrain().getMemory(MemoryModuleType.HOME).equals(originalHome)
                    && librarian.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.CARTOGRAPHER)
                    && librarian.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(j -> j.pos().equals(cartTable)).isPresent(), "Incomplete map trade or career handover");
            var released = VillagerResourceWorkforceRuntime.claimExactJobSite(level, libraryRole, originalLectern.pos()).orElseThrow();
            level.getPoiManager().release(released);
            GuardEquipmentOrders.observe(level, guard, 48300);
            var map = wallet.snapshot().stream().filter(stack -> stack.is(Items.FILLED_MAP)).findFirst().orElseThrow();
            var mapId = map.get(net.minecraft.core.component.DataComponents.MAP_ID);
            h.assertTrue(mapId != null && level.getMapData(mapId) != null && map.getOrDefault(net.minecraft.core.component.DataComponents.MAP_DECORATIONS,
                    net.minecraft.world.item.component.MapDecorations.EMPTY).decorations().containsKey("post_" + postId + "_" + pad.asLong())
                    && wallet.countMatchingItem(new ItemStack(Items.MAP)) == 0, "Patrol map has no post marker or saved map");
            var apprentice = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 3));
            try {
                var bed = apprentice.blockPosition().south(7);
                level.setBlock(bed, level.getBlockState(originalHome.orElseThrow().pos()), 2);
                apprentice.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(apprentice, 20);
                var materials = accounts.inventory(apprentice.getUUID());
                materials.insertExact(new ItemStack(Items.BREAD, 4));
                materials.insertExact(new ItemStack(Items.GLOWSTONE_DUST, 3));
                var brewing = apprentice.blockPosition().north();
                level.setBlockAndUpdate(brewing, Blocks.BREWING_STAND.defaultBlockState());
                wallet.insertExact(coin.copyWithCount(3));
                var lamp = pad.above(3);
                level.setBlockAndUpdate(lamp.above(), Blocks.OAK_PLANKS.defaultBlockState());
                for (long t = 48400; t <= 96400; t += 100) GuardEquipmentOrders.observe(level, guard, t);
                h.assertTrue(wallet.countMatchingItem(coin) == 3 && level.getBlockState(lamp).isAir()
                        && materials.countMatchingItem(new ItemStack(Items.GLOWSTONE_DUST)) == 3, "Incomplete dust batch commissioned a lamp");
                materials.insertExact(new ItemStack(Items.GLOWSTONE_DUST));
                level.setBlockAndUpdate(pad.above(2), Blocks.STONE.defaultBlockState());
                GuardEquipmentOrders.observe(level, guard, 96500);
                h.assertTrue(wallet.countMatchingItem(coin) == 3 && materials.countMatchingItem(new ItemStack(Items.GLOWSTONE_DUST)) == 4,
                        "Guard paid for an unreachable lamp above a solid ceiling");
                level.removeBlock(pad.above(2), false);
                for (long t = 96600; t <= 144600; t += 100) GuardEquipmentOrders.observe(level, guard, t);
                h.assertTrue(wallet.countMatchingItem(new ItemStack(Items.GLOWSTONE)) == 1 && wallet.countMatchingItem(coin) == 0
                        && materials.countMatchingItem(coin) == 3 && materials.countMatchingItem(new ItemStack(Items.GLOWSTONE_DUST)) == 0
                        && apprentice.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.CLERIC)
                        && apprentice.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(j -> j.pos().equals(brewing)).isPresent(),
                        "Cleric trial: profession=" + apprentice.getVillagerData().profession() + " station=" + level.getBlockState(brewing)
                        + " atStation=" + VillageWorkshopApprenticeship.atStation(level, apprentice, brewing)
                        + " allowed=" + dev.totem.villagers.world.WorldWorkPermissions.mayUseHouseholdLand(level, apprentice, brewing)
                        + " lampAllowed=" + dev.totem.villagers.world.WorldWorkPermissions.mayUseHouseholdLand(level, guard, lamp)
                        + " light=" + level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pad)
                        + " home=" + level.getBlockState(bed) + " stock=" + wallet.snapshot().stream().filter(s -> !s.isEmpty()).toList()
                        + " ingredients=" + materials.snapshot().stream().filter(s -> !s.isEmpty()).toList());
                level.removeBlock(lamp.above(), false);
                GuardEquipmentOrders.observe(level, guard, 144700);
                h.assertTrue(level.getBlockState(lamp).isAir() && wallet.countMatchingItem(new ItemStack(Items.GLOWSTONE)) == 1,
                        "Removing the roof stranded or consumed the lamp");
                level.setBlockAndUpdate(lamp.above(), Blocks.OAK_PLANKS.defaultBlockState());
                GuardEquipmentOrders.observe(level, guard, 144800);
                h.assertTrue(level.getBlockState(lamp).is(Blocks.GLOWSTONE) && wallet.countMatchingItem(new ItemStack(Items.GLOWSTONE)) == 0,
                        "Guard failed to install its lamp under the post roof");
            } finally {
                apprentice.discard();
            }
        } finally {
            villages.remove(postId, owner);
            jobs.removeAssignment(guard.getUUID());
            guard.discard();
            relief.discard();
        }
    }

    private enum Trial { LIBRARY, WOOL, UNEMPLOYED_SMITH, MINER_SMITH, MASON, GUARD_ARROWS, WORKSHOP_ARROWS }

    @SuppressWarnings("unchecked")
    private void checkTrial(GameTestHelper h, Trial trial) {
        boolean arrows = trial == Trial.GUARD_ARROWS || trial == Trial.WORKSHOP_ARROWS;
        boolean wool = trial == Trial.WOOL;
        boolean library = trial == Trial.LIBRARY;
        boolean miner = trial != Trial.UNEMPLOYED_SMITH && !wool && !library;
        boolean masonry = trial == Trial.MASON;
        var source = arrows ? "totem:lumberjack" : "totem:miner";
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var smith = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 4));
        var relief = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 4));
        var customer = masonry || arrows || wool || library ? h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 4)) : farmer;
        var residents = masonry || arrows || wool || library ? List.of(smith, farmer, relief, customer) : List.of(smith, farmer, relief);
        var anchor = smith.blockPosition();
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var buyer = inventories.inventory(customer.getUUID());
        var seller = inventories.inventory(smith.getUUID());
        var parcels = ProductionParcel.get(server);
        var owner = UUID.randomUUID();
        var mine = anchor.north(3);
        var spareMine = relief.blockPosition().east();
        var zone = assignments.createZone(source, mine(level, owner, mine));
        var spareZone = assignments.createZone(source, new WorkZone(owner, level.dimension().identifier().toString(),
                new BlockCoordinate(spareMine.getX(), spareMine.getY(), spareMine.getZ()),
                new BlockCoordinate(spareMine.getX(), spareMine.getY() + 3, spareMine.getZ())));
        var table = anchor.north();
        try {
            for (int i = 0; i < residents.size(); i++) {
                var v = residents.get(i);
                var bed = anchor.offset(i * 2 - 2, 0, 3);
                var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
                level.setBlock(bed.south().below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.south(), state.setValue(BedBlock.PART, BedPart.FOOT), 2);
                level.setBlock(bed, state.setValue(BedBlock.PART, BedPart.HEAD), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(v, 20);
            }
            for (var resident : residents) inventories.inventory(resident.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            var home = smith.getBrain().getMemory(MemoryModuleType.HOME);
            profession(farmer, "minecraft:farmer");
            profession(relief, source);
            profession(smith, miner ? source : "minecraft:none");
            var composter = anchor.west(2);
            var soil = composter.south();
            level.setBlockAndUpdate(composter, Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            if (arrows || masonry || wool || library) inventories.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.WOODEN_HOE));
            var station = GlobalPos.of(level.dimension(), composter);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
            h.assertTrue(parcels.acquire(level, farmer, station, soil, true), "Missing food producer's parcel");
            level.setBlockAndUpdate(mine, Blocks.FURNACE.defaultBlockState());
            level.setBlockAndUpdate(spareMine, Blocks.FURNACE.defaultBlockState());
            if (!arrows) level.setBlockAndUpdate(spareMine.above(), Blocks.STONE.defaultBlockState());
            if (miner) {
                assignments.putAssignment(new WorkerAssignment(smith.getUUID(), source, Optional.of(zone.id()), Optional.empty(), true));
                smith.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), mine));
            }
            assignments.putAssignment(new WorkerAssignment(relief.getUUID(), source, Optional.of(spareZone.id()), Optional.empty()));
            relief.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), spareMine));
            level.setBlockAndUpdate(table, Blocks.SMITHING_TABLE.defaultBlockState());
            var smithProfession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:toolsmith"));
            if (library) {
                profession(customer, "totem:builder");
                level.setBlockAndUpdate(table, Blocks.LECTERN.defaultBlockState());
                var template = Identifier.parse("minecraft:village/plains/houses/plains_library_1");
                var design = dev.totem.villagers.builder.VanillaVillageBlueprints.resolve(server, template, anchor).orElseThrow();
                var shelf = design.blocks().stream().filter(b -> b.state().is(Blocks.BOOKSHELF)).findFirst().orElseThrow();
                var target = customer.blockPosition().east(2);
                var origin = anchor.offset(target.subtract(shelf.position()));
                int index = design.blocks().indexOf(shelf);
                var site = new dev.totem.villagers.builder.BuilderSite(UUID.randomUUID(), owner, customer.getUUID(),
                        template.toString(), level.dimension().identifier().toString(), origin.asLong(), index);
                var construction = assignments.createZone("totem:builder", mine(level, owner, target));
                var sites = dev.totem.villagers.builder.BuilderSiteSavedData.forServer(server);
                try {
                    assignments.putAssignment(new WorkerAssignment(customer.getUUID(), "totem:builder", Optional.of(construction.id()), Optional.empty()));
                    h.assertTrue(sites.registerOrReplace(site, owner), "Missing library site");
                    level.setBlockAndUpdate(customer.blockPosition().below(), Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                    buyer.insertExact(new ItemStack(Items.EMERALD, 4));
                    seller.insertExact(new ItemStack(Items.EMERALD, 4));
                    var bookSupplier = inventories.inventory(relief.getUUID());
                    bookSupplier.insertExact(new ItemStack(Items.OAK_PLANKS, 6));
                    bookSupplier.insertExact(new ItemStack(Items.BOOK, 2));
                    settings.setMode(WorkBackedTradingMode.ENFORCED);
                    for (long t = 0; t <= 48000; t += 10) BuilderMaterialOrders.request(level, customer, site, shelf, buyer, t);
                    h.assertTrue(!smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.LIBRARIAN)
                            && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 4, "Incomplete books founded a library");
                    bookSupplier.insertExact(new ItemStack(Items.BOOK));
                    for (long t = 48100; t <= 48600; t += 100) BuilderMaterialOrders.request(level, customer, site, shelf, buyer, t);
                    h.assertTrue(smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.LIBRARIAN)
                            && smith.getBrain().getMemory(MemoryModuleType.HOME).equals(home)
                            && smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(j -> j.pos().equals(table)).isPresent()
                            && buyer.countMatchingItem(new ItemStack(Items.BOOKSHELF)) == 1
                            && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 0
                            && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 5
                            && seller.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 0
                            && seller.countMatchingItem(new ItemStack(Items.BOOK)) == 0
                            && bookSupplier.countMatchingItem(new ItemStack(Items.BOOK)) == 0
                            && bookSupplier.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 0
                            && bookSupplier.countMatchingItem(new ItemStack(Items.EMERALD)) == 3, "Incomplete paid bookshelf handover");
                    VillagerBuilderRuntime.tickBuilder(server, level, customer, site, sites,
                            dev.totem.villagers.work.VillagerWorkSavedData.forServer(server), assignments, inventories);
                    h.assertTrue(level.getBlockState(target).equals(shelf.state()) && sites.getByBuilder(customer.getUUID()).orElseThrow().nextBlockIndex() == index + 1
                            && buyer.countMatchingItem(new ItemStack(Items.BOOKSHELF)) == 0, "Shelf was not installed in the library");
                    // This village hires a new cleric after its library opens. Close the unused
                    // quarry from the shared fixture before entity-load recruitment sees the new arrival.
                    assignments.removeZone(zone.id());
                    mapForGuard(h, smith, type, owner);
                } finally {
                    sites.removeByBuilder(customer.getUUID(), owner);
                    assignments.removeZone(construction.id());
                }
                h.succeed();
                return;
            }
            if (wool) {
                var sheepType = (EntityType<net.minecraft.world.entity.animal.sheep.Sheep>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:sheep"));
                var sheep = h.spawnWithNoFreeWill(sheepType, new BlockPos(4, 3, 3));
                var otherShepherd = h.spawnWithNoFreeWill(type, new BlockPos(7, 3, 3));
                var grazingSheep = h.spawnWithNoFreeWill(sheepType, new BlockPos(7, 3, 3));
                var indoorSheep = h.spawnWithNoFreeWill(sheepType, new BlockPos(3, 3, 3));
                var template = Identifier.parse("minecraft:village/plains/houses/plains_shepherds_house_1");
                var design = dev.totem.villagers.builder.VanillaVillageBlueprints.resolve(server, template, anchor).orElseThrow();
                var block = design.blocks().stream().filter(b -> b.materialItemId().equals("minecraft:white_wool")).findFirst().orElseThrow();
                var target = customer.blockPosition().east(2);
                var origin = anchor.offset(target.subtract(block.position()));
                var site = new dev.totem.villagers.builder.BuilderSite(UUID.randomUUID(), owner, customer.getUUID(),
                        template.toString(), level.dimension().identifier().toString(), origin.asLong(), design.blocks().indexOf(block));
                var construction = assignments.createZone("totem:builder", mine(level, owner, target));
                var sites = dev.totem.villagers.builder.BuilderSiteSavedData.forServer(server);
                try {
                    profession(customer, "totem:builder");
                    level.setBlockAndUpdate(table, Blocks.LOOM.defaultBlockState());
                    // The neighbouring shepherd owns a different pasture and cannot fill this colour order.
                    var otherLoom = otherShepherd.blockPosition().east();
                    var otherHome = otherLoom.north(2);
                    level.setBlock(otherHome, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                    level.setBlockAndUpdate(otherLoom, Blocks.LOOM.defaultBlockState());
                    var shepherdRole = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:shepherd"));
                    VillagerResourceWorkforceRuntime.claimExactJobSite(level, shepherdRole, otherLoom).orElseThrow();
                    profession(otherShepherd, "minecraft:shepherd");
                    otherShepherd.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), otherHome));
                    otherShepherd.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), otherLoom));
                    var otherStock = inventories.inventory(otherShepherd.getUUID());
                    otherStock.insertExact(new ItemStack(Items.SHEARS));
                    otherStock.insertExact(new ItemStack(Items.BREAD, 4));
                    VillagerNutrition.setFoodLevel(otherShepherd, 20);
                    var otherSoil = otherShepherd.blockPosition().below();
                    level.setBlockAndUpdate(otherSoil, Blocks.GRASS_BLOCK.defaultBlockState());
                    h.assertTrue(parcels.reservePasture(level, otherShepherd, otherLoom, otherSoil), "Missing neighbouring pasture");
                    otherShepherd.snapTo(otherShepherd.getX() - 1, otherShepherd.getY(), otherShepherd.getZ());
                    level.setBlockAndUpdate(customer.blockPosition().below(), Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                    assignments.putAssignment(new WorkerAssignment(customer.getUUID(), "totem:builder", Optional.of(construction.id()), Optional.empty()));
                    h.assertTrue(sites.registerOrReplace(site, owner), "Unregistered wool construction");
                    level.setBlockAndUpdate(sheep.blockPosition().below(), Blocks.GRASS_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(indoorSheep.blockPosition().below(), Blocks.STONE.defaultBlockState());
                    indoorSheep.setColor(net.minecraft.world.item.DyeColor.WHITE);
                    grazingSheep.setColor(net.minecraft.world.item.DyeColor.BLACK);
                    sheep.setColor(net.minecraft.world.item.DyeColor.BLACK);
                    var white = new ItemStack(block.state().getBlock());
                    buyer.insertExact(new ItemStack(Items.EMERALD, 2));
                    seller.insertExact(new ItemStack(Items.SHEARS));
                    settings.setMode(WorkBackedTradingMode.ENFORCED);
                    long now = level.getGameTime();
                    for (long t = now - 48600; t <= now - 600; t += 10) BuilderMaterialOrders.request(level, customer, site, block, buyer, t);
                    h.assertTrue(sheep.readyForShearing() && buyer.countMatchingItem(white) == 0
                            && !smith.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE), "Wrong wool funded a career: target ready=" + sheep.readyForShearing()
                            + ", buyer wool=" + buyer.countMatchingItem(white) + ", seller emeralds=" + seller.countMatchingItem(new ItemStack(Items.EMERALD))
                            + ", existing seller emeralds=" + otherStock.countMatchingItem(new ItemStack(Items.EMERALD)) + ", job=" + smith.getBrain().getMemory(MemoryModuleType.JOB_SITE));
                    sheep.setColor(net.minecraft.world.item.DyeColor.WHITE);
                    grazingSheep.setColor(net.minecraft.world.item.DyeColor.WHITE);
                    grazingSheep.setNoAi(false);
                    grazingSheep.setSheared(true);
                    var whiteOrder = dev.totem.villagers.work.WorkOrderDefinitions.catalog().snapshot().get("totem:shepherd_white_wool");
                    h.assertTrue(VillagerShepherdWorkRuntime.canSupplyColour(level, otherShepherd, customer, whiteOrder, white, inventories),
                            "Existing household cannot supply: soil=" + level.getBlockState(grazingSheep.blockPosition().below())
                            + ", plot=" + parcels.claim(otherShepherd.getUUID()) + ", at=" + grazingSheep.blockPosition()
                            + ", authorized=" + parcels.canWorkPasture(level, otherShepherd, otherLoom, grazingSheep.blockPosition())
                            + ", range=" + otherShepherd.distanceToSqr(customer) + ", los=" + customer.hasLineOfSight(otherShepherd));
                    BuilderMaterialOrders.request(level, customer, site, block, buyer, now - 500);
                    h.assertTrue(sheep.readyForShearing() && parcels.claim(smith.getUUID()).isEmpty()
                            && buyer.countMatchingItem(white) == 0, "Unexpected regrowth decision: sheep=" + sheep.readyForShearing() + ", wool=" + buyer.countMatchingItem(white) + ", job=" + smith.getBrain().getMemory(MemoryModuleType.JOB_SITE));
                    grazingSheep.setColor(net.minecraft.world.item.DyeColor.BLACK);
                    var loomType = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:shepherd"));
                    VillagerResourceWorkforceRuntime.claimExactJobSite(level, loomType, table).orElseThrow();
                    BuilderMaterialOrders.request(level, customer, site, block, buyer, now - 400);
                    h.assertTrue(sheep.readyForShearing() && buyer.countMatchingItem(white) == 0, "Occupied loom permitted appointment");
                    level.getPoiManager().release(table);
                    var filler = new ItemStack(Items.WOODEN_SHOVEL);
                    while (seller.insertExact(filler)) { }
                    BuilderMaterialOrders.request(level, customer, site, block, buyer, now - 200);
                    h.assertTrue(sheep.readyForShearing() && parcels.claim(smith.getUUID()).isEmpty()
                            && !smith.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE)
                            && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 2, "Failed payment left a pasture or sheared animal");
                    seller.takeExactMatchingItem(filler).orElseThrow();
                    var states = dev.totem.villagers.work.VillagerWorkSavedData.forServer(server);
                    VillagerBuilderRuntime.tickBuilder(server, level, customer, site, sites, states, assignments, inventories);
                    var worn = new ItemStack(Items.SHEARS);
                    worn.setDamageValue(1);
                    h.assertTrue(!sheep.readyForShearing() && indoorSheep.readyForShearing() && seller.countMatchingItem(worn) == 1
                            && buyer.countMatchingItem(white) == 1 && seller.countMatchingItem(white) == 0
                            && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 1 && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                            && smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.SHEPHERD)
                            && smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow().pos().equals(table)
                            && smith.getBrain().getMemory(MemoryModuleType.HOME).equals(home), "Missing physical paid wool appointment: sheepReady=" + sheep.readyForShearing() + ", job="
                            + smith.getBrain().getMemory(MemoryModuleType.JOB_SITE) + ", wool=" + buyer.countMatchingItem(white)
                            + ", pasture=" + parcels.claim(smith.getUUID()) + ", sellerMoney=" + seller.countMatchingItem(new ItemStack(Items.EMERALD)));
                    h.assertTrue(parcels.claim(smith.getUUID()).filter(c -> c.home().equals(home) && c.station().pos().equals(table)
                            && c.cells().contains(sheep.blockPosition().below())).isPresent()
                            && !parcels.mayTendSheep(level, relief, sheep.blockPosition()), "No exclusive grazing claim");
                    h.assertTrue(java.util.Collections.disjoint(parcels.claim(smith.getUUID()).orElseThrow().cells(),
                            parcels.claim(otherShepherd.getUUID()).orElseThrow().cells()), "Neighbouring pastures overlap");
                    var ops = com.mojang.serialization.JsonOps.INSTANCE;
                    var restored = ProductionParcel.CODEC.parse(ops, ProductionParcel.CODEC.encodeStart(ops, parcels).getOrThrow()).getOrThrow();
                    h.assertTrue(restored.claim(smith.getUUID()).equals(parcels.claim(smith.getUUID()))
                            && !restored.mayTendSheep(level, relief, sheep.blockPosition()), "Reload lost exclusive pasture");
                    VillagerBuilderRuntime.tickBuilder(server, level, customer, site, sites, states, assignments, inventories);
                    h.assertTrue(level.getBlockState(target).equals(block.state()) && buyer.countMatchingItem(white) == 0, "Paid wool did not enter blueprint");
                    sheep.setSheared(false);
                    var bedPos = home.orElseThrow().pos();
                    var savedBed = level.getBlockState(bedPos);
                    level.setBlock(bedPos, Blocks.AIR.defaultBlockState(), 2);
                    BuilderMaterialOrders.request(level, customer, site.withNextBlockIndex(site.nextBlockIndex() + 1), block, buyer, now + 100);
                    h.assertTrue(sheep.readyForShearing() && buyer.countMatchingItem(white) == 0
                            && !parcels.canWorkPasture(level, smith, table, sheep.blockPosition()), "Destroyed bed still authorized paid work");
                    parcels.reconcile(level, smith);
                    h.assertTrue(parcels.claim(smith.getUUID()).isEmpty(), "Destroyed home held the pasture");
                    level.setBlock(bedPos, savedBed, 2);
                    BuilderMaterialOrders.request(level, customer, site.withNextBlockIndex(site.nextBlockIndex() + 1), block, buyer, now + 200);
                    h.assertTrue(buyer.countMatchingItem(white) == 1 && seller.countMatchingItem(white) == 0
                            && !sheep.readyForShearing() && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 0, "Existing shepherd failed to sell wool");
                } finally {
                    sites.removeByBuilder(customer.getUUID(), owner);
                    assignments.removeZone(construction.id());
                    sheep.discard();
                    indoorSheep.discard();
                    grazingSheep.discard();
                    otherShepherd.discard();
                }
                h.succeed();
                return;
            }
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            if (arrows) {
                var villages = dev.totem.villagers.guard.ManagedVillageSavedData.forServer(server);
                var villageId = UUID.randomUUID();
                try {
                    dev.totem.villagers.work.WorkOrder demand = null;
                    if (trial == Trial.GUARD_ARROWS) {
                        profession(customer, "totem:guard");
                        var post = new dev.totem.villagers.guard.GuardPost(villageId, owner, level.dimension().identifier().toString(), anchor.asLong());
                        villages.registerOrUpdate(new dev.totem.villagers.guard.ManagedVillageState(post, customer.getUUID(), java.util.Set.of(), Optional.empty()), owner);
                        assignments.putAssignment(new WorkerAssignment(customer.getUUID(), "totem:guard", Optional.empty(), Optional.of(villageId)));
                    } else {
                        profession(customer, "minecraft:fletcher");
                        var existing = anchor.east(2);
                        level.setBlockAndUpdate(existing, Blocks.FLETCHING_TABLE.defaultBlockState());
                        customer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), existing));
                        var tipped = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW,
                                net.minecraft.world.item.alchemy.Potions.SWIFTNESS).copyWithCount(5);
                        var offer = new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, 5), tipped, 12, 1, .2F);
                        customer.getOffers().add(offer);
                        buyer.insertExact(dev.totem.villagers.work.FletcherTippedArrowOrders.lingeringPotionFor(offer).orElseThrow());
                        demand = VillagerWorkshopRuntime.materialDemandFor(level, customer).orElseThrow();
                        h.assertTrue(dev.totem.villagers.work.FletcherTippedArrowOrders.isOfferBoundTippedArrowOrder(demand), "No live demand for tipped arrows");
                    }
                    level.setBlockAndUpdate(table, Blocks.FLETCHING_TABLE.defaultBlockState());
                    buyer.insertExact(new ItemStack(Items.EMERALD, 2));
                    buyer.insertExact(new ItemStack(Items.BREAD, 20));
                    inventories.inventory(relief.getUUID()).insertExact(new ItemStack(Items.STONE_AXE));
                    seller.insertExact(new ItemStack(Items.FLINT, 2));
                    seller.insertExact(new ItemStack(Items.FEATHER, 2));
                    seller.insertExact(new ItemStack(Items.STICK, 2));
                    if (trial == Trial.GUARD_ARROWS) {
                        for (int i = 0; i <= 48000; i += 20) VillageWorkshopApprenticeship.observeArrowDemand(level, customer, demand, i);
                        h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.ARROW)) == 0, "Guard without a bow ordered arrows");
                        buyer.insertExact(new ItemStack(Items.BOW));
                    }
                    for (int i = 1000; i <= 49000; i += 20) VillageWorkshopApprenticeship.observeArrowDemand(level, customer, demand, i);
                    h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.ARROW)) == 0, "Empty forest counted as a working backup");
                    level.setBlockAndUpdate(spareMine.below(), Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(spareMine, Blocks.OAK_LOG.defaultBlockState());
                    level.setBlockAndUpdate(spareMine.above(), Blocks.OAK_LOG.defaultBlockState());
                    level.setBlockAndUpdate(spareMine.above(2), Blocks.OAK_LEAVES.defaultBlockState());
                    inventories.inventory(relief.getUUID()).insertExact(new ItemStack(Items.OAK_SAPLING));
                    for (int i = 50000; i <= 98000; i += 20) VillageWorkshopApprenticeship.observeArrowDemand(level, customer, demand, i);
                    VillageWorkshopApprenticeship.observeArrowDemand(level, customer, demand, 98100);
                    int batches = trial == Trial.GUARD_ARROWS ? 1 : 2;
                    h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.ARROW)) == 4 * batches
                            && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 2 - batches
                            && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == batches
                            && seller.countMatchingItem(new ItemStack(Items.FEATHER)) == 2 - batches
                            && seller.countMatchingItem(new ItemStack(Items.FLINT)) == 2 - batches
                            && seller.countMatchingItem(new ItemStack(Items.STICK)) == 2 - batches
                            && assignments.getAssignment(smith.getUUID()).isEmpty() && assignments.getAssignment(relief.getUUID()).isPresent()
                            && smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.FLETCHER)
                            && smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(j -> j.pos().equals(table)).isPresent(),
                            "Arrow order, settlement or career handoff failed: " + buyer.snapshot());
                    h.succeed();
                } finally {
                    villages.remove(villageId, owner);
                }
                return;
            }
            if (masonry) {
                profession(customer, "totem:builder");
                level.setBlockAndUpdate(table, Blocks.STONECUTTER.defaultBlockState());
                var site = new dev.totem.villagers.builder.BuilderSite(UUID.randomUUID(), owner, customer.getUUID(),
                        "minecraft:village/plains/houses/plains_small_house_1", level.dimension().identifier().toString(), anchor.asLong(), 0);
                var block = new dev.totem.villagers.builder.VanillaVillageBlueprints.BlueprintBlock(anchor.west(),
                        Blocks.COBBLESTONE_STAIRS.defaultBlockState(), "minecraft:cobblestone_stairs");
                buyer.insertExact(new ItemStack(Items.BREAD, 20));
                buyer.insertExact(new ItemStack(Items.EMERALD, 3));
                seller.insertExact(new ItemStack(Items.EMERALD, 2));
                var quarry = inventories.inventory(relief.getUUID());
                quarry.insertExact(new ItemStack(Items.COBBLESTONE, 4));
                for (long sample = 0; sample <= 48000; sample += 10) BuilderMaterialOrders.request(level, customer, site, block, buyer, sample);
                h.assertTrue(assignments.getAssignment(smith.getUUID()).isPresent(), "Only working miner left the mine");
                inventories.inventory(relief.getUUID()).insertExact(new ItemStack(Items.STONE_PICKAXE));
                BuilderMaterialOrders.request(level, customer, site, block, buyer, 48100);
                h.assertTrue(quarry.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                        && quarry.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 0
                        && seller.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 4
                        && assignments.getAssignment(smith.getUUID()).isPresent(), "Raw material purchase did not settle before trial");
                BuilderMaterialOrders.request(level, customer, site, block, buyer, 48200);
                h.assertTrue(smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.MASON)
                        && buyer.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 1
                        && assignments.getAssignment(smith.getUUID()).isEmpty()
                        && smith.getBrain().getMemory(MemoryModuleType.HOME).equals(home), "No paid first order or no handoff");
                buyer.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)).orElseThrow();
                BuilderMaterialOrders.request(level, customer, site.withNextBlockIndex(1), block, buyer, 48300);
                h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 1
                        && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                        && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 3
                        && seller.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 2,
                        "Established mason failed to supply the next paid construction step");
                buyer.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)).orElseThrow();
                seller.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE, 2)).orElseThrow();
                seller.insertExact(new ItemStack(Items.COBBLESTONE_STAIRS));
                BuilderMaterialOrders.request(level, customer, site.withNextBlockIndex(2), block, buyer, 48400);
                h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 1
                        && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 0
                        && seller.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 0
                        && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 4, "Finished stock was stranded");
                // Feed a real vanilla house step through the normal builder tick, including its zone and placement checks.
                var template = Identifier.parse("minecraft:village/plains/houses/plains_big_house_1");
                var original = dev.totem.villagers.builder.VanillaVillageBlueprints.resolve(server, template, anchor).orElseThrow();
                var stairs = original.blocks().stream().filter(b -> b.state().is(Blocks.COBBLESTONE_STAIRS)).findFirst().orElseThrow();
                var target = customer.blockPosition().east(2);
                var origin = anchor.offset(target.subtract(stairs.position()));
                var index = original.blocks().indexOf(stairs);
                var registered = new dev.totem.villagers.builder.BuilderSite(UUID.randomUUID(), owner, customer.getUUID(),
                        template.toString(), level.dimension().identifier().toString(), origin.asLong(), index);
                var construction = assignments.createZone("totem:builder", mine(level, owner, target));
                var sites = dev.totem.villagers.builder.BuilderSiteSavedData.forServer(server);
                try {
                    assignments.putAssignment(new WorkerAssignment(customer.getUUID(), "totem:builder", Optional.of(construction.id()), Optional.empty()));
                    h.assertTrue(sites.registerOrReplace(registered, owner), "Could not register physical site");
                    level.setBlockAndUpdate(customer.blockPosition().below(), Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
                    buyer.takeExactMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)).orElseThrow();
                    buyer.insertExact(new ItemStack(Items.EMERALD));
                    seller.insertExact(new ItemStack(Items.COBBLESTONE));
                    var states = dev.totem.villagers.work.VillagerWorkSavedData.forServer(server);
                    VillagerBuilderRuntime.tickBuilder(server, level, customer, registered, sites, states, assignments, inventories);
                    h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 1
                            && level.getBlockState(target).isAir(), "Missing blueprint step did not commission actual cut stone");
                    VillagerBuilderRuntime.tickBuilder(server, level, customer, registered, sites, states, assignments, inventories);
                    h.assertTrue(level.getBlockState(target).equals(stairs.state()) && sites.getByBuilder(customer.getUUID()).orElseThrow().nextBlockIndex() == index + 1
                            && buyer.countMatchingItem(new ItemStack(Items.COBBLESTONE_STAIRS)) == 0, "Builder failed to place the paid staircase: wanted=" + stairs.state() + " got=" + level.getBlockState(target)
                            + " progress=" + sites.getByBuilder(customer.getUUID()) + " stock=" + buyer.snapshot());
                } finally {
                    sites.removeByBuilder(customer.getUUID(), owner);
                    assignments.removeZone(construction.id());
                }
                h.succeed();
                return;
            }
            if (!miner) {
                var ticket = VillagerResourceWorkforceRuntime.claimExactJobSite(level, smithProfession, table).orElseThrow();
                smith.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(level.dimension(), ticket));
                AssignProfessionFromJobSite.create().tryStart(level, smith, level.getGameTime());
                h.assertTrue(smith.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.NONE)
                        && !smith.getBrain().hasMemoryValue(MemoryModuleType.POTENTIAL_JOB_SITE), "Vanilla claimed the trial workstation");
            }
            buyer.insertExact(new ItemStack(Items.EMERALD, 3));
            buyer.insertExact(new ItemStack(Items.BREAD, 18));
            seller.insertExact(new ItemStack(Items.COBBLESTONE, 4));
            seller.insertExact(new ItemStack(Items.STICK, 4));
            // Exactly one free slot: payment must fit after materials have been debited.
            for (int i = 4; i < seller.snapshot().size(); i++) seller.insertExact(new ItemStack(Items.WOODEN_SHOVEL));
            long observed = level.getGameTime();
            for (int i = 0; i < 480; i++) VillageWorkshopApprenticeship.observe(level, farmer, observed);
            for (int i = 0; i < 479; i++) VillageWorkshopApprenticeship.observe(level, farmer, observed += 100);
            h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.STONE_HOE)) == 0, "Transient shortage caused a career change");
            if (miner) {
                VillageWorkshopApprenticeship.observe(level, farmer, observed += 100);
                h.assertTrue(assignments.getAssignment(smith.getUUID()).isPresent()
                        && seller.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 4, "Last working miner left before a replacement was equipped");
            }
            inventories.inventory(relief.getUUID()).insertExact(new ItemStack(Items.STONE_PICKAXE));
            var ticket = VillagerResourceWorkforceRuntime.claimExactJobSite(level, smithProfession, table).orElseThrow();
            VillageWorkshopApprenticeship.observe(level, farmer, observed += 100);
            h.assertTrue(seller.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 4, "Consumed inputs at an occupied station");
            level.getPoiManager().release(ticket);
            VillageWorkshopApprenticeship.observe(level, farmer, observed += 100);
            h.assertTrue(smith.getVillagerData().profession().value() == smithProfession
                    && home.equals(smith.getBrain().getMemory(MemoryModuleType.HOME))
                    && smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(p -> p.pos().equals(table)).isPresent(),
                    "Completed paid trial did not preserve the household and bind the workstation");
            h.assertTrue(buyer.countMatchingItem(new ItemStack(Items.STONE_HOE)) == 1 && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 0
                    && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 3 && seller.countMatchingItem(new ItemStack(Items.COBBLESTONE)) == 2
                    && seller.countMatchingItem(new ItemStack(Items.STICK)) == 2 && assignments.getAssignment(smith.getUUID()).isEmpty()
                    && assignments.getAssignment(relief.getUUID()).isPresent(), "Paid sale or zone handoff lost items or workers");
            h.succeed();
        } finally {
            settings.setMode(previous);
            for (var resident : residents) resident.discard();
            assignments.removeZone(zone.id());
            assignments.removeZone(spareZone.id());
        }
    }

    private static WorkZone mine(ServerLevel level, UUID owner, BlockPos pos) {
        var cell = new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ());
        return new WorkZone(owner, level.dimension().identifier().toString(), cell, cell);
    }

    private static void profession(Villager v, String id) {
        v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(id)))));
    }
}
