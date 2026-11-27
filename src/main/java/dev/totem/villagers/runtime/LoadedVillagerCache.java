package dev.totem.villagers.runtime;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityTypeTest;

import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.schedule.Activity;

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

    private static final Map<Villager, net.minecraft.world.level.pathfinder.Path> WORK_PATHS = new WeakHashMap<>();
    private static final Map<Villager, net.minecraft.world.level.pathfinder.Path> BEFORE_WORK = new WeakHashMap<>();

    private LoadedVillagerCache() {
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            BEFORE_WORK.clear();
            if (dev.totem.villagers.config.WorkBackedTradingSettingsSavedData.forServer(server).settings().mode()
                    != dev.totem.villagers.config.WorkBackedTradingMode.ENFORCED) {
                WORK_PATHS.clear();
                return;
            }
            for (var level : server.getAllLevels()) {
                for (var v : loaded(level)) {
                    var navigation = v.getNavigation();
                    if (workTime(level, v)) BEFORE_WORK.put(v, navigation.getPath());
                    else {
                        var workPath = WORK_PATHS.remove(v);
                        if (workPath != null && navigation.getPath() == workPath) navigation.stop();
                    }
                }
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Villager) {
                invalidate(level);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof Villager) invalidate(level);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (SNAPSHOTS) {
                SNAPSHOTS.clear();
                WORK_PATHS.clear();
                BEFORE_WORK.clear();
            }
        });
    }

    public static void registerAfterWork() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            BEFORE_WORK.forEach((v, previous) -> {
                var path = v.getNavigation().getPath();
                if (path != previous) {
                    if (path == null) WORK_PATHS.remove(v);
                    else WORK_PATHS.put(v, path);
                }
            });
            BEFORE_WORK.clear();
        });
    }

    public static List<Villager> loaded(ServerLevel level) {
        int serverTick = level.getServer().getTickCount();
        synchronized (SNAPSHOTS) {
            Snapshot cached = SNAPSHOTS.get(level);
            if (cached != null && cached.serverTick == serverTick) {
                return cached.villagers;
            }
            List<Villager> villagers = level.getEntities(
                            EntityTypeTest.forClass(Villager.class), Villager::isAlive)
                    .stream().map(Villager.class::cast).toList();
            SNAPSHOTS.put(level, new Snapshot(serverTick, villagers));
            return villagers;
        }
    }

    static boolean workTime(ServerLevel level, Villager worker) {
        var brain = worker.getBrain();
        return !worker.isBaby() && !worker.isTrading() && !worker.isSleeping()
                && !brain.isActive(Activity.PANIC) && !brain.isActive(Activity.HIDE)
                && !brain.isActive(Activity.RAID) && !brain.isActive(Activity.PRE_RAID)
                && !brain.isActive(Activity.REST) && !brain.isActive(Activity.MEET)
                && level.environmentAttributes().getValue(EnvironmentAttributes.VILLAGER_ACTIVITY,
                        worker.position()) == Activity.WORK;
    }

    static List<Villager> workers(ServerLevel level) {
        var all = loaded(level);
        var snapshot = SNAPSHOTS.get(level);
        if (snapshot.workers == null) {
            snapshot.workers = all.stream().filter(worker -> workTime(level, worker)).toList();
        }
        return snapshot.workers;
    }

    static void invalidate(ServerLevel level) {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.remove(level);
        }
    }

    private static final class Snapshot {
        final int serverTick;
        final List<Villager> villagers;
        List<Villager> workers;

        Snapshot(int serverTick, List<Villager> villagers) {
            this.serverTick = serverTick;
            this.villagers = List.copyOf(villagers);
        }
    }
}
