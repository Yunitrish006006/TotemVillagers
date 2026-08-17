package dev.totem.villagers.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.UUID;

/** Staggers expensive discovery and path retries instead of bursting on every server tick. */
public final class VillagerRuntimeBudget {
    public static final int IDLE_SCAN_INTERVAL_TICKS = 20;
    public static final int NAVIGATION_RETRY_INTERVAL_TICKS = 10;

    private VillagerRuntimeBudget() {
    }

    public static boolean dueForIdleScan(ServerLevel level, Villager villager) {
        return due(level.getGameTime(), villager.getUUID(), IDLE_SCAN_INTERVAL_TICKS);
    }

    public static boolean dueForNavigationRetry(ServerLevel level, Villager villager) {
        return villager.getNavigation().isDone()
                && due(level.getGameTime(), villager.getUUID(), NAVIGATION_RETRY_INTERVAL_TICKS);
    }

    /** Pure overload used by deterministic budget tests. */
    public static boolean due(long gameTime, UUID villagerId, int intervalTicks) {
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("intervalTicks must be positive");
        }
        return Math.floorMod(gameTime + villagerId.hashCode(), intervalTicks) == 0L;
    }
}
