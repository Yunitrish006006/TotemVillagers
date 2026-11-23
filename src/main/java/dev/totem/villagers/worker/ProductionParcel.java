package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.world.WorldWorkPermissions;
import dev.totem.villagers.world.FishermanWorkstation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Exclusive field and fishing parcels. Work cells and the household bed have independent lifetimes. */
public final class ProductionParcel extends SavedData {
    public static final TagKey<Block> FISHING_WATER = TagKey.create(Registries.BLOCK, Identifier.parse("totem:fishing_water"));
    private static final int MAX_CELLS = 64;
    public record Claim(UUID worker, String role, GlobalPos station, Optional<GlobalPos> home, List<BlockPos> cells, Optional<BlockPos> campfire) {
        static final Codec<Claim> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("worker").forGetter(Claim::worker),
                Codec.STRING.fieldOf("role").forGetter(Claim::role),
                GlobalPos.CODEC.fieldOf("station").forGetter(Claim::station),
                GlobalPos.CODEC.optionalFieldOf("home").forGetter(Claim::home),
                BlockPos.CODEC.listOf(1, MAX_CELLS).fieldOf("cells").forGetter(Claim::cells),
                BlockPos.CODEC.optionalFieldOf("campfire").forGetter(Claim::campfire)
        ).apply(i, Claim::new));
    }

    public static final Codec<ProductionParcel> CODEC = Claim.CODEC.listOf().xmap(
            ProductionParcel::new, data -> List.copyOf(data.byWorker.values()));
    private static final SavedDataType<ProductionParcel> TYPE = new SavedDataType<>(
            Identifier.parse("totem-villagers:production_parcels"), () -> new ProductionParcel(List.of()), CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final Map<UUID, Claim> byWorker = new HashMap<>();
    private final Map<GlobalPos, UUID> byCell = new HashMap<>();

    private ProductionParcel(List<Claim> saved) {
        for (Claim claim : saved) {
            if (!byWorker.containsKey(claim.worker()) && claim.cells().stream()
                    .noneMatch(pos -> byCell.containsKey(GlobalPos.of(claim.station().dimension(), pos)))) index(claim);
        }
    }

    public static ProductionParcel get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private void index(Claim claim) {
        byWorker.put(claim.worker(), claim);
        for (BlockPos cell : claim.cells()) byCell.put(GlobalPos.of(claim.station().dimension(), cell), claim.worker());
    }

    public void release(UUID worker) {
        var old = byWorker.remove(worker);
        if (old == null) return;
        for (BlockPos cell : old.cells()) byCell.remove(GlobalPos.of(old.station().dimension(), cell), worker);
        setDirty();
    }

    public static final int HOUSEHOLD_FIELD_SIZE = 6;

    /** Extend only a household's own contiguous, freshly planted field. */
    public boolean recordStarter(ServerLevel level, Villager worker, GlobalPos home, BlockPos soil) {
        if (!home.dimension().equals(level.dimension())
                || worker.getBrain().getMemory(MemoryModuleType.HOME).filter(home::equals).isEmpty()
                || !dev.totem.villagers.runtime.StarterWheatField.canTend(level, worker)
                || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, soil)
                || !WorldWorkPermissions.mayUseHouseholdLand(level, worker, soil.above())
                || !level.getBlockState(soil).is(Blocks.FARMLAND) || !level.getBlockState(soil.above()).is(Blocks.WHEAT))
            return false;
        Claim old = byWorker.get(worker.getUUID());
        var cells = new java.util.ArrayList<BlockPos>();
        if (old != null) {
            if (!old.role().equals("starter") || !old.station().equals(home)) return false;
            if (old.cells().contains(soil)) return true;
            if (old.cells().size() >= HOUSEHOLD_FIELD_SIZE || old.cells().stream()
                    .noneMatch(cell -> cell.getY() == soil.getY() && cell.distManhattan(soil) == 1)) return false;
            cells.addAll(old.cells());
        }
        cells.add(soil.immutable());
        index(new Claim(worker.getUUID(), "starter", home, Optional.of(home), List.copyOf(cells), Optional.empty()));
        setDirty();
        return true;
    }

    public boolean heldByAnother(ServerLevel level, BlockPos pos, Villager worker) {
        UUID caretaker = byCell.get(GlobalPos.of(level.dimension(), pos));
        return caretaker != null && !caretaker.equals(worker.getUUID());
    }

    public Optional<Claim> pastureAt(ServerLevel level, BlockPos ground) {
        var owner = byCell.get(GlobalPos.of(level.dimension(), ground));
        return claim(owner).filter(c -> c.role().equals("minecraft:shepherd"));
    }

    public Optional<Claim> claim(UUID worker) {
        return Optional.ofNullable(byWorker.get(worker));
    }

    public void reconcile(ServerLevel level, Villager worker) {
        Claim old = byWorker.get(worker.getUUID());
        if (old == null) return;
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(worker.getVillagerData().profession().value());
        var brain = worker.getBrain();
        if (old.role().equals("starter")) {
            if (!brain.getMemory(MemoryModuleType.HOME).equals(old.home())
                    || !old.station().dimension().equals(level.dimension())
                    || !dev.totem.villagers.runtime.StarterWheatField.canTend(level, worker)) {
                release(worker.getUUID());
            } else {
                var remaining = old.cells().stream().filter(pos -> !level.isLoaded(pos)
                        || matchesCell(level, pos, true) && (level.getBlockState(pos.above()).is(Blocks.WHEAT) || level.getBlockState(pos.above()).isAir())).toList();
                if (remaining.size() != old.cells().size()) {
                    release(worker.getUUID());
                    if (!remaining.isEmpty()) {
                        var connected = new LinkedHashSet<BlockPos>();
                        var pending = new ArrayDeque<BlockPos>();
                        pending.add(remaining.getFirst());
                        while (!pending.isEmpty()) {
                            var next = pending.removeFirst();
                            if (!remaining.contains(next) || !connected.add(next)) continue;
                            for (var dir : Direction.Plane.HORIZONTAL) pending.addLast(next.relative(dir));
                        }
                        index(new Claim(old.worker(), old.role(), old.station(), old.home(), List.copyOf(connected), old.campfire()));
                    }
                }
            }
            return;
        }
        if (!worker.isAlive() || !old.role().equals(String.valueOf(id)) || !old.station().dimension().equals(level.dimension())
                || brain.getMemory(MemoryModuleType.JOB_SITE).filter(old.station()::equals).isEmpty()
                || !brain.getMemory(MemoryModuleType.HOME).equals(old.home())) {
            release(worker.getUUID());
            return;
        }
        BlockPos station = old.station().pos();
        if (old.role().equals("minecraft:shepherd")) {
            if (old.home().isEmpty() || old.home().filter(h -> level.isLoaded(h.pos()) && !level.getBlockState(h.pos()).is(net.minecraft.tags.BlockTags.BEDS)).isPresent()
                    || level.isLoaded(station) && !level.getBlockState(station).is(Blocks.LOOM)
                    || old.cells().stream().allMatch(cell -> level.isLoaded(cell) && !grazingGround(level, cell)))
                release(worker.getUUID());
            return;
        }
        boolean field = old.role().equals("minecraft:farmer");
        if (level.isLoaded(station) && !(field ? level.getBlockState(station).is(Blocks.COMPOSTER)
                : usableFishingStation(level, old))) {
            release(worker.getUUID());
            return;
        }
        if (old.cells().stream().allMatch(pos -> level.isLoaded(pos)
                && !matchesCell(level, pos, field))) release(worker.getUUID());
    }

    private static boolean grazingGround(ServerLevel level, BlockPos pos) {
        var soil = level.getBlockState(pos);
        var feet = pos.above();
        return (soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.DIRT))
                && level.getFluidState(feet).isEmpty() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
    }

    public boolean mayTendSheep(ServerLevel level, Villager worker, BlockPos feet) {
        var claim = byWorker.get(worker.getUUID());
        return (claim == null || !claim.role().equals("minecraft:shepherd") || claim.station().dimension().equals(level.dimension())
                && claim.cells().contains(feet.below()) && grazingGround(level, feet.below()))
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, feet.below())
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, feet);
    }

    public boolean canWorkPasture(ServerLevel level, Villager worker, BlockPos loom, BlockPos feet) {
        var claim = byWorker.get(worker.getUUID());
        if (claim == null) return canStartPasture(level, worker, loom, feet.below());
        return claim.role().equals("minecraft:shepherd") && claim.station().equals(GlobalPos.of(level.dimension(), loom))
                && level.isLoaded(loom) && level.getBlockState(loom).is(Blocks.LOOM)
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, loom)
                && worker.getBrain().getMemory(MemoryModuleType.HOME).equals(claim.home())
                && claim.home().filter(h -> h.dimension().equals(level.dimension()) && level.isLoaded(h.pos())
                        && level.getBlockState(h.pos()).is(net.minecraft.tags.BlockTags.BEDS)).isPresent()
                && mayTendSheep(level, worker, feet);
    }

    public boolean canStartPasture(ServerLevel level, Villager worker, BlockPos loom, BlockPos ground) {
        var home = worker.getBrain().getMemory(MemoryModuleType.HOME);
        return home.isPresent() && home.get().dimension().equals(level.dimension()) && level.isLoaded(home.get().pos())
                && level.getBlockState(home.get().pos()).is(net.minecraft.tags.BlockTags.BEDS)
                && level.isLoaded(loom) && level.getBlockState(loom).is(Blocks.LOOM)
                && WorldWorkPermissions.mayUseHouseholdLand(level, worker, loom)
                && ground.distSqr(loom) <= 32 * 32 && !byWorker.containsKey(worker.getUUID())
                && level.isLoaded(ground) && grazingGround(level, ground) && mayTendSheep(level, worker, ground.above());
    }

    /** Reserve up to a five by five patch of connected grazing ground around the first sheep. */
    public boolean reservePasture(ServerLevel level, Villager worker, BlockPos loom, BlockPos ground) {
        if (!canStartPasture(level, worker, loom, ground)) return false;
        var home = worker.getBrain().getMemory(MemoryModuleType.HOME);
        var seen = new HashSet<BlockPos>();
        var cells = new LinkedHashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        pending.add(ground.immutable());
        while (!pending.isEmpty()) {
            var pos = pending.removeFirst();
            if (!seen.add(pos) || Math.abs(pos.getX() - ground.getX()) > 2 || Math.abs(pos.getZ() - ground.getZ()) > 2
                    || !level.isLoaded(pos) || !grazingGround(level, pos) || !mayTendSheep(level, worker, pos.above())) continue;
            cells.add(pos);
            for (var dir : Direction.Plane.HORIZONTAL) pending.addLast(pos.relative(dir));
        }
        if (cells.isEmpty()) return false;
        index(new Claim(worker.getUUID(), "minecraft:shepherd", GlobalPos.of(level.dimension(), loom),
                home, List.copyOf(cells), Optional.empty()));
        setDirty();
        return true;
    }

    private boolean usableFishingStation(ServerLevel level, Claim claim) {
        var station = claim.station().pos();
        var state = level.getBlockState(station);
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return true;
        if (!state.is(Blocks.BARREL)) return false;
        if (claim.campfire().isPresent()) {
            BlockPos fire = claim.campfire().get();
            if (!level.isLoaded(fire) || level.getBlockState(fire).is(Blocks.CAMPFIRE)
                    || level.getBlockState(fire).is(Blocks.SOUL_CAMPFIRE)) return true;
        }
        var replacement = FishermanWorkstation.campfireForJobSite(level, station);
        if (replacement.isEmpty()) return false;
        byWorker.put(claim.worker(), new Claim(claim.worker(), claim.role(), claim.station(), claim.home(), claim.cells(), replacement));
        setDirty();
        return true;
    }

    private static boolean matchesCell(ServerLevel level, BlockPos pos, boolean field) {
        return field ? level.getBlockState(pos).is(Blocks.FARMLAND) : level.getBlockState(pos).is(FISHING_WATER);
    }

    public static boolean availableCell(ServerLevel level, Villager worker, BlockPos pos, boolean field) {
        return level.isLoaded(pos) && matchesCell(level, pos, field) && WorldWorkPermissions.mayWork(level, worker, pos)
                && !WorkerAssignmentSavedData.forServer(level.getServer()).containsZoneCell(level.dimension().identifier().toString(), pos);
    }

    public boolean acquire(ServerLevel level, Villager worker, GlobalPos station, BlockPos target, boolean field) {
        String role = field ? "minecraft:farmer" : "minecraft:fisherman";
        if (!worker.isAlive() || worker.isBaby()
                || !Identifier.parse(role).equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(worker.getVillagerData().profession().value()))
                || worker.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(station::equals).isEmpty()) return false;
        reconcile(level, worker);
        return reserve(level, worker, station, target, field);
    }

    /** The caller holds (or is committing) the exact job-site ticket on the server thread. */
    public boolean reserve(ServerLevel level, Villager worker, GlobalPos station, BlockPos target, boolean field) {
        if (!station.dimension().equals(level.dimension()) || !level.isLoaded(station.pos())
                || !availableCell(level, worker, target, field)) return false;
        var dimension = level.dimension();
        var bounds = WorkerAssignmentSavedData.forServer(level.getServer());
        Claim prior = byWorker.get(worker.getUUID());
        if (prior != null && !prior.role().equals("starter")) return prior.cells().contains(target);
        if (heldByAnother(level, target, worker)) return false;

        var pending = new ArrayDeque<BlockPos>();
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> cells = new LinkedHashSet<>();
        pending.add(target.immutable());
        while (!pending.isEmpty()) {
            BlockPos pos = pending.removeFirst();
            if (!seen.add(pos) || !level.isLoaded(pos) || !matchesCell(level, pos, field)
                    || pos.distSqr(station.pos()) > 24 * 24
                    || !WorldWorkPermissions.mayWork(level, worker, pos)
                    || bounds.containsZoneCell(dimension.identifier().toString(), pos)) continue;
            // A shoreline is a local stretch of water; an ocean can support several fishing stations.
            if (!field && (pos.distSqr(target) > 9 || !level.getBlockState(pos.above()).isAir())) continue;
            // A neighbouring household's boundary ends the flood-fill in this direction.
            if (heldByAnother(level, pos, worker)) continue;
            if (cells.size() == MAX_CELLS) return false;
            cells.add(pos);
            for (Direction dir : Direction.Plane.HORIZONTAL) pending.addLast(pos.relative(dir));
        }
        if (!cells.contains(target) || prior != null && !cells.containsAll(prior.cells())) return false;
        var fire = field ? Optional.<BlockPos>empty() : FishermanWorkstation.campfireForJobSite(level, station.pos());
        if (!field && fire.isEmpty() || field && !level.getBlockState(station.pos()).is(Blocks.COMPOSTER)) return false;
        if (prior != null) release(worker.getUUID());
        index(new Claim(worker.getUUID(), field ? "minecraft:farmer" : "minecraft:fisherman", station,
                worker.getBrain().getMemory(MemoryModuleType.HOME), List.copyOf(cells), fire));
        setDirty();
        return true;
    }
}
