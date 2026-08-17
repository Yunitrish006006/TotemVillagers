package dev.totem.villagers.runtime;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * One loaded-villager query per level and server tick.  Minecraft's unbounded
 * entity query walks every loaded entity section, so independently repeating
 * it from each profession runtime makes unrelated animals appear to freeze in
 * entity-heavy worlds.
 */
public final class LoadedVillagerCache {
    private static final Map<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private LoadedVillagerCache() {
    }

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager) {
                invalidate(level);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (SNAPSHOTS) {
                SNAPSHOTS.clear();
            }
        });
    }

    public static List<Villager> loaded(ServerLevel level) {
        int serverTick = level.getServer().getTickCount();
        synchronized (SNAPSHOTS) {
            Snapshot cached = SNAPSHOTS.get(level);
            if (cached != null && cached.serverTick() == serverTick) {
                return cached.villagers();
            }
            List<Villager> villagers = level.getEntities(
                            EntityTypeTest.forClass(Villager.class), Villager::isAlive)
                    .stream().map(Villager.class::cast).toList();
            SNAPSHOTS.put(level, new Snapshot(serverTick, villagers));
            return villagers;
        }
    }

    static void invalidate(ServerLevel level) {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.remove(level);
        }
    }

    private record Snapshot(int serverTick, List<Villager> villagers) {
        private Snapshot {
            villagers = List.copyOf(villagers);
        }
    }
}
