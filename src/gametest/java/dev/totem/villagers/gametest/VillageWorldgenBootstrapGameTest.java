package dev.totem.villagers.gametest;

import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.runtime.VillageWorldgenBootstrapRuntime;
import dev.totem.villagers.world.FishermanWorkstation;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import dev.totem.villagers.worldgen.MangroveVillageFeature;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import dev.totem.villagers.worldgen.VillageUtilityPoolElement;
import dev.totem.villagers.worldgen.VillageTownCenterUtilityInjector;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Covers the distinct Lumberyard and Miner starter sites established for a generated village. */
public final class VillageWorldgenBootstrapGameTest {
    @GameTest(maxTicks = 20)
    public void mangroveVillageWorldgenResourcesAreRegisteredAndBiomeRestricted(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var structures = registries.lookupOrThrow(Registries.STRUCTURE);
        var structureSets = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        var pools = registries.lookupOrThrow(Registries.TEMPLATE_POOL);
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        Identifier structureId = MangroveVillageFeature.STRUCTURE_ID;
        Identifier structureSetId = Identifier.fromNamespaceAndPath("totem", "mangrove_villages");
        Identifier poolId = Identifier.fromNamespaceAndPath("totem", "village/mangrove/town_centers");

        require(helper, structures.getValue(structureId) instanceof JigsawStructure,
                "Mangrove village datapack structure was not registered as a Jigsaw structure");
        JigsawStructure structure = (JigsawStructure) structures.getValue(structureId);
        require(helper, structure.getStartPool().unwrapKey()
                        .map(ResourceKey::identifier).filter(poolId::equals).isPresent()
                        && pools.containsKey(poolId) && pools.getValue(poolId).size() == 1
                        && pools.getValue(poolId).getTemplates().getFirst().getFirst()
                        instanceof VillageUtilityPoolElement,
                "Mangrove village did not retain its one-element registered start pool");

        var structureSet = structureSets.getValue(structureSetId);
        require(helper, structureSet != null && structureSet.structures().size() == 1
                        && structureSet.structures().getFirst().structure().unwrapKey()
                        .map(ResourceKey::identifier).filter(structureId::equals).isPresent()
                        && structureSet.placement() instanceof RandomSpreadStructurePlacement placement
                        && placement.spacing() == 8 && placement.separation() == 4,
                "Mangrove village structure set is missing or has unexpected spacing");

        TagKey<net.minecraft.world.level.biome.Biome> allowedBiomes = TagKey.create(Registries.BIOME,
                Identifier.fromNamespaceAndPath("totem", "has_structure/mangrove_village"));
        var mangroveSwamp = biomes.get(Identifier.withDefaultNamespace("mangrove_swamp")).orElseThrow();
        var ordinarySwamp = biomes.get(Identifier.withDefaultNamespace("swamp")).orElseThrow();
        require(helper, mangroveSwamp.is(allowedBiomes) && !ordinarySwamp.is(allowedBiomes),
                "Mangrove village biome tag must include Mangrove Swamp and exclude ordinary Swamp");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void mangroveVillageResidenceLayoutIsDeterministicButVariesByWorldSeed(GameTestHelper helper) {
        BlockPos origin = new BlockPos(128, 63, -256);
        long expected = MangroveVillageFeature.layoutSignature(8675309L, origin);
        require(helper, expected == MangroveVillageFeature.layoutSignature(8675309L, origin),
                "Mangrove residence layout changed when recomputed for the same seed and origin");

        Set<Long> signatures = new HashSet<>();
        int widestAppearanceSelection = 0;
        for (long seed = 0; seed < 32; seed++) {
            int count = MangroveVillageFeature.optionalResidenceCount(seed, origin);
            int beds = MangroveVillageFeature.expectedBedCount(seed, origin);
            require(helper, count >= 3 && count <= 6,
                    "Mangrove optional residence count escaped its 3-6 contract: " + count);
            require(helper, beds >= 7 && beds <= 16,
                    "Mangrove total bed count escaped its valid range: " + beds);
            signatures.add(MangroveVillageFeature.layoutSignature(seed, origin));
            widestAppearanceSelection = Math.max(widestAppearanceSelection,
                    MangroveVillageFeature.residenceAppearanceVariantCount(seed, origin));
        }
        require(helper, signatures.size() >= 24,
                "Mangrove world seeds produced too little visible layout variation: " + signatures.size());
        require(helper, widestAppearanceSelection == 3,
                "Mangrove seeds did not exercise all three residence appearance variants");
        require(helper, expected != MangroveVillageFeature.layoutSignature(8675309L, origin.offset(16, 0, 0)),
                "Mangrove layout ignored the structure origin");
        helper.succeed();
    }

    @GameTest(maxTicks = 30, padding = 44, skyAccess = true)
    public void mangroveVillageBuildsACompleteRaisedProductionSettlement(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(44, 80, 44));
        BoundingBox bounds = MangroveVillageFeature.boundingBox(origin);
        require(helper, placeRegisteredMangroveVillage(helper, origin),
                "Could not place the registered Mangrove village start-pool element");
        int deckY = MangroveVillageFeature.deckY(helper.getLevel(), origin);
        BlockPos barrel = origin.offset(-10, deckY + 1 - origin.getY(), -6);
        BlockPos campfire = origin.offset(-10, deckY + 1 - origin.getY(), -2);
        BlockPos woodcutter = origin.offset(-8, deckY + 1 - origin.getY(), 11);
        BlockPos furnace = origin.offset(9, deckY + 1 - origin.getY(), 11);
        long bedHeads = BlockPos.betweenClosedStream(
                        new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                        new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()))
                .filter(position -> helper.getLevel().getBlockState(position).is(BlockTags.BEDS))
                .filter(position -> helper.getLevel().getBlockState(position).hasProperty(BedBlock.PART)
                        && helper.getLevel().getBlockState(position).getValue(BedBlock.PART) == BedPart.HEAD)
                .count();
        long craftingTables = BlockPos.betweenClosedStream(
                        new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                        new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()))
                .filter(position -> helper.getLevel().getBlockState(position).is(Blocks.CRAFTING_TABLE))
                .count();
        Set<BlockPos> reachableDeck = reachableMangroveDeck(helper,
                origin.offset(1, deckY - origin.getY(), 0), bounds, deckY);
        long connectedCraftingTables = BlockPos.betweenClosedStream(
                        new BlockPos(bounds.minX(), deckY + 1, bounds.minZ()),
                        new BlockPos(bounds.maxX(), deckY + 1, bounds.maxZ()))
                .filter(position -> helper.getLevel().getBlockState(position).is(Blocks.CRAFTING_TABLE))
                .filter(position -> java.util.List.of(Direction.NORTH, Direction.EAST,
                                Direction.SOUTH, Direction.WEST).stream()
                        .map(direction -> position.below().relative(direction))
                        .anyMatch(reachableDeck::contains))
                .count();
        require(helper, helper.getLevel().getBlockState(origin.offset(0, deckY + 1 - origin.getY(), 0)).is(Blocks.BELL)
                        && helper.getLevel().getBlockState(barrel).is(Blocks.BARREL)
                        && helper.getLevel().getBlockState(campfire).is(Blocks.CAMPFIRE)
                        && FishermanWorkstation.campfireForJobSite(helper.getLevel(), barrel).isPresent(),
                "Mangrove village is missing its Bell or Barrel-backed Fisherman smokehouse: bell="
                        + helper.getLevel().getBlockState(origin.offset(0, deckY + 1 - origin.getY(), 0))
                        + ", barrel=" + helper.getLevel().getBlockState(barrel)
                        + ", campfire=" + helper.getLevel().getBlockState(campfire)
                        + ", resolved=" + FishermanWorkstation.campfireForJobSite(helper.getLevel(), barrel));
        int optionalResidences = MangroveVillageFeature.optionalResidenceCount(helper.getLevel().getSeed(), origin);
        int expectedBeds = MangroveVillageFeature.expectedBedCount(helper.getLevel().getSeed(), origin);
        require(helper, bedHeads == expectedBeds && craftingTables == optionalResidences
                        && connectedCraftingTables == optionalResidences,
                "Mangrove optional homes were incomplete: homes=" + optionalResidences
                        + ", crafting tables=" + craftingTables + ", beds=" + bedHeads
                        + ", connected crafting tables=" + connectedCraftingTables
                        + ", expected beds=" + expectedBeds
                        + ", connections=" + describeCraftingTableConnections(helper, bounds,
                        deckY, origin, reachableDeck));
        require(helper, helper.getLevel().getBlockState(origin.offset(-18, deckY - origin.getY(), 4)).is(Blocks.WATER)
                        && helper.getLevel().getBlockState(woodcutter).is(TotemVillagerBlocks.WOODCUTTER)
                        && helper.getLevel().getBlockState(VillageUtilityFeature.treeBaseFromWoodcutter(woodcutter))
                        .is(Blocks.MANGROVE_LOG)
                        && helper.getLevel().getBlockState(furnace).is(Blocks.FURNACE),
                "Mangrove village is missing its casting basin, cultivated tree, Woodcutter or Mine");
        for (int step = 0; step < 16; step++) {
            require(helper, helper.getLevel().getBlockState(VillageUtilityFeature.mineLanding(furnace, step).below())
                            .is(Blocks.COBBLESTONE_STAIRS),
                    "Mangrove Mine did not keep a walkable spiral stair at step " + step);
        }
        require(helper, helper.getLevel().getBlockState(origin.offset(0, deckY - origin.getY(), 8))
                        .is(Blocks.STRIPPED_MANGROVE_LOG),
                "Raised boardwalk is missing its visible Mangrove stilt");
        require(helper, helper.getLevel().getBlockState(origin.offset(0, deckY + 1 - origin.getY(), -16)).isAir()
                        && helper.getLevel().getBlockState(
                        origin.offset(0, deckY + 1 - origin.getY(), 17)).isAir()
                        && helper.getLevel().getBlockState(
                        origin.offset(-3, deckY + 1 - origin.getY(), 11)).isAir()
                        && helper.getLevel().getBlockState(
                        origin.offset(3, deckY + 1 - origin.getY(), 11)).isAir()
                        && helper.getLevel().getBlockState(
                        origin.offset(-21, deckY + 1 - origin.getY(), 3)).isAir()
                        && helper.getLevel().getBlockState(
                        origin.offset(-21, deckY + 1 - origin.getY(), 5)).isAir(),
                "Core longhouses or fishing basin did not preserve their boardwalk clearances");
        BlockState westRoof = helper.getLevel().getBlockState(
                origin.offset(-4, deckY + 4 - origin.getY(), -12));
        BlockState eastRoof = helper.getLevel().getBlockState(
                origin.offset(4, deckY + 4 - origin.getY(), -12));
        BlockState northPavilionRoof = helper.getLevel().getBlockState(
                origin.offset(0, deckY + 5 - origin.getY(), -3));
        require(helper, westRoof.is(Blocks.BAMBOO_MOSAIC_STAIRS)
                        && westRoof.getValue(StairBlock.FACING) == Direction.EAST
                        && eastRoof.is(Blocks.BAMBOO_MOSAIC_STAIRS)
                        && eastRoof.getValue(StairBlock.FACING) == Direction.WEST
                        && northPavilionRoof.is(Blocks.BAMBOO_MOSAIC_STAIRS)
                        && northPavilionRoof.getValue(StairBlock.FACING) == Direction.SOUTH,
                "Mangrove roofs do not rise inward toward their ridge or pavilion centre");
        helper.succeed();
    }

    @GameTest(maxTicks = 80, padding = 44, skyAccess = true)
    public void mangroveVillageFoundsFishermanMinerLumberjackAndToolsmithOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        BlockPos origin = helper.absolutePos(new BlockPos(44, 80, 44));
        BoundingBox bounds = MangroveVillageFeature.boundingBox(origin);
        int deckY = MangroveVillageFeature.deckY(level, origin);
        String villageId = level.dimension().identifier() + "|totem:mangrove_village|game-test-" + UUID.randomUUID();
        java.util.List<Villager> founded = java.util.List.of();
        try {
            require(helper, MangroveVillageFeature.place(level, origin, BoundingBox.infinite()),
                    "Could not place the Mangrove founding fixture");
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, level, villageId,
                    new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                    new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            GeneratedVillageState village = village(server, villageId);
            founded = new java.util.ArrayList<>(level.getEntities(
                            EntityTypeTest.forClass(Villager.class), Villager::isAlive).stream()
                    .filter(villager -> villager.getX() >= bounds.minX() && villager.getX() <= bounds.maxX() + 1
                            && villager.getY() >= bounds.minY() && villager.getY() <= bounds.maxY() + 1
                            && villager.getZ() >= bounds.minZ() && villager.getZ() <= bounds.maxZ() + 1)
                    .toList());
            java.util.Map<String, Long> professions = founded.stream().collect(java.util.stream.Collectors.groupingBy(
                    VillageWorldgenBootstrapGameTest::professionId, java.util.stream.Collectors.counting()));
            require(helper, village.foundingPopulationSpawned() && village.capitalGranted()
                            && village.endowedResidents().orElseThrow().size() == 4 && founded.size() == 4,
                    "Mangrove founding population was not persisted and endowed exactly once: spawned="
                            + village.foundingPopulationSpawned() + ", capital=" + village.capitalGranted()
                            + ", endowed=" + village.endowedResidents().orElseThrow().size()
                            + ", entities=" + founded.size() + ", lumber=" + village.lumberjackZoneId().isPresent()
                            + ", mine=" + village.minerZoneId().isPresent());
            require(helper, professions.getOrDefault("minecraft:fisherman", 0L) == 1L
                            && professions.getOrDefault("totem:miner", 0L) == 1L
                            && professions.getOrDefault("totem:lumberjack", 0L) == 1L
                            && professions.getOrDefault("minecraft:toolsmith", 0L) == 1L,
                    "Mangrove village did not form the Fisherman/Miner/Lumberjack/Toolsmith core: " + professions);
            BlockPos barrel = origin.offset(-10, deckY + 1 - origin.getY(), -6);
            Villager fisherman = founded.stream().filter(villager -> "minecraft:fisherman".equals(professionId(villager)))
                    .findFirst().orElseThrow();
            require(helper, fisherman.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .map(GlobalPos::pos).filter(barrel::equals).isPresent(),
                    "Founding Fisherman did not claim the vanilla Barrel POI");
            require(helper, founded.stream().allMatch(villager -> villager.getVillagerData().type().unwrapKey()
                            .map(key -> "minecraft:swamp".equals(key.identifier().toString())).orElse(false)),
                    "Mangrove founding villagers did not receive the Swamp appearance");
            for (Villager villager : founded) {
                var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
                String profession = professionId(villager);
                if ("minecraft:fisherman".equals(profession)) {
                    require(helper, inventory.snapshot().stream().anyMatch(stack -> stack.is(Items.FISHING_ROD)),
                            "Founding Fisherman received no fishing rod");
                } else if ("totem:miner".equals(profession)) {
                    require(helper, inventory.snapshot().stream().anyMatch(stack -> stack.is(Items.IRON_PICKAXE)),
                            "Founding Miner received no pickaxe");
                } else if ("totem:lumberjack".equals(profession)) {
                    require(helper, inventory.snapshot().stream().anyMatch(stack -> stack.is(Items.IRON_AXE)),
                            "Founding Lumberjack received no axe");
                }
            }

            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, level, villageId,
                    new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                    new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
            long repeatedPopulation = level.getEntities(EntityTypeTest.forClass(Villager.class), Villager::isAlive).stream()
                    .filter(villager -> villager.getX() >= bounds.minX() && villager.getX() <= bounds.maxX() + 1
                            && villager.getZ() >= bounds.minZ() && villager.getZ() <= bounds.maxZ() + 1)
                    .count();
            require(helper, repeatedPopulation == 4,
                    "Repeated Mangrove bootstrap cloned its founding population");
            helper.succeed();
        } finally {
            GeneratedVillageSavedData.forServer(server).snapshot().stream()
                    .filter(state -> state.id().equals(villageId))
                    .flatMap(state -> java.util.stream.Stream.concat(
                            state.lumberjackZoneId().stream(), state.minerZoneId().stream()))
                    .forEach(assignments::removeZone);
            founded.forEach(villager -> {
                assignments.removeAssignment(villager.getUUID());
                villager.discard();
            });
            GeneratedVillageSavedData.forServer(server).remove(villageId);
        }
    }

    @GameTest(maxTicks = 20)
    public void everyVanillaTownCenterHasFixedLumberyardAndMinePoolConnectors(GameTestHelper helper) {
        for (String templateId : java.util.List.of(
                "minecraft:village/plains/town_centers/plains_fountain_01",
                "minecraft:village/desert/town_centers/desert_meeting_point_1",
                "minecraft:village/savanna/town_centers/savanna_meeting_point_1",
                "minecraft:village/snowy/town_centers/snowy_meeting_point_1",
                "minecraft:village/taiga/town_centers/taiga_meeting_point_1",
                "minecraft:village/plains/zombie/town_centers/plains_fountain_01")) {
            var template = helper.getLevel().getServer().getStructureManager()
                    .get(Identifier.parse(templateId))
                    .orElseThrow(() -> helper.assertionException("Missing vanilla town-center template " + templateId));
            long lumberyardConnectors = template.getJigsaws(BlockPos.ZERO, Rotation.NONE).stream()
                    .filter(jigsaw -> VillageTownCenterUtilityInjector.LUMBERYARD_ANCHOR_NAME.equals(jigsaw.name())
                            && VillageTownCenterUtilityInjector.LUMBERYARD_POOL_ID.equals(jigsaw.pool().identifier()))
                    .count();
            long mineConnectors = template.getJigsaws(BlockPos.ZERO, Rotation.NONE).stream()
                    .filter(jigsaw -> VillageTownCenterUtilityInjector.MINE_ANCHOR_NAME.equals(jigsaw.name())
                            && VillageTownCenterUtilityInjector.MINE_POOL_ID.equals(jigsaw.pool().identifier()))
                    .count();
            require(helper, lumberyardConnectors == 1 && mineConnectors == 1,
                    "Town-center template " + templateId + " did not receive exactly one fixed Lumberyard and Mine connector");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 30)
    public void fixedUtilityPoolsPlaceSeparateLumberyardAndMine(GameTestHelper helper) {
        // Place this well above the test world's bottom edge: the complete
        // utility shaft descends sixteen levels below its jigsaw connector.
        BlockPos lumberyardConnectorPosition = new BlockPos(8, 100, 8);
        BlockPos mineConnectorPosition = new BlockPos(40, 100, 8);
        helper.setBlock(lumberyardConnectorPosition, Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION,
                FrontAndTop.fromFrontAndTop(Direction.UP, Direction.SOUTH)));
        helper.setBlock(mineConnectorPosition, Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION,
                FrontAndTop.fromFrontAndTop(Direction.UP, Direction.SOUTH)));
        JigsawBlockEntity lumberyardConnector = helper.getBlockEntity(lumberyardConnectorPosition, JigsawBlockEntity.class);
        JigsawBlockEntity mineConnector = helper.getBlockEntity(mineConnectorPosition, JigsawBlockEntity.class);
        configureTownCenterConnector(lumberyardConnector, VillageTownCenterUtilityInjector.LUMBERYARD_ANCHOR_NAME,
                VillageTownCenterUtilityInjector.LUMBERYARD_POOL_ID);
        configureTownCenterConnector(mineConnector, VillageTownCenterUtilityInjector.MINE_ANCHOR_NAME,
                VillageTownCenterUtilityInjector.MINE_POOL_ID);
        lumberyardConnector.generate(helper.getLevel(), 1, false);
        mineConnector.generate(helper.getLevel(), 1, false);
        helper.runAfterDelay(2, () -> {
            BlockPos lumberyardOrigin = lumberyardConnector.getBlockPos();
            BlockPos mineOrigin = mineConnector.getBlockPos();
            java.util.List<BlockPos> woodcutters = new java.util.ArrayList<>();
            java.util.List<BlockPos> furnaces = new java.util.ArrayList<>();
            java.util.List<BlockPos> smithingTables = new java.util.ArrayList<>();
            for (int x = lumberyardOrigin.getX() - 8; x <= mineOrigin.getX() + 16; x++) {
                for (int y = lumberyardOrigin.getY() - 2; y <= lumberyardOrigin.getY() + 8; y++) {
                    for (int z = lumberyardOrigin.getZ() - 8; z <= lumberyardOrigin.getZ() + 16; z++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (helper.getLevel().getBlockState(candidate).is(TotemVillagerBlocks.WOODCUTTER)) {
                            woodcutters.add(candidate);
                        }
                        if (helper.getLevel().getBlockState(candidate).is(Blocks.FURNACE)) {
                            furnaces.add(candidate);
                        }
                        if (helper.getLevel().getBlockState(candidate).is(Blocks.SMITHING_TABLE)) {
                            smithingTables.add(candidate);
                        }
                    }
                }
            }
            require(helper, woodcutters.size() == 1 && furnaces.size() == 1 && smithingTables.size() == 1,
                    "Fixed village utility pools placed " + woodcutters.size() + " Woodcutters and " + furnaces.size()
                            + " Furnaces and " + smithingTables.size()
                            + " Smithing Tables near the two independent connectors instead of exactly one of each");
            require(helper, smithingTables.getFirst().equals(
                            VillageUtilityFeature.smithingTableFromWoodcutter(woodcutters.getFirst())),
                    "Fixed Lumberyard Smithing Table drifted away from its safe utility position");
            assertRenewableFibreTrellis(helper, woodcutters.getFirst());
            require(helper, woodcutters.getFirst().distSqr(furnaces.getFirst()) >= 144,
                    "Fixed Lumberyard and Mine pools overlapped instead of generating separate buildings");
            BlockPos furnace = furnaces.getFirst();
            for (int step = 0; step < 16; step++) {
                BlockPos landing = VillageUtilityFeature.mineLanding(furnace, step);
                require(helper, helper.getLevel().getBlockState(landing.below()).is(Blocks.COBBLESTONE_STAIRS)
                                && helper.getLevel().getBlockState(VillageUtilityFeature.mineCenter(furnace).below(step)).isAir(),
                        "Fixed village utility pool did not cut the 5x5 mineshaft through stair " + step);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void mineCasingOnlyFillsUndergroundVoidOrFluid(GameTestHelper helper) {
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 20, 8));
        BlockPos center = VillageUtilityFeature.mineCenter(furnace);
        BlockPos underwaterVoid = center.north(4).below(4);
        BlockPos existingTerrain = center.south(4).below(4);
        BlockPos surfaceWater = center.north(4);
        helper.getLevel().setBlock(underwaterVoid, Blocks.WATER.defaultBlockState(), 3);
        helper.getLevel().setBlock(existingTerrain, Blocks.DIRT.defaultBlockState(), 3);
        helper.getLevel().setBlock(surfaceWater, Blocks.WATER.defaultBlockState(), 3);
        require(helper, VillageUtilityFeature.placeMine(helper.getLevel(), furnace, BoundingBox.infinite()),
                "Could not place a direct Mine casing fixture");
        require(helper, helper.getLevel().getBlockState(underwaterVoid).is(Blocks.STONE)
                        && helper.getLevel().getBlockState(existingTerrain).is(Blocks.DIRT)
                        && helper.getLevel().getBlockState(surfaceWater).is(Blocks.WATER),
                "Mine casing did not fill only underground air or fluid without replacing terrain");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void mineSurfaceBuildsAFramedShelterWithoutObstructingTheShaft(GameTestHelper helper) {
        BlockPos furnace = helper.absolutePos(new BlockPos(8, 20, 8));
        BlockPos center = VillageUtilityFeature.mineCenter(furnace);
        Direction forward = VillageUtilityFeature.mineDirection();
        Direction sideways = forward.getClockWise();
        require(helper, VillageUtilityFeature.placeMine(helper.getLevel(), furnace, BoundingBox.infinite()),
                "Could not place a direct Mine shelter fixture");

        for (int forwardOffset : new int[]{-3, 3}) {
            for (int sideOffset : new int[]{-3, 3}) {
                BlockPos post = center.relative(forward, forwardOffset).relative(sideways, sideOffset);
                for (int y = 0; y <= 3; y++) {
                    require(helper, helper.getLevel().getBlockState(post.above(y)).is(Blocks.STRIPPED_OAK_LOG),
                            "Mine shelter is missing a stripped-oak corner post");
                }
                require(helper, helper.getLevel().getBlockState(post.below()).is(Blocks.MOSSY_COBBLESTONE),
                        "Mine shelter corner is missing its mossy foundation accent");
            }
        }

        BlockPos gate = center.relative(forward, -3).relative(sideways, -1);
        BlockPos guardedRim = center.relative(forward, 3);
        BlockPos roofEave = center.relative(forward, -4).above(4);
        BlockPos raisedRoof = center.relative(forward, -3).relative(sideways, -3).above(5);
        require(helper, helper.getLevel().getBlockState(gate).is(Blocks.OAK_FENCE_GATE)
                        && helper.getLevel().getBlockState(gate).getValue(BlockStateProperties.OPEN)
                        && helper.getLevel().getBlockState(guardedRim).is(Blocks.OAK_FENCE)
                        && helper.getLevel().getBlockState(roofEave).is(Blocks.SPRUCE_STAIRS)
                        && helper.getLevel().getBlockState(raisedRoof).is(Blocks.SPRUCE_SLAB)
                        && helper.getLevel().getBlockState(center.above(4)).is(Blocks.IRON_CHAIN)
                        && helper.getLevel().getBlockState(center.above(3)).is(Blocks.LANTERN),
                "Mine shelter is missing its gate, safety rim, eaves or hanging light");
        require(helper, helper.getLevel().getBlockState(center).isAir()
                        && helper.getLevel().getBlockState(VillageUtilityFeature.mineLanding(furnace, 0).below())
                        .is(Blocks.COBBLESTONE_STAIRS),
                "Mine shelter decoration obstructed the hollow shaft or first spiral stair");

        BlockPos outside = gate.relative(forward.getOpposite(), 2);
        helper.getLevel().setBlock(outside.below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(outside.relative(forward).below(), Blocks.COBBLESTONE.defaultBlockState(), 3);
        // The navigation contract only needs a Villager mob. A Nitwit remains
        // fully mobile but cannot participate in concurrent workforce
        // allocation for other shared-level GameTests.
        Villager miner = spawnAtAbsolute(helper, outside, "minecraft:nitwit");
        BlockPos firstStoneFace = VillageUtilityFeature.mineLanding(furnace, 1).relative(forward.getOpposite());
        helper.getLevel().setBlock(gate, helper.getLevel().getBlockState(gate)
                .setValue(BlockStateProperties.OPEN, false), 3);
        BlockCoordinate furnaceCoordinate = new BlockCoordinate(furnace.getX(), furnace.getY(), furnace.getZ());
        WorkZone furnaceZone = new WorkZone(UUID.randomUUID(), helper.getLevel().dimension().identifier().toString(),
                furnaceCoordinate, furnaceCoordinate);
        require(helper, MinerFurnaceWorkstation.ensureAssigned(helper.getLevel(), miner, furnaceZone)
                        .filter(furnace::equals).isPresent()
                        && helper.getLevel().getBlockState(gate).getValue(BlockStateProperties.OPEN),
                "Miner assignment did not reopen the entrance of an existing generated Mine");
        helper.runAfterDelay(2, () -> {
            try {
                require(helper, WorldWorkNavigation.pathToReach(helper.getLevel(), miner, firstStoneFace).isPresent(),
                        "Open Mine entrance did not give an outside Miner a path to the first exposed work face"
                                + "; worker=" + miner.blockPosition() + ", gate="
                                + helper.getLevel().getBlockState(gate) + ", target=" + firstStoneFace);
                helper.succeed();
            } finally {
                miner.discard();
            }
        });
    }

    @GameTest(maxTicks = 40, padding = 44)
    public void generatedVillageBootstrapCreatesLumberyardAndMinerStarter(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        String villageId = "game-test-bootstrap-" + UUID.randomUUID();
        BlockPos furnace = new BlockPos(3, 21, 4);
        helper.setBlock(new BlockPos(12, 19, 12), Blocks.DIRT);
        helper.setBlock(new BlockPos(14, 19, 12), Blocks.COBBLESTONE);
        prepareDescendingMineShaft(helper, furnace, Direction.EAST);
        Villager resident = spawnUnemployed(helper, new BlockPos(3, 23, 4));
        Villager lateResident = null;
        try {
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, helper.getLevel(), villageId,
                    helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(28, 30, 28)));

            GeneratedVillageState village = village(server, villageId);
            UUID lumberjackZoneId = village.lumberjackZoneId().orElseThrow(() -> helper.assertionException(
                    "Generated village did not create its Lumberyard Work Zone"));
            WorkZoneRecord lumberjackZone = assignments.getZone(lumberjackZoneId).orElseThrow(() -> helper.assertionException(
                    "Generated Lumberyard Work Zone was not persisted"));
            BlockPos treeBase = new BlockPos(lumberjackZone.zone().minimum().x(), lumberjackZone.zone().minimum().y(),
                    lumberjackZone.zone().minimum().z());
            BlockPos treeTop = treeBase.above(3);
            require(helper, helper.getLevel().getBlockState(treeBase).is(Blocks.OAK_LOG)
                            && helper.getLevel().getBlockState(treeTop).is(Blocks.OAK_LOG)
                            && hasOakLeafNear(helper, treeTop)
                            && lumberjackZone.zone().maximum().y() >= treeBase.getY() + 15,
                    "Generated Lumberyard did not place a harvestable mature oak tree");
            require(helper, emeralds(server, resident) == 8,
                    "Generated village resident did not receive finite founding capital");
            require(helper, VillagerNutrition.foodLevel(resident) >= VillagerNutrition.EAT_UNTIL,
                    "Generated village resident started too hungry to perform founding work");
            BlockPos woodcutter = village.woodcutterPosition()
                    .map(position -> new BlockPos(position.x(), position.y(), position.z()))
                    .orElseThrow(() -> helper.assertionException("Generated Lumberjack village did not establish a Woodcutter"));
            require(helper, helper.getLevel().getBlockState(woodcutter).is(TotemVillagerBlocks.WOODCUTTER),
                    "Generated village recorded a Woodcutter without placing the physical station");
            assertRenewableFibreTrellis(helper, woodcutter);

            UUID minerZoneId = village.minerZoneId().orElseThrow(() -> helper.assertionException(
                    "Generated village did not create its Miner starter Work Zone"));
            WorkZoneRecord minerZone = assignments.getZone(minerZoneId).orElseThrow(() -> helper.assertionException(
                    "Generated Miner starter Work Zone was not persisted"));
            BlockPos absoluteFurnace = helper.absolutePos(furnace);
            BlockPos mineCenter = mineCenter(absoluteFurnace, Direction.EAST);
            BlockPos bottomLanding = mineLanding(mineCenter, Direction.EAST, 15).below(15);
            BlockState furnaceState = helper.getLevel().getBlockState(absoluteFurnace);
            require(helper, furnaceState.is(Blocks.FURNACE),
                    "Generated Miner starter furnace block not placed");
            require(helper, minerZone.zone().minimum().y() <= bottomLanding.getY(),
                    "Generated Miner shaft floor did not descend to expected depth");
            BlockState bottomAir = helper.getLevel().getBlockState(bottomLanding);
            require(helper, bottomAir.isAir(),
                    "Generated Miner shaft landing at bottom was not cleared");
            BlockState bottomStair = helper.getLevel().getBlockState(bottomLanding.below());
            require(helper, bottomStair.is(Blocks.COBBLESTONE_STAIRS),
                    "Generated Miner shaft landing had no cobblestone stair at minimum level");
            require(helper, bottomStair.getValue(StairBlock.FACING) == mineDescentFacing(Direction.EAST, 15),
                    "Generated Miner shaft landing stair orientation was incorrect at minimum level");
            int stoneCount = countBlocks(helper, minerZone, Blocks.STONE);
            // The inner side is now a guarded hollow shaft; raw stone is
            // retained on the outer wall beside each stair for the Miner.
            require(helper, stoneCount >= 12,
                    "Generated Miner starter did not retain enough stone faces (found " + stoneCount + ")");
            require(helper, "totem:miner".equals(professionId(resident))
                            && resident.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                            .map(net.minecraft.core.GlobalPos::pos)
                            .filter(absoluteFurnace::equals).isPresent(),
                    "Generated village did not immediately assign and bind a founding Miner to its Furnace");
            for (int step = 0; step < MINE_SPIRAL.length; step++) {
                BlockPos landing = mineLanding(mineCenter, Direction.EAST, step).below(step);
                BlockPos rail = landing.relative(mineInwardDirection(Direction.EAST, MINE_SPIRAL[step]));
                BlockPos core = mineCenter.below(step);
                require(helper, helper.getLevel().getBlockState(landing.above(2)).isAir()
                                && helper.getLevel().getBlockState(landing.above(3)).is(Blocks.COBBLESTONE),
                        "Generated mineshaft landing " + step + " lacks descending head clearance or a solid roof");
                require(helper, helper.getLevel().getBlockState(landing.below()).getValue(StairBlock.FACING)
                                == mineDescentFacing(Direction.EAST, step),
                        "Generated mineshaft stair on landing " + step + " is misoriented");
                require(helper, helper.getLevel().getBlockState(core).isAir(),
                        "Generated mineshaft centre was not hollow at depth " + step);
                if (mineHasGuardRail(step)) {
                    require(helper, helper.getLevel().getBlockState(rail).is(Blocks.OAK_FENCE)
                                    && helper.getLevel().getBlockState(rail.below()).is(Blocks.COBBLESTONE),
                            "Generated mineshaft stair lacked its inner oak safety rail at step " + step);
                }
            }
            lateResident = spawnUnemployed(helper, new BlockPos(5, 23, 4));
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, helper.getLevel(), villageId,
                    helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(28, 30, 28)));
            GeneratedVillageState repeated = village(server, villageId);
            require(helper, repeated.lumberjackZoneId().filter(lumberjackZoneId::equals).isPresent()
                            && repeated.minerZoneId().filter(minerZoneId::equals).isPresent()
                            && emeralds(server, resident) == 8
                            && emeralds(server, lateResident) == 8
                            && VillagerNutrition.foodLevel(lateResident) >= VillagerNutrition.EAT_UNTIL
                            && repeated.endowedResidents().orElseThrow().containsAll(
                                    java.util.List.of(resident.getUUID(), lateResident.getUUID())),
                    "Generated village bootstrap repeated capital or replaced a resource starter zone");
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, helper.getLevel(), villageId,
                    helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(28, 30, 28)));
            require(helper, emeralds(server, resident) == 8 && emeralds(server, lateResident) == 8,
                    "Per-resident founding endowment was paid more than once");
            helper.succeed();
        } finally {
            GeneratedVillageSavedData.forServer(server).snapshot().stream()
                    .filter(state -> state.id().equals(villageId))
                    .flatMap(state -> java.util.stream.Stream.concat(state.lumberjackZoneId().stream(), state.minerZoneId().stream()))
                    .forEach(assignments::removeZone);
            GeneratedVillageSavedData.forServer(server).remove(villageId);
            resident.discard();
            if (lateResident != null) {
                lateResident.discard();
            }
        }
    }

    private static void assertRenewableFibreTrellis(GameTestHelper helper, BlockPos woodcutter) {
        BlockPos support = woodcutter.north(2);
        BlockPos lowerVine = support.east().above();
        BlockPos motherVine = lowerVine.above();
        require(helper, helper.getLevel().getBlockState(support).is(Blocks.STRIPPED_OAK_LOG)
                        && helper.getLevel().getBlockState(support.above()).is(Blocks.STRIPPED_OAK_LOG)
                        && helper.getLevel().getBlockState(support.above(2)).is(Blocks.STRIPPED_OAK_LOG),
                "Generated Lumberyard is missing its three-block fibre trellis support");
        require(helper, helper.getLevel().getBlockState(lowerVine).is(Blocks.VINE)
                        && helper.getLevel().getBlockState(lowerVine).getValue(VineBlock.WEST)
                        && helper.getLevel().getBlockState(motherVine).is(Blocks.VINE)
                        && helper.getLevel().getBlockState(motherVine).getValue(VineBlock.WEST),
                "Generated Lumberyard is missing its renewable lower vine and protected mother vine");
    }

    @GameTest(maxTicks = 40)
    public void generatedVillageDoesNotReplaceItsOnlyFarmerWithAFoundingMiner(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        String villageId = "game-test-preserve-farmer-" + UUID.randomUUID();
        BlockPos furnace = new BlockPos(3, 21, 4);
        helper.setBlock(new BlockPos(12, 19, 12), Blocks.DIRT);
        helper.setBlock(new BlockPos(14, 19, 12), Blocks.COBBLESTONE);
        prepareDescendingMineShaft(helper, furnace, Direction.EAST);
        Villager resident = spawnWithProfession(helper, new BlockPos(3, 23, 4), "minecraft:farmer");
        try {
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, helper.getLevel(), villageId,
                    helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(28, 30, 28)));

            GeneratedVillageState village = village(server, villageId);
            UUID minerZoneId = village.minerZoneId().orElseThrow(() -> helper.assertionException(
                    "Generated village did not create its Miner starter Work Zone"));
            require(helper, "minecraft:farmer".equals(professionId(resident)),
                    "Generated-village bootstrap replaced the only Farmer and broke food priority");
            require(helper, assignments.getAssignment(resident.getUUID()).isEmpty(),
                    "Existing Farmer received a specialist assignment");
            require(helper, assignments.assignmentSnapshot().values().stream()
                            .noneMatch(assignment -> assignment.workZoneId().filter(minerZoneId::equals).isPresent()),
                    "Miner zone was staffed by overwriting an existing career");
            helper.succeed();
        } finally {
            GeneratedVillageSavedData.forServer(server).snapshot().stream()
                    .filter(state -> state.id().equals(villageId))
                    .flatMap(state -> java.util.stream.Stream.concat(state.lumberjackZoneId().stream(), state.minerZoneId().stream()))
                    .forEach(assignments::removeZone);
            assignments.removeAssignment(resident.getUUID());
            GeneratedVillageSavedData.forServer(server).remove(villageId);
            resident.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void generatedVillageWithoutRoomForAResourceSiteDoesNotCreateStarterZones(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String villageId = "game-test-no-tree-" + UUID.randomUUID();
        Villager resident = spawnUnemployed(helper, new BlockPos(3, 3, 3));
        try {
            VillageWorldgenBootstrapRuntime.bootstrapForGameTest(server, helper.getLevel(), villageId,
                    helper.absolutePos(new BlockPos(3, 3, 3)), helper.absolutePos(new BlockPos(3, 3, 3)));
            GeneratedVillageState village = village(server, villageId);
            require(helper, village.capitalGranted() && village.lumberjackZoneId().isEmpty()
                            && village.woodcutterPosition().isEmpty() && village.minerZoneId().isEmpty(),
                    "Village without safe room received a generated resource starter");
            require(helper, emeralds(server, resident) == 8,
                    "Village without room did not retain its one-time founding capital");
            helper.succeed();
        } finally {
            WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
            GeneratedVillageSavedData.forServer(server).snapshot().stream()
                    .filter(state -> state.id().equals(villageId))
                    .flatMap(state -> java.util.stream.Stream.concat(state.lumberjackZoneId().stream(), state.minerZoneId().stream()))
                    .forEach(assignments::removeZone);
            GeneratedVillageSavedData.forServer(server).remove(villageId);
            resident.discard();
        }
    }

    private static GeneratedVillageState village(net.minecraft.server.MinecraftServer server, String villageId) {
        return GeneratedVillageSavedData.forServer(server).snapshot().stream()
                .filter(state -> state.id().equals(villageId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing generated village " + villageId));
    }

    private static final int[][] MINE_SPIRAL = {
            {-2, 0}, {-2, 1}, {-2, 2}, {-1, 2},
            {0, 2}, {1, 2}, {2, 2}, {2, 1},
            {2, 0}, {2, -1}, {2, -2}, {1, -2},
            {0, -2}, {-1, -2}, {-2, -2}, {-2, -1}
    };

    private static BlockPos mineCenter(BlockPos furnace, Direction direction) {
        return furnace.relative(direction, 3);
    }

    private static BlockPos mineLanding(BlockPos mineCenter, Direction direction, int step) {
        int forward = MINE_SPIRAL[step][0];
        int sideways = MINE_SPIRAL[step][1];
        Direction sidewaysDirection = direction.getClockWise();
        return mineCenter.relative(direction, forward).relative(sidewaysDirection, sideways);
    }

    private static Direction mineInwardDirection(Direction forward, int[] coordinate) {
        Direction sideways = forward.getClockWise();
        if (coordinate[0] == -2) {
            return forward;
        }
        if (coordinate[0] == 2) {
            return forward.getOpposite();
        }
        if (coordinate[1] == -2) {
            return sideways;
        }
        if (coordinate[1] == 2) {
            return sideways.getOpposite();
        }
        throw new IllegalArgumentException("Mine spiral coordinate must be on the 5x5 perimeter");
    }

    private static Direction mineDescentDirection(Direction forward, int step) {
        int source = step == MINE_SPIRAL.length - 1 ? step - 1 : step;
        int[] current = MINE_SPIRAL[source];
        int[] next = MINE_SPIRAL[source + 1];
        int forwardDelta = next[0] - current[0];
        if (forwardDelta != 0) {
            return forwardDelta > 0 ? forward : forward.getOpposite();
        }
        int sidewaysDelta = next[1] - current[1];
        return sidewaysDelta > 0 ? forward.getClockWise() : forward.getClockWise().getOpposite();
    }

    private static Direction mineDescentFacing(Direction forward, int step) {
        return mineDescentDirection(forward, step).getOpposite();
    }

    private static boolean mineHasGuardRail(int step) {
        return step % 4 != 2;
    }

    private static void prepareDescendingMineShaft(GameTestHelper helper, BlockPos furnace, Direction direction) {
        // The stock GameTest template is bounded by barrier blocks.  Build a
        // compact solid-stone test vein around the planned 5 × 5 descent so
        // that this is the only vacant Furnace site the bootstrap can choose.
        // The deepest guard-rail base is deliberately one layer above that
        // boundary.
        for (int x = furnace.getX(); x <= furnace.getX() + 6; x++) {
            for (int y = furnace.getY() - 16; y <= furnace.getY() + 3; y++) {
                for (int z = furnace.getZ() - 3; z <= furnace.getZ() + 3; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos mineCenter = mineCenter(furnace, direction);
        helper.setBlock(furnace.below(), Blocks.STONE);
        BlockPos entrance = mineLanding(mineCenter, direction, 0);
        helper.setBlock(entrance, Blocks.AIR);
        helper.setBlock(entrance.above(), Blocks.AIR);
        helper.setBlock(entrance.below(), Blocks.STONE);
        for (int step = 0; step < MINE_SPIRAL.length; step++) {
            BlockPos landing = mineLanding(mineCenter, direction, step).below(step);
            BlockPos face = landing.relative(mineInwardDirection(direction, MINE_SPIRAL[step]));
            BlockPos outerFace = landing.relative(mineInwardDirection(direction, MINE_SPIRAL[step]).getOpposite());
            helper.setBlock(landing, Blocks.STONE);
            helper.setBlock(landing.above(), Blocks.STONE);
            helper.setBlock(landing.below(), Blocks.STONE);
            helper.setBlock(landing.above(2), Blocks.STONE);
            helper.setBlock(landing.above(3), Blocks.STONE);
            helper.setBlock(face, Blocks.STONE);
            helper.setBlock(face.below(), Blocks.STONE);
            helper.setBlock(outerFace, Blocks.STONE);
        }
        // The first landing shares a wall block with the Furnace position;
        // leave that one block vacant so this prepared site can be selected.
        helper.setBlock(furnace, Blocks.AIR);
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnUnemployed(GameTestHelper helper, BlockPos relativePosition) {
        return spawnWithProfession(helper, relativePosition, "minecraft:unemployed");
    }

    private static void configureTownCenterConnector(JigsawBlockEntity connector, Identifier anchorName, Identifier poolId) {
        connector.setName(anchorName);
        connector.setTarget(Identifier.withDefaultNamespace("bottom"));
        connector.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, poolId));
        connector.setFinalState("minecraft:air");
        connector.setJoint(JigsawBlockEntity.JointType.ROLLABLE);
    }

    private static boolean placeRegisteredMangroveVillage(GameTestHelper helper, BlockPos origin) {
        var level = helper.getLevel();
        var pool = level.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).getValue(
                Identifier.fromNamespaceAndPath("totem", "village/mangrove/town_centers"));
        if (pool == null || pool.getTemplates().size() != 1
                || !(pool.getTemplates().getFirst().getFirst() instanceof VillageUtilityPoolElement element)) {
            return false;
        }
        return element.place(level.getServer().getStructureManager(), level, level.structureManager(),
                level.getChunkSource().getGenerator(), origin, origin, Rotation.NONE, BoundingBox.infinite(),
                level.getRandom(), LiquidSettings.APPLY_WATERLOGGING, false);
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnWithProfession(GameTestHelper helper, BlockPos relativePosition, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawn((EntityType<Villager>) type, relativePosition);
        VillagerProfession unemployed = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.parse(professionId));
        if (unemployed == null) {
            throw new IllegalStateException("Missing " + professionId + " profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(unemployed)));
        return villager;
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnAtAbsolute(GameTestHelper helper, BlockPos position, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = (Villager) type.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        require(helper, villager != null, "Could not create a Villager at an absolute test position");
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        require(helper, profession != null, "Missing " + professionId + " profession");
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        villager.setPos(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        require(helper, helper.getLevel().addFreshEntity(villager),
                "Could not add the absolute-position Villager to the test level");
        return villager;
    }

    private static boolean hasOakLeafNear(GameTestHelper helper, BlockPos top) {
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (helper.getLevel().getBlockState(top.offset(x, y, z)).is(Blocks.OAK_LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static int countBlocks(GameTestHelper helper, WorkZoneRecord zone, net.minecraft.world.level.block.Block block) {
        int count = 0;
        for (int x = zone.zone().minimum().x(); x <= zone.zone().maximum().x(); x++) {
            for (int y = zone.zone().minimum().y(); y <= zone.zone().maximum().y(); y++) {
                for (int z = zone.zone().minimum().z(); z <= zone.zone().maximum().z(); z++) {
                    if (helper.getLevel().getBlockState(new BlockPos(x, y, z)).is(block)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static Set<BlockPos> reachableMangroveDeck(GameTestHelper helper, BlockPos start,
                                                        BoundingBox bounds, int deckY) {
        Set<BlockPos> reachable = new HashSet<>();
        java.util.ArrayDeque<BlockPos> frontier = new java.util.ArrayDeque<>();
        frontier.add(start);
        while (!frontier.isEmpty()) {
            BlockPos position = frontier.removeFirst();
            if (position.getY() != deckY || !bounds.isInside(position)
                    || !isOpenMangroveDeck(helper, position) || !reachable.add(position)) {
                continue;
            }
            frontier.add(position.north());
            frontier.add(position.east());
            frontier.add(position.south());
            frontier.add(position.west());
        }
        return reachable;
    }

    private static boolean isOpenMangroveDeck(GameTestHelper helper, BlockPos position) {
        BlockState floor = helper.getLevel().getBlockState(position);
        return (floor.is(Blocks.MANGROVE_PLANKS)
                || floor.is(Blocks.BAMBOO_MOSAIC)
                || floor.is(Blocks.STRIPPED_MANGROVE_LOG))
                && helper.getLevel().getBlockState(position.above()).isAir();
    }

    private static String describeCraftingTableConnections(GameTestHelper helper, BoundingBox bounds,
                                                            int deckY, BlockPos origin,
                                                            Set<BlockPos> reachableDeck) {
        return BlockPos.betweenClosedStream(
                        new BlockPos(bounds.minX(), deckY + 1, bounds.minZ()),
                        new BlockPos(bounds.maxX(), deckY + 1, bounds.maxZ()))
                .filter(position -> helper.getLevel().getBlockState(position).is(Blocks.CRAFTING_TABLE))
                .map(position -> {
                    StringBuilder details = new StringBuilder("table@").append(position.subtract(origin));
                    for (Direction direction : java.util.List.of(
                            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                        BlockPos floor = position.below().relative(direction);
                        details.append(' ').append(direction.getName()).append('=')
                                .append(BuiltInRegistries.BLOCK.getKey(
                                        helper.getLevel().getBlockState(floor).getBlock()))
                                .append('/')
                                .append(BuiltInRegistries.BLOCK.getKey(
                                        helper.getLevel().getBlockState(floor.above()).getBlock()))
                                .append(reachableDeck.contains(floor) ? "*" : "-");
                    }
                    return details.toString();
                })
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }

    private static int emeralds(net.minecraft.server.MinecraftServer server, Villager villager) {
        return VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID())
                .countMatchingItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD));
    }
}
