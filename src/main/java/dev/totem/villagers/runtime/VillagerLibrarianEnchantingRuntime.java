package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.inventory.VillagerPhysicalStock;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.schedule.VillagerWorkScheduler;
import dev.totem.villagers.schedule.WorkCandidate;
import dev.totem.villagers.schedule.WorkScheduleInput;
import dev.totem.villagers.trade.LibrarianEnchantedBookTrades;
import dev.totem.villagers.trade.LibrarianEnchantedEquipmentTrades;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.work.WorldWorkTarget;
import dev.totem.villagers.workshop.LibrarianEnchantingWorkshopAction;
import dev.totem.villagers.workshop.LibrarianEnchantingEquipmentWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import dev.totem.villagers.world.WorldWorkPermissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;

/**
 * Drives a librarian's real book-and-lapis enchanting-table work. A table is
 * a bounded target rather than a second claimed profession POI, so the normal
 * Lectern profession remains intact and no chunk is force-loaded.
 */
public final class VillagerLibrarianEnchantingRuntime {
    public static final String ORDER_PREFIX = "totem:librarian_enchanting_";
    public static final int WORK_TICKS = 120;
    private static final int TABLE_SEARCH_RADIUS = 16;
    private static final int[] TABLE_Y_OFFSETS = {0, -1, 1};
    private static final double WORK_REACH_SQUARED = 16.0D;
    private static final VillagerWorkScheduler SCHEDULER = new VillagerWorkScheduler();
    private static final WorkshopCommitService COMMITS = new WorkshopCommitService();

    private VillagerLibrarianEnchantingRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerLibrarianEnchantingRuntime::tick);
    }

    /** Runs the production path synchronously for GameTests. */
    public static void tickForGameTest(MinecraftServer server) {
        run(server, true);
    }

    public static WorkOrder orderForVillagerLevel(int villagerLevel) {
        return new WorkOrder(ORDER_PREFIX + villagerLevel, "minecraft:librarian",
                new ItemAmount("minecraft:enchanted_book", 1), List.of(
                        new ItemAmount("minecraft:book", 1),
                        new ItemAmount("minecraft:lapis_lazuli", LibrarianEnchantingRules.lapisCost(villagerLevel))
                ), Set.of(WorkSource.ENCHANTING), "", WORK_TICKS, 1);
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        run(server, false);
    }

    private static void run(MinecraftServer server, boolean forceIdleScan) {
        VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (isLibrarian(villager)) {
                    tickLibrarian(level, villager, states, inventories, forceIdleScan);
                }
            }
        }
    }

    private static void tickLibrarian(
            ServerLevel level, Villager librarian, VillagerWorkSavedData states, VillagerWorkInventorySavedData inventories,
            boolean forceIdleScan
    ) {
        VillagerWorkState state = states.getOrCreate(librarian.getUUID());
        if (!VillagerWorkNeeds.canWork(librarian)) {
            VillagerWorkState paused = VillagerWorkNeeds.pauseForHunger(state);
            if (!paused.equals(state)) {
                states.put(paused);
            }
            librarian.getNavigation().stop();
            return;
        }
        if (state.activeWork().isPresent() && state.activeWork().orElseThrow().source() != WorkSource.ENCHANTING) {
            return;
        }
        boolean active = state.activeWork().isPresent();
        if (!active && !forceIdleScan && !VillagerRuntimeBudget.dueForIdleScan(level, librarian)) {
            return;
        }

        int villagerLevel = librarian.getVillagerData().level();
        List<WorkOrder> orders = ordersForVillagerLevel(villagerLevel);
        WorkOrderCatalog catalog = new WorkOrderCatalog(orders);
        VillagerWorkInventory inventory = inventories.inventory(librarian.getUUID());
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) librarian).totemVillagers$existingOffers();
        if (offers == null) {
            offers = librarian.getOffers();
        }
        MerchantOffers liveOffers = offers;
        LibrarianEnchantedBookTrades.removeLegacyOffers(liveOffers);
        LibrarianEnchantedEquipmentTrades.removeLegacyOffers(liveOffers);
        MerchantStock stock = VillagerPhysicalStock.snapshot(inventory, level.registryAccess());

        Optional<WorkOrder> activeOrder = state.activeWork()
                .filter(activeWork -> activeWork.source() == WorkSource.ENCHANTING)
                .flatMap(activeWork -> orders.stream().filter(order -> order.id().equals(activeWork.orderId())).findFirst());
        Optional<BlockPos> table = activeTable(level, librarian, state)
                .or(() -> active ? Optional.empty() : findTable(level, librarian));
        List<WorkCandidate> candidates = table.map(target -> candidates(level, target, inventory, liveOffers, stock, orders, activeOrder))
                .orElseGet(List::of);
        boolean atTable = table.map(target -> librarian.distanceToSqr(Vec3.atCenterOf(target)) <= WORK_REACH_SQUARED).orElse(false);
        WorkScheduleInput input = new WorkScheduleInput(librarian.getUUID(), "minecraft:librarian", level.getGameTime(),
                librarian.isAlive(), level.isLoaded(librarian.blockPosition()), inDanger(librarian), librarian.isSleeping(),
                level.isRaided(librarian.blockPosition()), table.isPresent() && (!candidates.isEmpty() || activeOrder.isPresent()), atTable, candidates);
        var scheduled = SCHEDULER.tick(catalog, state, input);
        VillagerWorkState next = scheduled.state();
        if (scheduled.readyToCommit().isPresent()) {
            next = commit(level, librarian, scheduled.state(), scheduled.readyToCommit().orElseThrow(),
                    table.orElse(null), inventory, offers);
        }

        if (state.activeWork().isPresent() && next.activeWork().isEmpty()) {
            librarian.getNavigation().stop();
        } else if (next.activeWork().flatMap(value -> value.worldTarget()).flatMap(WorldWorkTarget::packedBlockPosition).isPresent()
                && !atTable && !inDanger(librarian)
                && VillagerRuntimeBudget.dueForNavigationRetry(level, librarian)) {
            BlockPos target = BlockPos.of(next.activeWork().orElseThrow().worldTarget().orElseThrow()
                    .packedBlockPosition().orElseThrow());
            librarian.getNavigation().moveTo(target.getX() + .5D, target.getY(), target.getZ() + .5D, .5D);
        }
        if (!next.equals(state)) {
            states.put(next);
            VillagerTradeStockAuthority.refreshOffers(librarian, liveOffers);
        }
    }

    private static List<WorkOrder> ordersForVillagerLevel(int villagerLevel) {
        List<WorkOrder> orders = new ArrayList<>();
        orders.add(orderForVillagerLevel(villagerLevel));
        LibrarianEnchantingEquipmentRules.definitions().stream()
                .filter(definition -> villagerLevel >= definition.minimumLibrarianLevel())
                .map(definition -> orderForEquipment(definition, villagerLevel))
                .forEach(orders::add);
        return List.copyOf(orders);
    }

    /** Creates the material-backed table order for one of the old vanilla enchanted-equipment rows. */
    public static WorkOrder orderForEquipment(
            LibrarianEnchantingEquipmentRules.EquipmentDefinition definition, int librarianLevel
    ) {
        return new WorkOrder(definition.orderId(), "minecraft:librarian", new ItemAmount(definition.itemId(), 1), List.of(
                new ItemAmount(definition.itemId(), 1),
                new ItemAmount("minecraft:lapis_lazuli", LibrarianEnchantingRules.lapisCost(librarianLevel))
        ), Set.of(WorkSource.ENCHANTING), "", WORK_TICKS, 1);
    }

    private static List<WorkCandidate> candidates(
            ServerLevel level, BlockPos table, VillagerWorkInventory inventory, MerchantOffers offers, MerchantStock stock,
            List<WorkOrder> orders, Optional<WorkOrder> activeOrder
    ) {
        return orders.stream()
                .filter(order -> hasCapacity(order, offers, stock, level.registryAccess())
                        || activeOrder.map(active -> active.id().equals(order.id())).orElse(false))
                .filter(order -> canReserve(inventory, order))
                .map(order -> new WorkCandidate(order.id(), WorkSource.ENCHANTING, priority(order),
                        Optional.of(new WorldWorkTarget(level.dimension().identifier().toString(), table.asLong()))))
                .toList();
    }

    private static boolean canReserve(VillagerWorkInventory inventory, WorkOrder order) {
        Optional<LibrarianEnchantingEquipmentRules.EquipmentDefinition> equipment =
                LibrarianEnchantingEquipmentRules.definitionForOrder(order.id());
        return equipment.map(definition -> inventory.snapshot().stream()
                        .anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, definition.baseStack())
                                && stack.getCount() >= 1)
                        && inventory.canReserveExact(order.requiredInputs().subList(1, order.requiredInputs().size())))
                .orElseGet(() -> inventory.canReserveExact(order.requiredInputs()));
    }

    private static boolean hasCapacity(WorkOrder order, MerchantOffers offers, MerchantStock stock,
                                       net.minecraft.core.HolderLookup.Provider registries) {
        return LibrarianEnchantingEquipmentRules.definitionForOrder(order.id()).isPresent()
                ? LibrarianEnchantedEquipmentTrades.hasCapacity(offers, stock, registries)
                : LibrarianEnchantedBookTrades.hasCapacity(offers, stock, registries);
    }

    private static int priority(WorkOrder order) {
        return LibrarianEnchantingEquipmentRules.definitionForOrder(order.id()).isPresent() ? 1 : 0;
    }

    private static VillagerWorkState commit(
            ServerLevel level, Villager librarian, VillagerWorkState state, WorkOrder order, BlockPos table,
            VillagerWorkInventory inventory, MerchantOffers offers
    ) {
        if (table == null || !validTable(level, librarian, table)) {
            return failed(state, order, "enchanting table changed");
        }
        MerchantStock stock = VillagerPhysicalStock.snapshot(inventory, level.registryAccess());
        WorkshopCommitResult result;
        Optional<LibrarianEnchantingEquipmentRules.EquipmentDefinition> equipment =
                LibrarianEnchantingEquipmentRules.definitionForOrder(order.id());
        if (equipment.isPresent()) {
            var reservation = inventory.reserveExactMatching(equipment.orElseThrow().baseStack(),
                    order.requiredInputs().subList(1, order.requiredInputs().size())).orElse(null);
            if (reservation == null) {
                return failed(state, order, "inputs unavailable");
            }
            LibrarianEnchantingEquipmentWorkshopAction action = new LibrarianEnchantingEquipmentWorkshopAction(
                    level, librarian, table, order, equipment.orElseThrow(), offers, stock);
            result = COMMITS.completePhysical(reservation, order, action,
                    completed -> producedOfferStack(completed, offers, level));
        } else {
            LibrarianEnchantingWorkshopAction action = new LibrarianEnchantingWorkshopAction(level, librarian, table, order, offers, stock);
            result = COMMITS.completePhysical(inventory, order, action,
                    completed -> producedOfferStack(completed, offers, level));
        }
        if (result == WorkshopCommitResult.COMPLETED) {
            TradeDiagnostic completed = new TradeDiagnostic(order.id(), WorkSource.ENCHANTING, order.workTicks(), "");
            return state.withActiveWork(Optional.empty(), Optional.of(completed));
        }
        return failed(state, order, switch (result) {
            case INPUTS_UNAVAILABLE -> "inputs unavailable";
            case INPUT_NOT_ACCEPTED -> "input not accepted";
            case RETURN_UNAVAILABLE -> "personal work inventory cannot return crafting remainder";
            case JOB_SITE_REJECTED -> "enchanting result rejected";
            case COMPLETED -> throw new IllegalStateException("Completed enchanting work was not committed");
        });
    }

    private static ItemStack producedOfferStack(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        return offers.stream().map(net.minecraft.world.item.trading.MerchantOffer::getResult)
                .filter(result -> !result.isEmpty())
                .filter(result -> dev.totem.villagers.work.StockVariantKey.fromStack(result, level.registryAccess())
                        .equals(order.outputKey()))
                .findFirst().map(result -> result.copyWithCount(order.output().count())).orElse(ItemStack.EMPTY);
    }

    private static Optional<BlockPos> activeTable(ServerLevel level, Villager librarian, VillagerWorkState state) {
        return state.activeWork().filter(active -> active.source() == WorkSource.ENCHANTING)
                .flatMap(active -> active.worldTarget())
                .filter(target -> target.dimensionId().equals(level.dimension().identifier().toString()))
                .flatMap(WorldWorkTarget::packedBlockPosition).map(BlockPos::of)
                .filter(target -> validTable(level, librarian, target));
    }

    private static Optional<BlockPos> findTable(ServerLevel level, Villager librarian) {
        BlockPos origin = librarian.blockPosition();
        for (int yOffset : TABLE_Y_OFFSETS) {
            int y = origin.getY() + yOffset;
            for (int radius = 0; radius <= TABLE_SEARCH_RADIUS; radius++) {
                for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
                    for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                        if (radius > 0 && x != origin.getX() - radius && x != origin.getX() + radius
                                && z != origin.getZ() - radius && z != origin.getZ() + radius) {
                            continue;
                        }
                        BlockPos target = new BlockPos(x, y, z);
                        if (validTable(level, librarian, target)) {
                            return Optional.of(target);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static boolean validTable(ServerLevel level, Villager librarian, BlockPos table) {
        return level.isLoaded(table)
                && level.getBlockState(table).is(Blocks.ENCHANTING_TABLE)
                && WorldWorkPermissions.mayWork(level, librarian, table)
                && (librarian.distanceToSqr(Vec3.atCenterOf(table)) <= WORK_REACH_SQUARED
                    || librarian.getNavigation().createPath(table, 0) != null);
    }

    private static VillagerWorkState failed(VillagerWorkState state, WorkOrder order, String reason) {
        return state.withActiveWork(Optional.empty(), Optional.of(new TradeDiagnostic(
                order.id(), WorkSource.ENCHANTING, order.workTicks(), reason)));
    }

    private static boolean inDanger(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET);
    }

    private static boolean isLibrarian(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:librarian".equals(id.toString());
    }
}
