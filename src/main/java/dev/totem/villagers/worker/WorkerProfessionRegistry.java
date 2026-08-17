package dev.totem.villagers.worker;

import dev.totem.villagers.work.WorkSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Initial role registry used by later server scheduling and profession registration. */
public final class WorkerProfessionRegistry {
    public static final WorkerProfession MINER = new WorkerProfession(
            "totem:miner", "profession.totem.miner", false, Set.of(WorkSource.WORLD, WorkSource.WORKSHOP));
    public static final WorkerProfession LUMBERJACK = new WorkerProfession(
            "totem:lumberjack", "profession.totem.lumberjack", false, Set.of(WorkSource.WORLD, WorkSource.WORKSHOP));
    public static final WorkerProfession BUILDER = new WorkerProfession(
            "totem:builder", "profession.totem.builder", false, Set.of(WorkSource.WORLD));
    public static final WorkerProfession GUARD = new WorkerProfession(
            "totem:guard", "profession.totem.guard", false, Set.of(WorkSource.WORKSHOP));
    public static final WorkerProfession SHEPHERD = new WorkerProfession(
            "minecraft:shepherd", "entity.minecraft.villager.shepherd", true, Set.of(WorkSource.WORLD, WorkSource.WORKSHOP));

    private static final Map<String, WorkerProfession> ROLES = index(MINER, LUMBERJACK, BUILDER, GUARD, SHEPHERD);

    private WorkerProfessionRegistry() {
    }

    public static WorkerProfession require(String id) {
        WorkerProfession profession = ROLES.get(id);
        if (profession == null) {
            throw new IllegalArgumentException("Unknown worker profession: " + id);
        }
        return profession;
    }

    public static Map<String, WorkerProfession> snapshot() {
        return ROLES;
    }

    private static Map<String, WorkerProfession> index(WorkerProfession... professions) {
        Map<String, WorkerProfession> result = new LinkedHashMap<>();
        for (WorkerProfession profession : professions) {
            if (result.putIfAbsent(profession.id(), profession) != null) {
                throw new IllegalStateException("Duplicate worker profession: " + profession.id());
            }
        }
        return Map.copyOf(result);
    }
}
