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
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/** Bounded autonomous world work for the initial Miner and Lumberjack specialist roles. */
public final class VillagerWorldWorkRuntime {
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final WorldWorkZoneEligibility ZONES = new WorldWorkZoneEligibility();
    private static final BoundedZoneTargetFinder TARGETS = new BoundedZoneTargetFinder();
    private static final MinerWorldWorkAction MINER_ACTION = new MinerWorldWorkAction();
    private static final LumberjackWorldWorkAction LUMBERJACK_ACTION = new LumberjackWorldWorkAction();

    private static final VillagerHeadStatus.Kind[] WORLD_SHORTAGES = {VillagerHeadStatus.Kind.MINE_ZONE,
            VillagerHeadStatus.Kind.FOREST_ZONE, VillagerHeadStatus.Kind.FURNACE, VillagerHeadStatus.Kind.PICKAXE, VillagerHeadStatus.Kind.AXE,
            VillagerHeadStatus.Kind.STRONG_PICKAXE, VillagerHeadStatus.Kind.NO_MINE_FACE, VillagerHeadStatus.Kind.NO_TREE};

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
            VillagerHeadStatus.clear(worker, WORLD_SHORTAGES);
            return;
        }
        if (!VillagerWorkNeeds.canWork(worker)) {
            VillagerHeadStatus.clear(worker, WORLD_SHORTAGES);
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
                level.dimension().identifier().toString(), assignments::getZone, assignments.getAssignment(worker.getUUID()));
        boolean hasPhysicalWorkstation = !"totem:miner".equals(professionId)
                || zone.map(assigned -> MinerFurnaceWorkstation.ensureAssigned(level, worker, assigned.zone()).isPresent()).orElse(false);
        VillagerWorkInventory inventory = inventories.inventory(worker.getUUID());
        var tool = professionId.equals("totem:miner") ? ToolsmithVillageEconomyRuntime.ReplacementTool.PICKAXE
                : ToolsmithVillageEconomyRuntime.ReplacementTool.AXE;
        boolean equipped = tool.hasUsableTool(inventory);
        var search = new SearchEvidence();
        List<WorkCandidate> candidates = hasPhysicalWorkstation && equipped ? zone.map(assigned ->
                worldCandidates(level, worker, professionId, state, assigned, inventory, catalog, search)).orElseGet(List::of)
                : List.of();
        if (forceIdleScan || VillagerRuntimeBudget.dueForIdleScan(level, worker)) {
            var miner = professionId.equals("totem:miner");
            VillagerHeadStatus.Kind shortage = null;
            if (VillageWorkshopApprenticeship.available(level, worker)) {
                if (zone.isEmpty()) shortage = miner ? VillagerHeadStatus.Kind.MINE_ZONE : VillagerHeadStatus.Kind.FOREST_ZONE;
                else if (!hasPhysicalWorkstation) shortage = VillagerHeadStatus.Kind.FURNACE;
                else if (!equipped)
                    shortage = miner ? VillagerHeadStatus.Kind.PICKAXE : VillagerHeadStatus.Kind.AXE;
            }
            if (shortage == null && search.hasDemand && !search.searching && candidates.isEmpty() && VillageWorkshopApprenticeship.available(level, worker)) {
                shortage = miner ? (search.needsBetterPick ? VillagerHeadStatus.Kind.STRONG_PICKAXE : VillagerHeadStatus.Kind.NO_MINE_FACE)
                        : VillagerHeadStatus.Kind.NO_TREE;
            }
            if (!search.hasDemand && shortage != null && catalog.snapshot().values().stream().noneMatch(order -> professionId.equals(order.professionId())
                    && order.allowedSources().contains(WorkSource.WORLD) && VillageProductionStockPolicy.needsWorldWork(level, worker, inventory, order))) shortage = null;
            for (var kind : WORLD_SHORTAGES) {
                if (kind == shortage) VillagerHeadStatus.report(worker, kind);
                else VillagerHeadStatus.clear(worker, kind);
            }
        }
        Optional<BlockPos> activeTarget = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of);
        boolean atTarget = activeTarget.map(target -> WorldWorkNavigation.isWithinReach(worker, target)).orElse(false);
        if (atTarget) {
            VillagerHeadStatus.clear(worker, VillagerHeadStatus.Kind.UNREACHABLE);
            worker.getNavigation().stop();
            worker.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(activeTarget.orElseThrow()));
        }
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
                    VillagerHeadStatus.report(worker, VillagerHeadStatus.Kind.UNREACHABLE);
                    next = failed(next, activeOrder, "world target unreachable");
                }
            }
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    // Search progress belongs to the loaded entity. Values never reference the entity or its level.
    private static final java.util.Map<Villager, WorkerSearch> SEARCHES = new java.util.WeakHashMap<>();
    private static final int SCAN_PAGE = 128;

    private static final class WorkerSearch {
        final WorkZoneRecord zone;
        final WorkOrderCatalog catalog;
        final java.util.Map<String, OrderSearch> orders = new java.util.HashMap<>();
        final java.util.Map<String, OrderSearch> supplyChecks = new java.util.HashMap<>();

        WorkerSearch(WorkZoneRecord zone, WorkOrderCatalog catalog) {
            this.zone = zone;
            this.catalog = catalog;
        }
    }

    private static final class OrderSearch {
        BoundedZoneTargetFinder.Cursor cursor;
        long retryAt;
        boolean needsBetterPick;
        boolean scannedPage;
        boolean completedSweep;
        boolean lastSweepNeededPick;
        BlockPos confirmed;
    }

    private static final class SearchEvidence {
        boolean hasDemand;
        boolean searching;
        boolean needsBetterPick;
    }

    private static List<WorkCandidate> worldCandidates(ServerLevel level, Villager worker, String professionId,
                                                        VillagerWorkState state, WorkZoneRecord zone,
                                                        VillagerWorkInventory inventory, WorkOrderCatalog catalog, SearchEvidence search) {
        var tools = inventory.snapshot();
        var active = state.activeWork();
        var orders = active.isPresent()
                ? java.util.stream.Stream.ofNullable(catalog.snapshot().get(active.get().orderId()))
                : catalog.snapshot().values().stream();
        return orders.filter(order -> professionId.equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(level, worker, inventory, order))
                .filter(order -> canStore(inventory, order))
                .map(order -> {
                    search.hasDemand = true;
                    return candidateFor(level, worker, state, zone, order, tools, search);
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /** A spare worker counts only with a usable tool and a permitted, reachable renewable tree or mine face. */
    static boolean canKeepSupplying(ServerLevel level, Villager worker, WorkZoneRecord zone) {
        var inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(worker.getUUID());
        boolean miner = zone.roleId().equals("totem:miner");
        var tool = miner ? ToolsmithVillageEconomyRuntime.ReplacementTool.PICKAXE : ToolsmithVillageEconomyRuntime.ReplacementTool.AXE;
        if (!zone.roleId().equals(tool.professionId()) || !tool.hasUsableTool(inventory)) return false;
        if (miner && worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE)
                .filter(job -> job.dimension().equals(level.dimension()) && level.isLoaded(job.pos())
                        && zone.zone().contains(zone.zone().dimensionId(), new BlockCoordinate(job.pos().getX(), job.pos().getY(), job.pos().getZ()))
                        && level.getBlockState(job.pos()).is(net.minecraft.world.level.block.Blocks.FURNACE)
                        && WorldWorkPermissions.mayWork(level, worker, job.pos())
                        && MinerFurnaceWorkstation.privateStationAvailable(level, worker, zone.zone(), job.pos())).isEmpty()) return false;
        var tools = inventory.snapshot();
        return WorkOrderDefinitions.catalog().snapshot().values().stream()
                .filter(order -> order.professionId().equals(zone.roleId()) && order.allowedSources().contains(WorkSource.WORLD)
                        && canStore(inventory, order))
                .anyMatch(order -> {
                    var tag = blockTag(order.worldTargetTag());
                    if (tag == null) return false;
                    return discover(level, worker, zone, order, tag, true, new SearchEvidence(), pos -> {
                        if (miner) return validActionTarget(level,worker,zone,tag,pos,order) && tools.stream().anyMatch(stack -> MinerWorldWorkAction.canMine(stack, level.getBlockState(pos)));
                        var id = Identifier.tryParse(order.worldReplantBlockId());
                        var sapling = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
                        return sapling != null && LumberjackWorldWorkAction.isEligibleBase(level, worker, zone.zone(), pos, tag, order)
                                && sapling.defaultBlockState().canSurvive(level, pos)
                                && (inventory.countMatchingItem(new net.minecraft.world.item.ItemStack(sapling)) > 0
                                || LumberjackWorldWorkAction.isRenewableGeneratedLumberyardPlot(level, worker, pos));
                    }).isPresent();
                });
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager miner, VillagerWorkState state,
                                                         WorkZoneRecord zone, WorkOrder order, List<net.minecraft.world.item.ItemStack> tools, SearchEvidence search) {
        TagKey<Block> tag = blockTag(order.worldTargetTag());
        if (tag == null) return Optional.empty();
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        java.util.function.Predicate<BlockPos> harvestable = pos -> {
            if (!validActionTarget(level, miner, zone, tag, pos, order)) return false;
            if (!"totem:miner".equals(order.professionId()) || tools.stream().anyMatch(tool -> MinerWorldWorkAction.canMine(tool, level.getBlockState(pos)))) return true;
            if (!search.needsBetterPick && WorldWorkNavigation.canReach(level, miner, pos)) search.needsBetterPick = true;
            return false;
        };
        Optional<BlockPos> target = current.flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(pos -> validTarget(level, miner, zone, tag, pos, harvestable));
        if (target.isEmpty() && current.isEmpty()) {
            target = discover(level, miner, zone, order, tag, false, search, harvestable);
        }
        return target.map(pos -> new WorkCandidate(order.id(), WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), pos.asLong()))));
    }

    private static Optional<BlockPos> discover(ServerLevel level, Villager worker, WorkZoneRecord zone,
                                                WorkOrder order, TagKey<Block> tag, boolean supplyCheck,
                                                SearchEvidence search, java.util.function.Predicate<BlockPos> valid) {
        var catalog = WorkOrderDefinitions.catalog();
        var workerSearch = SEARCHES.get(worker);
        if (workerSearch == null || !workerSearch.zone.equals(zone) || workerSearch.catalog != catalog) {
            workerSearch = new WorkerSearch(zone, catalog);
            SEARCHES.put(worker, workerSearch);
        }
        var checks = supplyCheck ? workerSearch.supplyChecks : workerSearch.orders;
        var progress = checks.computeIfAbsent(order.id(), ignored -> new OrderSearch());
        if (progress.confirmed != null) {
            var pos = progress.confirmed;
            if (level.isLoaded(pos) && level.getBlockState(pos).is(tag)
                    && WorldWorkPermissions.mayWork(level, worker, pos) && valid.test(pos)
                    && WorldWorkNavigation.canReach(level, worker, pos)) return Optional.of(pos);
            progress.confirmed = null;
        }
        long now = level.getGameTime();
        if (now < progress.retryAt) {
            search.needsBetterPick |= progress.needsBetterPick;
            return Optional.empty();
        }
        if (progress.cursor == null) {
            progress.cursor = new BoundedZoneTargetFinder.Cursor(zone.zone(), worker.blockPosition());
            progress.scannedPage = false;
            progress.needsBetterPick = false;
        }
        boolean previousPick = search.needsBetterPick;
        search.needsBetterPick = progress.needsBetterPick;
        var target = TARGETS.scan(level, worker, zone.zone(), tag, SCAN_PAGE, valid, progress.cursor);
        progress.needsBetterPick = search.needsBetterPick;
        search.needsBetterPick |= previousPick;
        if (target.isPresent()) {
            progress.confirmed = supplyCheck ? target.get() : null;
            progress.cursor = null;
            progress.completedSweep = false;
        } else if (progress.cursor.hasNext()) {
            search.searching |= !progress.completedSweep;
            search.needsBetterPick |= progress.lastSweepNeededPick;
            progress.scannedPage = true;
        } else {
            progress.cursor = null;
            progress.completedSweep = true;
            progress.lastSweepNeededPick = progress.needsBetterPick;
            if (progress.scannedPage) progress.retryAt = now + 100;
        }
        return target;
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
            case "totem:miner" -> (!zone.naturalQuarry() || dev.totem.villagers.world.NaturalStoneFaces.usable(level,worker,zone,pos)) && MINER_ACTION.complete(level, worker, pos, tag, order, inventory);
            case "totem:lumberjack" -> LUMBERJACK_ACTION.complete(level, worker, zone, pos, tag, order, inventory);
            default -> false;
        };
    }

    private static boolean validTarget(ServerLevel level, Villager miner, WorkZoneRecord zone, TagKey<Block> tag,
                                       BlockPos pos, java.util.function.Predicate<BlockPos> harvestable) {
        return level.isLoaded(pos)
                && zone.zone().contains(level.dimension().identifier().toString(), new BlockCoordinate(pos.getX(), pos.getY(), pos.getZ()))
                && level.getBlockState(pos).is(tag)
                && WorldWorkPermissions.mayWork(level, miner, pos)
                && harvestable.test(pos);
    }

    private static boolean validActionTarget(ServerLevel level, Villager worker, WorkZoneRecord zone,
                                             TagKey<Block> tag, BlockPos pos, WorkOrder order) {
        if ("totem:miner".equals(order.professionId())) return !zone.zone().naturalQuarry()
                || dev.totem.villagers.world.NaturalStoneFaces.usable(level,worker,zone.zone(),pos);
        return LumberjackWorldWorkAction.isEligibleBase(level, worker, zone.zone(), pos, tag, order);
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
