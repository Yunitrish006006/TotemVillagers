package dev.totem.villagers.world;

import java.util.Collections;
import java.util.Iterator;
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
        return scan(level, villager, zone, eligibleTargets, maximumChecks, actionTarget,
                new Cursor(zone, villager.blockPosition()));
    }

    public Optional<BlockPos> scan(ServerLevel level, Villager worker, WorkZone zone, TagKey<Block> targets,
                                   int budget, Predicate<BlockPos> valid, Cursor cursor) {
        if (!cursor.zone.equals(zone) || budget < 1 || budget > 512)
            throw new IllegalArgumentException("Cursor zone or scan budget does not match");
        if (!zone.dimensionId().equals(level.dimension().identifier().toString())) return Optional.empty();
        for (int i = 0; i < budget && cursor.hasNext(); i++) {
            BlockPos candidate = cursor.positions.next();
            if (level.isLoaded(candidate) && level.getBlockState(candidate).is(targets)
                    && WorldWorkPermissions.mayWork(level, worker, candidate) && valid.test(candidate)
                    && WorldWorkNavigation.canReach(level, worker, candidate)) return Optional.of(candidate.immutable());
        }
        return Optional.empty();
    }

    /** Each layer visits expanding square perimeters, clipped to the assigned rectangle. */
    public static final class Cursor {
        private final WorkZone zone;
        private final int cx, cy, cz, lastRadius, lastLayer;
        private int layer, radius, side;
        private Iterator<BlockPos> positions = Collections.emptyIterator();

        public Cursor(WorkZone zone, BlockPos origin) {
            this.zone = zone;
            var lo = zone.minimum();
            var hi = zone.maximum();
            cx = Math.clamp(origin.getX(), lo.x(), hi.x());
            cy = Math.clamp(origin.getY(), lo.y(), hi.y());
            cz = Math.clamp(origin.getZ(), lo.z(), hi.z());
            lastRadius = Math.max(Math.max(cx-lo.x(), hi.x()-cx), Math.max(cz-lo.z(), hi.z()-cz));
            lastLayer = 2 * Math.max(cy-lo.y(), hi.y()-cy);
        }

        public boolean hasNext() {
            while (!positions.hasNext() && layer <= lastLayer) {
                int y = cy + ((layer & 1) == 0 ? layer/2 : -(layer+1)/2);
                if (y < zone.minimum().y() || y > zone.maximum().y()) {
                    layer++;
                    continue;
                }
                int r = radius;
                if (r == 0) positions = Collections.singleton(new BlockPos(cx, y, cz)).iterator();
                else switch (side) {
                    case 0 -> edge(cx-r, cz-r, cx-r, cz+r, y);
                    case 1 -> edge(cx+r, cz-r, cx+r, cz+r, y);
                    case 2 -> edge(cx-r+1, cz-r, cx+r-1, cz-r, y);
                    case 3 -> edge(cx-r+1, cz+r, cx+r-1, cz+r, y);
                }
                if (r == 0 || ++side == 4) {
                    side = 0;
                    if (++radius > lastRadius) {
                        radius = 0;
                        layer++;
                    }
                }
            }
            return positions.hasNext();
        }

        private void edge(int x1, int z1, int x2, int z2, int y) {
            int left = Math.max(x1, zone.minimum().x()), right = Math.min(x2, zone.maximum().x());
            int top = Math.max(z1, zone.minimum().z()), bottom = Math.min(z2, zone.maximum().z());
            if (left <= right && top <= bottom)
                positions = BlockPos.betweenClosed(left, y, top, right, y, bottom).iterator();
        }
    }
}
