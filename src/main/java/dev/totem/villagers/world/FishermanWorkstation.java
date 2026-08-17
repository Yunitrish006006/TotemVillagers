package dev.totem.villagers.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.Optional;

/**
 * Resolves the two physical parts of a vanilla Fisherman's workplace.
 *
 * <p>The Barrel remains the career POI, so ordinary village AI can claim and
 * retain the profession. A nearby Campfire is an additional production
 * requirement for cooking catches. Direct Campfire memories from older
 * Totem Villagers worlds remain accepted as a compatibility path.</p>
 */
public final class FishermanWorkstation {
    public static final int CAMPFIRE_RADIUS = 8;
    private static final int CAMPFIRE_VERTICAL_RADIUS = 4;

    private FishermanWorkstation() {
    }

    public static Optional<BlockPos> campfireForJobSite(ServerLevel level, BlockPos jobSite) {
        if (!level.isLoaded(jobSite)) {
            return Optional.empty();
        }
        if (isCampfire(level, jobSite)) {
            return Optional.of(jobSite.immutable());
        }
        if (!level.getBlockState(jobSite).is(Blocks.BARREL)) {
            return Optional.empty();
        }
        return BlockPos.betweenClosedStream(
                        jobSite.offset(-CAMPFIRE_RADIUS, -CAMPFIRE_VERTICAL_RADIUS, -CAMPFIRE_RADIUS),
                        jobSite.offset(CAMPFIRE_RADIUS, CAMPFIRE_VERTICAL_RADIUS, CAMPFIRE_RADIUS))
                .filter(level::isLoaded)
                .filter(position -> isCampfire(level, position))
                .min(Comparator.comparingDouble(position -> position.distSqr(jobSite)))
                .map(BlockPos::immutable);
    }

    public static boolean isSupportedJobSite(ServerLevel level, BlockPos jobSite) {
        return campfireForJobSite(level, jobSite).isPresent();
    }

    private static boolean isCampfire(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).is(Blocks.CAMPFIRE)
                || level.getBlockState(position).is(Blocks.SOUL_CAMPFIRE);
    }
}
