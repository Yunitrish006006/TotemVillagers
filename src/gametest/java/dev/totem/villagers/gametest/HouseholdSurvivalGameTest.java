package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.runtime.HouseholdSurvivalRuntime;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class HouseholdSurvivalGameTest {
    private static final AtomicReference<BlockPos> DENIED = new AtomicReference<>();
    static {
        WorldWorkPermissions.CHECK.register((level, villager, target) -> !target.equals(DENIED.get()));
    }

    @GameTest(maxTicks = 100)
    public void unemployedResidentMakesFirstRodAndStopsWithFood(GameTestHelper helper) {
        checkHousehold(helper, false, false);
    }

    @GameTest(maxTicks = 100)
    public void twoUnemployedResidentsOpenTheirFirstWheatField(GameTestHelper helper) {
        checkHousehold(helper, true, false);
    }

    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void hungryHouseholdClearsGrassAndPlantsAnIrrigatedRow(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var worker = helper.spawnWithNoFreeWill((EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:villager")), new BlockPos(4, 3, 4));
        var mode = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = mode.settings().mode();
        var stock = VillagerWorkInventorySavedData.forServer(server).inventory(worker.getUUID());
        var home = worker.blockPosition().north();
        var first = worker.blockPosition().south();
        var second = first.west();
        var straight = second.west();
        var bend = second.north();
        try {
            level.setBlock(home, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 2);
            worker.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), home));
            VillagerNutrition.setFoodLevel(worker, 0);
            mode.setMode(WorkBackedTradingMode.ENFORCED);
            stock.insertExact(new ItemStack(Items.WOODEN_HOE));
            stock.insertExact(new ItemStack(Items.WHEAT_SEEDS, 3));
            level.setBlock(first.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            level.setBlock(first, Blocks.SHORT_GRASS.defaultBlockState(), 2);
            level.setBlock(first.south().below(), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(first.south().east().below(), Blocks.GRAVEL.defaultBlockState(), 2);
            DENIED.set(first.below());
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, level.getBlockState(first).is(Blocks.SHORT_GRASS) && stock.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 3,
                    "Field clearing crossed protected soil");
            DENIED.set(null);
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, level.getBlockState(first).isAir() && stock.countMatchingItem(new ItemStack(Items.SHORT_GRASS)) == 1,
                    "The first planting site did not yield its actual grass clipping");
            HouseholdSurvivalRuntime.work(level, worker);
            level.setBlock(second.below(), Blocks.DIRT.defaultBlockState(), 2);
            HouseholdSurvivalRuntime.work(level, worker);
            for (var option : List.of(straight, bend)) level.setBlock(option.below(), Blocks.DIRT.defaultBlockState(), 2);
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, List.of(first, second, straight).stream().allMatch(pos -> level.getBlockState(pos).is(Blocks.WHEAT)
                    && dev.totem.villagers.runtime.StarterWheatField.owns(level, worker, pos)) && level.getBlockState(bend).isAir(),
                    "An irrigated straight row was available for all three seeds");
            helper.succeed();
        } finally {
            DENIED.set(null);
            mode.setMode(previous);
            worker.discard();
        }
    }

    @GameTest(maxTicks = 100)
    public void firstHarvestSettlesFarmer(GameTestHelper helper) {
        checkHousehold(helper, true, true);
    }

    @GameTest(maxTicks = 100)
    public void firstCatchSettlesFisherman(GameTestHelper helper) {
        checkHousehold(helper, false, true);
    }

    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void connectedFieldsKeepOneCaretakerUntilTheirStationIsRemoved(GameTestHelper helper) {
        var level = helper.getLevel();
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var first = helper.spawnWithNoFreeWill(type, new BlockPos(2, 3, 2));
        var second = helper.spawnWithNoFreeWill(type, new BlockPos(3, 3, 2));
        var role = BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")));
        var soil = first.blockPosition().below();
        var station = GlobalPos.of(level.dimension(), soil.north());
        var otherStation = GlobalPos.of(level.dimension(), soil.east().north());
        for (var pos : List.of(soil, soil.east())) level.setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
        for (var pos : List.of(station, otherStation)) level.setBlockAndUpdate(pos.pos(), Blocks.COMPOSTER.defaultBlockState());
        first.setVillagerData(first.getVillagerData().withProfession(role));
        second.setVillagerData(second.getVillagerData().withProfession(role));
        first.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
        second.getBrain().setMemory(MemoryModuleType.JOB_SITE, otherStation);
        var home = GlobalPos.of(level.dimension(), soil.west());
        first.getBrain().setMemory(MemoryModuleType.HOME, home);
        var parcels = dev.totem.villagers.worker.ProductionParcel.get(level.getServer());
        require(helper, parcels.acquire(level, first, station, soil, true), "First farmer failed to claim field");
        require(helper, !parcels.acquire(level, second, otherStation, soil.east(), true), "Second farmer claimed adjacent soil");
        var codec = dev.totem.villagers.worker.ProductionParcel.CODEC;
        var ops = com.mojang.serialization.JsonOps.INSTANCE;
        var restored = codec.parse(ops, codec.encodeStart(ops, parcels).getOrThrow()).getOrThrow();
        require(helper, restored.claim(first.getUUID()).equals(parcels.claim(first.getUUID()))
                && !restored.acquire(level, second, otherStation, soil.east(), true), "Reload lost ownership");
        level.setBlockAndUpdate(station.pos(), Blocks.AIR.defaultBlockState());
        parcels.reconcile(level, first);
        require(helper, parcels.claim(first.getUUID()).isEmpty()
                && first.getBrain().getMemory(MemoryModuleType.HOME).filter(home::equals).isPresent()
                && parcels.acquire(level, second, otherStation, soil.east(), true), "Station loss failed to transfer field or changed home");
        second.getBrain().setMemory(MemoryModuleType.HOME, home);
        parcels.reconcile(level, second);
        require(helper, parcels.claim(second.getUUID()).isEmpty(), "Moved farmer kept the old field");
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    @SuppressWarnings("unchecked")
    public void hungryFarmerGetsReplantedCarrotsFromHousedHelper(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var owner = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 4));
        var helper = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 4));
        var soil = owner.blockPosition().north().below();
        var crop = soil.above();
        var station = GlobalPos.of(level.dimension(), soil.west());
        var parcels = dev.totem.villagers.worker.ProductionParcel.get(server);
        var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(helper.getUUID());
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            for (var resident : List.of(owner, helper)) {
                var bed = resident.blockPosition().south(2);
                level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD), 2);
                resident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(resident, 20);
            }
            owner.setVillagerData(owner.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")))));
            level.setBlockAndUpdate(station.pos(), Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            owner.getBrain().setMemory(MemoryModuleType.JOB_SITE, station);
            require(h, parcels.acquire(level, owner, station, soil, true), "Farmer failed to claim field");
            var claim = parcels.claim(owner.getUUID());
            var carrots = (net.minecraft.world.level.block.CropBlock)Blocks.CARROTS;
            var mature = carrots.getStateForAge(carrots.getMaxAge());
            level.setBlockAndUpdate(crop, mature);
            inventory.insertExact(new ItemStack(Items.WOODEN_HOE));
            HouseholdSurvivalRuntime.work(level, helper);
            require(h, level.getBlockState(crop).equals(mature), "Healthy caretaker lost harvest");
            VillagerNutrition.setFoodLevel(owner, 8);
            DENIED.set(soil);
            HouseholdSurvivalRuntime.work(level, helper);
            require(h, level.getBlockState(crop).equals(mature), "Helper ignored soil protection");
            DENIED.set(null);
            HouseholdSurvivalRuntime.work(level, helper);
            int harvest = inventory.countMatchingItem(new ItemStack(Items.CARROT));
            require(h, harvest > 0 && carrots.getAge(level.getBlockState(crop)) == 0
                    && inventory.snapshot().stream().anyMatch(stack -> stack.is(Items.WOODEN_HOE) && stack.getDamageValue() == 1),
                    "Emergency harvest did not store drops, replant and wear the helper's hoe");
            HouseholdSurvivalRuntime.work(level, helper);
            require(h, VillagerNutrition.foodLevel(owner) == 11 && inventory.countMatchingItem(new ItemStack(Items.CARROT)) == harvest - 1
                    && parcels.claim(owner.getUUID()).equals(claim) && parcels.claim(helper.getUUID()).isEmpty(),
                    "Emergency meal failed to use one real carrot or changed field ownership");
            level.setBlockAndUpdate(crop, mature);
            HouseholdSurvivalRuntime.work(level, helper);
            require(h, level.getBlockState(crop).equals(mature), "Replaced recovered caretaker");
            h.succeed();
        } finally {
            DENIED.set(null);
            settings.setMode(previous);
            owner.discard();
            helper.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private void checkHousehold(GameTestHelper helper, boolean sowWheat, boolean settle) {
        var level = helper.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        var worker = helper.spawnWithNoFreeWill((EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager")), new BlockPos(4, 3, 4));
        var neighbor = helper.spawnWithNoFreeWill((EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.parse("minecraft:villager")), new BlockPos(4, 3, 7));
        var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(worker.getUUID());
        BlockPos home = helper.absolutePos(new BlockPos(3, 3, 4));
        BlockPos stand = worker.blockPosition();
        BlockPos water = stand.east();
        BlockPos tree = stand.north();
        BlockPos table = stand.south();
        var grass = List.of(stand.north(2), stand.west(2).south(), stand.north().east(), stand.south().east(),
                stand.south(2), stand.south().west());
        try {
            var head = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD);
            // Keep the six collection targets distinct from the bed's second block.
            head = head.setValue(BedBlock.FACING, net.minecraft.core.Direction.EAST);
            var other = home.west();
            level.setBlock(other.below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(home.below(), Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(other, head.setValue(BedBlock.PART, BedPart.FOOT), 2);
            level.setBlock(home, head, 2);
            worker.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), home));
            level.setBlockAndUpdate(stand.below(), Blocks.DIRT.defaultBlockState());
            level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(water.above(), Blocks.AIR.defaultBlockState(), 2);
            for (var pos : grass) {
                level.setBlockAndUpdate(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                level.setBlockAndUpdate(pos, Blocks.SHORT_GRASS.defaultBlockState());
            }
            level.setBlockAndUpdate(table.below(), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(table, Blocks.AIR.defaultBlockState());
            var placed = new java.util.HashSet<BlockPos>();
            for (int y = 0; y < 4; y++) {
                level.setBlockAndUpdate(tree.above(y), Blocks.OAK_LOG.defaultBlockState());
                placed.add(tree.above(y));
            }
            level.setBlockAndUpdate(tree.above(4), Blocks.OAK_LEAVES.defaultBlockState());
            BlockPos firstField = stand.south(2).east();
            if (sowWheat) {
                level.setBlockAndUpdate(firstField, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(firstField.below(), Blocks.DIRT.defaultBlockState());
                level.setBlock(firstField.below().east(), Blocks.WATER.defaultBlockState(), 2);
                require(helper, inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS)), "Missing starting seed");
            }
            VillagerNutrition.setFoodLevel(worker, settle ? 20 : 0);
            var chunk = level.getChunkAt(tree);
            var featureWrites = new java.util.HashSet<>(placed);
            featureWrites.add(tree.below());
            dev.totem.villagers.world.NaturalOakTimber.record(chunk, tree, featureWrites, java.util.Set.of(tree.above(4)));
            level.setBlockAndUpdate(tree.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(tree.above(), Blocks.OAK_LOG.defaultBlockState());
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, level.getBlockState(tree).is(Blocks.OAK_LOG) && inventory.countMatchingItem(new ItemStack(Items.OAK_LOG)) == 0,
                    "Replaced player log retained a natural timber permit");
            // Supply the same exact placement evidence that successful terrain tree generation records.
            dev.totem.villagers.world.NaturalOakTimber.record(chunk, tree, featureWrites, java.util.Set.of(tree.above(4)));
            if (settle) {
                var crown = tree.above(4);
                level.setBlockAndUpdate(crown, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(crown, Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
            }
            settings.setMode(WorkBackedTradingMode.DISABLED);
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, level.getBlockState(tree).is(Blocks.OAK_LOG), "Disabled world performed work");
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            if (!sowWheat) {
                DENIED.set(water);
                HouseholdSurvivalRuntime.work(level, worker);
                require(helper, level.getBlockState(tree).is(Blocks.OAK_LOG), "Protected water triggered work");
            }
            DENIED.set(tree.above(2));
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, placed.stream().allMatch(p -> level.getBlockState(p).is(Blocks.OAK_LOG)),
                    "A vetoed trunk was partly cut");
            DENIED.set(null);
            BlockPos station = stand.north().east();
            if (sowWheat) {
                for (int step = 0; step < 20 && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) > 0; step++) {
                    HouseholdSurvivalRuntime.work(level, worker);
                }
                BlockPos field = null;
                for (BlockPos pos : BlockPos.withinManhattan(home, 5)) {
                    if (dev.totem.villagers.runtime.StarterWheatField.owns(level, worker, pos)) field = pos.immutable();
                }
                require(helper, field != null && level.getBlockState(field).is(Blocks.WHEAT)
                        && level.getBlockState(field.below()).is(Blocks.FARMLAND)
                        && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0,
                        "Starter seed did not become a claimed wheat crop: " + inventory.snapshot());
                if (!settle) {
                    level.setBlockAndUpdate(field, Blocks.AIR.defaultBlockState());
                    require(helper, level.getBlockState(field.below()).is(Blocks.FARMLAND), "Missing household tilled soil");
                    require(helper, inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS)), "Missing replacement seed");
                    VillagerNutrition.setFoodLevel(worker, 0);
                    HouseholdSurvivalRuntime.work(level, worker);
                    require(helper, dev.totem.villagers.runtime.StarterWheatField.owns(level, worker, field)
                            && level.getBlockState(field).is(Blocks.WHEAT)
                            && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0,
                            "Starving household with a hoe and seed did not restore its lost crop");
                    var expansion = field.west();
                    level.setBlockAndUpdate(expansion, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(expansion.below(), Blocks.DIRT.defaultBlockState());
                    require(helper, inventory.insertExact(new ItemStack(Items.WHEAT_SEEDS)), "Could not supply second seed");
                    HouseholdSurvivalRuntime.work(level, worker);
                    var cells = dev.totem.villagers.worker.ProductionParcel.get(server).claim(worker.getUUID()).orElseThrow().cells();
                    require(helper, cells.size() == 2 && cells.contains(field.below())
                            && cells.stream().allMatch(pos -> level.getBlockState(pos.above()).is(Blocks.WHEAT))
                            && inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) == 0,
                            "Hungry household did not plant and register two paid, adjacent wheat plots");
                    VillagerNutrition.setFoodLevel(worker, 20);
                }
                if (settle) {
                    station = field.east();
                    level.setBlockAndUpdate(station, Blocks.COMPOSTER.defaultBlockState());
                    var role = BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                            BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")));
                    neighbor.setVillagerData(neighbor.getVillagerData().withProfession(role));
                    var otherSite = GlobalPos.of(level.dimension(), station.east());
                    level.setBlockAndUpdate(otherSite.pos(), Blocks.COMPOSTER.defaultBlockState());
                    neighbor.getBrain().setMemory(MemoryModuleType.JOB_SITE, otherSite);
                    require(helper, !dev.totem.villagers.worker.ProductionParcel.get(server)
                            .acquire(level, neighbor, otherSite, field.below(), true), "Farmer stole a starter plot");
                }
                var wheat = (net.minecraft.world.level.block.CropBlock) Blocks.WHEAT;
                worker.setPos(field.getX() + .5, field.getY(), field.getZ() + .5);
                for (int cycle = 0; cycle < 5 && inventory.countMatchingItem(new ItemStack(Items.BREAD)) == 0; cycle++) {
                    level.setBlockAndUpdate(field, wheat.getStateForAge(wheat.getMaxAge()));
                    var harvest = new dev.totem.villagers.world.FarmerWorldWorkAction();
                    require(helper, !harvest.complete(level, neighbor, field,
                            dev.totem.villagers.work.WorkOrderDefinitions.catalog().require("totem:farmer_wheat"), inventory),
                            "A second unemployed resident harvested someone else's field");
                    for (int step = 0; step < 5; step++) {
                        if (!settle && inventory.countMatchingItem(new ItemStack(Items.WHEAT)) >= 3)
                            worker.snapTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(stand));
                        HouseholdSurvivalRuntime.work(level, worker);
                    }
                    if (settle) {
                        require(helper, inventory.countMatchingItem(new ItemStack(Items.WHEAT)) > 0, "No harvest before promotion");
                        checkCareer(helper, worker, home, station, "farmer", field.below());
                        helper.succeed();
                        return;
                    }
                }
                require(helper, inventory.countMatchingItem(new ItemStack(Items.BREAD)) == 1
                        && level.getBlockState(field).is(Blocks.WHEAT)
                        && !wheat.isMaxAge(level.getBlockState(field)), "Wheat was not replanted and made into bread");
                helper.succeed();
                return;
            }
            for (int step = 0; step < 22 && inventory.countMatchingItem(new ItemStack(Items.FISHING_ROD)) == 0; step++) {
                HouseholdSurvivalRuntime.work(level, worker);
            }
            require(helper, inventory.countMatchingItem(new ItemStack(Items.FISHING_ROD)) == 1
                    && inventory.countMatchingItem(new ItemStack(Items.OAK_PLANKS)) == 2
                    && inventory.countMatchingItem(new ItemStack(Items.STICK)) >= 1
                    && inventory.countMatchingItem(new ItemStack(Items.OAK_LOG)) == 2
                    && placed.stream().allMatch(p -> level.getBlockState(p).isAir())
                    && level.getBlockState(table).is(Blocks.CRAFTING_TABLE)
                    && level.getBlockState(tree.above(4)).isAir() == !settle
                    && grass.stream().filter(p -> level.getBlockState(p).isAir()).count() == (settle ? 6 : 5),
                    "First rod did not use the available natural fibres and three sticks: " + inventory.snapshot());
            if (settle) {
                level.setBlockAndUpdate(station, Blocks.BARREL.defaultBlockState());
                level.setBlockAndUpdate(station.east(), Blocks.CAMPFIRE.defaultBlockState());
            }
            for (int step = 0; step < 60; step++) HouseholdSurvivalRuntime.work(level, worker);
            var stocked = inventory.snapshot();
            if (settle) {
                require(helper, stocked.stream().anyMatch(s -> s.is(Items.COD) || s.is(Items.SALMON)), "No catch before promotion");
                checkCareer(helper, worker, home, station, "fisherman", water);
                level.setBlockAndUpdate(station.east(), Blocks.AIR.defaultBlockState());
                var parcels = dev.totem.villagers.worker.ProductionParcel.get(server);
                parcels.reconcile(level, worker);
                require(helper, parcels.claim(worker.getUUID()).isEmpty(), "Disabled fishing station retained its shore");
                helper.succeed();
                return;
            }
            require(helper, !VillagerNutrition.isHungry(worker), "Household did not eat its own catch");
            require(helper, stocked.stream().mapToInt(s -> VillagerNutrition.nutrition(s) * s.getCount()).sum() >= 20,
                    "No physical food reserve after fishing");
            ItemStack rod = stocked.stream().filter(s -> s.is(Items.FISHING_ROD)).findFirst().orElseThrow();
            require(helper, rod.getDamageValue() > 0, "Fishing did not wear the rod");
            HouseholdSurvivalRuntime.work(level, worker);
            require(helper, inventory.countMatchingItem(rod) == 1, "Well-supplied resident kept fishing");
            helper.succeed();
        } finally {
            DENIED.set(null);
            settings.setMode(previous);
            VillagerWorkInventorySavedData.forServer(server).drain(worker.getUUID());
            worker.discard();
            neighbor.discard();
        }
    }

    private static void checkCareer(GameTestHelper helper, Villager worker, BlockPos home, BlockPos station,
                                    String career, BlockPos target) {
        var level = helper.getLevel();
        var job = GlobalPos.of(level.dimension(), station);
        var free = level.getPoiManager().find(worker.getVillagerData().profession().value().heldJobSite(),
                station::equals, station, 1, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.HAS_SPACE);
        require(helper, free.isEmpty(), "Career left the job-site ticket available");
        require(helper, Identifier.parse("minecraft:" + career).equals(
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(worker.getVillagerData().profession().value()))
                && worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(job::equals).isPresent()
                && worker.getBrain().getMemory(MemoryModuleType.HOME).filter(pos -> pos.pos().equals(home)).isPresent()
                && dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).claim(worker.getUUID())
                    .filter(claim -> claim.cells().contains(target)).isPresent(), "Missing career, home, job ticket or parcel: " + career);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) throw helper.assertionException(message);
    }
}
