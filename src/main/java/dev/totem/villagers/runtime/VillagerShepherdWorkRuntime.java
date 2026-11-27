package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.world.ShepherdWorldWorkAction;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.totem.villagers.world.ShearUse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Existing vanilla Shepherd profession performs bounded flock shearing near its native job site. */
public final class VillagerShepherdWorkRuntime {
    private static final double FLOCK_RADIUS_SQUARED = 32.0D * 32.0D;
    private static final double WORK_REACH_SQUARED = 16.0D;
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final ShepherdWorldWorkAction ACTION = new ShepherdWorldWorkAction();

    private VillagerShepherdWorkRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerShepherdWorkRuntime::tick);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    /** Runs discovery immediately for deterministic GameTests. */
    public static void tickForGameTest(MinecraftServer server) {
        run(server, true);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        if (catalog.snapshot().values().stream().noneMatch(order -> "minecraft:shepherd".equals(order.professionId())
                && order.allowedSources().contains(WorkSource.WORLD))) {
            return;
        }
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.workers(level)) {
                if ("minecraft:shepherd".equals(professionId(villager))) {
                    tickShepherd(level, villager, catalog, states, forceIdleScan);
                }
            }
        }
    }

    private static void tickShepherd(ServerLevel level, Villager shepherd, WorkOrderCatalog catalog,
                                     VillagerWorkSavedData states, boolean forceIdleScan) {
        VillagerWorkState state = states.getOrCreate(shepherd.getUUID());
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORLD) {
            return;
        }
        if (!VillagerWorkNeeds.canWork(shepherd)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            shepherd.getNavigation().stop();
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, shepherd)) {
            return;
        }
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer())
                .inventory(shepherd.getUUID());
        Optional<ShepherdContext> context = resolveContext(level, shepherd);
        if (context.isPresent()) VillagerHeadStatus.clear(shepherd, VillagerHeadStatus.Kind.LOOM);
        else if (!shepherd.isSleeping() && !inDanger(shepherd)) {
            var site = shepherd.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
            if (site == null || site.dimension().equals(level.dimension()) && level.isLoaded(site.pos()))
                VillagerHeadStatus.report(shepherd, VillagerHeadStatus.Kind.LOOM);
        }
        List<WorkCandidate> candidates = context.map(value -> candidates(
                level, shepherd, state, value, catalog, inventory)).orElseGet(List::of);
        if (!candidates.isEmpty() && ShearUse.best(inventory.snapshot()).isEmpty()) {
            if (!shepherd.isBaby() && shepherd.isAlive() && !shepherd.isTrading() && !shepherd.isSleeping()
                    && !inDanger(shepherd) && !level.isRaided(shepherd.blockPosition())) buySpareShears(level, shepherd, inventory);
            if (ShearUse.best(inventory.snapshot()).isEmpty()) {
                VillagerHeadStatus.report(shepherd, VillagerHeadStatus.Kind.SHEARS);
                candidates = List.of();
            } else VillagerHeadStatus.clear(shepherd, VillagerHeadStatus.Kind.SHEARS);
        } else VillagerHeadStatus.clear(shepherd, VillagerHeadStatus.Kind.SHEARS);
        Optional<Sheep> activeSheep = state.activeWork().flatMap(active -> active.worldTarget())
                .flatMap(WorldWorkTarget::entityId).map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast);
        if (candidates.isEmpty() && state.activeWork().isEmpty() && VillagerRuntimeBudget.dueForIdleScan(level, shepherd)
                && context.isPresent()) {
            for (var seen : VillagerWorkMemory.sites(shepherd)) {
                if (seen.animal().isEmpty() || !seen.site().dimension().equals(level.dimension())) continue;
                var pos = seen.site().pos();
                if (!level.isLoaded(pos) || pos.distSqr(context.get().jobSite().pos()) > FLOCK_RADIUS_SQUARED) continue;
                if (level.getEntity(seen.animal().get()) instanceof Sheep animal && shepherd.distanceToSqr(animal) <= FLOCK_RADIUS_SQUARED && shepherd.hasLineOfSight(animal)) {
                    VillagerWorkMemory.observe(shepherd, animal);
                    continue;
                }
                if (shepherd.blockPosition().distSqr(pos) > 9 && shepherd.getNavigation().isDone()) {
                    VillagerHeadStatus.report(shepherd, VillagerHeadStatus.Kind.SEEKING_SHEEP);
                    shepherd.getNavigation().moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, .5);
                    break;
                }
            }
        }
        boolean atTarget = activeSheep.map(sheep -> withinReach(shepherd, sheep)).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(shepherd.getUUID(), "minecraft:shepherd", level.getGameTime(),
                shepherd.isAlive(), level.isLoaded(shepherd.blockPosition()), inDanger(shepherd), shepherd.isSleeping(),
                level.isRaided(shepherd.blockPosition()), context.isPresent(), atTarget, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, shepherd, scheduled.state(), scheduled.readyToCommit().orElseThrow(), context.orElse(null),
                    inventory);
        }
        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            shepherd.getNavigation().stop();
        } else if (next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId).isPresent()
                && !atTarget && !inDanger(shepherd)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, shepherd)) {
            Sheep sheep = next.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId)
                    .map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast).orElse(null);
            if (sheep != null) {
                if (VillagerWorkMemory.observe(shepherd, sheep)) shepherd.getNavigation().moveTo(sheep, .5D);
            }
        }
        if (!next.equals(state)) {
            states.put(next);
        }
    }

    private static List<WorkCandidate> candidates(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                                   ShepherdContext context, WorkOrderCatalog catalog,
                                                   VillagerWorkInventory inventory) {
        return catalog.snapshot().values().stream()
                .filter(order -> "minecraft:shepherd".equals(order.professionId()))
                .filter(order -> order.allowedSources().contains(WorkSource.WORLD))
                .filter(order -> VillageProductionStockPolicy.needsWorldWork(level, shepherd, inventory, order))
                .map(order -> candidateFor(level, shepherd, state, context, order))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<WorkCandidate> candidateFor(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                                         ShepherdContext context, WorkOrder order) {
        if (!"minecraft:sheep".equals(order.worldTargetEntityType())) {
            return Optional.empty();
        }
        Optional<WorldWorkTarget> current = state.activeWork()
                .filter(active -> active.orderId().equals(order.id()) && active.source() == WorkSource.WORLD)
                .flatMap(active -> active.worldTarget());
        Optional<Sheep> sheep = current.flatMap(WorldWorkTarget::entityId).map(level::getEntity)
                .filter(Sheep.class::isInstance).map(Sheep.class::cast)
                .filter(target -> validTarget(level, shepherd, context, target, order, false));
        if (sheep.isEmpty() && current.isEmpty()) {
            AABB flock = new AABB(context.jobSite().pos()).inflate(32.0D);
            sheep = level.getEntitiesOfClass(Sheep.class, flock,
                            target -> shepherd.hasLineOfSight(target) && validTarget(level, shepherd, context, target, order, true))
                    .stream().limit(64).min(Comparator.comparingDouble(shepherd::distanceToSqr));
        }
        return sheep.filter(shepherd::hasLineOfSight).map(target -> new WorkCandidate(order.id(), WorkSource.WORLD, 10,
                Optional.of(WorldWorkTarget.entity(level.dimension().identifier().toString(), target.getUUID()))));
    }

    private static VillagerWorkState commit(ServerLevel level, Villager shepherd, VillagerWorkState state,
                                            WorkOrder order, ShepherdContext context, VillagerWorkInventory inventory) {
        if (context == null) return failed(state, order, "job site changed");
        Sheep sheep = state.activeWork().flatMap(active -> active.worldTarget()).flatMap(WorldWorkTarget::entityId)
                .map(level::getEntity).filter(Sheep.class::isInstance).map(Sheep.class::cast).orElse(null);
        if (sheep == null || !validTarget(level, shepherd, context, sheep, order, false)
                || !ACTION.complete(level, shepherd, sheep, order, inventory)) {
            return failed(state, order, "flock target rejected");
        }
        TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORLD, order.workTicks(), "");
        return state.withActiveWork(Optional.empty(), Optional.of(completed));
    }

    static void buySpareShears(ServerLevel level, Villager shepherd, VillagerWorkInventory buyer) {
        var money = new ItemStack(Items.EMERALD, 3);
        if (buyer.countMatchingItem(money) <= money.getCount() || ShearUse.best(buyer.snapshot()).isPresent()) return;
        var inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        var suppliers = level.getEntitiesOfClass(Villager.class, shepherd.getBoundingBox().inflate(32), v ->
                v != shepherd && v.isAlive() && !v.isBaby() && !v.isTrading() && !v.isSleeping()
                        && ("minecraft:shepherd".equals(professionId(v)) || "minecraft:toolsmith".equals(professionId(v))))
                .stream().sorted(Comparator.comparingDouble(shepherd::distanceToSqr)).toList();
        for (var supplier : suppliers) {
            var seller = inventories.inventory(supplier.getUUID());
            var stock = seller.snapshot();
            if (stock.stream().filter(ShearUse::usable).mapToInt(ItemStack::getCount).sum() < 2) continue;
            var goods = ShearUse.best(stock).orElseThrow();
            if (shepherd.distanceToSqr(supplier) <= 9 && shepherd.hasLineOfSight(supplier)) {
                if (WorkshopLocalMarket.exchange(inventories, seller, buyer, goods, money)) return;
            } else {
                var path = shepherd.getNavigation().createPath(supplier, 1);
                if (path != null && path.canReach() && shepherd.getNavigation().moveTo(path, .5)) return;
            }
        }
    }

    private static Optional<ShepherdContext> resolveContext(ServerLevel level, Villager shepherd) {
        GlobalPos jobSite = shepherd.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (jobSite == null || !jobSite.dimension().equals(level.dimension())
                || !level.isLoaded(jobSite.pos()) || !level.getBlockState(jobSite.pos()).is(Blocks.LOOM)) return Optional.empty();
        return Optional.of(new ShepherdContext(jobSite));
    }

    private static boolean validTarget(ServerLevel level, Villager shepherd, ShepherdContext context, Sheep sheep,
                                       WorkOrder order, boolean requirePath) {
        var outputColour = ShepherdWorldWorkAction.outputColour(order).orElse(null);
        return VillagerWorkMemory.canTend(shepherd, sheep) && outputColour != null && sheep.isAlive() && sheep.getColor() == outputColour && sheep.readyForShearing()
                && level.isLoaded(sheep.blockPosition()) && sheep.distanceToSqr(Vec3.atCenterOf(context.jobSite().pos())) <= FLOCK_RADIUS_SQUARED
                && dev.totem.villagers.worker.ProductionParcel.get(level.getServer()).canWorkPasture(level, shepherd, context.jobSite().pos(), sheep.blockPosition())
                && (!requirePath || canApproach(shepherd, sheep))
                && "minecraft:sheep".equals(order.worldTargetEntityType());
    }

    static boolean prepareShearing(ServerLevel level, Villager worker, Sheep sheep, VillagerWorkInventory inventory) {
        if (!VillagerWorkMemory.canTend(worker, sheep) || !worker.hasLineOfSight(sheep)) return false;
        if (ShearUse.best(inventory.snapshot()).isEmpty()) {
            buySpareShears(level, worker, inventory);
            // The next trial pass rechecks household food after a purchase.
            return false;
        }
        if (withinReach(worker, sheep)) return true;
        var path = worker.getNavigation().createPath(sheep, 0);
        if (path != null && path.canReach()) worker.getNavigation().moveTo(path, .5);
        return false;
    }

    /** A different household may serve a persistent colour shortage outside this shepherd's pasture. */
    static boolean canSupplyColour(ServerLevel level, Villager worker, Villager customer, WorkOrder order,
                                   ItemStack wool, VillagerWorkInventorySavedData inventories) {
        var station = resolveContext(level, worker).orElse(null);
        if (station == null || !WorldWorkPermissions.mayWork(level, worker, station.jobSite().pos())) return false;
        if (worker.distanceToSqr(customer) > WORK_REACH_SQUARED || !customer.hasLineOfSight(worker)) {
            var path = customer.getNavigation().createPath(worker, 1);
            if (path == null || !path.canReach()) return false;
        }
        var inventory = inventories.inventory(worker.getUUID());
        if (WorkshopLocalMarket.hasSurplus(level, worker, inventory, wool)) return true;
        if (ShearUse.best(inventory.snapshot()).isEmpty()) return false;
        var loom = station.jobSite().pos();
        var parcels = dev.totem.villagers.worker.ProductionParcel.get(level.getServer());
        var colour = ShepherdWorldWorkAction.outputColour(order).orElseThrow();
        return level.getEntitiesOfClass(Sheep.class, new AABB(loom).inflate(32), sheep ->
                        sheep.isAlive() && !sheep.isBaby() && sheep.getColor() == colour
                                && sheep.distanceToSqr(Vec3.atCenterOf(loom)) <= FLOCK_RADIUS_SQUARED
                                && parcels.canWorkPasture(level, worker, loom, sheep.blockPosition())
                                && (sheep.readyForShearing() || !sheep.isNoAi() && level.getBlockState(sheep.blockPosition().below()).is(Blocks.GRASS_BLOCK)))
                .stream().anyMatch(sheep -> canApproach(worker, sheep));
    }

    static Optional<Sheep> trialSheep(ServerLevel level, Villager worker, net.minecraft.core.BlockPos loom, WorkOrder order) {
        var pasture = new AABB(loom).inflate(32);
        var context = new ShepherdContext(GlobalPos.of(level.dimension(), loom));
        return level.getEntitiesOfClass(Sheep.class, pasture, s -> worker.hasLineOfSight(s) && validTarget(level, worker, context, s, order, false))
                .stream().sorted(Comparator.comparingDouble(worker::distanceToSqr))
                .filter(s -> canApproach(worker, s)).findFirst();
    }

    private static boolean withinReach(Villager worker, Sheep sheep) {
        return worker.distanceToSqr(sheep) <= WORK_REACH_SQUARED && worker.hasLineOfSight(sheep);
    }

    private static boolean canApproach(Villager worker, Sheep sheep) {
        if (withinReach(worker, sheep)) return true;
        var path = worker.getNavigation().createPath(sheep, 0);
        return path != null && path.canReach();
    }

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

    private record ShepherdContext(GlobalPos jobSite) {
    }
}
