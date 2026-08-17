package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZoneRecord;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.world.BoundedZoneTargetFinder;
import dev.totem.villagers.world.LumberjackWorldWorkAction;
import dev.totem.villagers.world.MinerWorldWorkAction;
import dev.totem.villagers.world.MinerFurnaceWorkstation;
import dev.totem.villagers.world.WorldWorkPermissions;
import dev.totem.villagers.world.WorldWorkNavigation;
import dev.totem.villagers.world.WorldWorkZoneEligibility;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Optional;

/** Bounded autonomous world work for the initial Miner and Lumberjack specialist roles. */
public final class VillagerWorldWorkRuntime {
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final WorldWorkZoneEligibility ZONES = new WorldWorkZoneEligibility();
    private static final BoundedZoneTargetFinder TARGETS = new BoundedZoneTargetFinder();
    private static final MinerWorldWorkAction MINER_ACTION = new MinerWorldWorkAction();
    private static final LumberjackWorldWorkAction LUMBERJACK_ACTION = new LumberjackWorldWorkAction();

    private VillagerWorldWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerWorldWorkRuntime::tick);
    }

    /** Public only for deterministic GameTests of selection, navigation and commit. */
    public static void tickForGameTest(MinecraftServer server) {
        run(server, true);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        if (catalog.snapshot().values().stream().noneMatch(order -> order.allowedSources().contains(WorkSource.WORLD))) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                String professionId = professionId(villager);
                if ("totem:miner".equals(professionId) || "totem:lumberjack".equals(professionId)) {
                    tickWorker(level, villager, professionId, catalog, states, assignments, inventories, forceIdleScan);
                }
            }
        }
    }

    private static void tickWorker(ServerLevel level, Villager worker, String professionId, WorkOrderCatalog catalog,
                                   VillagerWorkSavedData states, WorkerAssignmentSavedData assignments,
                                   VillagerWorkInventorySavedData inventories, boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(worker.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(worker)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            worker.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, worker)) {
            return;
        }
        Optional<WorkZoneRecord> zone = ZONES.assignedZone(worker.getUUID(), professionId,
                level.dimension().identifier().toString(), assignments.zoneSnapshot(), assignments.getAssignment(worker.getUUID()));
        boolean hasPhysicalWorkstation = !"totem:miner".equals(professionId)
                || zone.map(assigned -> MinerFurnaceWorkstation.ensureAssigned(level, worker, assigned.zone()).isPresent()).orElse(false);
        VillagerWorkInventory inventory = inventories.inventory(worker.getUUID());
        List<WorkCandidate> candidates = hasPhysicalWorkstation ? zone.map(assigned ->
                worldCandidates(level, worker, professionId, state, assigned, inventory, catalog)).orElseGet(List::of)
                : List.of();
        Optional<BlockPos> activeTarget = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of);
        boolean atTarget = activeTarget.map(target -> WorldWorkNavigation.isWithinReach(worker, target)).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(worker.getUUID(), professionId, level.getGameTime(), worker.isAlive(),
                level.isLoaded(worker.blockPosition()), inDanger(worker), worker.isSleeping(), level.isRaided(worker.blockPosition()),
                zone.isPresent() && hasPhysicalWorkstation, atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, worker, scheduled.state(), scheduled.readyToCommit().orElseThrow(), zone.orElse(null), inventory);
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            worker.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).isPresent() && !atTarget && !inDanger(worker)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, worker)) {
            BlockPos target = BlockPos.of(next.activeWork().orElseThrow().worldTarget().orElseThrow()
                    .packedBlockPosition().orElseThrow());
            if (!WorldWorkNavigation.moveToReach(level, worker, target, .5D)) {
                WorkOrder activeOrder = catalog.snapshot().get(next.activeWork().orElseThrow().orderId());
                if (activeOrder != null) {
                    next = failed(next, activeOrder, "world target unreachable");
                }
            }
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    private static List<WorkCandidate> worldCandidates(ServerLevel level, Villager worker, String professionId,
                                                        VillagerWorkState state, WorkZoneRecord zone,
                                                        VillagerWorkInventory inventory, WorkOrderCatalog catalog) {
        return catalog.snapshot().values().stream()
                .filter(order -> professionId.equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(level, worker, inventory, order))
                .filter(order -> canStore(inventory, order))
                .map(order -> candidateFor(level, worker, state, zone, order))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager miner, VillagerWorkState state,
                                                         WorkZoneRecord zone, WorkOrder order) {
        TagKey<Block> tag = blockTag(order.worldTargetTag());
        if (tag == null) return Optional.empty();
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<BlockPos> target = current.flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(pos -> validTarget(level, miner, zone, tag, pos, order));
        if (target.isEmpty() && current.isEmpty()) {
            target = TARGETS.findNearest(level, miner, zone.zone(), tag, 512,
                    pos -> validActionTarget(level, miner, zone, tag, pos, order));
        }
        return target.filter(pos -> validTarget(level, miner, zone, tag, pos, order)).map(pos -> new WorkCandidate(order.id(), WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), pos.asLong()))));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager miner, VillagerWorkState state, WorkOrder order,
                                            WorkZoneRecord zone, VillagerWorkInventory inventory) {
        WorldWorkTarget target = state.activeWork().flatMap(active -> active.worldTarget()).orElse(null);
        TagKey<Block> tag = blockTag(order.worldTargetTag());
        if (target == null || tag == null || zone == null
                || !target.dimensionId().equals(level.dimension().identifier().toString())) {
            return failed(state, order, "world target changed");
        }
        BlockPos pos = BlockPos.of(target.packedBlockPosition().orElseThrow());
        if (!zone.zone().contains(target.dimensionId(), new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))
                || !completeAction(level, miner, zone.zone(), pos, tag, order, inventory)) {
            return failed(state, order, "world target rejected");
        }
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    private static VillagerWorkState failed(VillagerWorkState state, WorkOrder order, String reason) {
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(order.id(), WorkSource.WORLD,
                order.workTicks(), reason)));
    }

    private static boolean completeAction(ServerLevel level, Villager worker, dev.totem.villagers.worker.WorkZone zone,
                                          BlockPos pos, TagKey<Block> tag, WorkOrder order, VillagerWorkInventory inventory) {
        return switch (order.professionId()) {
            case "totem:miner" -> MINER_ACTION.complete(level, worker, pos, tag, order, inventory);
            case "totem:lumberjack" -> LUMBERJACK_ACTION.complete(level, worker, zone, pos, tag, order, inventory);
            default -> false;
        };
    }

    private static boolean validTarget(ServerLevel level, Villager miner, WorkZoneRecord zone, TagKey<Block> tag,
                                       BlockPos pos, WorkOrder order) {
        return level.isLoaded(pos)
                && zone.zone().contains(level.dimension().identifier().toString(), new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))
                && level.getBlockState(pos).is(tag)
                && WorldWorkPermissions.mayWork(level, miner, pos)
                && validActionTarget(level, miner, zone, tag, pos, order);
    }

    private static boolean validActionTarget(ServerLevel level, Villager worker, WorkZoneRecord zone,
                                             TagKey<Block> tag, BlockPos pos, WorkOrder order) {
        return "totem:miner".equals(order.professionId())
                || LumberjackWorldWorkAction.isEligibleBase(level, worker, zone.zone(), pos, tag, order);
    }

    private static TagKey<Block> blockTag(String id) {
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? null : TagKey.create(Registries.BLOCK, identifier);
    }

    private static boolean canStore(VillagerWorkInventory inventory, WorkOrder order) {
        if (!order.outputComponentPatch().isBlank()) {
            return false;
        }
        Identifier identifier = Identifier.tryParse(order.output().itemId());
        Item item = identifier == null ? null : BuiltInRegistries.ITEM.getValue(identifier);
        if (item == null) {
            return false;
        }
        return inventory.canInsertExact(new ItemStack(item, order.output().count()));
    }

    private static boolean inDanger(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET);
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

}
