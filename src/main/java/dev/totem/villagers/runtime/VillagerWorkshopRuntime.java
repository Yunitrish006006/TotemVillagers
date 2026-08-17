package dev.totem.villagers.runtime;

import dev.totem.villagers.TotemVillagers;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.schedule.WorkshopCandidatePlanner;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.trade.VillagerOfferSides;
import dev.totem.villagers.work.FarmerSuspiciousStewOrders;
import dev.totem.villagers.work.FletcherTippedArrowOrders;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.LeatherworkerDyedArmorOrders;
import dev.totem.villagers.work.RemnantBackpackOrders;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.workshop.FarmerSuspiciousStewWorkshopAction;
import dev.totem.villagers.workshop.FletcherTippedArrowWorkshopAction;
import dev.totem.villagers.workshop.LeatherworkerDyedArmorWorkshopAction;
import dev.totem.villagers.workshop.CartographerExplorerMapWorkshopAction;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import dev.totem.villagers.workshop.ValidatedWorkshopAction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Server-tick bridge for workshop work. Only loaded villagers at their native job
 * site with personal work materials and an exact vanilla recipe can create stock.
 */
public final class VillagerWorkshopRuntime {
    private static final double NAVIGATION_REACH_SQUARED = 16.0D;
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final WorkshopCandidatePlanner CANDIDATES = new WorkshopCandidatePlanner();
    private static final WorkshopCommitService COMMITS = new WorkshopCommitService();

    private VillagerWorkshopRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerWorkshopRuntime::tick);
    }

    /**
     * Drives the same server-only workshop path used by the registered tick.
     * Kept public solely so an integration GameTest can advance a complete
     * player-material-to-trade-stock cycle without relying on test ordering.
     */
    public static void tickForGameTest(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() == WorkBackedTradingMode.ENFORCED) {
            run(server, true);
        }
    }

    /**
     * Returns the one live, recipe-valid workshop order this villager would
     * next try to supply. Village logistics uses this to request only actual
     * ingredients for an existing sell offer; it never guesses from a static
     * profession table or bypasses a changed data-pack recipe.
     */
    public static Optional<WorkOrder> materialDemandFor(ServerLevel level, Villager villager) {
        if (!VillagerWorkNeeds.canWork(villager)) {
            return Optional.empty();
        }
        Optional<WorkshopContext> workshop = resolveWorkshop(level, villager,
                VillagerWorkInventorySavedData.forServer(level.getServer()));
        if (workshop.isEmpty()) {
            return Optional.empty();
        }
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers == null) {
            offers = villager.getOffers();
        }
        MerchantOffers liveOffers = offers;
        WorkshopContext context = workshop.orElseThrow();
        String professionId = professionId(villager);
        WorkOrderCatalog catalog = WorkOrderCatalogs.effectiveFor(WorkOrderDefinitions.catalog(), professionId, liveOffers, level);
        VillagerWorkState state = VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(villager.getUUID());
        if (state.activeWork().isPresent()) {
            if (state.activeWork().orElseThrow().source() != WorkSource.WORKSHOP) {
                return Optional.empty();
            }
            WorkOrder active = catalog.snapshot().get(state.activeWork().orElseThrow().orderId());
            return active != null && isLiveWorkshopOrder(active, level, villager, context, liveOffers)
                    ? Optional.of(active) : Optional.empty();
        }
        return catalog.snapshot().values().stream()
                .filter(order -> order.professionId().equals(professionId))
                .filter(order -> order.allowedSources().contains(WorkSource.WORKSHOP))
                .filter(order -> stockNeedsWork(context.inventory(), order, liveOffers, level))
                .filter(order -> isLiveWorkshopOrder(order, level, villager, context, liveOffers))
                .filter(order -> missingMaterialUnits(context.inventory(), order) > 0)
                .sorted(java.util.Comparator
                        .comparingInt((WorkOrder order) -> missingMaterialTypes(context.inventory(), order))
                        .thenComparingInt(order -> -availableMaterialTypes(context.inventory(), order))
                        .thenComparingInt(order -> missingMaterialUnits(context.inventory(), order))
                        .thenComparing(WorkOrder::id))
                .findFirst();
    }

    /** Prefer work that already has the greatest variety of its recipe inputs on hand. */
    private static int missingMaterialTypes(VillagerWorkInventory inventory, WorkOrder order) {
        return (int) order.requiredInputs().stream()
                .filter(input -> availableUnits(inventory, input.itemId()) < input.count())
                .count();
    }

    private static int availableMaterialTypes(VillagerWorkInventory inventory, WorkOrder order) {
        return (int) order.requiredInputs().stream()
                .filter(input -> availableUnits(inventory, input.itemId()) >= input.count())
                .count();
    }

    /** Break equal recipe-coverage ties by the smallest remaining number of units. */
    private static int missingMaterialUnits(VillagerWorkInventory inventory, WorkOrder order) {
        return order.requiredInputs().stream()
                .mapToInt(input -> Math.max(0, input.count() - availableUnits(inventory, input.itemId())))
                .sum();
    }

    private static int availableUnits(VillagerWorkInventory inventory, String itemId) {
        return inventory.snapshot().stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> itemId.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        if (catalog.snapshot().isEmpty()) {
            return;
        }
        VillagerWorkSavedData workStates = VillagerWorkSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
                tickVillager(level, villager, catalog, offers, workStates, inventories, forceIdleScan);
            }
        }
    }

    private static void tickVillager(
            ServerLevel level,
            Villager villager,
            WorkOrderCatalog catalog,
            MerchantOffers offers,
            VillagerWorkSavedData workStates,
            VillagerWorkInventorySavedData inventories,
            boolean forceIdleScan
    ) {
        String professionId = professionId(villager);
        boolean leatherworker = "minecraft:leatherworker".equals(professionId);
        boolean farmer = "minecraft:farmer".equals(professionId);
        boolean fletcher = "minecraft:fletcher".equals(professionId);
        if (!leatherworker && !farmer && !fletcher
                && catalog.snapshot().values().stream().noneMatch(order -> order.professionId().equals(professionId))) {
            return;
        }
        VillagerWorkState state = workStates.getOrCreate(villager.getUUID());
        if (!VillagerWorkNeeds.canWork(villager)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                workStates.put(paused);
            }
            villager.getNavigation().stop();
            return;
        }
        // World-source actions own their active cycle. A workshop tick must not
        // cancel them merely because its own candidate list contains no world work.
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.WORKSHOP) {
            return;
        }
        if (state.activeWork().isEmpty() && !forceIdleScan
                && !VillagerRuntimeBudget.dueForIdleScan(level, villager)) {
            return;
        }
        Optional<WorkshopContext> workshop = resolveWorkshop(level, villager, inventories);
        MerchantOffers liveOffers = offers;
        if (liveOffers == null && workshop.isPresent()) {
            // Generate vanilla purchase rows and any component-bound special rows only after a native station exists.
            // Ordinary sell production is now authorised by the profession catalogue, not a random rolled sell list.
            liveOffers = villager.getOffers();
        }
        WorkOrderCatalog effectiveCatalog = WorkOrderCatalogs.effectiveFor(catalog, professionId, liveOffers, level);
        if (effectiveCatalog.snapshot().values().stream().noneMatch(order -> order.professionId().equals(professionId))) {
            return;
        }
        final WorkOrderCatalog candidateCatalog = effectiveCatalog;
        MerchantOffers candidateOffers = liveOffers;
        List<WorkCandidate> candidates = workshop.map(context -> CANDIDATES.candidates(
                        candidateCatalog, professionId, true).stream()
                .filter(candidate -> eligibleWorkshopOrder(candidateCatalog.require(candidate.orderId()), level, villager, context, candidateOffers))
                .filter(candidate -> stockNeedsWork(context.inventory(), candidateCatalog.require(candidate.orderId()), candidateOffers, level))
                .toList())
                .orElseGet(List::of);
        boolean atWorkLocation = workshop.map(context -> atWorkLocation(villager, context.jobSite())).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(
                villager.getUUID(), professionId, level.getGameTime(), villager.isAlive(),
                level.isLoaded(villager.blockPosition()), inDanger(villager), villager.isSleeping(),
                level.isRaided(villager.blockPosition()), workshop.isPresent(), atWorkLocation, candidates);
        var scheduled = SCHEDULER.tick(effectiveCatalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commitReadyWorkshopWork(scheduled.state(), scheduled.readyToCommit().orElseThrow(), workshop.orElse(null), level, villager, liveOffers);
        }

        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            villager.getNavigation().stop();
        } else if (next.activeWork().isPresent() && workshop.isPresent() && !atWorkLocation && !inDanger(villager)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, villager)) {
            BlockPos jobSite = workshop.orElseThrow().jobSite();
            villager.getNavigation().moveTo(jobSite.getX() + .5D, jobSite.getY(), jobSite.getZ() + .5D, .5D);
        }
        if (!next.equals(state)) {
            workStates.put(next);
            // A successful workshop job makes its exact recipe-backed offer
            // available immediately.
            if (liveOffers != null) {
                VillagerTradeStockAuthority.refreshOffers(villager, liveOffers);
            }
        }
    }

    private static VillagerWorkState commitReadyWorkshopWork(
            VillagerWorkState state,
            WorkOrder order,
            WorkshopContext workshop,
            ServerLevel level,
            Villager villager,
            MerchantOffers offers
    ) {
        if (workshop == null) {
            return failed(state, order, "job site changed");
        }
        ValidatedWorkshopAction action = workshopAction(order, level, villager, workshop.jobSite(), offers);
        if (action == null) {
            return failed(state, order, "job site recipe rejected");
        }
        WorkshopCommitResult result;
        if (FletcherTippedArrowOrders.isOfferBoundTippedArrowOrder(order)) {
            var reservation = FletcherTippedArrowOrders.reserve(order, workshop.inventory(), offers, level).orElse(null);
            if (reservation == null) {
                return failed(state, order, "inputs unavailable");
            }
            result = COMMITS.completePhysical(reservation, order, action,
                    completed -> producedOfferStack(completed, offers, level));
        } else if (RemnantBackpackOrders.isBackpackOrder(order)) {
            var reservation = RemnantBackpackOrders.reservePristineInputs(order, workshop.inventory()).orElse(null);
            if (reservation == null) {
                return failed(state, order, "pristine backpack inputs unavailable");
            }
            result = COMMITS.completePhysical(reservation, order, action,
                    completed -> producedOfferStack(completed, offers, level));
        } else {
            result = COMMITS.completePhysical(workshop.inventory(), order, action,
                    completed -> producedOfferStack(completed, offers, level));
        }
        if (result == WorkshopCommitResult.COMPLETED) {
            TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.WORKSHOP, order.workTicks(), "");
            return state.withActiveWork(Optional.empty(), Optional.of(completed));
        }
        return failed(state, order, commitFailure(result));
    }

    private static VillagerWorkState failed(VillagerWorkState state, WorkOrder order, String reason) {
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(
                order.id(), WorkSource.WORKSHOP, order.workTicks(), reason)));
    }

    private static String commitFailure(WorkshopCommitResult result) {
        return switch (result) {
            case INPUTS_UNAVAILABLE -> "inputs unavailable";
            case INPUT_NOT_ACCEPTED -> "workshop source unavailable";
            case RETURN_UNAVAILABLE -> "personal work inventory cannot return crafting remainder";
            case JOB_SITE_REJECTED -> "job site recipe rejected";
            case COMPLETED -> throw new IllegalArgumentException("completed work has no failure reason");
        };
    }

    private static Optional<WorkshopContext> resolveWorkshop(
            ServerLevel level,
            Villager villager,
            VillagerWorkInventorySavedData inventories
    ) {
        GlobalPos nativeJobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null);
        if (nativeJobSite == null
                || !nativeJobSite.dimension().equals(level.dimension())
                || !level.isLoaded(nativeJobSite.pos())) {
            return Optional.empty();
        }
        return Optional.of(new WorkshopContext(nativeJobSite.pos(), inventories.inventory(villager.getUUID())));
    }

    private static boolean eligibleWorkshopOrder(
            WorkOrder order, ServerLevel level, Villager villager, WorkshopContext context, MerchantOffers offers
    ) {
        if (!isLiveWorkshopOrder(order, level, villager, context, offers)) {
            return false;
        }
        return FletcherTippedArrowOrders.isOfferBoundTippedArrowOrder(order)
                ? FletcherTippedArrowOrders.canReserve(order, context.inventory(), offers, level)
                : RemnantBackpackOrders.isBackpackOrder(order)
                ? RemnantBackpackOrders.canReservePristineInputs(order, context.inventory())
                : context.inventory().canReserveExact(order.requiredInputs());
    }

    private static boolean isLiveWorkshopOrder(
            WorkOrder order, ServerLevel level, Villager villager, WorkshopContext context, MerchantOffers offers
    ) {
        return (order.outputComponentPatch().isEmpty() || hasMatchingSellOffer(order, level, offers))
                && workshopAction(order, level, villager, context.jobSite(), offers) != null;
    }

    private static boolean stockNeedsWork(VillagerWorkInventory inventory, WorkOrder order,
                                          MerchantOffers offers, ServerLevel level) {
        ItemStack output = producedOfferStack(order, offers, level);
        int current = output.isEmpty() ? 0 : inventory.countMatchingItem(output);
        return current <= order.stockCap() - order.output().count();
    }

    private static ItemStack producedOfferStack(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        if (order.outputComponentPatch().isEmpty()) {
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getValue(
                    net.minecraft.resources.Identifier.parse(order.output().itemId()));
            return item == null ? ItemStack.EMPTY : new ItemStack(item, order.output().count());
        }
        if (offers == null) {
            return ItemStack.EMPTY;
        }
        return offers.stream().map(MerchantOffer::getResult)
                .filter(result -> !result.isEmpty())
                .filter(result -> StockVariantKey.fromStack(result, level.registryAccess()).equals(order.outputKey()))
                .findFirst().map(result -> result.copyWithCount(order.output().count())).orElse(ItemStack.EMPTY);
    }

    /** Component-bound special outputs still require their exact generated physical variant. */
    private static boolean hasMatchingSellOffer(WorkOrder order, ServerLevel level, MerchantOffers offers) {
        return offers != null && offers.stream()
                .filter(VillagerOfferSides::isVillagerSellOffer)
                .map(MerchantOffer::getResult)
                .filter(result -> !result.isEmpty())
                .map(result -> StockVariantKey.fromStack(result, level.registryAccess()))
                .anyMatch(order.outputKey()::equals);
    }

    private static ValidatedWorkshopAction workshopAction(
            WorkOrder order, ServerLevel level, Villager villager, BlockPos jobSite, MerchantOffers offers
    ) {
        if (FarmerSuspiciousStewOrders.isOfferBoundSuspiciousStewOrder(order)) {
            return offers != null && FarmerSuspiciousStewWorkshopAction.supports(order, level, jobSite, offers)
                    ? new FarmerSuspiciousStewWorkshopAction(level, villager, jobSite, order, offers) : null;
        }
        if (offers != null && FletcherTippedArrowWorkshopAction.supports(order, level, jobSite, offers)) {
            return new FletcherTippedArrowWorkshopAction(level, villager, jobSite, order, offers);
        }
        if (offers != null && LeatherworkerDyedArmorWorkshopAction.supports(order, level, jobSite, offers)) {
            return new LeatherworkerDyedArmorWorkshopAction(level, villager, jobSite, order, offers);
        }
        if (offers != null && CartographerExplorerMapWorkshopAction.supports(order, level, jobSite)) {
            return new CartographerExplorerMapWorkshopAction(level, villager, jobSite, order, offers);
        }
        if (RecipeBackedWorkshopAction.supports(order, level, jobSite)) {
            return new RecipeBackedWorkshopAction(level, villager, jobSite, order);
        }
        return null;
    }

    private static boolean atWorkLocation(Villager villager, BlockPos jobSite) {
        return villager.distanceToSqr(Vec3.atCenterOf(jobSite)) <= NAVIGATION_REACH_SQUARED;
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

    private record WorkshopContext(BlockPos jobSite, VillagerWorkInventory inventory) {
    }
}
