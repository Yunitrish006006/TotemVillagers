package dev.totem.villagers.world;

import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Bounded, read-only scan of already loaded blocks. This never calls a chunk
 * getter and requires an ordinary navigation path plus the protection hook.
 */
public final class BoundedZoneTargetFinder {
    public Optional<BlockPos> findNearest(
            ServerLevel level,
            Villager villager,
            WorkZone zone,
            TagKey<Block> eligibleTargets,
            int maximumChecks
    ) {
        return findNearest(level, villager, zone, eligibleTargets, maximumChecks, ignored -> true);
    }

    /**
     * Continues past tagged blocks that are not valid action roots.  In
     * particular, a Lumberjack standing above a tree must skip the nearer
     * middle logs and keep searching until the actual trunk base is found.
     */
    public Optional<BlockPos> findNearest(
            ServerLevel level,
            Villager villager,
            WorkZone zone,
            TagKey<Block> eligibleTargets,
            int maximumChecks,
            Predicate<BlockPos> actionTarget
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(villager, "villager");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(eligibleTargets, "eligibleTargets");
        Objects.requireNonNull(actionTarget, "actionTarget");
        if (maximumChecks < 1 || maximumChecks > 512) {
            throw new IllegalArgumentException("maximumChecks must be between 1 and 512");
        }
        BlockPos origin = villager.blockPosition();
        int centerX = clamp(origin.getX(), zone.minimum().x(), zone.maximum().x());
        int centerY = clamp(origin.getY(), zone.minimum().y(), zone.maximum().y());
        int centerZ = clamp(origin.getZ(), zone.minimum().z(), zone.maximum().z());
        int checks = 0;
        int maximumRadius = Math.max(Math.max(centerX - zone.minimum().x(), zone.maximum().x() - centerX),
                Math.max(centerZ - zone.minimum().z(), zone.maximum().z() - centerZ));
        int maximumVerticalRadius = Math.max(centerY - zone.minimum().y(), zone.maximum().y() - centerY);
        // Sweep a complete horizontal layer before descending further. The old
        // column-first order spent the 256-check budget on only eight columns
        // of a natural 7 x 7 x 20 mine and could miss every visible work face.
        for (int vertical = 0; vertical <= maximumVerticalRadius && checks < maximumChecks; vertical++) {
            int[] heights = vertical == 0 ? new int[]{centerY} : new int[]{centerY - vertical, centerY + vertical};
            for (int y : heights) {
                if (y < zone.minimum().y() || y > zone.maximum().y()) {
                    continue;
                }
                for (int radius = 0; radius <= maximumRadius && checks < maximumChecks; radius++) {
                    for (int x = centerX - radius; x <= centerX + radius && checks < maximumChecks; x++) {
                        for (int z = centerZ - radius; z <= centerZ + radius && checks < maximumChecks; z++) {
                            if (radius != 0 && x != centerX - radius && x != centerX + radius
                                    && z != centerZ - radius && z != centerZ + radius) {
                                continue;
                            }
                            BlockPos candidate = new BlockPos(x, y, z);
                            checks++;
                            if (!zone.contains(level.dimension().identifier().toString(), new BlockCoordinate(x, y, z))
                                    || !level.isLoaded(candidate)
                                    || !level.getBlockState(candidate).is(eligibleTargets)
                                    || !WorldWorkPermissions.mayWork(level, villager, candidate)
                                    || !actionTarget.test(candidate)
                                    || !WorldWorkNavigation.canReach(level, villager, candidate)) {
                                continue;
                            }
                            return Optional.of(candidate);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
