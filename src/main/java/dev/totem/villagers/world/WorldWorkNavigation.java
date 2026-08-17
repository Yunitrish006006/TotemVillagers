package dev.totem.villagers.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a reachable standing position around a solid world-work target.
 * Trees and mine faces are not themselves valid navigation nodes, so asking
 * vanilla to path to the target block is unreliable on uneven village terrain.
 */
public final class WorldWorkNavigation {
    public static final double WORK_REACH_SQUARED = 16.0D;
    private static final int HORIZONTAL_STANCE_RADIUS = 3;
    private static final int VERTICAL_STANCE_RADIUS = 2;

    private WorldWorkNavigation() {
    }

    public static boolean canReach(ServerLevel level, Villager worker, BlockPos target) {
        return isWithinReach(worker, target) || pathToReach(level, worker, target).isPresent();
    }

    /** Starts navigation to one reachable air cell while preserving the solid block as the durable work target. */
    public static boolean moveToReach(ServerLevel level, Villager worker, BlockPos target, double speed) {
        if (isWithinReach(worker, target)) {
            return true;
        }
        Optional<Path> path = pathToReach(level, worker, target);
        return path.isPresent() && worker.getNavigation().moveTo(path.orElseThrow(), speed);
    }

    public static boolean isWithinReach(Villager worker, BlockPos target) {
        return worker.distanceToSqr(Vec3.atCenterOf(target)) <= WORK_REACH_SQUARED;
    }

    /** Public for deterministic GameTests and concise live-work diagnostics. */
    public static Optional<Path> pathToReach(ServerLevel level, Villager worker, BlockPos target) {
        Set<BlockPos> stances = reachableStanceCandidates(level, target);
        if (stances.isEmpty()) {
            return Optional.empty();
        }
        Path path = worker.getNavigation().createPath(stances, 0);
        return path != null && path.canReach() ? Optional.of(path) : Optional.empty();
    }

    private static Set<BlockPos> reachableStanceCandidates(ServerLevel level, BlockPos target) {
        Set<BlockPos> result = new LinkedHashSet<>();
        // Nearest horizontal cells are presented first for deterministic tests;
        // PathNavigation still selects the cheapest reachable member of the set.
        for (int radius = 1; radius <= HORIZONTAL_STANCE_RADIUS; radius++) {
            for (int yOffset = -VERTICAL_STANCE_RADIUS; yOffset <= VERTICAL_STANCE_RADIUS; yOffset++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        BlockPos stance = target.offset(x, yOffset, z);
                        if (Vec3.atCenterOf(stance).distanceToSqr(Vec3.atCenterOf(target)) > WORK_REACH_SQUARED
                                || !level.isLoaded(stance)
                                || !level.getBlockState(stance).isPathfindable(PathComputationType.LAND)
                                || !level.getBlockState(stance.above()).isPathfindable(PathComputationType.LAND)) {
                            continue;
                        }
                        result.add(stance.immutable());
                    }
                }
            }
        }
        return Set.copyOf(result);
    }
}
