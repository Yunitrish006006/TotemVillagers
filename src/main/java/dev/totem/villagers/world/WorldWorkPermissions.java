package dev.totem.villagers.world;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Claim/protection integrations can veto autonomous work. Vanilla interaction
 * permission is always checked first; this hook is intentionally deny-capable
 * before any role-specific code is allowed to mutate a target.
 */
public final class WorldWorkPermissions {
    public static final Event<Check> CHECK = EventFactory.createArrayBacked(Check.class, callbacks ->
            (level, villager, target) -> {
                for (Check callback : callbacks) {
                    if (!callback.mayWork(level, villager, target)) {
                        return false;
                    }
                }
                return true;
            });

    private WorldWorkPermissions() {
    }

    public static boolean mayWork(ServerLevel level, Villager villager, BlockPos target) {
        return level.isLoaded(target)
                && level.mayInteract(villager, target)
                && CHECK.invoker().mayWork(level, villager, target);
    }

    /** Household gathering and construction leave assigned plots and operator zones to their workers. */
    public static boolean mayUseHouseholdLand(ServerLevel level, Villager villager, BlockPos target) {
        return mayWork(level, villager, target)
                && !dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).heldByAnother(level, target, villager)
                && !dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(level.getServer())
                        .containsZoneCell(level.dimension().identifier().toString(), target);
    }

    @FunctionalInterface
    public interface Check {
        boolean mayWork(ServerLevel level, Villager villager, BlockPos target);
    }
}
