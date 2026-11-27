package dev.totem.villagers.runtime;

import dev.totem.villagers.worker.ProductionParcel;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
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
import dev.totem.villagers.world.FarmerWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded Farmer field work. Mature wheat within the native Composter's local
 * field is placed in the same personal work inventory used by recipe-backed
 * bread job; harvesting itself never creates merchant stock.
 */
public final class VillagerFarmerWorkRuntime {
    private static final int FARM_RADIUS = 24;
    private static final int MAX_CROP_CHECKS = 1_024;
    private static final int[] FARM_Y_OFFSETS = {0, -1, 1, -2, 2, -3, -4};
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final FarmerWorldWorkAction ACTION = new FarmerWorldWorkAction();

    private VillagerFarmerWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerFarmerWorkRuntime::tick);
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
        if (catalog.snapshot().values().stream().noneMatch(ACTION::supports)) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager farmer : LoadedVillagerCache.workers(level)) {
                if ("minecraft:farmer".equals(professionId(farmer))) {
                    tickFarmer(level, farmer, catalog, states, inventories, forceIdleScan);
                }
            }
        }
    }

    private static void tickFarmer(ServerLevel level, Villager farmer, WorkOrderCatalog catalog,
                                   VillagerWorkSavedData states, VillagerWorkInventorySavedData inventories,
                                   boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(farmer.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(farmer)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            farmer.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, farmer)) {
            return;
        }
        Optional<FarmerContext> context = resolveContext(level, farmer, inventories);
        if (context.isPresent()) {
            VillagerHeadStatus.clear(farmer, VillagerHeadStatus.Kind.COMPOSTER);
            if (ACTION.hasUsableHoe(context.get().inventory())) VillagerHeadStatus.clear(farmer, VillagerHeadStatus.Kind.HOE,
                    VillagerHeadStatus.Kind.TABLE, VillagerHeadStatus.Kind.WOOD, VillagerHeadStatus.Kind.UNREACHABLE);
        } else if (!farmer.isSleeping() && !inDanger(farmer)) {
            var site = farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
            if (site == null || site.dimension().equals(level.dimension()) && level.isLoaded(site.pos()))
                VillagerHeadStatus.report(farmer, VillagerHeadStatus.Kind.COMPOSTER);
        }
        if (state.activeWork().isEmpty() && context.isPresent() && !farmer.isBaby() && farmer.isAlive()
                && !farmer.isSleeping() && !inDanger(farmer) && !level.isRaided(farmer.blockPosition())
                && !farmer.isTrading() && FarmerWorldWorkAction.needsReplacementHoe(context.get().inventory())) {
            if (!ACTION.hasUsableHoe(context.get().inventory())) VillagerHeadStatus.report(farmer, VillagerHeadStatus.Kind.HOE);
            tryReplaceHoe(level, farmer, context.get(), inventories);
        }
        List<WorkCandidate> candidates = context.map(value -> candidates(level, farmer, state, value, catalog)).orElseGet(List::of);
        Optional<BlockPos> activeCrop = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of);
        boolean atTarget = activeCrop.map(crop -> FarmerWorldWorkAction.besideCrop(farmer, crop)).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(farmer.getUUID(), "minecraft:farmer", level.getGameTime(),
                farmer.isAlive(), level.isLoaded(farmer.blockPosition()), inDanger(farmer), farmer.isSleeping(),
                level.isRaided(farmer.blockPosition()), context.isPresent(), atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, farmer, scheduled.state(), scheduled.readyToCommit().orElseThrow(), context.orElse(null));
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            farmer.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition).isPresent()
                && !atTarget && !inDanger(farmer)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, farmer)) {
            BlockPos crop = BlockPos.of(next.activeWork().orElseThrow().worldTarget().orElseThrow()
                    .packedBlockPosition().orElseThrow());
            farmer.getNavigation().moveTo(crop.getX() + .5D, crop.getY(), crop.getZ() + .5D, .5D);
        }
        if (next.activeWork().isEmpty() && state.activeWork().isEmpty() && context.isPresent()
                && candidates.isEmpty() && farmer.getNavigation().isDone() && !farmer.isSleeping()
                && !farmer.isTrading() && !farmer.isBaby() && !inDanger(farmer) && !level.isRaided(farmer.blockPosition())
                && ACTION.hasUsableHoe(context.get().inventory()) && VillagerRuntimeBudget.due(level.getGameTime(), farmer.getUUID(), 200)) {
            weedNearby(level, farmer, context.get().jobSite().pos());
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    private static void weedNearby(ServerLevel level, Villager farmer, BlockPos station) {
        var feet = BlockPos.containing(farmer.getX(), farmer.getY() + .125, farmer.getZ());
        if (feet.distSqr(station) > FARM_RADIUS * FARM_RADIUS) return;
        for (var crop : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 0, 1))) {
            if (!level.isLoaded(crop)) continue;
            var state = level.getBlockState(crop);
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock plant && !plant.isMaxAge(state)
                    && FarmerWorldWorkAction.besideCrop(farmer, crop) && WorldWorkPermissions.mayWork(level, farmer, crop)
                    && !ProductionParcel.get(level.getServer()).heldByAnother(level, crop.below(), farmer)) {
                farmer.getLookControl().setLookAt(crop.getX() + .5, crop.getY(), crop.getZ() + .5);
                farmer.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                VillagerHeadStatus.report(farmer, VillagerHeadStatus.Kind.WEEDING);
                return;
            }
        }
    }

    private static void tryReplaceHoe(ServerLevel level, Villager farmer, FarmerContext context,
                                      VillagerWorkInventorySavedData inventories) {
        VillagerWorkInventory inventory = context.inventory();
        BlockPos composter = context.jobSite().pos();
        // A small work area around the assigned farm. Never inspect containers or load adjacent chunks.
        var table = VillageWorktable.near(level, farmer, composter);
        if (table.isEmpty()) {
            VillageWorktable.prepareTable(level, farmer, composter, inventory, inventories);
            return;
        }
        BlockPos pos = table.get();
        if (VillageWorktable.seekMissingWood(level, farmer, inventory, inventories, 2)) return;
        if (HouseholdSurvivalRuntime.atTable(farmer, pos) && VillageWoodCrafting.prepareHoe(level, inventory)) {
            VillagerHeadStatus.clear(farmer, VillagerHeadStatus.Kind.HOE, VillagerHeadStatus.Kind.UNREACHABLE);
            VillagerHeadStatus.report(farmer, VillagerHeadStatus.Kind.MAKING_TOOL);
            farmer.playWorkSound();
        }
    }

    private static List<WorkCandidate> candidates(ServerLevel level, Villager farmer, VillagerWorkState state,
                                                   FarmerContext context, WorkOrderCatalog catalog) {
        if (!ACTION.hasUsableHoe(context.inventory())) {
            return List.of();
        }
        List<WorkOrder> orders = catalog.snapshot().values().stream()
                .filter(ACTION::supports)
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(
                        level, farmer, context.inventory(), order))
                .sorted(java.util.Comparator.comparing(WorkOrder::id))
                .toList();
        if (orders.isEmpty()) {
            return List.of();
        }
        if (state.activeWork().isPresent()) {
            String activeOrderId = state.activeWork().orElseThrow().orderId();
            return orders.stream()
                    .filter(order -> order.id().equals(activeOrderId))
                    .map(order -> candidateFor(level, farmer, state, context, order))
                    .flatMap(Optional::stream)
                    .toList();
        }
        Map<String, WorkOrder> ordersById = new LinkedHashMap<>();
        for (WorkOrder order : orders) {
            ordersById.put(order.id(), order);
        }
        return findCrop(level, farmer, context, ordersById)
                .map(found -> List.of(candidate(level, found.order(), found.position())))
                .orElseGet(List::of);
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager farmer, VillagerWorkState state,
                                                         FarmerContext context, WorkOrder order) {
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<BlockPos> crop = current.flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(target -> validCropTarget(level, farmer, context, target, order, false));
        return crop.map(target -> candidate(level, order, target));
    }

    private static WorkCandidate candidate(ServerLevel level, WorkOrder order, BlockPos target) {
        return new WorkCandidate(order.id(), WorkSource.WORLD, 0,
                Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), target.asLong())));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager farmer, VillagerWorkState state,
                                            WorkOrder order, FarmerContext context) {
        BlockPos crop = state.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition)
                .map(BlockPos::of).orElse(null);
        if (context == null || crop == null || !validCropTarget(level, farmer, context, crop, order, false)
                || !ACTION.complete(level, farmer, crop, order, context.inventory())) {
            return failed(state, order, "crop harvest rejected");
        }
        // A successful harvest changes the server-owned work inventory. Push
        // the updated snapshot immediately to a player who is already looking
        // at this Farmer, rather than making the backpack wait for its normal
        // periodic merchant-screen refresh.
        TradeSnapshotRuntime.refreshOpenTrade(farmer);
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    private static Optional<FarmerContext> resolveContext(ServerLevel level, Villager farmer,
                                                           VillagerWorkInventorySavedData inventories) {
        GlobalPos jobSite = farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || !jobSite.dimension().equals(level.dimension())
                || !level.isLoaded(jobSite.pos()) || !level.getBlockState(jobSite.pos()).is(Blocks.COMPOSTER)) {
            return Optional.empty();
        }
        return Optional.of(new FarmerContext(jobSite, inventories.inventory(farmer.getUUID())));
    }

    private static boolean reachableCrop(Villager farmer, BlockPos crop) {
        if (FarmerWorldWorkAction.besideCrop(farmer, crop)) return true;
        var path = farmer.getNavigation().createPath(crop, 1);
        return path != null && path.canReach();
    }

    private static boolean validCropTarget(ServerLevel level, Villager farmer, FarmerContext context,
                                           BlockPos crop, WorkOrder order, boolean requirePath) {
        return ACTION.supports(order)
                && ACTION.hasUsableHoe(context.inventory())
                && ACTION.isMatureCrop(level, crop, order)
                && crop.distSqr(context.jobSite().pos()) <= (double) FARM_RADIUS * FARM_RADIUS
                && WorldWorkPermissions.mayWork(level, farmer, crop)
                && (!requirePath || reachableCrop(farmer, crop))
                && ACTION.primaryHarvest(order).filter(context.inventory()::canInsertExact).isPresent()
                && ProductionParcel.get(level.getServer())
                    .acquire(level, farmer, context.jobSite(), crop.below(), true);
    }

    private static Optional<LocatedCrop> findCrop(ServerLevel level, Villager farmer, FarmerContext context,
                                                  Map<String, WorkOrder> ordersById) {
        var parcels = ProductionParcel.get(level.getServer());
        parcels.reconcile(level, farmer);
        var field = parcels.claim(farmer.getUUID());
        if (field.isPresent() && field.get().role().equals("minecraft:farmer")) {
            FIELD_SEARCHES.remove(farmer);
            for (var soil : field.get().cells()) {
                var found = ripeCrop(level, farmer, context, ordersById, soil.above());
                if (found.isPresent()) return found;
            }
            return Optional.empty();
        }
        var search = FIELD_SEARCHES.get(farmer);
        if (search == null || !search.station().equals(context.jobSite())) {
            var columns = BlockPos.withinBoxByManhattanDistance(context.jobSite().pos(), FARM_RADIUS, 0, FARM_RADIUS).iterator();
            search = new FieldSearch(context.jobSite(), columns);
            FIELD_SEARCHES.put(farmer, search);
        }
        // Finish all supported heights in each nearby column, then resume at the next column on the next scan.
        for (int budget = MAX_CROP_CHECKS; budget >= FARM_Y_OFFSETS.length && search.columns().hasNext(); budget -= FARM_Y_OFFSETS.length) {
            var column = search.columns().next();
            for (int dy : FARM_Y_OFFSETS) {
                var found = ripeCrop(level, farmer, context, ordersById, column.above(dy));
                if (found.isPresent()) {
                    FIELD_SEARCHES.remove(farmer);
                    return found;
                }
            }
        }
        if (!search.columns().hasNext()) FIELD_SEARCHES.remove(farmer);
        return Optional.empty();
    }

    private static Optional<LocatedCrop> ripeCrop(ServerLevel level, Villager farmer, FarmerContext context,
                                                  Map<String, WorkOrder> ordersById, BlockPos crop) {
        var order = ACTION.matureCropOrderId(level, crop).map(ordersById::get).orElse(null);
        return order != null && validCropTarget(level, farmer, context, crop, order, true)
                ? Optional.of(new LocatedCrop(order, crop.immutable())) : Optional.empty();
    }

    private record FieldSearch(GlobalPos station, java.util.Iterator<BlockPos> columns) {}
    private static final java.util.Map<Villager, FieldSearch> FIELD_SEARCHES = new java.util.WeakHashMap<>();

    private static VillagerWorkState failed(VillagerWorkState state, WorkOrder order, String reason) {
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(order.id(), WorkSource.WORLD,
                order.workTicks(), reason)));
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

    private record FarmerContext(GlobalPos jobSite, VillagerWorkInventory inventory) {
    }

    private record LocatedCrop(WorkOrder order, BlockPos position) {
    }
}
