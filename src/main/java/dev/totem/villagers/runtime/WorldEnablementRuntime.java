package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Objects;

/**
 * Applies the world-scoped rollout choice without scanning or loading unloaded
 * chunks. Existing villagers receive only an empty durable work state on first
 * enablement; their profession, vanilla inventory, offers, and merchant stock
 * are never imported as free work-backed stock.
 */
public final class WorldEnablementRuntime {
    private WorldEnablementRuntime() {
    }

    public record ApplyResult(int initialisedVillagers, int restoredOfferSets) {
        public ApplyResult {
            if (initialisedVillagers < 0 || restoredOfferSets < 0) {
                throw new IllegalArgumentException("World enablement counts cannot be negative");
            }
        }
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager villager
                    && WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode().enforcesWorkBackedTrading()) {
                ensureEmptyWorkState(level.getServer(), villager);
            }
        });
    }

    /**
     * Makes a mode change take effect for already-loaded villagers. This never
     * force-loads an entity or modifies a stored merchant-stock balance.
     */
    public static ApplyResult apply(MinecraftServer server, WorkBackedTradingMode mode) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(mode, "mode");
        int initialised = 0;
        int restored = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (mode.enforcesWorkBackedTrading()) {
                    if (ensureEmptyWorkState(server, villager)) {
                        initialised++;
                    }
                } else if (mode == WorkBackedTradingMode.VANILLA_ROLLBACK
                        && VillagerTradeStockAuthority.restoreVanillaRestocking(villager)) {
                    restored++;
                }
            }
        }
        return new ApplyResult(initialised, restored);
    }

    /** Returns true only when a missing legacy state became a zero-stock state. */
    private static boolean ensureEmptyWorkState(MinecraftServer server, Villager villager) {
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        VillagerWorkState state = states.get(villager.getUUID()).orElse(null);
        boolean created = state == null;
        if (created) {
            state = states.getOrCreate(villager.getUUID());
        }
        // Treat a pre-existing hungry worker as uninitialised exactly once when
        // this module starts owning it. The durable marker prevents later
        // starvation or a chunk reload from minting another food buffer.
        if (!state.nutritionBootstrapGranted()) {
            if (VillagerNutrition.isHungry(villager)) {
                VillagerNutrition.grantFoundingNutrition(villager);
            }
            states.put(state.withNutritionBootstrapGranted());
        }
        return created;
    }
}
