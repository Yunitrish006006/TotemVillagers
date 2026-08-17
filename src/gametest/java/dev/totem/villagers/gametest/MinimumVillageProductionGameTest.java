package dev.totem.villagers.gametest;

import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.runtime.VillagerFarmerWorkRuntime;
import dev.totem.villagers.runtime.VillagerSpecialistProfessionRuntime;
import dev.totem.villagers.runtime.VillagerWorldWorkRuntime;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.BoundedZoneTargetFinder;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import dev.totem.villagers.world.WorldWorkNavigation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A smallest useful village production simulation: one Farmer, Miner, and
 * Lumberjack each take one real world target into their own material store.
 */
public final class MinimumVillageProductionGameTest {
    private static final TagKey<Block> MINER_TARGETS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_targets"));
    private static final TagKey<Block> LUMBERJACK_LOGS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "lumberjack_oak_logs"));

    @GameTest(maxTicks = 40)
    public void boundedMineScanChecksTheWholeSurfaceBeforeSpendingItsBudgetAtDepth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos workerPosition = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos distantFace = helper.absolutePos(new BlockPos(8, 2, 7));
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 9; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.COBBLESTONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
        level.setBlock(distantFace, Blocks.STONE.defaultBlockState(), 3);
        Villager miner = spawnMobileVillager(helper, new BlockPos(5, 2, 5), "totem:miner");
        WorkZone deepNaturalMine = new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(workerPosition.getX() - 3, workerPosition.getY(), workerPosition.getZ() - 3),
                new BlockCoordinate(workerPosition.getX() + 3, workerPosition.getY() + 19, workerPosition.getZ() + 3));
        helper.runAfterDelay(2, () -> {
            try {
                require(helper, new BoundedZoneTargetFinder()
                            .findNearest(level, miner, deepNaturalMine, MINER_TARGETS, 256)
                            .filter(distantFace::equals).isPresent(),
                    "Bounded natural-mine scan exhausted its budget below nearby empty columns before checking the surface face"
                            + "; worker=" + miner.blockPosition() + ", target=" + distantFace
                            + ", tagged=" + level.getBlockState(distantFace).is(MINER_TARGETS)
                            + ", permitted=" + WorldWorkPermissions.mayWork(level, miner, distantFace)
                            + ", withinReach=" + WorldWorkNavigation.isWithinReach(miner, distantFace)
                            + ", path=" + WorldWorkNavigation.pathToReach(level, miner, distantFace)
                            .map(path -> "reachable=" + path.canReach() + ", nodes=" + path.getNodeCount())
                            .orElse("none"));
                helper.succeed();
            } finally {
                miner.discard();
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void lumberjackTargetScanSkipsMiddleLogsAndFindsTheTreeBase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos treeBase = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.DIRT);
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.COBBLESTONE);
        for (int height = 0; height < 6; height++) {
            level.setBlock(treeBase.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        level.setBlock(treeBase.above(6), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true), 3);
        Villager lumberjack = spawnMobileVillager(helper, new BlockPos(4, 2, 5), "totem:lumberjack");
        WorkOrder order = order(helper, "totem:lumberjack_oak_logs");
        WorkZone variableHeightPlot = zone(level, treeBase, treeBase.above(15));
        try {
            Optional<BlockPos> selected = new BoundedZoneTargetFinder().findNearest(
                    level, lumberjack, variableHeightPlot, LUMBERJACK_LOGS, 512,
                    target -> LumberjackWorldWorkAction.isEligibleBase(
                            level, lumberjack, variableHeightPlot, target, LUMBERJACK_LOGS, order));
            require(helper, selected.filter(treeBase::equals).isPresent(),
                    "Lumberjack stopped at a nearer middle log instead of continuing to the six-block tree base"
                            + "; selected=" + selected + ", worker=" + lumberjack.blockPosition());
            helper.succeed();
        } finally {
            lumberjack.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void farmerMinerAndLumberjackProduceMaterialsFromOneMinimalVillage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos composter = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos crop = helper.absolutePos(new BlockPos(5, 2, 3));
        BlockPos furnace = helper.absolutePos(new BlockPos(9, 2, 5));
        BlockPos oreFace = helper.absolutePos(new BlockPos(10, 2, 5));
        BlockPos treeBase = helper.absolutePos(new BlockPos(17, 2, 5));
        BlockPos woodcutter = helper.absolutePos(new BlockPos(19, 2, 5));
        Villager farmer = spawnVillager(helper, new BlockPos(3, 3, 4), "minecraft:farmer");
        Villager miner = spawnVillager(helper, new BlockPos(9, 3, 4), "totem:miner");
        Villager lumberjack = spawnVillager(helper, new BlockPos(16, 3, 5), "totem:lumberjack");
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        try {
            miner.setPos(oreFace.getX() - 0.5D, oreFace.getY(), oreFace.getZ() + 0.5D);
            level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), 3);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), composter));
            level.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            CropBlock wheat = (CropBlock) Blocks.WHEAT;
            level.setBlock(crop, wheat.getStateForAge(wheat.getMaxAge()), 3);

            level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
            level.setBlock(oreFace, Blocks.STONE.defaultBlockState(), 3);
            WorkZone mineZone = zone(level, furnace, oreFace);
            require(helper, MinerFurnaceWorkstation.ensureAssigned(level, miner, mineZone).filter(furnace::equals).isPresent(),
                    "Miner did not bind the Furnace in its own Mine Zone");

            level.setBlock(treeBase.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int height = 0; height < 4; height++) {
                level.setBlock(treeBase.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
            level.setBlock(treeBase.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), 3);
            level.setBlock(woodcutter, TotemVillagerBlocks.WOODCUTTER.defaultBlockState(), 3);
            WorkZone lumberyard = zone(level, treeBase, treeBase.above(4));

            WorkOrder farmerOrder = order(helper, "totem:farmer_wheat");
            WorkOrder minerOrder = order(helper, "totem:miner_stone");
            WorkOrder lumberjackOrder = order(helper, "totem:lumberjack_oak_logs");
            VillagerWorkInventory farmerInventory = inventories.inventory(farmer.getUUID());
            VillagerWorkInventory minerInventory = inventories.inventory(miner.getUUID());
            VillagerWorkInventory lumberjackInventory = inventories.inventory(lumberjack.getUUID());
            require(helper, minerInventory.insertExact(new ItemStack(Items.STONE_PICKAXE)),
                    "Could not give the Miner a personal stone pickaxe");
            require(helper, lumberjackInventory.insertAllExact(List.of(
                            new ItemStack(Items.IRON_AXE), new ItemStack(Items.OAK_SAPLING))),
                    "Could not give the manual-zone Lumberjack an axe and physical replanting sapling");

            require(helper, farmerInventory.insertExact(new ItemStack(Items.IRON_HOE)),
                    "Could not give the Farmer its physical work hoe");
            require(helper, new FarmerWorldWorkAction().complete(level, farmer, crop, farmerOrder, farmerInventory),
                    "Farmer could not harvest and replant the prepared wheat field");
            require(helper, new MinerWorldWorkAction().complete(level, miner, oreFace, MINER_TARGETS, minerOrder, minerInventory),
                    "Miner could not mine the prepared stone face");
            require(helper, new LumberjackWorldWorkAction().complete(level, lumberjack, lumberyard, treeBase, LUMBERJACK_LOGS,
                    lumberjackOrder, lumberjackInventory), "Lumberjack could not harvest the prepared mature tree");
            require(helper, farmer.swinging && miner.swinging && lumberjack.swinging,
                    "Successful resource work did not trigger synchronized hand animations");

            require(helper, count(farmerInventory, Items.WHEAT) == 1
                            && count(minerInventory, Items.COBBLESTONE) == 1
                            && count(lumberjackInventory, Items.OAK_LOG) == 4,
                    "Minimal village did not produce exactly wheat, cobblestone, and four oak logs");
            require(helper, level.getBlockState(crop).is(Blocks.WHEAT) && wheat.getAge(level.getBlockState(crop)) == 0,
                    "Farmer did not replant harvested wheat");
            require(helper, level.getBlockState(oreFace).isAir(), "Miner did not consume the mined stone face");
            require(helper, level.getBlockState(treeBase).is(Blocks.OAK_SAPLING),
                    "Lumberjack did not replant the harvested tree");
            require(helper, level.getBlockState(composter).is(Blocks.COMPOSTER)
                            && level.getBlockState(furnace).is(Blocks.FURNACE)
                            && level.getBlockState(woodcutter).is(TotemVillagerBlocks.WOODCUTTER),
                    "Minimal village lost one of its physical workstations during production");
            helper.succeed();
        } finally {
            inventories.drain(farmer.getUUID());
            inventories.drain(miner.getUUID());
            inventories.drain(lumberjack.getUUID());
            farmer.discard();
            miner.discard();
            lumberjack.discard();
        }
    }

    @GameTest(maxTicks = 300)
    public void autonomousRuntimesNavigateAndProduceForAllThreeFoundingRoles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);

        BlockPos composter = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos crop = helper.absolutePos(new BlockPos(6, 2, 1));
        BlockPos furnace = helper.absolutePos(new BlockPos(1, 2, 3));
        BlockPos stone = helper.absolutePos(new BlockPos(6, 2, 3));
        BlockPos treeBase = helper.absolutePos(new BlockPos(6, 2, 6));
        prepareWalkway(helper, 1, 1, 6);
        prepareWalkway(helper, 3, 1, 6);
        prepareWalkway(helper, 6, 1, 6);

        helper.setBlock(new BlockPos(1, 2, 1), Blocks.COMPOSTER);
        helper.setBlock(new BlockPos(6, 1, 1), Blocks.FARMLAND);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        level.setBlock(crop, wheat.getStateForAge(wheat.getMaxAge()), 3);
        helper.setBlock(new BlockPos(1, 2, 3), Blocks.FURNACE);
        helper.setBlock(new BlockPos(6, 2, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 1, 6), Blocks.DIRT);
        for (int height = 0; height < 4; height++) {
            level.setBlock(treeBase.above(height), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        level.setBlock(treeBase.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true), 3);

        Villager farmer = spawnMobileVillager(helper, new BlockPos(1, 3, 1), "minecraft:farmer");
        Villager miner = spawnMobileVillager(helper, new BlockPos(1, 3, 3), "totem:miner");
        Villager lumberjack = spawnMobileVillager(helper, new BlockPos(1, 3, 6), "totem:lumberjack");
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), composter));
        VillagerNutrition.setFoodLevel(farmer, 20);
        VillagerNutrition.setFoodLevel(miner, 20);
        VillagerNutrition.setFoodLevel(lumberjack, 20);

        WorkZoneRecord minerZone = assignments.createZone("totem:miner", zone(level, furnace, stone));
        WorkZoneRecord lumberjackZone = assignments.createZone("totem:lumberjack", zone(level, treeBase, treeBase.above(4)));
        assignments.putAssignment(new WorkerAssignment(miner.getUUID(), "totem:miner",
                java.util.Optional.of(minerZone.id()), java.util.Optional.empty()));
        assignments.putAssignment(new WorkerAssignment(lumberjack.getUUID(), "totem:lumberjack",
                java.util.Optional.of(lumberjackZone.id()), java.util.Optional.empty()));

        VillagerWorkInventory farmerInventory = inventories.inventory(farmer.getUUID());
        VillagerWorkInventory minerInventory = inventories.inventory(miner.getUUID());
        VillagerWorkInventory lumberjackInventory = inventories.inventory(lumberjack.getUUID());
        require(helper, minerInventory.insertExact(new ItemStack(Items.STONE_PICKAXE)),
                "Could not give the Miner a personal stone pickaxe");
        require(helper, lumberjackInventory.insertAllExact(List.of(
                        new ItemStack(Items.IRON_AXE), new ItemStack(Items.OAK_SAPLING))),
                "Could not give the manual-zone Lumberjack an axe and physical replanting sapling");
        require(helper, farmerInventory.insertExact(new ItemStack(Items.IRON_HOE)),
                "Could not give the Farmer a personal iron hoe");
        helper.succeedWhen(() -> {
            // The live GameTest server also runs vanilla POI reset AI. This
            // fixture supplies the Composter directly rather than through the
            // POI claim queue, so retain its intended Farmer identity while
            // exercising the production scheduler and navigation below.
            ensureProfession(farmer, "minecraft:farmer");
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), composter));
            VillagerSpecialistProfessionRuntime.reconcileForGameTest(server);
            VillagerFarmerWorkRuntime.tickForGameTest(server);
            VillagerWorldWorkRuntime.tickForGameTest(server);
            int wheatCount = count(farmerInventory, Items.WHEAT);
            int cobblestoneCount = count(minerInventory, Items.COBBLESTONE);
            int logCount = count(lumberjackInventory, Items.OAK_LOG);
            require(helper, wheatCount >= 1 && cobblestoneCount >= 1 && logCount >= 4,
                    "Autonomous founding workforce has not yet completed all three world jobs"
                            + "; items=" + wheatCount + "/" + cobblestoneCount + "/" + logCount
                            + ", distance=" + distance(farmer, crop) + "/" + distance(miner, stone)
                            + "/" + distance(lumberjack, treeBase)
                            + ", state=" + workState(server, farmer) + "/" + workState(server, miner)
                            + "/" + workState(server, lumberjack)
                            + ", facts=" + farmerFacts(level, farmer, composter, crop)
                            + "/" + minerFacts(level, miner, stone, minerZone)
                            + "/" + lumberjackFacts(level, lumberjack, treeBase, lumberjackZone));
            require(helper, farmer.distanceToSqr(Vec3.atCenterOf(crop)) <= 16.0D
                            && WorldWorkNavigation.isWithinReach(miner, stone)
                            && WorldWorkNavigation.isWithinReach(lumberjack, treeBase),
                    "A founding resource worker committed without occupying an adjacent work face");
            assignments.removeZone(minerZone.id());
            assignments.removeZone(lumberjackZone.id());
            VillagerWorkSavedData.forServer(server).remove(farmer.getUUID());
            VillagerWorkSavedData.forServer(server).remove(miner.getUUID());
            VillagerWorkSavedData.forServer(server).remove(lumberjack.getUUID());
            inventories.drain(farmer.getUUID());
            inventories.drain(miner.getUUID());
            inventories.drain(lumberjack.getUUID());
            farmer.discard();
            miner.discard();
            lumberjack.discard();
        });
    }

    private static void prepareWalkway(GameTestHelper helper, int z, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.COBBLESTONE);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
        }
    }

    private static String workState(net.minecraft.server.MinecraftServer server, Villager villager) {
        return VillagerWorkSavedData.forServer(server).get(villager.getUUID())
                .map(state -> state.activeWork().map(active -> active.orderId() + ":" + active.elapsedTicks())
                        .orElseGet(() -> state.diagnostic().map(diagnostic -> diagnostic.orderId() + ":"
                                + diagnostic.blockedReason()).orElse("idle")))
                .orElse("missing");
    }

    private static String distance(Villager villager, BlockPos target) {
        return String.format(java.util.Locale.ROOT, "%.1f", villager.distanceToSqr(Vec3.atCenterOf(target)));
    }

    private static String farmerFacts(ServerLevel level, Villager farmer, BlockPos composter, BlockPos crop) {
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        return professionId(farmer) + ",food=" + VillagerNutrition.foodLevel(farmer)
                + ",job=" + farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos).map(composter::equals).orElse(false)
                + ",crop=" + (level.getBlockState(crop).is(Blocks.WHEAT)
                && wheat.isMaxAge(level.getBlockState(crop)))
                + ",path=" + (farmer.getNavigation().createPath(crop, 0) != null)
                + ",permission=" + WorldWorkPermissions.mayWork(level, farmer, crop);
    }

    private static String minerFacts(ServerLevel level, Villager miner, BlockPos stone, WorkZoneRecord zone) {
        return professionId(miner) + ",food=" + VillagerNutrition.foodLevel(miner)
                + ",target=" + level.getBlockState(stone).is(MINER_TARGETS)
                + ",path=" + (miner.getNavigation().createPath(stone, 0) != null)
                + ",workPath=" + WorldWorkNavigation.pathToReach(level, miner, stone)
                .map(path -> path.getEndNode() + "->" + path.getTarget()).orElse("none")
                + ",permission=" + WorldWorkPermissions.mayWork(level, miner, stone)
                + ",station=" + MinerFurnaceWorkstation.ensureAssigned(level, miner, zone.zone()).isPresent();
    }

    private static String lumberjackFacts(ServerLevel level, Villager lumberjack, BlockPos treeBase, WorkZoneRecord zone) {
        return professionId(lumberjack) + ",food=" + VillagerNutrition.foodLevel(lumberjack)
                + ",target=" + level.getBlockState(treeBase).is(LUMBERJACK_LOGS)
                + ",path=" + (lumberjack.getNavigation().createPath(treeBase, 0) != null)
                + ",workPath=" + WorldWorkNavigation.pathToReach(level, lumberjack, treeBase)
                .map(path -> path.getEndNode() + "->" + path.getTarget()).orElse("none")
                + ",permission=" + WorldWorkPermissions.mayWork(level, lumberjack, treeBase)
                + ",tree=" + LumberjackWorldWorkAction.isEligibleBase(level, lumberjack, zone.zone(), treeBase,
                LUMBERJACK_LOGS, WorkOrderDefinitions.catalog().snapshot().get("totem:lumberjack_oak_logs"));
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static WorkOrder order(GameTestHelper helper, String id) {
        WorkOrder value = WorkOrderDefinitions.catalog().snapshot().get(id);
        if (value == null) {
            throw helper.assertionException("Missing live work order " + id);
        }
        return value;
    }

    private static WorkZone zone(ServerLevel level, BlockPos first, BlockPos second) {
        return new WorkZone(UUID.randomUUID(), level.dimension().identifier().toString(),
                new BlockCoordinate(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                        Math.min(first.getZ(), second.getZ())),
                new BlockCoordinate(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                        Math.max(first.getZ(), second.getZ())));
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw helper.assertionException("Missing profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnMobileVillager(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawn((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw helper.assertionException("Missing profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private static void ensureProfession(Villager villager, String professionId) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
    }
}
