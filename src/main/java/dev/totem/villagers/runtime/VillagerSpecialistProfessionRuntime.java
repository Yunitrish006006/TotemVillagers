package dev.totem.villagers.runtime;

import dev.totem.villagers.worker.TotemVillagerProfessions;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps operator-assigned Totem careers authoritative over vanilla's
 * ResetProfession behaviour. Totem professions deliberately match no vanilla
 * POI, so an ordinary mobile villager would otherwise become unemployed again
 * on its next AI tick despite still having a durable specialist assignment.
 */
public final class VillagerSpecialistProfessionRuntime {
    private static final int RECONCILE_INTERVAL_TICKS = 20;
    private static final Set<String> SPECIALIST_ROLES = Set.of(
            TotemVillagerProfessions.MINER_ID.toString(),
            TotemVillagerProfessions.LUMBERJACK_ID.toString(),
            TotemVillagerProfessions.BUILDER_ID.toString(),
            TotemVillagerProfessions.GUARD_ID.toString()
    );

    private VillagerSpecialistProfessionRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerSpecialistProfessionRuntime::tick);
    }

    /** Public only for deterministic mobile-villager GameTests. */
    public static void reconcileForGameTest(MinecraftServer server) {
        reconcile(server);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % RECONCILE_INTERVAL_TICKS == 0) {
            reconcile(server);
        }
    }

    private static void reconcile(MinecraftServer server) {
        Map<UUID, WorkerAssignment> assignments =
                WorkerAssignmentSavedData.forServer(server).assignmentSnapshot();
        if (assignments.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                WorkerAssignment assignment = assignments.get(villager.getUUID());
                if (assignment == null || !SPECIALIST_ROLES.contains(assignment.roleId())
                        || assignment.roleId().equals(professionId(villager))) {
                    continue;
                }
                Identifier roleId = Identifier.tryParse(assignment.roleId());
                VillagerProfession profession = roleId == null
                        ? null : BuiltInRegistries.VILLAGER_PROFESSION.getValue(roleId);
                if (profession != null) {
                    villager.setVillagerData(villager.getVillagerData().withProfession(
                            BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
                }
            }
        }
    }

    private static String professionId(Villager villager) {
        Identifier id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }
}
