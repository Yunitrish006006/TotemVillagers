package dev.totem.villagers.world;

import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import dev.totem.villagers.worldgen.VillageUtilityFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Adds one safe, persisted tread beneath a generated village's spiral Mine. */
public final class GeneratedMineExpansion {
    private static final int INITIAL_SPIRAL_STEPS = 16;
    private static final TagKey<Block> MINER_TARGETS = TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("totem", "miner_targets"));

    private GeneratedMineExpansion() {
    }

    /**
     * Plans the next tread without changing the world. A missing plan never
     * prevents the already-selected source block from being mined normally.
     */
    public static Optional<Plan> plan(ServerLevel level, Villager miner, BlockPos minedTarget) {
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        Optional<UUID> zoneId = assignments.getAssignment(miner.getUUID())
                .filter(assignment -> "totem:miner".equals(assignment.roleId()))
                .flatMap(assignment -> assignment.workZoneId());
        if (zoneId.isEmpty()) {
            return Optional.empty();
        }
        UUID assignedZoneId = zoneId.orElseThrow();
        Optional<WorkZoneRecord> record = assignments.getZone(assignedZoneId)
                .filter(zone -> "totem:miner".equals(zone.roleId()))
                .filter(zone -> zone.zone().contains(level.dimension().identifier().toString(), coordinate(minedTarget)));
        if (record.isEmpty() || GeneratedVillageSavedData.forServer(level.getServer()).snapshot().stream()
                .filter(village -> village.dimensionId().equals(level.dimension().identifier().toString()))
                .noneMatch(village -> village.minerZoneId().filter(assignedZoneId::equals).isPresent())) {
            return Optional.empty();
        }

        Optional<BlockPos> furnace = miner.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .map(GlobalPos::pos)
                .filter(level::isLoaded)
                .filter(position -> level.getBlockState(position).is(Blocks.FURNACE));
        if (furnace.isEmpty()) {
            return Optional.empty();
        }
        BlockPos furnacePosition = furnace.orElseThrow();
        WorkZone zone = record.orElseThrow().zone();
        Optional<Direction> direction = mineDirection(level, furnacePosition);
        if (direction.isEmpty()) {
            return Optional.empty();
        }
        Direction mineDirection = direction.orElseThrow();
        int step = furnacePosition.getY() - zone.minimum().y();
        if (step < INITIAL_SPIRAL_STEPS) {
            return Optional.empty();
        }

        BlockPos landing = VillageUtilityFeature.mineLanding(furnacePosition, mineDirection, step);
        BlockPos floor = landing.below();
        if (floor.getY() != zone.minimum().y() - 1 || floor.getY() < level.getMinY()) {
            return Optional.empty();
        }
        WorkZone extended = new WorkZone(zone.ownerId(), zone.dimensionId(),
                new BlockCoordinate(zone.minimum().x(), floor.getY(), zone.minimum().z()), zone.maximum());
        Direction inward = VillageUtilityFeature.mineInwardDirection(mineDirection, step);
        BlockPos face = landing.relative(inward.getOpposite());
        if (!safeTarget(level, miner, extended, face)) {
            return Optional.empty();
        }

        LinkedHashMap<BlockPos, BlockState> desired = new LinkedHashMap<>();
        BlockPos center = furnacePosition.relative(mineDirection, 3).below(step);
        desired.put(center, Blocks.AIR.defaultBlockState());
        desired.put(landing, Blocks.AIR.defaultBlockState());
        desired.put(landing.above(), Blocks.AIR.defaultBlockState());
        desired.put(landing.above(2), Blocks.AIR.defaultBlockState());
        desired.put(floor, Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,
                VillageUtilityFeature.mineDescentDirection(mineDirection, step).getOpposite()));
        if (VillageUtilityFeature.hasGuardRail(step)) {
            desired.put(landing.relative(inward).below(), Blocks.COBBLESTONE.defaultBlockState());
            desired.put(landing.relative(inward), Blocks.OAK_FENCE.defaultBlockState());
        }
        desired.put(landing.above(3), Blocks.COBBLESTONE.defaultBlockState());

        LinkedHashMap<BlockPos, BlockState> originals = new LinkedHashMap<>();
        LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : desired.entrySet()) {
            BlockPos position = entry.getKey();
            BlockState current = level.getBlockState(position);
            if (!safeConstructionCell(level, miner, extended, position, current)) {
                return Optional.empty();
            }
            if (!current.equals(entry.getValue())) {
                originals.put(position.immutable(), current);
                changes.put(position.immutable(), entry.getValue());
            }
        }
        if (changes.isEmpty() || occupied(level, changes.keySet())) {
            return Optional.empty();
        }
        return Optional.of(new Plan(assignedZoneId, step, landing.immutable(), floor.immutable(),
                face.immutable(), Map.copyOf(originals), Map.copyOf(changes)));
    }

    private static Optional<Direction> mineDirection(ServerLevel level, BlockPos furnace) {
        for (Direction direction : List.of(Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH)) {
            BlockPos firstFloor = VillageUtilityFeature.mineLanding(furnace, direction, 0).below();
            BlockState state = level.getBlockState(firstFloor);
            if (state.is(Blocks.COBBLESTONE_STAIRS)
                    && state.getValue(StairBlock.FACING)
                    == VillageUtilityFeature.mineDescentDirection(direction, 0).getOpposite()) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    private static boolean safeTarget(ServerLevel level, Villager miner, WorkZone extended, BlockPos position) {
        return extended.contains(level.dimension().identifier().toString(), coordinate(position))
                && level.isLoaded(position)
                && level.getBlockEntity(position) == null
                && level.getBlockState(position).is(MINER_TARGETS)
                && level.getBlockState(position).getFluidState().isEmpty()
                && WorldWorkPermissions.mayWork(level, miner, position);
    }

    private static boolean safeConstructionCell(ServerLevel level, Villager miner, WorkZone extended,
                                                BlockPos position, BlockState state) {
        return extended.contains(level.dimension().identifier().toString(), coordinate(position))
                && level.isLoaded(position)
                && level.getBlockEntity(position) == null
                && state.getFluidState().isEmpty()
                && safeMineMaterial(state)
                && WorldWorkPermissions.mayWork(level, miner, position);
    }

    private static boolean safeMineMaterial(BlockState state) {
        return state.isAir() || state.canBeReplaced()
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.DIRT)
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.TUFF) || state.is(Blocks.CALCITE)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE) || state.is(Blocks.LAPIS_ORE)
                || state.is(Blocks.DEEPSLATE_LAPIS_ORE) || state.is(Blocks.REDSTONE_ORE)
                || state.is(Blocks.DEEPSLATE_REDSTONE_ORE) || state.is(Blocks.DIAMOND_ORE)
                || state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.EMERALD_ORE)
                || state.is(Blocks.DEEPSLATE_EMERALD_ORE) || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLESTONE_STAIRS) || state.is(Blocks.OAK_FENCE)
                || state.is(Blocks.BAMBOO_FENCE) || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.PACKED_MUD);
    }

    private static boolean occupied(ServerLevel level, Iterable<BlockPos> positions) {
        for (BlockPos position : positions) {
            AABB cell = new AABB(position);
            if (!level.getEntitiesOfClass(Entity.class, cell, Entity::isAlive).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static BlockCoordinate coordinate(BlockPos position) {
        return new BlockCoordinate(position.getX(), position.getY(), position.getZ());
    }

    /** One immutable, compare-before-write extension transaction. */
    public record Plan(UUID zoneId, int step, BlockPos landing, BlockPos floor, BlockPos nextFace,
                       Map<BlockPos, BlockState> originals, Map<BlockPos, BlockState> changes) {
        public Plan {
            originals = Map.copyOf(originals);
            changes = Map.copyOf(changes);
        }

        public boolean apply(ServerLevel level) {
            if (originals.entrySet().stream()
                    .anyMatch(entry -> !level.getBlockState(entry.getKey()).equals(entry.getValue()))) {
                return false;
            }
            LinkedHashMap<BlockPos, BlockState> changed = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, BlockState> entry : changes.entrySet()) {
                changed.put(entry.getKey(), level.getBlockState(entry.getKey()));
                if (!level.setBlock(entry.getKey(), entry.getValue(), 3)) {
                    changed.forEach((position, state) -> level.setBlock(position, state, 3));
                    return false;
                }
            }
            if (WorkerAssignmentSavedData.forServer(level.getServer())
                    .extendMinerZoneDownward(zoneId, floor.getY())) {
                return true;
            }
            changed.forEach((position, state) -> level.setBlock(position, state, 3));
            return false;
        }
    }
}
