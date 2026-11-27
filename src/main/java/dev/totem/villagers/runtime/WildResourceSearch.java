package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.world.*;
import dev.totem.villagers.worldgen.GeneratedVillageSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** One rotating queue per level. Traversal visits loaded chunk indexes, never a cube of terrain. */
final class WildResourceSearch {
    static final int RADIUS = 48;
    private static final Map<ServerLevel, WildResourceSearch> LEVELS = new WeakHashMap<>();
    private final LinkedHashMap<UUID, Sweep> waiting = new LinkedHashMap<>();
    private final Map<BlockPos, Lease> claims = new HashMap<>();
    private record Visit(BlockPos site, long retryAt) {}
    private final Map<UUID, Visit> visits = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private long nextPath, lastTick = Long.MIN_VALUE;
    private record Route(BlockPos target, BlockPos from, net.minecraft.world.level.pathfinder.Path path, long expires) {}
    private final Map<UUID, Route> routes = new HashMap<>();

    /** Null means queued; false means a path attempt actually failed. */
    Boolean move(ServerLevel level, Villager worker, BlockPos target) {
        var saved = routes.remove(worker.getUUID());
        long now = level.getGameTime();
        if (saved != null && saved.expires >= now && saved.from.equals(worker.blockPosition()) && saved.target.equals(target))
            return worker.getNavigation().moveTo(saved.path, .5);
        if (!allowPath(now)) return null;
        return WorldWorkNavigation.pathToReach(level, worker, target, true)
                .map(path -> worker.getNavigation().moveTo(path, .5)).orElse(false);
    }

    private record Lease(UUID owner, long expires) {}
    record Match(BlockPos pos, WorkOrder order) {}
    private static final class Sweep {
        final BlockPos home;
        final List<ChunkPos> chunks;
        final List<WorkOrder> orders;
        int chunk;
        int radius = 16;
        Iterator<BlockPos> cells = Collections.emptyIterator();
        boolean recalled;
        Match found;
        long lastRequest;
        Sweep(BlockPos home, List<WorkOrder> orders) {
            this.home = home;
            this.orders = orders;
            var center = new ChunkPos(home.getX() >> 4, home.getZ() >> 4);
            chunks = ChunkPos.rangeClosed(center, 3).sorted(Comparator.comparingInt(p ->
                    Math.max(Math.abs(p.x()-center.x()), Math.abs(p.z()-center.z())))).toList();
        }
    }

    static WildResourceSearch get(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, ignored -> new WildResourceSearch());
    }

    static boolean permitted(ServerLevel level, Villager worker, WorkZoneRecord zone) {
        var assignment = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(level.getServer()).getAssignment(worker.getUUID());
        if (zone == null) return assignment.isEmpty() || assignment.get().automaticallyAssigned() && assignment.get().workZoneId().isEmpty();
        if (assignment.isEmpty() || !assignment.get().automaticallyAssigned()) return false;
        return GeneratedVillageSavedData.forServer(level.getServer()).ownsResourceZone(level.dimension().identifier().toString(), zone.id());
    }

    static BlockPos home(ServerLevel level, Villager worker) {
        return worker.getBrain().getMemory(MemoryModuleType.HOME).filter(h -> h.dimension().equals(level.dimension())
                && level.isLoaded(h.pos()) && level.getBlockState(h.pos()).is(net.minecraft.tags.BlockTags.BEDS))
                .map(h -> h.pos()).orElse(null);
    }

    Match request(ServerLevel level, Villager worker, List<WorkOrder> orders) {
        var home = home(level, worker);
        if (home == null || orders.isEmpty()) return null;
        long now = level.getGameTime();
        var id = worker.getUUID();
        if (cooldown.getOrDefault(id, 0L) > now) return null;
        var s = waiting.get(id);
        if (s == null || !s.home.equals(home) || !s.orders.equals(orders)) {
            s = new Sweep(home, orders);
            waiting.put(id, s);
        }
        s.lastRequest = now;
        if (s.found == null) return null;
        waiting.remove(id);
        return s.found;
    }

    boolean reserve(ServerLevel level, Villager worker, BlockPos pos) {
        var claim = claims.get(pos);
        long now = level.getGameTime();
        if (claim != null && claim.expires > now && !claim.owner.equals(worker.getUUID())) return false;
        claims.put(pos, new Lease(worker.getUUID(), now + 80));
        return true;
    }

    static boolean valid(ServerLevel level, Villager worker, BlockPos pos, WorkOrder order, VillagerWorkInventory stock) {
        if (!level.isLoaded(pos) || !order.allowedSources().contains(WorkSource.WORLD) || order.worldTargetTag().isBlank()) return false;
        var block = level.getBlockState(pos);
        if (!block.is(TagKey.create(Registries.BLOCK, Identifier.parse(order.worldTargetTag())))) return false;
        if (order.professionId().equals("totem:miner")) return NaturalStoneFaces.wildFace(level,worker,pos)
                && stock.snapshot().stream().anyMatch(s -> MinerWorldWorkAction.canMine(s, block));
        var tree = NaturalWorkTrees.at(level, pos);
        if (tree == null || !tree.log().asItem().equals(BuiltInRegistries.ITEM.getValue(Identifier.parse(order.output().itemId())))) return false;
        var seed = BuiltInRegistries.BLOCK.getValue(Identifier.parse(order.worldReplantBlockId()));
        return seed != Blocks.AIR && LumberjackWorldWorkAction.wildPlantingSite(level, worker, tree, seed) != null;
    }

    boolean allowPath(long tick) {
        if (tick < nextPath) return false;
        nextPath = tick + 5;
        return true;
    }

    long nextPathTick() { return nextPath; }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now == lastTick) return;
        lastTick = now;
        if (now % 80 == 0) {
            claims.values().removeIf(c -> c.expires <= now);
            routes.values().removeIf(r -> r.expires < now);
            cooldown.values().removeIf(end -> end <= now);
            visits.values().removeIf(visit -> visit.retryAt <= now);
            waiting.values().removeIf(s -> s.lastRequest + 80 < now);
        }
        if (waiting.isEmpty() || now < nextPath) return;
        var entry = waiting.pollFirstEntry();
        var s = entry.getValue();
        if (!(level.getEntity(entry.getKey()) instanceof Villager worker) || s.lastRequest + 80 < now
                || !LoadedVillagerCache.workTime(level, worker)) return;
        waiting.put(entry.getKey(), s);
        if (s.found != null) return;
        if (!s.recalled) {
            s.recalled = true;
            var known = new java.util.ArrayList<BlockPos>();
            for (var observation : VillagerWorkMemory.sites(worker)) {
                var site = observation.site();
                if (observation.animal().isPresent() || !site.dimension().equals(level.dimension())
                        || site.pos().distSqr(s.home) > RADIUS * RADIUS || !level.isLoaded(site.pos())) continue;
                var blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(site.pos()).getBlock()).toString();
                if (blockId.equals(observation.kind())) known.add(site.pos());
                else if (VillagerWorkMemory.seesSite(level, worker, site.pos())) VillagerWorkMemory.forget(worker, observation);
                else {
                    var oldBlock = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(observation.kind()));
                    var visit = visits.get(worker.getUUID());
                    if (oldBlock == null || visit != null && visit.site.equals(site.pos()) && now < visit.retryAt
                            || s.orders.stream().noneMatch(o -> oldBlock.defaultBlockState().is(TagKey.create(
                                    Registries.BLOCK, Identifier.parse(o.worldTargetTag()))))) continue;
                    if (!worker.getNavigation().isDone() || !allowPath(now)) continue;
                    visits.put(worker.getUUID(), new Visit(site.pos(), now + 200));
                    WorldWorkNavigation.moveToReach(level, worker, site.pos(), .5);
                }
            }
            s.cells = known.iterator();
        }
        if (!s.cells.hasNext()) {
            if (s.chunk == s.chunks.size()) {
                if (s.radius == RADIUS) {
                    waiting.remove(entry.getKey());
                    cooldown.put(entry.getKey(), now + 120);
                    return;
                }
                s.radius += 16;
                s.chunk = 0;
            }
            var at = s.chunks.get(s.chunk++);
            // Skip chunks outside this ring without visiting any blocks or loading terrain.
            int dx = Math.max(Math.max(at.getMinBlockX() - s.home.getX(), s.home.getX() - at.getMaxBlockX()), 0);
            int dz = Math.max(Math.max(at.getMinBlockZ() - s.home.getZ(), s.home.getZ() - at.getMaxBlockZ()), 0);
            if (dx * dx + dz * dz > s.radius * s.radius) return;
            var chunk = level.getChunkSource().getChunkNow(at.x(), at.z());
            if (chunk == null) return;
            s.cells = s.orders.getFirst().professionId().equals("totem:miner") ? NaturalStoneFaces.indexed(chunk).iterator()
                    : NaturalWorkTrees.trees(chunk).stream().map(NaturalWorkTrees.Tree::base).iterator();
        }
        var stock = dev.totem.villagers.inventory.VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(worker.getUUID());
        for (int i = 0; i < 8 && s.cells.hasNext(); i++) {
            var pos = s.cells.next();
            double distance = pos.distSqr(s.home);
            int previousRadius = s.radius - 16;
            if (s.chunk != 0 && (distance > s.radius * s.radius || previousRadius > 0 && distance <= previousRadius * previousRadius)
                    || claims.containsKey(pos)) continue;
            var order = s.orders.stream().filter(o -> valid(level, worker, pos, o, stock)).findFirst().orElse(null);
            if (order == null) continue;
            VillagerWorkMemory.observeBlock(level, worker, pos);
            if (!allowPath(now)) return;
            if (WorldWorkNavigation.atWildTarget(level, worker, pos)) {
                if (reserve(level, worker, pos)) s.found = new Match(pos, order);
            } else {
                var path = WorldWorkNavigation.pathToReach(level, worker, pos, true);
                if (path.isPresent() && reserve(level, worker, pos)) {
                    s.found = new Match(pos, order);
                    routes.put(worker.getUUID(), new Route(pos, worker.blockPosition(), path.get(), now + 40));
                }
            }
            return;
        }
    }
}
