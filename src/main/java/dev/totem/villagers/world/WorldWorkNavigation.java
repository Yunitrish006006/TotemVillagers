package dev.totem.villagers.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.LinkedHashSet;
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

    public static boolean atWildTarget(ServerLevel level, Villager worker, BlockPos pos) {
        var tree = NaturalWorkTrees.at(level, pos);
        return tree == null ? isWithinReach(worker, pos) : visibleTrunk(level, worker, worker.getEyePosition(), tree);
    }

    private static boolean visibleTrunk(ServerLevel level, Villager worker, net.minecraft.world.phys.Vec3 eye,
                                        NaturalWorkTrees.Tree tree) {
        for (var log : tree.logs()) {
            if (log.getY() > tree.base().getY() + 2) break;
            var center = net.minecraft.world.phys.Vec3.atCenterOf(log);
            if (eye.distanceToSqr(center) > 3 * 3) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, center,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, worker));
            if (hit.getBlockPos().equals(log)) return true;
        }
        return false;
    }

    private static Set<BlockPos> wildTreeStances(ServerLevel level, Villager worker, NaturalWorkTrees.Tree tree) {
        var stances = new LinkedHashSet<BlockPos>();
        var base = tree.base();
        for (var p : BlockPos.betweenClosed(base.offset(-3,-2,-3), base.offset(3,2,3))) {
            if (!level.isLoaded(p) || !level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                    || !level.getBlockState(p).isPathfindable(PathComputationType.LAND)
                    || !level.getBlockState(p.above()).isPathfindable(PathComputationType.LAND)) continue;
            if (visibleTrunk(level, worker, net.minecraft.world.phys.Vec3.atBottomCenterOf(p).add(0,worker.getEyeHeight(),0), tree))
                stances.add(p.immutable());
        }
        return stances;
    }

    /** Public for deterministic GameTests and concise live-work diagnostics. */
    public static Optional<Path> pathToReach(ServerLevel level, Villager worker, BlockPos target) {
        return pathToReach(level, worker, target, false);
    }

    public static Optional<Path> pathToReach(ServerLevel level, Villager worker, BlockPos target, boolean wild) {
        restoreSupportedGroundState(level, worker);
        var tree = wild ? NaturalWorkTrees.at(level, target) : null;
        Set<BlockPos> stances = tree == null ? reachableStanceCandidates(level, target) : wildTreeStances(level, worker, tree);
        if (stances.isEmpty()) {
            return Optional.empty();
        }
        // The multi-target overload takes air nodes directly and performs one A* search.
        Path path = wild ? ((dev.totem.villagers.mixin.ResourcePathInvoker)worker.getNavigation())
                .totemVillagers$pathToStances(stances, 8, false, 0, 72) : worker.getNavigation().createPath(stances, 0);
        if (path == null || !path.canReach() || path.getNodeCount() == 0 || !stances.contains(BlockPos.containing(
                path.getEntityPosAtNode(worker, path.getNodeCount() - 1)))) return Optional.empty();
        return Optional.of(path);
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
                        || !level.getBlockState(stance.below()).isFaceSturdy(level, stance.below(), Direction.UP)
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
