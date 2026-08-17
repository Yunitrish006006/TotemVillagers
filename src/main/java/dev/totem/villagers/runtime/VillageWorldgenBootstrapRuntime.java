package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageState;
import dev.totem.villagers.worldgen.MangroveVillageFeature;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import dev.totem.villagers.world.FishermanWorkstation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * One-time, bounded startup for supported world-generated villages. A generated
 * structure is recognised at chunk generation time, never from player-built
 * beds or bells. When its residents load, they receive finite founding capital
 * and safe resource starter sites for the Lumberjack and Miner.
 */
public final class VillageWorldgenBootstrapRuntime {
    private static final long BOOTSTRAP_INTERVAL_TICKS = 20L;
    private static final int RESIDENT_MARGIN = 16;
    private static final int TREE_FALLBACK_MARGIN = 0;
    private static final int TREE_SEARCH_RADIUS = 24;
    private static final int TREE_SCAN_Y_BELOW = 4;
    private static final int TREE_SCAN_Y_ABOVE = 8;
    private static final int TREE_SCAN_MAX_CHECKS = 8_192;
    private static final int MIN_TREE_TRUNK_HEIGHT = 2;
    private static final int MAX_TREE_TRUNK_HEIGHT = 16;
    private static final int WOODCUTTER_SEARCH_RADIUS = 16;
    private static final int WOODCUTTER_SCAN_Y_BELOW = 3;
    private static final int WOODCUTTER_SCAN_Y_ABOVE = 3;
    private static final int WOODCUTTER_SCAN_MAX_CHECKS = 8_192;
    private static final int RESOURCE_SITE_SEARCH_RADIUS = 24;
    private static final int RESOURCE_SITE_SCAN_Y_BELOW = 3;
    private static final int RESOURCE_SITE_SCAN_Y_ABOVE = 4;
    private static final int RESOURCE_SITE_SCAN_MAX_CHECKS = 8_192;
    /** The jigsaw utility child can sit just outside the vanilla start-piece bounds. */
    private static final int GENERATED_FACILITY_MARGIN = 48;
    private static final boolean DEBUG_MINING_STARTER = false;
    private static final int MINE_SPIRAL_SIZE = 5;
    private static final int MINE_SPIRAL_RADIUS = MINE_SPIRAL_SIZE / 2;
    /** One complete perimeter lap: 5 × 5 outer ring, descending one block per tread. */
    private static final List<SpiralCoordinate> MINE_SPIRAL = List.of(
            spiral(-2, 0), spiral(-2, 1), spiral(-2, 2), spiral(-1, 2),
            spiral(0, 2), spiral(1, 2), spiral(2, 2), spiral(2, 1),
            spiral(2, 0), spiral(2, -1), spiral(2, -2), spiral(1, -2),
            spiral(0, -2), spiral(-1, -2), spiral(-2, -2), spiral(-2, -1)
    );
    private static final int MINESHAFT_DEPTH = MINE_SPIRAL.size();
    private static final Set<String> GENERATED_VILLAGE_STRUCTURE_IDS = Set.of(
            "minecraft:village_plains",
            "minecraft:village_desert",
            "minecraft:village_savanna",
            "minecraft:village_snowy",
            "minecraft:village_taiga",
            MangroveVillageFeature.STRUCTURE_ID.toString()
    );
    private static final String MANGROVE_VILLAGE_ID_SEGMENT = "|" + MangroveVillageFeature.STRUCTURE_ID + "|";
    private static final int MANGROVE_FOUNDING_POPULATION = 4;
    private static final TagKey<Block> LUMBERJACK_LEAVES = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "lumberjack_tree_leaves"));

    private VillageWorldgenBootstrapRuntime() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(VillageWorldgenBootstrapRuntime::rememberNewlyGeneratedVillage);
        ServerTickEvents.END_SERVER_TICK.register(VillageWorldgenBootstrapRuntime::tick);
    }

    /** Deterministic test hook; production discovery is restricted to registered generated structures. */
    public static void bootstrapForGameTest(MinecraftServer server, ServerLevel level, String villageId,
                                            BlockPos minimum, BlockPos maximum) {
        GeneratedVillageSavedData villages = GeneratedVillageSavedData.forServer(server);
        GeneratedVillageState state = villages.discover(new GeneratedVillageState(villageId,
                level.dimension().identifier().toString(), coordinate(minimum), coordinate(maximum), false, Optional.empty()));
        bootstrap(level, state, villages, true);
        VillagerStarterSupplyRuntime.tickForGameTest(server);
    }

    private static void rememberNewlyGeneratedVillage(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (!generated || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode()
                != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        GeneratedVillageSavedData villages = GeneratedVillageSavedData.forServer(level.getServer());
        level.structureManager().startsForStructure(chunk.getPos(), structure -> isGeneratedVillageStructure(structures, structure))
                .stream()
                .filter(StructureStart::isValid)
                .forEach(start -> villages.discover(fromStructure(level, structures, start)));
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED
                || server.overworld().getGameTime() % BOOTSTRAP_INTERVAL_TICKS != 0L) {
            return;
        }
        GeneratedVillageSavedData villages = GeneratedVillageSavedData.forServer(server);
        for (GeneratedVillageState state : villages.snapshot()) {
            levelFor(server, state.dimensionId()).ifPresent(level -> bootstrap(level, state, villages, false));
        }
    }

    /**
     * Binds the work zones from the utility facility placed by the village
     * jigsaw.  The emergency builder remains exclusively available to the
     * isolated GameTest hook so it can test the legacy recovery layout without
     * turning live village generation back into a post-generation retrofit.
     */
    private static void bootstrap(ServerLevel level, GeneratedVillageState state, GeneratedVillageSavedData villages,
                                  boolean allowEmergencyBuilder) {
        List<Villager> residents = residents(level, state);
        boolean pendingMangrovePopulation = isMangroveVillage(state) && !state.foundingPopulationSpawned();
        if (residents.isEmpty() && !pendingMangrovePopulation) {
            return;
        }
        GeneratedVillageState current = state;
        boolean allowResourceFallback = allowEmergencyBuilder || generatedFacilityAreaLoaded(level, state);
        // The deterministic GameTest hook owns an exact fixture box. Searching
        // the live-world compatibility margin there can see facilities from a
        // concurrently running test and falsely bind them to this village.
        int generatedFacilitySearchMargin = allowEmergencyBuilder ? 0 : generatedFacilityMargin(state);
        int initialResourceZoneCount = resourceZoneCount(current);
        List<Villager> endowmentResidents = residents;
        if (current.lumberjackZoneId().isEmpty()) {
            GeneratedVillageState village = current;
            Optional<FoundingLumberyard> lumberyard = findGeneratedLumberyard(
                    level, village, generatedFacilitySearchMargin);
            if (lumberyard.isEmpty() && allowResourceFallback) {
                lumberyard = establishFoundingLumberyard(level, village, residents);
            }
            lumberyard.ifPresentOrElse(site -> {
                UUID zoneId = WorkerAssignmentSavedData.forServer(level.getServer())
                        .createZone(TotemVillagerProfessions.LUMBERJACK_ID.toString(), site.zone()).id();
                villages.markLumberjackZone(village.id(), zoneId);
                villages.markWoodcutter(village.id(), coordinate(site.woodcutter()));
            }, () -> {
                if (allowResourceFallback) {
                    findLumberjackZone(level, village, residents).ifPresent(zone -> {
                        UUID zoneId = WorkerAssignmentSavedData.forServer(level.getServer())
                                .createZone(TotemVillagerProfessions.LUMBERJACK_ID.toString(), zone).id();
                        villages.markLumberjackZone(village.id(), zoneId);
                    });
                }
            });
            current = currentVillage(villages, current);
        }
        if (current.lumberjackZoneId().isPresent() && current.woodcutterPosition().isEmpty()) {
            GeneratedVillageState village = current;
            findWoodcutterSite(level, village, residents).ifPresent(position -> {
                if (level.getBlockState(position).is(TotemVillagerBlocks.WOODCUTTER)
                        || level.setBlock(position, TotemVillagerBlocks.WOODCUTTER.defaultBlockState(), 3)) {
                    villages.markWoodcutter(village.id(), coordinate(position));
                }
            });
            current = currentVillage(villages, current);
        }
        if (current.minerZoneId().isEmpty()) {
            GeneratedVillageState village = current;
            Optional<WorkZone> mine = findGeneratedMine(level, village, generatedFacilitySearchMargin);
            if (mine.isEmpty() && allowResourceFallback) {
                mine = establishFoundingMine(level, village, residents);
            }
            mine.ifPresent(zone -> {
                WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
                WorkZoneRecord record = assignments.createZone(TotemVillagerProfessions.MINER_ID.toString(), zone);
                villages.markMinerZone(village.id(), record.id());
            });
        }
        current = currentVillage(villages, current);
        boolean populationFounded = false;
        if (isMangroveVillage(current) && !current.foundingPopulationSpawned()
                && current.lumberjackZoneId().isPresent() && current.minerZoneId().isPresent()) {
            Optional<List<Villager>> founded = foundMangrovePopulation(level, current);
            if (founded.isPresent()) {
                villages.markFoundingPopulationSpawned(current.id());
                current = currentVillage(villages, current);
                endowmentResidents = residents(level, current);
                populationFounded = true;
            }
        }
        current = endowResidents(endowmentResidents, current, villages);
        if (resourceZoneCount(current) > initialResourceZoneCount || populationFounded) {
            VillagerResourceWorkforceRuntime.allocateFoundingWorkforce(level.getServer());
        }
    }

    private static GeneratedVillageState endowResidents(List<Villager> residents, GeneratedVillageState state,
                                                         GeneratedVillageSavedData villages) {
        GeneratedVillageState current = state;
        if (current.endowedResidents().isPresent()) {
            for (Villager resident : residents) {
                if (current.hasEndowed(resident.getUUID())) {
                    continue;
                }
                VillagerNutrition.grantFoundingNutrition(resident);
                villages.markResidentEndowed(current.id(), resident.getUUID());
                current = currentVillage(villages, current);
            }
        } else if (!current.capitalGranted() && !residents.isEmpty()) {
            // Compatibility for an incomplete pre-v4 record. Completed legacy
            // records remain closed so an upgrade can never duplicate capital.
            residents.forEach(VillagerNutrition::grantFoundingNutrition);
            villages.markCapitalGranted(current.id());
            current = current.withCapitalGranted();
        }
        return current;
    }

    private static int resourceZoneCount(GeneratedVillageState village) {
        return (village.lumberjackZoneId().isPresent() ? 1 : 0)
                + (village.minerZoneId().isPresent() ? 1 : 0);
    }

    /** Waits until the complete generated-facility search area is loaded before building a recovery site. */
    private static boolean generatedFacilityAreaLoaded(ServerLevel level, GeneratedVillageState village) {
        int margin = generatedFacilityMargin(village);
        int minimumX = village.minimum().x() - margin;
        int maximumX = village.maximum().x() + margin;
        int minimumZ = village.minimum().z() - margin;
        int maximumZ = village.maximum().z() + margin;
        int y = Math.max(level.getMinY(), Math.min(level.getMaxY() - 1, village.minimum().y()));
        return level.isLoaded(new BlockPos(minimumX, y, minimumZ))
                && level.isLoaded(new BlockPos(minimumX, y, maximumZ))
                && level.isLoaded(new BlockPos(maximumX, y, minimumZ))
                && level.isLoaded(new BlockPos(maximumX, y, maximumZ));
    }

    private static GeneratedVillageState currentVillage(GeneratedVillageSavedData villages, GeneratedVillageState fallback) {
        return villages.snapshot().stream().filter(record -> record.id().equals(fallback.id())).findFirst().orElse(fallback);
    }

    private static GeneratedVillageState fromStructure(ServerLevel level, Registry<Structure> structures, StructureStart start) {
        BoundingBox bounds = start.getBoundingBox();
        String structureId = structures.getKey(start.getStructure()).toString();
        String dimensionId = level.dimension().identifier().toString();
        String villageId = dimensionId + "|" + structureId + "|" + bounds.minX() + "," + bounds.minY() + "," + bounds.minZ()
                + "|" + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ();
        return new GeneratedVillageState(villageId, dimensionId,
                new BlockCoordinate(bounds.minX(), bounds.minY(), bounds.minZ()),
                new BlockCoordinate(bounds.maxX(), bounds.maxY(), bounds.maxZ()), false, Optional.empty());
    }

    private static boolean isGeneratedVillageStructure(Registry<Structure> structures, Structure structure) {
        return GENERATED_VILLAGE_STRUCTURE_IDS.contains(structures.getKey(structure).toString());
    }

    private static Optional<ServerLevel> levelFor(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimensionId)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    private static boolean isResident(Villager villager, GeneratedVillageState village) {
        BlockPos position = villager.blockPosition();
        return position.getX() >= village.minimum().x() - RESIDENT_MARGIN
                && position.getX() <= village.maximum().x() + RESIDENT_MARGIN
                && position.getY() >= village.minimum().y() - RESIDENT_MARGIN
                && position.getY() <= village.maximum().y() + RESIDENT_MARGIN
                && position.getZ() >= village.minimum().z() - RESIDENT_MARGIN
                && position.getZ() <= village.maximum().z() + RESIDENT_MARGIN;
    }

    private static List<Villager> residents(ServerLevel level, GeneratedVillageState village) {
        return LoadedVillagerCache.loaded(level).stream()
                .filter(villager -> !villager.isBaby() && isResident(villager, village))
                .sorted(Comparator.comparing(villager -> villager.getUUID().toString()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean isMangroveVillage(GeneratedVillageState village) {
        return village.id().contains(MANGROVE_VILLAGE_ID_SEGMENT);
    }

    /**
     * Creates the four-person fishing economy only after both renewable
     * resource zones exist. The persisted flag is written by the caller only
     * after all four entities enter the world, so a crash can neither clone a
     * completed population nor permanently record a partial one.
     */
    private static Optional<List<Villager>> foundMangrovePopulation(ServerLevel level,
                                                                    GeneratedVillageState village) {
        Optional<MangrovePopulationSites> sites = mangrovePopulationSites(level, village);
        if (sites.isEmpty()) {
            return Optional.empty();
        }
        List<BlockPos> spawnPositions = safeMangroveSpawnPositions(level, sites.orElseThrow().bell());
        if (spawnPositions.size() < MANGROVE_FOUNDING_POPULATION) {
            return Optional.empty();
        }
        VillagerProfession fisherman = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.withDefaultNamespace("fisherman"));
        if (fisherman == null) {
            return Optional.empty();
        }
        List<Villager> spawned = new ArrayList<>();
        for (int index = 0; index < MANGROVE_FOUNDING_POPULATION; index++) {
            Optional<Villager> villager = spawnMangroveVillager(level, spawnPositions.get(index),
                    sites.orElseThrow().beds().get(index), index == 0 ? fisherman : null,
                    index == 0 ? sites.orElseThrow().barrel() : null);
            if (villager.isEmpty()) {
                spawned.forEach(Entity::discard);
                return Optional.empty();
            }
            spawned.add(villager.orElseThrow());
        }
        return Optional.of(List.copyOf(spawned));
    }

    private static Optional<MangrovePopulationSites> mangrovePopulationSites(ServerLevel level,
                                                                              GeneratedVillageState village) {
        Optional<BlockPos> barrel = findVillageBlock(level, village,
                state -> state.is(Blocks.BARREL));
        Optional<BlockPos> bell = findVillageBlock(level, village,
                state -> state.is(Blocks.BELL));
        if (barrel.isEmpty() || bell.isEmpty()
                || FishermanWorkstation.campfireForJobSite(level, barrel.orElseThrow()).isEmpty()) {
            return Optional.empty();
        }
        List<BlockPos> beds = findVillageBlocks(level, village,
                state -> state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                        && state.getValue(BedBlock.PART) == BedPart.HEAD, MANGROVE_FOUNDING_POPULATION);
        return beds.size() == MANGROVE_FOUNDING_POPULATION
                ? Optional.of(new MangrovePopulationSites(barrel.orElseThrow(), bell.orElseThrow(), beds))
                : Optional.empty();
    }

    private static List<BlockPos> safeMangroveSpawnPositions(ServerLevel level, BlockPos bell) {
        return List.of(
                        bell.offset(-2, 0, 0), bell.offset(2, 0, 0),
                        bell.offset(0, 0, -2), bell.offset(0, 0, 2),
                        bell.offset(-2, 0, -2), bell.offset(2, 0, 2))
                .stream()
                .filter(position -> level.isLoaded(position)
                        && level.getBlockState(position).isAir()
                        && level.getBlockState(position.above()).isAir()
                        && level.getBlockState(position.below()).isFaceSturdy(level, position.below(), Direction.UP))
                .limit(MANGROVE_FOUNDING_POPULATION)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Optional<Villager> spawnMangroveVillager(ServerLevel level, BlockPos position, BlockPos bed,
                                                             VillagerProfession profession, BlockPos jobSite) {
        EntityType<?> rawType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("villager"));
        if (rawType == null) {
            return Optional.empty();
        }
        Villager villager = ((EntityType<Villager>) rawType).create(level, EntitySpawnReason.STRUCTURE);
        if (villager == null) {
            return Optional.empty();
        }
        villager.setPos(position.getX() + .5D, position.getY(), position.getZ() + .5D);
        villager.finalizeSpawn(level, level.getCurrentDifficultyAt(position), EntitySpawnReason.STRUCTURE, null);
        var data = villager.getVillagerData().withType(level.registryAccess(), VillagerType.SWAMP);
        if (profession != null) {
            // Establish the founding career before ENTITY_LOAD listeners see
            // this villager. Otherwise the resource-workforce bootstrap can
            // legitimately recruit the still-unemployed Fisherman first.
            data = data.withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession));
        }
        villager.setVillagerData(data);
        villager.setVillagerDataFinalized(true);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
        if (!level.addFreshEntity(villager)) {
            villager.discard();
            return Optional.empty();
        }
        if (jobSite != null && profession != null) {
            Optional<BlockPos> claimed = level.getPoiManager().take(profession.heldJobSite(),
                    (poi, candidate) -> candidate.equals(jobSite), jobSite, 1);
            if (claimed.filter(jobSite::equals).isEmpty()) {
                villager.discard();
                return Optional.empty();
            }
            // The POI can only be reserved transactionally after the entity is
            // accepted into the world. Rebuild the profession brain now, then
            // restore the memories that refreshBrain intentionally replaces.
            villager.setVillagerData(villager.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
            villager.refreshBrain(level);
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
            villager.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(level.dimension(), jobSite));
        }
        return Optional.of(villager);
    }

    private static Optional<BlockPos> findVillageBlock(ServerLevel level, GeneratedVillageState village,
                                                        Predicate<BlockState> predicate) {
        List<BlockPos> blocks = findVillageBlocks(level, village, predicate, 1);
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.getFirst());
    }

    private static List<BlockPos> findVillageBlocks(ServerLevel level, GeneratedVillageState village,
                                                     Predicate<BlockState> predicate, int limit) {
        List<BlockPos> result = new ArrayList<>(limit);
        int minimumY = Math.max(level.getMinY(), village.minimum().y());
        int maximumY = Math.min(level.getMaxY() - 1, village.maximum().y() + 4);
        for (int y = minimumY; y <= maximumY && result.size() < limit; y++) {
            for (int x = village.minimum().x(); x <= village.maximum().x() && result.size() < limit; x++) {
                for (int z = village.minimum().z(); z <= village.maximum().z() && result.size() < limit; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (level.isLoaded(candidate) && predicate.test(level.getBlockState(candidate))) {
                        result.add(candidate);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Prefer a visible, finite lumberyard over claiming arbitrary surrounding
     * terrain. The placed tree still has to satisfy the same live world order
     * that later authorises the Lumberjack's harvest.
     */
    private static Optional<FoundingLumberyard> establishFoundingLumberyard(
            ServerLevel level,
            GeneratedVillageState village,
            List<Villager> residents
    ) {
        for (WorkOrder order : lumberjackWorldOrders()) {
            if (!supportsGeneratedOakTree(level, order)) {
                continue;
            }
            int height = order.output().count();
            Optional<BlockPos> base = findResourceSite(level, village, residents,
                    candidate -> canPlaceFoundingLumberyard(level, village, candidate, height));
            if (base.isEmpty()) {
                continue;
            }
            BlockPos treeBase = base.orElseThrow();
            BlockPos woodcutter = treeBase.east(2);
            BlockPos smithingTable = woodcutter.south(2);
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            for (int index = 0; index < height; index++) {
                blocks.put(treeBase.above(index), Blocks.OAK_LOG.defaultBlockState());
            }
            BlockPos canopy = treeBase.above(height);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    blocks.put(canopy.offset(x, 0, z), Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
            blocks.put(canopy.above(), Blocks.OAK_LEAVES.defaultBlockState());
            blocks.put(woodcutter, TotemVillagerBlocks.WOODCUTTER.defaultBlockState());
            if (hasSturdySupport(level, smithingTable.below())
                    && isVacant(level, village, smithingTable)
                    && isVacant(level, village, smithingTable.above())) {
                blocks.put(smithingTable, Blocks.SMITHING_TABLE.defaultBlockState());
            }
            BlockPos fibreTrellis = woodcutter.north(2);
            if (!hasSturdySupport(level, fibreTrellis.below())) {
                blocks.put(fibreTrellis.below(), Blocks.COBBLESTONE.defaultBlockState());
            }
            for (int y = 0; y <= 2; y++) {
                blocks.put(fibreTrellis.above(y), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
            }
            BlockState attachedVine = Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true);
            blocks.put(fibreTrellis.east().above(), attachedVine);
            blocks.put(fibreTrellis.east().above(2), attachedVine);
            if (placeGeneratedBlocks(level, village, blocks)) {
                return Optional.of(new FoundingLumberyard(trunkZone(level, village, treeBase), woodcutter));
            }
        }
        return Optional.empty();
    }

    /** Finds the fixed Oak and Woodcutter emitted by {@link VillageUtilityFeature}. */
    private static Optional<FoundingLumberyard> findGeneratedLumberyard(ServerLevel level, GeneratedVillageState village,
                                                                        int searchMargin) {
        Optional<BlockPos> woodcutter = findGeneratedFacilityBlock(
                level, village, TotemVillagerBlocks.WOODCUTTER, searchMargin);
        if (woodcutter.isEmpty()) {
            return Optional.empty();
        }
        BlockPos treeBase = VillageUtilityFeature.treeBaseFromWoodcutter(woodcutter.orElseThrow());
        for (WorkOrder order : lumberjackWorldOrders()) {
            TagKey<Block> logs = blockTag(order.worldTargetTag());
            if (logs != null && isMatureTreeBase(level, treeBase, logs, order)) {
                return Optional.of(new FoundingLumberyard(trunkZone(level, village, treeBase), woodcutter.orElseThrow()));
            }
        }
        return Optional.empty();
    }

    /** Creates a walkable, bounded 5 × 5 spiral mineshaft and its Furnace worksite. */
    private static Optional<WorkZone> establishFoundingMine(
            ServerLevel level,
            GeneratedVillageState village,
            List<Villager> residents
    ) {
        if (!supportsGeneratedStoneMine(level)) {
            return Optional.empty();
        }
        for (Direction direction : List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH)) {
            Optional<BlockPos> furnace = findResourceSite(level, village, residents,
                    candidate -> foundingMine(level, village, candidate, direction).isPresent());
            if (furnace.isEmpty()) {
                continue;
            }
            FoundingMine mine = foundingMine(level, village, furnace.orElseThrow(), direction).orElseThrow();
            if (placeFoundingMine(level, mine)) {
                return Optional.of(mine.zone());
            }
        }
        return Optional.empty();
    }

    /** Finds the fixed guarded 5 x 5 spiral emitted by {@link VillageUtilityFeature}. */
    private static Optional<WorkZone> findGeneratedMine(ServerLevel level, GeneratedVillageState village,
                                                         int searchMargin) {
        Optional<BlockPos> furnace = findGeneratedFacilityBlock(level, village, Blocks.FURNACE, searchMargin);
        if (furnace.isEmpty() || !matchesGeneratedMine(level, furnace.orElseThrow())) {
            return Optional.empty();
        }
        BlockPos furnacePosition = furnace.orElseThrow();
        List<BlockPos> bounds = new ArrayList<>();
        bounds.add(furnacePosition);
        BlockPos center = VillageUtilityFeature.mineCenter(furnacePosition);
        for (int depth = -2; depth < MINESHAFT_DEPTH; depth++) {
            bounds.add(center.below(depth));
        }
        for (int step = 0; step < MINESHAFT_DEPTH; step++) {
            BlockPos landing = VillageUtilityFeature.mineLanding(furnacePosition, step);
            bounds.add(landing);
            bounds.add(landing.above(3));
            bounds.add(landing.below());
            bounds.add(landing.relative(outwardDirection(VillageUtilityFeature.mineDirection(), MINE_SPIRAL.get(step))));
            if (VillageUtilityFeature.hasGuardRail(step)) {
                bounds.add(landing.relative(inwardDirection(VillageUtilityFeature.mineDirection(), MINE_SPIRAL.get(step))));
            }
        }
        UUID owner = UUID.nameUUIDFromBytes(("totem-villagers:pool-mine:" + village.id()).getBytes(StandardCharsets.UTF_8));
        return Optional.of(zone(level, owner, bounds));
    }

    private static boolean matchesGeneratedMine(ServerLevel level, BlockPos furnace) {
        if (!level.getBlockState(furnace).is(Blocks.FURNACE)
                || !isGeneratedMineMasonry(level.getBlockState(furnace.below()))) {
            return false;
        }
        BlockPos center = VillageUtilityFeature.mineCenter(furnace);
        for (int depth = -2; depth < MINESHAFT_DEPTH; depth++) {
            if (!level.getBlockState(center.below(depth)).isAir()) {
                return false;
            }
        }
        for (int step = 0; step < MINESHAFT_DEPTH; step++) {
            BlockPos landing = VillageUtilityFeature.mineLanding(furnace, step);
            if (!level.getBlockState(landing.below()).is(Blocks.COBBLESTONE_STAIRS)
                    || !level.getBlockState(landing.above(2)).isAir()
                    || !isGeneratedMineMasonry(level.getBlockState(landing.above(3)))) {
                return false;
            }
            BlockPos rail = landing.relative(inwardDirection(VillageUtilityFeature.mineDirection(), MINE_SPIRAL.get(step)));
            if (VillageUtilityFeature.hasGuardRail(step)
                    && (!isGeneratedMineRail(level.getBlockState(rail))
                    || !isGeneratedMineMasonry(level.getBlockState(rail.below())))) {
                return false;
            }
        }
        return true;
    }

    /** Both village palettes retain the same furnace, hollow shaft and stair contract. */
    private static boolean isGeneratedMineMasonry(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.MUD_BRICKS);
    }

    private static boolean isGeneratedMineRail(BlockState state) {
        return state.is(Blocks.OAK_FENCE) || state.is(Blocks.BAMBOO_FENCE);
    }

    private static Optional<BlockPos> findGeneratedFacilityBlock(ServerLevel level, GeneratedVillageState village,
                                                                  Block target, int margin) {
        int minimumX = village.minimum().x() - margin;
        int maximumX = village.maximum().x() + margin;
        int minimumY = Math.max(level.getMinY(), village.minimum().y() - 3);
        int maximumY = Math.min(level.getMaxY() - 1, village.maximum().y() + 8);
        int minimumZ = village.minimum().z() - margin;
        int maximumZ = village.maximum().z() + margin;
        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (level.isLoaded(candidate) && level.getBlockState(candidate).is(target)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int generatedFacilityMargin(GeneratedVillageState village) {
        return isMangroveVillage(village) ? 0 : GENERATED_FACILITY_MARGIN;
    }

    private static boolean supportsGeneratedOakTree(ServerLevel level, WorkOrder order) {
        TagKey<Block> logs = blockTag(order.worldTargetTag());
        Block replacement = replacementBlock(order);
        int height = order.output().count();
        return logs != null
                && height >= 2 && height <= 6
                && Blocks.OAK_LOG.defaultBlockState().is(logs)
                && Blocks.OAK_LEAVES.defaultBlockState().is(LUMBERJACK_LEAVES)
                && replacement == Blocks.OAK_SAPLING
                && order.matchesOutput(new ItemStack(Items.OAK_LOG, height), level.registryAccess());
    }

    private static boolean supportsGeneratedStoneMine(ServerLevel level) {
        return WorkOrderDefinitions.catalog().snapshot().values().stream()
                .filter(order -> TotemVillagerProfessions.MINER_ID.toString().equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> !order.worldTargetTag().isBlank())
                .anyMatch(order -> {
                    TagKey<Block> targets = blockTag(order.worldTargetTag());
                    return targets != null
                            && Blocks.STONE.defaultBlockState().is(targets)
                            && order.matchesOutput(new ItemStack(Items.COBBLESTONE, order.output().count()), level.registryAccess());
                });
    }

    private static Optional<BlockPos> findResourceSite(ServerLevel level, GeneratedVillageState village,
                                                       List<Villager> residents, Predicate<BlockPos> compatible) {
        int checks = 0;
        for (Villager resident : residents) {
            BlockPos origin = resident.blockPosition();
            for (int radius = 0; radius <= RESOURCE_SITE_SEARCH_RADIUS && checks < RESOURCE_SITE_SCAN_MAX_CHECKS; radius++) {
                for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < RESOURCE_SITE_SCAN_MAX_CHECKS; x++) {
                    for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < RESOURCE_SITE_SCAN_MAX_CHECKS; z++) {
                        if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                                && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                            continue;
                        }
                        for (int y = origin.getY() - RESOURCE_SITE_SCAN_Y_BELOW;
                             y <= origin.getY() + RESOURCE_SITE_SCAN_Y_ABOVE && checks < RESOURCE_SITE_SCAN_MAX_CHECKS; y++) {
                            BlockPos candidate = new BlockPos(x, y, z);
                            checks++;
                            if (insideVillageStructure(candidate, village) && compatible.test(candidate)) {
                                return Optional.of(candidate);
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean canPlaceFoundingLumberyard(ServerLevel level, GeneratedVillageState village,
                                                       BlockPos treeBase, int height) {
        BlockPos woodcutter = treeBase.east(2);
        BlockPos fibreTrellis = woodcutter.north(2);
        BlockPos vineColumn = fibreTrellis.east();
        if (!hasSturdySupport(level, treeBase.below())
                || !hasSturdySupport(level, woodcutter.below())
                || !Blocks.OAK_SAPLING.defaultBlockState().canSurvive(level, treeBase)
                || !isVacant(level, village, woodcutter.above())) {
            return false;
        }
        Map<BlockPos, BlockState> planned = new LinkedHashMap<>();
        for (int index = 0; index < height; index++) {
            planned.put(treeBase.above(index), Blocks.OAK_LOG.defaultBlockState());
        }
        BlockPos canopy = treeBase.above(height);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                planned.put(canopy.offset(x, 0, z), Blocks.OAK_LEAVES.defaultBlockState());
            }
        }
        planned.put(canopy.above(), Blocks.OAK_LEAVES.defaultBlockState());
        planned.put(woodcutter, TotemVillagerBlocks.WOODCUTTER.defaultBlockState());
        if (!hasSturdySupport(level, fibreTrellis.below())) {
            planned.put(fibreTrellis.below(), Blocks.COBBLESTONE.defaultBlockState());
        }
        for (int y = 0; y <= 2; y++) {
            planned.put(fibreTrellis.above(y), Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        }
        BlockState attachedVine = Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true);
        planned.put(vineColumn.above(), attachedVine);
        planned.put(vineColumn.above(2), attachedVine);
        return canPlaceGeneratedBlocks(level, village, planned);
    }

    private static Optional<FoundingMine> foundingMine(ServerLevel level, GeneratedVillageState village,
                                                       BlockPos furnace, Direction direction) {
        BlockState mineFloor = level.getBlockState(furnace.below());
        if (!isVacant(level, village, furnace)
                || !(hasSturdySupport(level, furnace.below()) || mineFloor.isAir() || mineFloor.is(Blocks.STONE))) {
            if (DEBUG_MINING_STARTER) {
                System.out.println("[totem-villagers] Miner starter candidate rejected: furnace " + furnace
                        + " not empty or with non-stable floor (vacant=" + isVacant(level, village, furnace)
                        + ", sturdy=" + hasSturdySupport(level, furnace.below()) + ", direction=" + direction + ")");
            }
            return Optional.empty();
        }
        Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
        List<BlockPos> bounds = new ArrayList<>();
        changes.put(furnace, Blocks.FURNACE.defaultBlockState());
        bounds.add(furnace);

        // Put the entry at the middle of the near edge. The outer ring is a
        // full 5 × 5 footprint, rather than the former one-block-wide line.
        BlockPos center = furnace.relative(direction, MINE_SPIRAL_RADIUS + 1);
        BlockPos entrance = spiralPosition(center, direction, MINE_SPIRAL.getFirst());
        if (!entrance.equals(furnace.relative(direction))) {
            throw new IllegalStateException("Mine spiral entrance drifted away from its Furnace");
        }
        protectMineFloor(changes, level, furnace.below());

        List<BlockPos> centralShaft = centralShaft(center);
        for (BlockPos position : centralShaft) {
            if (!mineTunnelBlock(level, village, position)) {
                return Optional.empty();
            }
            clearMineTunnel(changes, level, position);
            bounds.add(position);
        }

        for (int step = 0; step < MINESHAFT_DEPTH; step++) {
            SpiralCoordinate coordinate = MINE_SPIRAL.get(step);
            boolean hasGuardRail = hasMineGuardRail(step);
            BlockPos landing = spiralPosition(center, direction, coordinate).below(step);
            BlockPos floor = landing.below();
            BlockPos rail = landing.relative(inwardDirection(direction, coordinate));
            BlockPos railBase = rail.below();
            BlockPos miningFace = landing.relative(outwardDirection(direction, coordinate));
            // A descending mob still occupies the height of the previous step
            // while crossing into this landing.  Keep the third block clear
            // and place the roof at the fourth, otherwise a two-block roof
            // makes every downward edge collide with the mob's head.
            BlockPos clearance = landing.above(2);
            BlockPos roof = landing.above(3);
            if (!mineTunnelBlock(level, village, landing) || !mineTunnelBlock(level, village, landing.above())
                    || !mineTunnelBlock(level, village, clearance)
                    || !mineFloorBlock(level, village, floor)
                    || (hasGuardRail && (!mineRailBlock(level, village, rail)
                    || !mineRailBaseBlock(level, village, railBase))) || !mineFaceBlock(level, village, miningFace)
                    || !mineRoofBlock(level, village, roof)) {
                if (DEBUG_MINING_STARTER) {
                    System.out.println("[totem-villagers] Miner starter failed at direction " + direction
                            + ", furnace " + furnace + ", step " + step
                            + ", landing " + landing + ", coordinate " + coordinate.forward() + "," + coordinate.sideways());
                    System.out.println("[totem-villagers]  tunnel=" + mineTunnelBlock(level, village, landing)
                            + "/" + mineTunnelBlock(level, village, landing.above())
                            + "/" + mineTunnelBlock(level, village, clearance)
                            + " states=" + level.getBlockState(landing)
                            + "/" + level.getBlockState(landing.above())
                            + "/" + level.getBlockState(clearance)
                            + " floor=" + mineFloorBlock(level, village, floor)
                            + "(" + level.getBlockState(floor) + ")"
                            + " rail=" + mineRailBlock(level, village, rail)
                            + "/" + mineRailBaseBlock(level, village, railBase)
                            + "(" + level.getBlockState(rail) + "/" + level.getBlockState(railBase) + ")"
                            + " miningFace=" + mineFaceBlock(level, village, miningFace)
                            + "(" + level.getBlockState(miningFace) + ")"
                            + " roof=" + mineRoofBlock(level, village, roof)
                            + "(" + level.getBlockState(roof) + ")");
                }
                return Optional.empty();
            }
            clearMineTunnel(changes, level, landing);
            clearMineTunnel(changes, level, landing.above());
            clearMineTunnel(changes, level, clearance);
            installMineStair(changes, level, floor, descentDirection(direction, step));
            protectMineFace(changes, level, miningFace);
            if (hasGuardRail) {
                installMineRail(changes, level, railBase, rail);
            }
            protectMineRoof(changes, level, roof);
            bounds.add(landing);
            bounds.add(landing.above());
            bounds.add(clearance);
            bounds.add(floor);
            if (hasGuardRail) {
                bounds.add(rail);
                bounds.add(railBase);
            }
            bounds.add(miningFace);
            bounds.add(roof);
        }
        if (!mineBoundsClear(level, changes.keySet())) {
            if (DEBUG_MINING_STARTER) {
                int minimumX = bounds.stream().mapToInt(BlockPos::getX).min().orElseThrow();
                int minimumY = bounds.stream().mapToInt(BlockPos::getY).min().orElseThrow();
                int minimumZ = bounds.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
                int maximumX = bounds.stream().mapToInt(BlockPos::getX).max().orElseThrow();
                int maximumY = bounds.stream().mapToInt(BlockPos::getY).max().orElseThrow();
                int maximumZ = bounds.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
                System.out.println("[totem-villagers] Miner starter candidate rejected by boundary/entity check: "
                        + minimumX + "," + minimumY + "," + minimumZ + " -> " + maximumX + "," + maximumY + "," + maximumZ);
            }
            return Optional.empty();
        }
        UUID owner = UUID.nameUUIDFromBytes(("totem-villagers:worldgen-mine:" + village.id()).getBytes(StandardCharsets.UTF_8));
        return Optional.of(new FoundingMine(zone(level, owner, bounds), changes));
    }

    private static boolean placeFoundingMine(ServerLevel level, FoundingMine mine) {
        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : mine.changes().entrySet()) {
            original.put(entry.getKey(), level.getBlockState(entry.getKey()));
            if (!level.setBlock(entry.getKey(), entry.getValue(), 3)) {
                original.forEach((position, state) -> level.setBlock(position, state, 3));
                return false;
            }
        }
        return true;
    }

    private static boolean mineTunnelBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideMineFootprint(level, village, position)
                && isNaturalMineTerrain(level.getBlockState(position));
    }

    private static boolean mineFloorBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideMineFootprint(level, village, position)
                && isNaturalMineTerrain(level.getBlockState(position));
    }

    private static boolean mineFaceBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return insideMineFootprint(level, village, position)
                && (isNaturalMineTerrain(state) || state.is(Blocks.COBBLESTONE));
    }

    private static boolean mineRailBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideMineFootprint(level, village, position)
                && isNaturalMineTerrain(level.getBlockState(position));
    }

    private static boolean mineRailBaseBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideMineFootprint(level, village, position)
                && isNaturalMineTerrain(level.getBlockState(position));
    }

    private static boolean mineRoofBlock(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideMineFootprint(level, village, position)
                && isNaturalMineTerrain(level.getBlockState(position));
    }

    /** Natural overworld terrain may be carved during the one-shot generated-village recovery pass. */
    private static boolean isNaturalMineTerrain(BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty())
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.TUFF) || state.is(Blocks.CALCITE)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE) || state.is(Blocks.LAPIS_ORE)
                || state.is(Blocks.DEEPSLATE_LAPIS_ORE) || state.is(Blocks.REDSTONE_ORE)
                || state.is(Blocks.DEEPSLATE_REDSTONE_ORE) || state.is(Blocks.DIAMOND_ORE)
                || state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.EMERALD_ORE)
                || state.is(Blocks.DEEPSLATE_EMERALD_ORE);
    }

    private static boolean insideMineFootprint(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return level.isLoaded(position)
                && position.getX() >= village.minimum().x() && position.getX() <= village.maximum().x()
                && position.getZ() >= village.minimum().z() && position.getZ() <= village.maximum().z()
                && position.getY() >= level.getMinY() + 2 && position.getY() <= village.maximum().y();
    }

    private static void clearMineTunnel(Map<BlockPos, BlockState> changes, ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position).isAir()) {
            changes.put(position, Blocks.AIR.defaultBlockState());
        }
    }

    private static void protectMineFloor(Map<BlockPos, BlockState> changes, ServerLevel level, BlockPos position) {
        if (isNaturalMineTerrain(level.getBlockState(position))) {
            changes.put(position, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    /**
     * A real stair shape is required for vanilla mob navigation across the descending ramp.
     * Stair {@code FACING} denotes the uphill side, so a ramp that descends in
     * {@code descentDirection} has to face back toward the entrance.
     */
    private static void installMineStair(Map<BlockPos, BlockState> changes, ServerLevel level, BlockPos position,
                                         Direction descentDirection) {
        if (isNaturalMineTerrain(level.getBlockState(position))) {
            changes.put(position, Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, descentDirection.getOpposite()));
        }
    }

    /** Places a wooden guard rail on a solid base along the inner edge of each stair. */
    private static void installMineRail(Map<BlockPos, BlockState> changes, ServerLevel level,
                                        BlockPos base, BlockPos rail) {
        if (isNaturalMineTerrain(level.getBlockState(base))) {
            changes.put(base, Blocks.COBBLESTONE.defaultBlockState());
        }
        if (isNaturalMineTerrain(level.getBlockState(rail))) {
            changes.put(rail, Blocks.OAK_FENCE.defaultBlockState());
        }
    }

    private static void protectMineRoof(Map<BlockPos, BlockState> changes, ServerLevel level, BlockPos position) {
        if (isNaturalMineTerrain(level.getBlockState(position))) {
            changes.put(position, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private static void protectMineFace(Map<BlockPos, BlockState> changes, ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position).is(Blocks.STONE)) {
            // The first outward face is also the Furnace coordinate, and later
            // spiral geometry can overlap already planned stairs or tunnels.
            // Never replace a more specific earlier construction decision.
            changes.putIfAbsent(position, Blocks.STONE.defaultBlockState());
        }
    }

    /**
     * Only reject entities occupying a block that the starter will actually
     * replace.  A 5 × 5 spiral has a deliberately wide bounding box, but the
     * the protected central shaft and empty corners are safe for nearby residents.
     */
    private static boolean mineBoundsClear(ServerLevel level, Iterable<BlockPos> positions) {
        List<BlockPos> changed = new ArrayList<>();
        positions.forEach(changed::add);
        if (changed.isEmpty()) {
            return false;
        }
        int minimumX = changed.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minimumY = changed.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minimumZ = changed.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maximumX = changed.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maximumY = changed.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maximumZ = changed.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return level.getEntitiesOfClass(Entity.class,
                new AABB(minimumX, minimumY, minimumZ, maximumX + 1, maximumY + 1, maximumZ + 1),
                Entity::isAlive).stream().noneMatch(entity -> changed.stream()
                .anyMatch(position -> entity.getBoundingBox().intersects(new AABB(position))));
    }

    private static SpiralCoordinate spiral(int forward, int sideways) {
        return new SpiralCoordinate(forward, sideways);
    }

    private static BlockPos spiralPosition(BlockPos center, Direction forward, SpiralCoordinate coordinate) {
        return center.relative(forward, coordinate.forward()).relative(forward.getClockWise(), coordinate.sideways());
    }

    /** Returns the direction from the outer stair ring toward the guarded centre. */
    private static Direction inwardDirection(Direction forward, SpiralCoordinate coordinate) {
        Direction sideways = forward.getClockWise();
        if (coordinate.forward() == -MINE_SPIRAL_RADIUS) {
            return forward;
        }
        if (coordinate.forward() == MINE_SPIRAL_RADIUS) {
            return forward.getOpposite();
        }
        if (coordinate.sideways() == -MINE_SPIRAL_RADIUS) {
            return sideways;
        }
        if (coordinate.sideways() == MINE_SPIRAL_RADIUS) {
            return sideways.getOpposite();
        }
        throw new IllegalArgumentException("Mine spiral coordinate must be on the 5x5 perimeter");
    }

    /** The outer wall stays as raw stone for the Miner after the inner edge receives a guard rail. */
    private static Direction outwardDirection(Direction forward, SpiralCoordinate coordinate) {
        return inwardDirection(forward, coordinate).getOpposite();
    }

    /**
     * At every outside corner the next tread needs the inner block as headroom
     * (or stair support). Leave that one block open; the remaining three
     * treads per side keep a practical guard along the shaft.
     */
    private static boolean hasMineGuardRail(int step) {
        return step % 4 != 2;
    }

    /** Clears a centred vertical shaft while leaving the fence-support ring intact. */
    private static List<BlockPos> centralShaft(BlockPos center) {
        List<BlockPos> shaft = new ArrayList<>();
        for (int depth = -2; depth < MINESHAFT_DEPTH; depth++) {
            shaft.add(center.below(depth));
        }
        return shaft;
    }

    private static Direction descentDirection(Direction forward, int step) {
        int source = step == MINE_SPIRAL.size() - 1 ? step - 1 : step;
        SpiralCoordinate current = MINE_SPIRAL.get(source);
        SpiralCoordinate next = MINE_SPIRAL.get(source + 1);
        int forwardDelta = next.forward() - current.forward();
        if (forwardDelta != 0) {
            return forwardDelta > 0 ? forward : forward.getOpposite();
        }
        int sidewaysDelta = next.sideways() - current.sideways();
        return sidewaysDelta > 0 ? forward.getClockWise() : forward.getClockWise().getOpposite();
    }

    private static WorkZone zone(ServerLevel level, UUID owner, List<BlockPos> positions) {
        int minimumX = positions.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minimumY = positions.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minimumZ = positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maximumX = positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maximumY = positions.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maximumZ = positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return new WorkZone(owner, level.dimension().identifier().toString(),
                new BlockCoordinate(minimumX, minimumY, minimumZ), new BlockCoordinate(maximumX, maximumY, maximumZ));
    }

    private static boolean placeGeneratedBlocks(ServerLevel level, GeneratedVillageState village,
                                                Map<BlockPos, BlockState> planned) {
        if (!canPlaceGeneratedBlocks(level, village, planned)) {
            return false;
        }
        List<BlockPos> placed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : planned.entrySet()) {
            if (!level.setBlock(entry.getKey(), entry.getValue(), 3)) {
                placed.forEach(position -> level.setBlock(position, Blocks.AIR.defaultBlockState(), 3));
                return false;
            }
            placed.add(entry.getKey());
        }
        return true;
    }

    private static boolean canPlaceGeneratedBlocks(ServerLevel level, GeneratedVillageState village,
                                                   Map<BlockPos, BlockState> planned) {
        if (planned.isEmpty() || planned.keySet().stream().anyMatch(position -> !isVacant(level, village, position))) {
            return false;
        }
        int minimumX = planned.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int minimumY = planned.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int minimumZ = planned.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maximumX = planned.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int maximumY = planned.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int maximumZ = planned.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return level.getEntitiesOfClass(Entity.class,
                new AABB(minimumX, minimumY, minimumZ, maximumX + 1, maximumY + 1, maximumZ + 1), Entity::isAlive).isEmpty();
    }

    private static boolean isVacant(ServerLevel level, GeneratedVillageState village, BlockPos position) {
        return insideVillageStructure(position, village) && level.isLoaded(position) && level.getBlockState(position).isAir();
    }

    private static boolean hasSturdySupport(ServerLevel level, BlockPos position) {
        return level.isLoaded(position) && level.getBlockState(position).isFaceSturdy(level, position, Direction.UP);
    }

    private record FoundingLumberyard(WorkZone zone, BlockPos woodcutter) {
    }

    private record FoundingMine(WorkZone zone, Map<BlockPos, BlockState> changes) {
    }

    private record MangrovePopulationSites(BlockPos barrel, BlockPos bell, List<BlockPos> beds) {
        private MangrovePopulationSites {
            beds = List.copyOf(beds);
        }
    }

    private record SpiralCoordinate(int forward, int sideways) {
    }

    private static Optional<WorkZone> findLumberjackZone(ServerLevel level, GeneratedVillageState village,
                                                          List<Villager> residents) {
        for (WorkOrder order : lumberjackWorldOrders()) {
            TagKey<Block> logs = blockTag(order.worldTargetTag());
            if (logs == null || replacementBlock(order) == null) {
                continue;
            }
            for (Villager resident : residents) {
                Optional<BlockPos> base = findMatureTreeBase(level, village, resident.blockPosition(), logs, order);
                if (base.isEmpty()) {
                    continue;
                }
                return Optional.of(trunkZone(level, village, base.orElseThrow()));
            }
        }
        return Optional.empty();
    }

    /**
     * Records a pre-existing Woodcutter when a world-gen data pack supplied
     * one; otherwise the founding village receives one only on a loaded,
     * vacant, solid-topped block inside its original structure bounds.
     */
    private static Optional<BlockPos> findWoodcutterSite(ServerLevel level, GeneratedVillageState village,
                                                          List<Villager> residents) {
        int checks = 0;
        for (Villager resident : residents) {
            BlockPos origin = resident.blockPosition();
            for (int radius = 0; radius <= WOODCUTTER_SEARCH_RADIUS && checks < WOODCUTTER_SCAN_MAX_CHECKS; radius++) {
                for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < WOODCUTTER_SCAN_MAX_CHECKS; x++) {
                    for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < WOODCUTTER_SCAN_MAX_CHECKS; z++) {
                        if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                                && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                            continue;
                        }
                        for (int y = origin.getY() - WOODCUTTER_SCAN_Y_BELOW;
                             y <= origin.getY() + WOODCUTTER_SCAN_Y_ABOVE && checks < WOODCUTTER_SCAN_MAX_CHECKS; y++) {
                            BlockPos candidate = new BlockPos(x, y, z);
                            checks++;
                            if (!insideVillageStructure(candidate, village) || !level.isLoaded(candidate)) {
                                continue;
                            }
                            if (level.getBlockState(candidate).is(TotemVillagerBlocks.WOODCUTTER)) {
                                return Optional.of(candidate);
                            }
                            BlockPos below = candidate.below();
                            if (level.isLoaded(below) && level.getBlockState(candidate).isAir()
                                    && level.getBlockState(candidate.above()).isAir()
                                    && level.getEntitiesOfClass(Entity.class, new AABB(candidate), Entity::isAlive).isEmpty()
                                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                                return Optional.of(candidate);
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static List<WorkOrder> lumberjackWorldOrders() {
        return WorkOrderDefinitions.catalog().snapshot().values().stream()
                .filter(order -> TotemVillagerProfessions.LUMBERJACK_ID.toString().equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> !order.worldTargetTag().isBlank() && !order.worldReplantBlockId().isBlank())
                .filter(order -> order.output().count() >= 2)
                .sorted(Comparator.comparing(WorkOrder::id))
                .toList();
    }

    private static WorkZone trunkZone(ServerLevel level, GeneratedVillageState village, BlockPos base) {
        // Keep the rooted plot valid after its sapling grows into any ordinary
        // 2-16 block trunk.  The former four-block ceiling permanently stalled
        // a Lumberjack whenever vanilla regrew a taller Oak.
        BlockPos trunkTop = base.above(MAX_TREE_TRUNK_HEIGHT - 1);
        UUID owner = UUID.nameUUIDFromBytes(("totem-villagers:worldgen:" + village.id()).getBytes(StandardCharsets.UTF_8));
        return new WorkZone(owner, level.dimension().identifier().toString(), coordinate(base), coordinate(trunkTop));
    }

    private static Optional<BlockPos> findMatureTreeBase(ServerLevel level, GeneratedVillageState village, BlockPos origin,
                                                         TagKey<Block> logs, WorkOrder order) {
        int checks = 0;
        for (int radius = 0; radius <= TREE_SEARCH_RADIUS && checks < TREE_SCAN_MAX_CHECKS; radius++) {
            for (int x = origin.getX() - radius; x <= origin.getX() + radius && checks < TREE_SCAN_MAX_CHECKS; x++) {
                for (int z = origin.getZ() - radius; z <= origin.getZ() + radius && checks < TREE_SCAN_MAX_CHECKS; z++) {
                    if (radius != 0 && x != origin.getX() - radius && x != origin.getX() + radius
                            && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                        continue;
                    }
                    for (int y = origin.getY() - TREE_SCAN_Y_BELOW; y <= origin.getY() + TREE_SCAN_Y_ABOVE
                            && checks < TREE_SCAN_MAX_CHECKS; y++) {
                        checks++;
                        BlockPos base = new BlockPos(x, y, z);
                        if (insideFallbackTreeBoundary(base, village) && isMatureTreeBase(level, base, logs, order)) {
                            return Optional.of(base);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean insideFallbackTreeBoundary(BlockPos base, GeneratedVillageState village) {
        return base.getX() >= village.minimum().x() - TREE_FALLBACK_MARGIN
                && base.getX() <= village.maximum().x() + TREE_FALLBACK_MARGIN
                && base.getY() >= village.minimum().y() - TREE_FALLBACK_MARGIN
                && base.getY() <= village.maximum().y() + TREE_FALLBACK_MARGIN
                && base.getZ() >= village.minimum().z() - TREE_FALLBACK_MARGIN
                && base.getZ() <= village.maximum().z() + TREE_FALLBACK_MARGIN;
    }

    private static boolean insideVillageStructure(BlockPos position, GeneratedVillageState village) {
        return position.getX() >= village.minimum().x() && position.getX() <= village.maximum().x()
                && position.getY() >= village.minimum().y() && position.getY() <= village.maximum().y()
                && position.getZ() >= village.minimum().z() && position.getZ() <= village.maximum().z();
    }

    private static boolean isMatureTreeBase(ServerLevel level, BlockPos base, TagKey<Block> logs, WorkOrder order) {
        if (!level.isLoaded(base) || !level.isLoaded(base.below()) || level.getBlockState(base.below()).is(logs)
                || replacementBlock(order) == null || !replacementBlock(order).defaultBlockState().canSurvive(level, base)) {
            return false;
        }
        int height = 0;
        while (height < MAX_TREE_TRUNK_HEIGHT && level.isLoaded(base.above(height))
                && level.getBlockState(base.above(height)).is(logs)) {
            height++;
        }
        if (height < MIN_TREE_TRUNK_HEIGHT) {
            return false;
        }
        BlockPos aboveTrunk = base.above(height);
        return level.isLoaded(aboveTrunk) && !level.getBlockState(aboveTrunk).is(logs)
                && hasLeafCanopy(level, base.above(height - 1));
    }

    private static boolean hasLeafCanopy(ServerLevel level, BlockPos top) {
        for (int y = 0; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos leaf = top.offset(x, y, z);
                    if (level.isLoaded(leaf) && level.getBlockState(leaf).is(LUMBERJACK_LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Block replacementBlock(WorkOrder order) {
        Identifier id = Identifier.tryParse(order.worldReplantBlockId());
        return id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
    }

    private static TagKey<Block> blockTag(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? null : TagKey.create(Registries.BLOCK, identifier);
    }

    private static BlockCoordinate coordinate(BlockPos position) {
        return new BlockCoordinate(position.getX(), position.getY(), position.getZ());
    }
}
