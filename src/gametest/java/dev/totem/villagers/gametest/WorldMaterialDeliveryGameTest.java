package dev.totem.villagers.gametest;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.world.WorldWorkPermissions;
import dev.totem.villagers.world.ore.MinerIncidentalOreDefinitions;
import dev.totem.villagers.world.ore.MinerIncidentalOreRule;
import dev.totem.villagers.world.ore.MinerOreSafetySavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies world work uses a villager's personal inventory and preserves its source when it is full. */
public final class WorldMaterialDeliveryGameTest {
    private static final TagKey<Block> MINER_TARGETS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_targets"));
    private static final TagKey<Block> MINER_LAPIS_ORES = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_lapis_ores"));
    private static final TagKey<Block> MINER_ORES = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_ores"));
    private static final TagKey<Block> LUMBERJACK_LOGS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "lumberjack_oak_logs"));
    private static final AtomicReference<BlockPos> PROTECTED_TARGET = new AtomicReference<>();

    static {
        // The registration remains active for the shared GameTest server, but
        // only the one target selected by this test is denied.
        WorldWorkPermissions.CHECK.register((level, villager, target) -> !target.equals(PROTECTED_TARGET.get()));
    }

    @GameTest(maxTicks = 40)
    public void minerStoresVanillaCobblestoneExactlyOnce(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(8, 3, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.STONE_PICKAXE)),
                    "Could not give the Miner a personal stone pickaxe");
            WorkOrder order = minerOrder();

            require(helper, !WorldWorkNavigation.isWithinReach(miner, target)
                            && !new MinerWorldWorkAction().complete(
                            helper.getLevel(), miner, target, MINER_TARGETS, order, inventory),
                    "Miner standing on the surface mined a buried target through the floor");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.STONE)
                            && count(inventory.snapshot(), Items.COBBLESTONE) == 0,
                    "Rejected surface mining changed the target or credited materials");

            miner.setPos(target.getX() - 0.5D, target.getY(), target.getZ() + 0.5D);
            require(helper, WorldWorkNavigation.isWithinReach(miner, target),
                    "Miner did not enter the exposed horizontal work face");
            require(helper, new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_TARGETS, order, inventory),
                    "Miner could not commit a permitted stone target to its personal material store");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 1,
                    "Miner work did not create exactly one cobblestone in the personal inventory");
            require(helper, helper.getLevel().getBlockState(target).isAir(),
                    "Miner work left the committed source block behind");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer()).drain(miner.getUUID());
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40, padding = 20)
    public void generatedVillageMiningConsumesFacesAndExtendsItsSpiralDownward(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 20, 8));
        Direction direction = VillageUtilityFeature.mineDirection();
        fillExtensionTerrain(level, furnace);
        require(helper, VillageUtilityFeature.placeMine(level, furnace, BoundingBox.infinite()),
                "Could not place the generated Mine fixture");
        BlockPos firstTarget = VillageUtilityFeature.mineLanding(furnace, direction, 2)
                .relative(VillageUtilityFeature.mineOutwardDirection(direction, 2));
        BlockPos secondTarget = VillageUtilityFeature.mineLanding(furnace, direction, 3)
                .relative(VillageUtilityFeature.mineOutwardDirection(direction, 3));
        Villager miner = spawnVillager(helper, new BlockPos(1, 2, 1));
        var server = helper.getLevel().getServer();
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        GeneratedVillageSavedData villages = GeneratedVillageSavedData.forServer(server);
        String villageId = "game-test-progressive-mine-" + UUID.randomUUID();
        BlockCoordinate minimum = new BlockCoordinate(furnace.getX(), furnace.getY() - 16, furnace.getZ() - 3);
        BlockCoordinate maximum = new BlockCoordinate(furnace.getX() + 6, furnace.getY() + 3, furnace.getZ() + 3);
        WorkZoneRecord zone = assignments.createZone("totem:miner", new WorkZone(UUID.randomUUID(),
                level.dimension().identifier().toString(), minimum, maximum));
        try {
            setProfession(miner, "totem", "miner");
            assignments.putAssignment(new WorkerAssignment(miner.getUUID(), "totem:miner",
                    Optional.of(zone.id()), Optional.empty()));
            villages.discover(new GeneratedVillageState(villageId,
                    level.dimension().identifier().toString(), minimum, maximum, false,
                    Optional.empty(), Optional.empty(), Optional.of(zone.id())));
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), furnace));
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server)
                    .inventory(miner.getUUID());
            require(helper, level.getBlockState(firstTarget).is(Blocks.STONE)
                            && level.getBlockState(secondTarget).is(Blocks.STONE)
                            && inventory.insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not prepare the progressive generated-Mine fixture");
            MinerWorldWorkAction noRandomOre = new MinerWorldWorkAction(() -> 9_999);

            BlockPos firstLanding = VillageUtilityFeature.mineLanding(furnace, direction, 2);
            miner.setPos(firstLanding.getX() + 0.5D, firstLanding.getY(), firstLanding.getZ() + 0.5D);
            require(helper, noRandomOre.complete(level, miner, firstTarget, MINER_TARGETS, minerOrder(), inventory),
                    "Generated Mine could not consume its first physical work face");
            assertExtendedMineStep(helper, assignments, zone.id(), furnace, direction, miner, 16);

            BlockPos secondLanding = VillageUtilityFeature.mineLanding(furnace, direction, 3);
            miner.setPos(secondLanding.getX() + 0.5D, secondLanding.getY(), secondLanding.getZ() + 0.5D);
            require(helper, noRandomOre.complete(level, miner, secondTarget, MINER_TARGETS, minerOrder(), inventory),
                    "Generated Mine could not consume its second physical work face");
            assertExtendedMineStep(helper, assignments, zone.id(), furnace, direction, miner, 17);

            require(helper, level.getBlockState(firstTarget).isAir() && level.getBlockState(secondTarget).isAir(),
                    "Generated Mine restored a consumed work face instead of leaving a real excavation");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 2,
                    "Two progressive Mine cycles did not yield exactly two physical cobblestone");
            ItemStack tool = inventory.snapshot().stream().filter(stack -> stack.is(Items.IRON_PICKAXE))
                    .findFirst().orElse(ItemStack.EMPTY);
            require(helper, !tool.isEmpty() && tool.getDamageValue() == 2,
                    "Progressive Mine extension bypassed normal tool durability");
            helper.succeed();
        } finally {
            assignments.removeAssignment(miner.getUUID());
            assignments.removeZone(zone.id());
            villages.remove(villageId);
            MinerOreSafetySavedData.forServer(server).remove(miner.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(miner.getUUID());
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40, padding = 20)
    public void protectedNextLayerStopsMineExtensionWithoutRestoringTheConsumedFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 20, 8));
        Direction direction = VillageUtilityFeature.mineDirection();
        fillExtensionTerrain(level, furnace);
        require(helper, VillageUtilityFeature.placeMine(level, furnace, BoundingBox.infinite()),
                "Could not place the protected generated-Mine fixture");
        BlockPos target = VillageUtilityFeature.mineLanding(furnace, direction, 2)
                .relative(VillageUtilityFeature.mineOutwardDirection(direction, 2));
        BlockPos protectedFloor = VillageUtilityFeature.mineLanding(furnace, direction, 16).below();
        Villager miner = spawnVillager(helper, new BlockPos(1, 2, 1));
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        GeneratedVillageSavedData villages = GeneratedVillageSavedData.forServer(level.getServer());
        String villageId = "game-test-protected-mine-extension-" + UUID.randomUUID();
        BlockCoordinate minimum = new BlockCoordinate(furnace.getX(), furnace.getY() - 16, furnace.getZ() - 3);
        BlockCoordinate maximum = new BlockCoordinate(furnace.getX() + 6, furnace.getY() + 3, furnace.getZ() + 3);
        WorkZoneRecord zone = assignments.createZone("totem:miner", new WorkZone(UUID.randomUUID(),
                level.dimension().identifier().toString(), minimum, maximum));
        try {
            setProfession(miner, "totem", "miner");
            assignments.putAssignment(new WorkerAssignment(miner.getUUID(), "totem:miner",
                    Optional.of(zone.id()), Optional.empty()));
            villages.discover(new GeneratedVillageState(villageId, level.dimension().identifier().toString(),
                    minimum, maximum, false, Optional.empty(), Optional.empty(), Optional.of(zone.id())));
            miner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), furnace));
            BlockPos landing = VillageUtilityFeature.mineLanding(furnace, direction, 2);
            miner.setPos(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not give the protected-Mine Miner an iron pickaxe");
            PROTECTED_TARGET.set(protectedFloor);

            require(helper, new MinerWorldWorkAction(() -> 9_999).complete(
                            level, miner, target, MINER_TARGETS, minerOrder(), inventory),
                    "Protected next layer incorrectly prevented the current physical face from being mined");
            require(helper, level.getBlockState(target).isAir()
                            && level.getBlockState(protectedFloor).is(Blocks.STONE)
                            && assignments.getZone(zone.id()).orElseThrow().zone().minimum().y() == minimum.y(),
                    "Protected Mine extension changed its blocked layer, restored its source, or expanded its Zone");
            helper.succeed();
        } finally {
            PROTECTED_TARGET.set(null);
            assignments.removeAssignment(miner.getUUID());
            assignments.removeZone(zone.id());
            villages.remove(villageId);
            MinerOreSafetySavedData.forServer(level.getServer()).remove(miner.getUUID());
            VillagerWorkInventorySavedData.forServer(level.getServer()).drain(miner.getUUID());
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerTargetsExactlyTheTwoVanillaOverworldOreBaseFamilies(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not give the Miner an iron pickaxe");
            MinerWorldWorkAction noDiscovery = new MinerWorldWorkAction(() -> 9_999);
            List<BaseDrop> vanillaBases = List.of(
                    new BaseDrop(Blocks.GRANITE, Items.GRANITE),
                    new BaseDrop(Blocks.DIORITE, Items.DIORITE),
                    new BaseDrop(Blocks.ANDESITE, Items.ANDESITE),
                    new BaseDrop(Blocks.DEEPSLATE, Items.COBBLED_DEEPSLATE),
                    new BaseDrop(Blocks.TUFF, Items.TUFF));
            for (BaseDrop base : vanillaBases) {
                helper.getLevel().setBlock(target, base.block().defaultBlockState(), 3);
                int before = count(inventory.snapshot(), base.drop());
                require(helper, noDiscovery.complete(helper.getLevel(), miner, target, MINER_TARGETS,
                                minerOrder(), inventory),
                        "Miner target tag omitted vanilla ore-replaceable base " + base.block());
                require(helper, count(inventory.snapshot(), base.drop()) == before + 1,
                        "Vanilla base " + base.block() + " did not retain its own live base drop");
            }
            helper.getLevel().setBlock(target, Blocks.CALCITE.defaultBlockState(), 3);
            require(helper, !noDiscovery.complete(helper.getLevel(), miner, target, MINER_TARGETS,
                            minerOrder(), inventory),
                    "Miner accepted calcite even though vanilla does not use it as an ore-replaceable base");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.CALCITE),
                    "Rejected non-vanilla substrate was still destroyed");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerDiscoversOneIncidentalOreThroughItsVanillaLootTable(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            BlockState substrate = Blocks.STONE.defaultBlockState();
            helper.getLevel().setBlock(target, substrate, 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            require(helper, inventory.insertExact(tool), "Could not give the Miner an iron pickaxe");
            int roll = findIncidentalRoll(helper.getLevel(), target, substrate, tool, true);
            MinerIncidentalOreRule selected = selectedRule(helper.getLevel(), target, substrate, roll);

            require(helper, new MinerWorldWorkAction(() -> roll).complete(
                            helper.getLevel(), miner, target, MINER_TARGETS, minerOrder(), inventory),
                    "Miner could not commit a deterministic incidental-ore discovery");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 1,
                    "Incidental ore replaced rather than accompanied the base cobblestone drop");
            List<ItemStack> discovered = inventory.snapshot().stream()
                    .filter(stack -> !stack.isEmpty())
                    .filter(stack -> !stack.is(Items.COBBLESTONE) && !stack.is(Items.IRON_PICKAXE))
                    .toList();
            require(helper, discovered.size() == 1 && discovered.getFirst().getCount() > 0,
                    "Selected " + selected.id() + " did not pass through its vanilla loot table exactly once");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerIronDroughtSafetyUsesLiveEligibleRuleOnSixteenthMine(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        var server = helper.getLevel().getServer();
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        MinerOreSafetySavedData safety = MinerOreSafetySavedData.forServer(server);
        try {
            setProfession(miner, "totem", "miner");
            VillagerWorkInventory inventory = inventories.inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not give the drought-test Miner an iron pickaxe");
            MinerWorldWorkAction noRandomOre = new MinerWorldWorkAction(() -> 9_999);

            for (int mine = 1; mine <= MinerOreSafetySavedData.MAX_CONSECUTIVE_NON_IRON_MINES; mine++) {
                helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                int ironBefore = count(inventory.snapshot(), Items.RAW_IRON);
                require(helper, noRandomOre.complete(helper.getLevel(), miner, target, MINER_TARGETS,
                                minerOrder(), inventory),
                        "Drought-test base mine " + mine + " did not commit");
                require(helper, count(inventory.snapshot(), Items.RAW_IRON) == ironBefore,
                        "Iron safety triggered before the configured drought bound on mine " + mine);
                require(helper, safety.consecutiveNonIronMines(miner.getUUID()) == mine,
                        "Committed non-iron mine " + mine + " was not persisted exactly");
            }

            helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            require(helper, noRandomOre.complete(helper.getLevel(), miner, target, MINER_TARGETS,
                            minerOrder(), inventory),
                    "Eligible sixteenth mine did not commit through the live iron rule");
            require(helper, count(inventory.snapshot(), Items.RAW_IRON) > 0,
                    "Eligible sixteenth mine did not yield the live iron-ore loot");
            require(helper, safety.consecutiveNonIronMines(miner.getUUID()) == 0,
                    "Successful safety iron did not reset the persistent drought counter");

            int ironAfterSafety = count(inventory.snapshot(), Items.RAW_IRON);
            helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            require(helper, noRandomOre.complete(helper.getLevel(), miner, target, MINER_TARGETS,
                            minerOrder(), inventory),
                    "Post-reset base mine did not commit");
            require(helper, count(inventory.snapshot(), Items.RAW_IRON) == ironAfterSafety
                            && safety.consecutiveNonIronMines(miner.getUUID()) == 1,
                    "Iron safety did not restart a fresh drought sequence after reset");
            helper.succeed();
        } finally {
            safety.remove(miner.getUUID());
            inventories.drain(miner.getUUID());
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void incapablePickaxeCannotCollectASelectedIncidentalOre(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            BlockState substrate = Blocks.STONE.defaultBlockState();
            helper.getLevel().setBlock(target, substrate, 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            ItemStack tool = new ItemStack(Items.WOODEN_PICKAXE);
            require(helper, inventory.insertExact(tool), "Could not give the Miner a wooden pickaxe");
            int roll = findIncidentalRoll(helper.getLevel(), target, substrate, tool, false);

            require(helper, new MinerWorldWorkAction(() -> roll).complete(
                            helper.getLevel(), miner, target, MINER_TARGETS, minerOrder(), inventory),
                    "An incapable incidental-ore roll should not block the underlying stone work");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 1,
                    "Miner did not retain the normal stone drop after failing the ore harvest tier");
            require(helper, inventory.snapshot().stream().filter(stack -> !stack.isEmpty()).allMatch(
                            stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.WOODEN_PICKAXE)),
                    "An incapable wooden pickaxe collected a selected higher-tier incidental ore");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void incidentalOreCapacityFailureRollsBackTheWholeMiningAction(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            BlockState substrate = Blocks.STONE.defaultBlockState();
            helper.getLevel().setBlock(target, substrate, 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            require(helper, inventory.insertExact(tool), "Could not give the Miner an iron pickaxe");
            for (int slot = 0; slot < VillagerWorkInventorySavedData.SLOT_COUNT - 2; slot++) {
                require(helper, inventory.insertExact(new ItemStack(Items.COBBLESTONE, 64)),
                        "Could not prepare exact pre-bonus inventory capacity at slot " + slot);
            }
            int roll = findIncidentalRoll(helper.getLevel(), target, substrate, tool, true);

            require(helper, !new MinerWorldWorkAction(() -> roll).complete(
                            helper.getLevel(), miner, target, MINER_TARGETS, minerOrder(), inventory),
                    "Miner committed a base drop, bonus drop and worn tool into capacity for only two returns");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.STONE),
                    "Capacity-rejected incidental mining destroyed its source block");
            ItemStack restoredTool = inventory.snapshot().stream().filter(stack -> stack.is(Items.IRON_PICKAXE))
                    .findFirst().orElse(ItemStack.EMPTY);
            require(helper, !restoredTool.isEmpty() && restoredTool.getDamageValue() == 0,
                    "Capacity-rejected incidental mining did not restore the exact undamaged tool");
            require(helper, inventory.snapshot().stream().filter(stack -> !stack.isEmpty()).allMatch(
                            stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.IRON_PICKAXE)),
                    "Capacity-rejected incidental mining leaked an ore item into inventory");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerStoresFourLapisFromOneLapisOreFace(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.LAPIS_ORE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_PICKAXE)),
                    "Could not give the Miner a personal iron pickaxe");

            require(helper, new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_LAPIS_ORES,
                    minerLapisOrder(), inventory), "Miner could not commit a permitted lapis ore face");
            int lapis = count(inventory.snapshot(), Items.LAPIS_LAZULI);
            require(helper, lapis >= 4 && lapis <= 9,
                    "One mined lapis ore face did not use Minecraft's normal four-to-nine lapis drop range");
            require(helper, helper.getLevel().getBlockState(target).isAir(),
                    "Miner work left the committed lapis ore face behind");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerCollectsActualDiamondOreDrops(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.DIAMOND_PICKAXE)),
                    "Could not give the Miner a personal diamond pickaxe");

            require(helper, new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_ORES,
                    minerOreOrder(), inventory), "Miner could not commit a permitted diamond ore target");
            require(helper, count(inventory.snapshot(), Items.DIAMOND) == 1,
                    "Miner did not collect Minecraft's normal diamond-ore drop");
            ItemStack pickaxe = inventory.snapshot().stream().filter(stack -> stack.is(Items.DIAMOND_PICKAXE))
                    .findFirst().orElse(ItemStack.EMPTY);
            require(helper, !pickaxe.isEmpty() && pickaxe.getDamageValue() == 1,
                    "Mining did not return the personal diamond pickaxe with exactly one durability consumed");
            require(helper, helper.getLevel().getBlockState(target).isAir(),
                    "Miner work left the committed diamond ore target behind");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void minerWithoutCapableBackpackPickaxeLeavesOreUntouched(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.WOODEN_PICKAXE)),
                    "Could not give the Miner an incapable personal pickaxe");

            require(helper, !new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_ORES,
                    minerOreOrder(), inventory), "Miner mined diamond ore without a capable personal pickaxe");
            ItemStack pickaxe = inventory.snapshot().stream().filter(stack -> stack.is(Items.WOODEN_PICKAXE))
                    .findFirst().orElse(ItemStack.EMPTY);
            require(helper, !pickaxe.isEmpty() && pickaxe.getDamageValue() == 0,
                    "A failed mining attempt consumed durability from the personal pickaxe");
            require(helper, count(inventory.snapshot(), Items.DIAMOND) == 0,
                    "A failed mining attempt credited diamond to the personal inventory");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.DIAMOND_ORE),
                    "Miner destroyed diamond ore without a capable personal pickaxe");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fullMaterialStoreLeavesMineTargetUntouched(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.STONE_PICKAXE)),
                    "Could not give the Miner a personal stone pickaxe");
            for (int slot = 1; slot < VillagerWorkInventorySavedData.SLOT_COUNT; slot++) {
                require(helper, inventory.insertExact(new ItemStack(Items.COBBLESTONE, 64)),
                        "Could not fill personal inventory slot " + slot);
            }

            require(helper, !new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_TARGETS, minerOrder(),
                    inventory), "Miner accepted a target even though its personal material store was full");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.STONE),
                    "Full material storage destroyed a mine target without preserving its output");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void unloadedMineTargetIsNotLoadedOrWrittenTo(GameTestHelper helper) {
        BlockPos unloadedTarget = helper.absolutePos(new BlockPos(1_024, 2, 1_024));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            require(helper, !helper.getLevel().isLoaded(unloadedTarget),
                    "GameTest did not provide an unloaded target chunk");

            require(helper, !new MinerWorldWorkAction().complete(helper.getLevel(), miner, unloadedTarget, MINER_TARGETS,
                            minerOrder(), inventory),
                    "Miner accepted a target in an unloaded chunk");
            require(helper, !helper.getLevel().isLoaded(unloadedTarget),
                    "Miner work force-loaded an otherwise unloaded target chunk");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 0,
                    "Unloaded mine target credited physical materials");
            helper.succeed();
        } finally {
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void protectionVetoLeavesMineTargetAndPersonalInventoryUntouched(GameTestHelper helper) {
        BlockPos target = helper.absolutePos(new BlockPos(8, 2, 8));
        Villager miner = spawnVillager(helper, new BlockPos(7, 2, 8));
        try {
            setProfession(miner, "totem", "miner");
            helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(miner.getUUID());
            PROTECTED_TARGET.set(target);

            require(helper, !new MinerWorldWorkAction().complete(helper.getLevel(), miner, target, MINER_TARGETS,
                            minerOrder(), inventory),
                    "Miner bypassed the autonomous-work protection veto");
            require(helper, helper.getLevel().getBlockState(target).is(Blocks.STONE),
                    "Protection-vetoed mine work still destroyed the target block");
            require(helper, count(inventory.snapshot(), Items.COBBLESTONE) == 0,
                    "Protection-vetoed mine work still credited personal materials");
            helper.succeed();
        } finally {
            PROTECTED_TARGET.compareAndSet(target, null);
            miner.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void lumberjackStoresWholeTrunkAndReplants(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(10, 2, 10));
        Villager lumberjack = spawnVillager(helper, new BlockPos(8, 2, 10));
        try {
            setProfession(lumberjack, "totem", "lumberjack");
            helper.getLevel().setBlock(base.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int index = 0; index < 4; index++) {
                helper.getLevel().setBlock(base.above(index), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
            helper.getLevel().setBlock(base.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(lumberjack.getUUID());
            require(helper, inventory.insertAllExact(List.of(
                            new ItemStack(Items.IRON_AXE), new ItemStack(Items.OAK_SAPLING))),
                    "Could not give the manual-zone Lumberjack its physical axe and replanting sapling");
            WorkZone zone = new WorkZone(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(),
                    new BlockCoordinate(base.getX() - 2, base.getY() - 1, base.getZ() - 2),
                    new BlockCoordinate(base.getX() + 2, base.getY() + 6, base.getZ() + 2));

            require(helper, new LumberjackWorldWorkAction().complete(helper.getLevel(), lumberjack, zone, base, LUMBERJACK_LOGS,
                    lumberjackOrder(), inventory), "Lumberjack could not commit a mature tree to its personal material store");
            require(helper, count(inventory.snapshot(), Items.OAK_LOG) == 4,
                    "Lumberjack did not store the four native log drops in its personal inventory");
            ItemStack usedAxe = inventory.snapshot().stream().filter(stack -> stack.is(Items.IRON_AXE))
                    .findFirst().orElse(null);
            require(helper, usedAxe != null && usedAxe.getDamageValue() == 1,
                    "Lumberjack did not consume real durability from its personal axe");
            require(helper, !helper.getLevel().getBlockState(base.above(4)).is(Blocks.OAK_LEAVES),
                    "Lumberjack did not harvest the actual leaf-drop source above the felled tree");
            require(helper, helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "Lumberjack did not replant the committed tree base");
            helper.succeed();
        } finally {
            lumberjack.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void manualLumberyardConsumesPhysicalSaplingWhenLiveLeavesDoNotProvideItsReplacement(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(10, 2, 10));
        Villager lumberjack = spawnVillager(helper, new BlockPos(8, 2, 10));
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                .inventory(lumberjack.getUUID());
        try {
            setProfession(lumberjack, "totem", "lumberjack");
            helper.getLevel().setBlock(base.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int index = 0; index < 4; index++) {
                helper.getLevel().setBlock(base.above(index), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
            helper.getLevel().setBlock(base.above(4), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), 3);
            require(helper, inventory.insertExact(new ItemStack(Items.IRON_AXE)),
                    "Could not give the manual-zone Lumberjack its physical axe");
            WorkZone zone = new WorkZone(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(),
                    new BlockCoordinate(base.getX() - 2, base.getY() - 1, base.getZ() - 2),
                    new BlockCoordinate(base.getX() + 2, base.getY() + 6, base.getZ() + 2));
            WorkOrder birchReplant = new WorkOrder("totem:test_manual_birch_replant", "totem:lumberjack",
                    new ItemAmount("minecraft:oak_log", 4), List.of(), Set.of(WorkSource.WORLD),
                    "totem:lumberjack_oak_logs", "", "minecraft:birch_sapling", "", 40, 64);
            LumberjackWorldWorkAction action = new LumberjackWorldWorkAction();

            require(helper, !action.complete(helper.getLevel(), lumberjack, zone, base,
                            LUMBERJACK_LOGS, birchReplant, inventory),
                    "A manual Lumberyard created a free replacement sapling");
            require(helper, helper.getLevel().getBlockState(base).is(Blocks.OAK_LOG)
                            && count(inventory.snapshot(), Items.OAK_LOG) == 0,
                    "Rejected sapling-less harvest changed the tree or credited logs");
            require(helper, inventory.insertExact(new ItemStack(Items.BIRCH_SAPLING)),
                    "Could not give the manual Lumberyard its exact physical replacement sapling");

            require(helper, action.complete(helper.getLevel(), lumberjack, zone, base,
                            LUMBERJACK_LOGS, birchReplant, inventory),
                    "Manual Lumberyard could not consume its physical replacement sapling");
            require(helper, helper.getLevel().getBlockState(base).is(Blocks.BIRCH_SAPLING)
                            && count(inventory.snapshot(), Items.BIRCH_SAPLING) == 0
                            && count(inventory.snapshot(), Items.OAK_LOG) == 4,
                    "Manual Lumberyard did not atomically consume the sapling, replant and store its live logs");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer()).drain(lumberjack.getUUID());
            lumberjack.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void lumberjackAcceptsNaturalFiveLogOakTrunks(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(10, 2, 10));
        Villager lumberjack = spawnVillager(helper, new BlockPos(8, 2, 10));
        try {
            setProfession(lumberjack, "totem", "lumberjack");
            helper.getLevel().setBlock(base.below(), Blocks.DIRT.defaultBlockState(), 3);
            for (int index = 0; index < 5; index++) {
                helper.getLevel().setBlock(base.above(index), Blocks.OAK_LOG.defaultBlockState(), 3);
            }
            helper.getLevel().setBlock(base.above(5), Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, true), 3);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(helper.getLevel().getServer())
                    .inventory(lumberjack.getUUID());
            require(helper, inventory.insertAllExact(List.of(
                            new ItemStack(Items.IRON_AXE), new ItemStack(Items.OAK_SAPLING))),
                    "Could not give the manual-zone Lumberjack its physical axe and replanting sapling");
            WorkZone zone = new WorkZone(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(),
                    new BlockCoordinate(base.getX() - 2, base.getY() - 1, base.getZ() - 2),
                    new BlockCoordinate(base.getX() + 2, base.getY() + 7, base.getZ() + 2));

            require(helper, new LumberjackWorldWorkAction().complete(helper.getLevel(), lumberjack, zone, base,
                    LUMBERJACK_LOGS, lumberjackOrder(), inventory),
                    "Lumberjack rejected a natural five-log oak trunk");
            require(helper, count(inventory.snapshot(), Items.OAK_LOG) == 5,
                    "Lumberjack did not store every native drop from the five-log trunk");
            require(helper, helper.getLevel().getBlockState(base).is(Blocks.OAK_SAPLING),
                    "Lumberjack did not replant after harvesting a five-log trunk");
            helper.succeed();
        } finally {
            lumberjack.discard();
        }
    }

    private static WorkOrder minerOrder() {
        return new WorkOrder("totem:miner_stone", "totem:miner", new ItemAmount("minecraft:cobblestone", 1),
                List.of(), Set.of(WorkSource.WORLD), "totem:miner_targets", 20, 64);
    }

    private static WorkOrder minerLapisOrder() {
        return new WorkOrder("totem:miner_lapis_lazuli", "totem:miner", new ItemAmount("minecraft:lapis_lazuli", 4),
                List.of(), Set.of(WorkSource.WORLD), "totem:miner_lapis_ores", 30, 64);
    }

    private static WorkOrder minerOreOrder() {
        return new WorkOrder("totem:miner_ores", "totem:miner", new ItemAmount("minecraft:coal", 1),
                List.of(), Set.of(WorkSource.WORLD), "totem:miner_ores", 40, 64);
    }

    private static WorkOrder lumberjackOrder() {
        return new WorkOrder("totem:lumberjack_oak_logs", "totem:lumberjack", new ItemAmount("minecraft:oak_log", 4),
                List.of(), Set.of(WorkSource.WORLD), "totem:lumberjack_oak_logs", "", "minecraft:oak_sapling", "", 40, 64);
    }

    private static int findIncidentalRoll(ServerLevel level, BlockPos target, BlockState substrate,
                                          ItemStack tool, boolean harvestable) {
        for (int roll = 0; roll < 10_000; roll++) {
            MinerIncidentalOreRule selected = selectedRuleOrNull(level, target, substrate, roll);
            if (selected == null) {
                continue;
            }
            Block ore = BuiltInRegistries.BLOCK.getValue(Identifier.parse(selected.oreBlock()));
            if (ore != null && tool.isCorrectToolForDrops(ore.defaultBlockState()) == harvestable) {
                return roll;
            }
        }
        throw new IllegalStateException("No " + (harvestable ? "harvestable" : "tool-gated")
                + " incidental ore is configured at Y=" + target.getY());
    }

    private static MinerIncidentalOreRule selectedRule(ServerLevel level, BlockPos target,
                                                        BlockState substrate, int roll) {
        MinerIncidentalOreRule selected = selectedRuleOrNull(level, target, substrate, roll);
        if (selected == null) {
            throw new IllegalStateException("Expected an incidental ore for deterministic roll " + roll);
        }
        return selected;
    }

    private static MinerIncidentalOreRule selectedRuleOrNull(ServerLevel level, BlockPos target,
                                                              BlockState substrate, int roll) {
        return MinerIncidentalOreDefinitions.catalog().select(
                level.dimension().identifier().toString(), target.getY(),
                tagId -> substrate.is(TagKey.create(Registries.BLOCK, Identifier.parse(tagId))),
                tagId -> level.getBiome(target).is(TagKey.create(Registries.BIOME, Identifier.parse(tagId))),
                () -> roll).orElse(null);
    }

    private static void fillExtensionTerrain(ServerLevel level, BlockPos furnace) {
        for (int x = furnace.getX() - 1; x <= furnace.getX() + 7; x++) {
            for (int y = furnace.getY() - 19; y <= furnace.getY() - 13; y++) {
                for (int z = furnace.getZ() - 4; z <= furnace.getZ() + 4; z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void assertExtendedMineStep(GameTestHelper helper, WorkerAssignmentSavedData assignments,
                                               UUID zoneId, BlockPos furnace, Direction direction,
                                               Villager miner, int step) {
        BlockPos landing = VillageUtilityFeature.mineLanding(furnace, direction, step);
        BlockPos floor = landing.below();
        Direction inward = VillageUtilityFeature.mineInwardDirection(direction, step);
        BlockState stair = helper.getLevel().getBlockState(floor);
        require(helper, helper.getLevel().getBlockState(landing).isAir()
                        && helper.getLevel().getBlockState(landing.above()).isAir()
                        && helper.getLevel().getBlockState(landing.above(2)).isAir()
                        && stair.is(Blocks.COBBLESTONE_STAIRS)
                        && stair.getValue(StairBlock.FACING)
                        == VillageUtilityFeature.mineDescentDirection(direction, step).getOpposite()
                        && helper.getLevel().getBlockState(landing.above(3)).is(Blocks.COBBLESTONE)
                        && helper.getLevel().getBlockState(landing.relative(inward).below()).is(Blocks.COBBLESTONE)
                        && helper.getLevel().getBlockState(landing.relative(inward)).is(Blocks.OAK_FENCE)
                        && helper.getLevel().getBlockState(landing.relative(inward.getOpposite())).is(MINER_TARGETS),
                "Generated Mine extension did not reproduce spiral stair " + step);
        require(helper, assignments.getZone(zoneId).orElseThrow().zone().minimum().y() == floor.getY(),
                "Generated Mine extension did not persist its new lower Work Zone boundary at step " + step);
        BlockPos previousLanding = VillageUtilityFeature.mineLanding(furnace, direction, step - 1);
        BlockPos nextFace = landing.relative(inward.getOpposite());
        miner.setPos(previousLanding.getX() + 0.5D, previousLanding.getY(), previousLanding.getZ() + 0.5D);
        miner.setOnGround(true);
        require(helper, WorldWorkNavigation.pathToReach(helper.getLevel(), miner, nextFace).isPresent(),
                "Generated Mine extension did not connect spiral stair " + step + " to its next physical work face");
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setProfession(Villager villager, String namespace, String path) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.fromNamespaceAndPath(namespace, path));
        if (profession == null) {
            throw new IllegalStateException("Missing " + namespace + ":" + path + " profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static int count(List<ItemStack> inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : inventory) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private record BaseDrop(Block block, net.minecraft.world.item.Item drop) {
    }
}
