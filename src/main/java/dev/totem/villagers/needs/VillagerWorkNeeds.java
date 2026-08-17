package dev.totem.villagers.needs;

import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkSource;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Optional;

/** Makes hunger a hard, cancellable prerequisite for Totem-managed production. */
public final class VillagerWorkNeeds {
    private static final String NEEDS_FOOD_ORDER_ID = "totem:needs_food";
    private static final String NEEDS_FOOD_REASON = "needs food";

    private VillagerWorkNeeds() {
    }

    public static boolean canWork(Villager villager) {
        return canWork(VillagerNutrition.foodLevel(villager));
    }

    public static boolean canWork(int foodLevel) {
        return foodLevel > VillagerNutrition.HUNGRY_AT_OR_BELOW;
    }

    /** Cancels an in-flight job before it can commit stock after hunger begins. */
    public static VillagerWorkState pauseForHunger(VillagerWorkState state) {
        WorkSource source = state.activeWork().map(active -> active.source()).orElse(WorkSource.WORKSHOP);
        TradeDiagnostic diagnostic = new TradeDiagnostic(NEEDS_FOOD_ORDER_ID, source, 0, NEEDS_FOOD_REASON);
        if (state.activeWork().isEmpty() && state.diagnostic().filter(diagnostic::equals).isPresent()) {
            return state;
        }
        return state.withActiveWork(Optional.empty(), Optional.of(diagnostic));
    }
}
