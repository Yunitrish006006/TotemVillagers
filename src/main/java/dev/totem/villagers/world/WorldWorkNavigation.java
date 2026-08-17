package dev.totem.villagers.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a reachable standing position around a solid world-work target.
 * Trees and mine faces are not themselves valid navigation nodes, so asking
 * vanilla to path to the target block is unreliable on uneven village terrain.
 */
public final class WorldWorkNavigation {
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
        BlockPos stance = worker.blockPosition();
        int horizontalSteps = Math.abs(stance.getX() - target.getX())
                + Math.abs(stance.getZ() - target.getZ());
        int targetElevation = target.getY() - stance.getY();
        return horizontalSteps == 1 && targetElevation >= 0 && targetElevation <= 2;
    }

    /** Public for deterministic GameTests and concise live-work diagnostics. */
    public static Optional<Path> pathToReach(ServerLevel level, Villager worker, BlockPos target) {
        restoreSupportedGroundState(level, worker);
        Set<BlockPos> stances = reachableStanceCandidates(level, target);
        if (stances.isEmpty()) {
            return Optional.empty();
        }
        // Pathing to the solid work target lets vanilla choose its top face,
        // recreating the surface-mining bug. Path to the supporting block
        // beneath each candidate instead, then retain only paths whose entity
        // endpoint is the requested adjacent air stance.
        return stances.stream()
                .map(stance -> worker.getNavigation().createPath(stance.below(), 0))
                .filter(path -> path != null && path.getEndNode() != null && path.getNodeCount() > 0)
                .filter(path -> stances.contains(BlockPos.containing(
                        path.getEntityPosAtNode(worker, path.getNodeCount() - 1))))
                .min(Comparator.comparingInt(Path::getNodeCount));
    }

    /**
     * Structure-spawned villagers can retain a stale airborne flag while
     * already motionless on a full deck block. Ground navigation refuses to
     * compute any path in that state, so only repair the flag when the feet
     * are demonstrably supported by a sturdy top face.
     */
    private static void restoreSupportedGroundState(ServerLevel level, Villager worker) {
        if (worker.onGround() || worker.isInLiquid() || worker.isPassenger()) {
            return;
        }
        BlockPos support = worker.blockPosition().below();
        if (level.isLoaded(support)
                && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
            worker.setOnGround(true);
        }
    }

    private static Set<BlockPos> reachableStanceCandidates(ServerLevel level, BlockPos target) {
        Set<BlockPos> result = new LinkedHashSet<>();
        // A worker must occupy the air cell directly against one exposed
        // horizontal face.  Broad radius/vertical candidates let Miners stand
        // on the surface and mine blocks several layers below them.
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int targetElevation = 0; targetElevation <= 2; targetElevation++) {
                BlockPos stance = target.relative(direction).below(targetElevation);
                if (!level.isLoaded(stance)
                        || !level.getBlockState(stance).isPathfindable(PathComputationType.LAND)
                        || !level.getBlockState(stance.above()).isPathfindable(PathComputationType.LAND)) {
                    continue;
                }
                result.add(stance.immutable());
            }
        }
        return Set.copyOf(result);
    }
}
