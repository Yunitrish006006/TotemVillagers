package dev.totem.villagers.trade;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.guard.GuardPostFeedback;
import dev.totem.villagers.guard.ManagedVillageSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.inventory.VillagerPhysicalStock;
import dev.totem.villagers.network.TradeSnapshotPayload;
import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.workshop.CartographerExplorerMapWorkshopAction;
import dev.totem.villagers.worker.BlockCoordinate;
import dev.totem.villagers.worker.WorkZoneFeedback;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Produces the trade-screen state directly from server offers and persisted work state. */
public final class TradeSnapshotSender {
    private TradeSnapshotSender() {
    }

    public static void send(ServerPlayer player, Villager villager, MerchantOffers offers, int containerId) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayNetworking.send(player, snapshot(level, villager, offers, containerId));
    }

    public static TradeSnapshotPayload snapshot(ServerLevel level, Villager villager, MerchantOffers offers, int containerId) {
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode();
        List<TradeSnapshotPayload.WorkInventorySlot> inventory = inventorySnapshot(level, villager);
        List<TradeSnapshotPayload.ReservedMaterial> protectedMaterials = protectedMaterials(level, villager);
        Optional<TradeSnapshotPayload.WorkZoneStatus> workZone = workZoneFeedback(level, villager);
        Optional<TradeSnapshotPayload.GuardPostStatus> guardPost = guardPostFeedback(level, villager);
        if (!mode.enforcesWorkBackedTrading()) {
            return new TradeSnapshotPayload(containerId, villager.getUUID(), List.of(), inventory, protectedMaterials, workZone, guardPost);
        }
        VillagerWorkState state = VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(villager.getUUID());
        MerchantStock stock = VillagerPhysicalStock.snapshot(
                VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID()),
                level.registryAccess());
        WorkOrderCatalog catalog = effectiveCatalog(level, villager, offers);
        List<TradeSnapshotPayload.Offer> entries = java.util.stream.IntStream.range(0, offers.size())
                .filter(index -> VillagerOfferSides.isVillagerSellOffer(offers.get(index)))
                .mapToObj(index -> offerSnapshot(index, offers.get(index), state, stock, catalog, level,
                        villager))
                .toList();
        return new TradeSnapshotPayload(containerId, villager.getUUID(), entries, inventory, protectedMaterials, workZone, guardPost);
    }

    private static TradeSnapshotPayload.Offer offerSnapshot(
            int index,
            MerchantOffer offer,
            VillagerWorkState state,
            MerchantStock stock,
            WorkOrderCatalog catalog,
            ServerLevel level,
            Villager villager
    ) {
        ItemStack result = offer.getResult();
        String itemId = result.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        StockVariantKey key = result.isEmpty() ? StockVariantKey.base(itemId) : StockVariantKey.fromStack(result, level.registryAccess());
        int villagerLevel = villager.getVillagerData().level();
        if (MinerLapisTrades.isManagedOffer(offer)) {
            return physicalMaterialOffer(index, itemId, result, level, villager,
                    catalog.snapshot().get("totem:miner_lapis_lazuli"));
        }
        if (GatheredMaterialTrades.isMinerManagedOffer(offer)) {
            return physicalMaterialOffer(index, itemId, result, level, villager,
                    catalog.snapshot().get("totem:miner_ores"));
        }
        if (LumberjackAppleTrades.isManagedOffer(offer)) {
            return physicalMaterialOffer(index, itemId, result, level, villager,
                    catalog.snapshot().get("totem:lumberjack_oak_logs"));
        }
        if (GatheredMaterialTrades.isLumberjackManagedOffer(offer)) {
            return physicalMaterialOffer(index, itemId, result, level, villager,
                    catalog.snapshot().get("totem:lumberjack_oak_logs"));
        }
        if (FarmerGatheredCropTrades.isManagedOffer(offer)) {
            return physicalMaterialOffer(index, itemId, result, level, villager,
                    catalog.snapshot().get("totem:farmer_" + itemId.substring(itemId.indexOf(':') + 1)));
        }
        if (LibrarianEnchantedBookTrades.isManagedOffer(offer)) {
            int available = stock.available(key);
            int lapis = LibrarianEnchantingRules.lapisCost(villagerLevel);
            return new TradeSnapshotPayload.Offer(index, itemId, available, result.getCount(), WorkSource.ENCHANTING.id(),
                    0, dev.totem.villagers.runtime.VillagerLibrarianEnchantingRuntime.WORK_TICKS,
                    available < result.getCount() ? "awaiting_stock" : "", List.of(
                            new TradeSnapshotPayload.RecipeInput("minecraft:book", 1),
                            new TradeSnapshotPayload.RecipeInput("minecraft:lapis_lazuli", lapis)
                    ));
        }
        if (LibrarianEnchantedEquipmentTrades.isManagedOffer(offer)) {
            int available = stock.available(key);
            LibrarianEnchantingEquipmentRules.EquipmentDefinition definition =
                    LibrarianEnchantingEquipmentRules.definitionFor(result).orElseThrow();
            int lapis = LibrarianEnchantingRules.lapisCost(villagerLevel);
            return new TradeSnapshotPayload.Offer(index, itemId, available, result.getCount(), WorkSource.ENCHANTING.id(),
                    0, dev.totem.villagers.runtime.VillagerLibrarianEnchantingRuntime.WORK_TICKS,
                    available < result.getCount() ? "awaiting_stock" : "", List.of(
                            new TradeSnapshotPayload.RecipeInput(definition.itemId(), 1),
                            new TradeSnapshotPayload.RecipeInput("minecraft:lapis_lazuli", lapis)
                    ));
        }
        if (CartographerExplorerMapTrades.isManagedOffer(offer)) {
            WorkOrder order = catalog.snapshot().get(CartographerExplorerMapWorkshopAction.EMPTY_MAP_ORDER_ID);
            int available = stock.available(key);
            int workTicks = order == null ? 0 : order.workTicks();
            return new TradeSnapshotPayload.Offer(index, itemId, available, result.getCount(), WorkSource.WORKSHOP.id(),
                    0, workTicks, available < result.getCount() ? "awaiting_stock" : "", List.of(
                            new TradeSnapshotPayload.RecipeInput("minecraft:paper", 8),
                            new TradeSnapshotPayload.RecipeInput("minecraft:compass", 1)
                    ));
        }
        WorkOrder order = catalog.snapshot().values().stream()
                .filter(candidate -> candidate.outputKey().equals(key))
                .findFirst().orElse(null);
        if (order == null) {
            return new TradeSnapshotPayload.Offer(index, itemId, 0, Math.max(0, result.getCount()), "", 0, 0, "unmapped");
        }
        int available = stock.available(key);
        Optional<TradeDiagnostic> diagnostic = state.diagnostic().filter(value -> order.id().equals(value.orderId()));
        Optional<dev.totem.villagers.work.ActiveWork> active = state.activeWork().filter(value -> order.id().equals(value.orderId()));
        WorkSource source = active.map(dev.totem.villagers.work.ActiveWork::source)
                .or(() -> diagnostic.map(TradeDiagnostic::source))
                .orElseGet(() -> order.allowedSources().stream()
                        .min(Comparator.comparing(WorkSource::id)).orElse(WorkSource.WORKSHOP));
        int progress = active.map(dev.totem.villagers.work.ActiveWork::elapsedTicks)
                .or(() -> diagnostic.map(TradeDiagnostic::progressTicks)).orElse(0);
        String blocked = diagnostic.map(TradeDiagnostic::blockedReason).map(TradeSnapshotReason::codeFor).orElse("");
        if (blocked.isBlank() && available < result.getCount()) {
            blocked = "awaiting_stock";
        }
        return new TradeSnapshotPayload.Offer(index, itemId, available, result.getCount(), source.id(),
                Math.min(progress, order.workTicks()), order.workTicks(), blocked,
                order.requiredInputs().stream()
                        .map(input -> new TradeSnapshotPayload.RecipeInput(input.itemId(), input.count()))
                        .toList());
    }

    private static TradeSnapshotPayload.Offer physicalMaterialOffer(
            int index, String itemId, ItemStack result, ServerLevel level, Villager villager, WorkOrder order
    ) {
        int available = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID())
                .countMatchingItem(result);
        int workTicks = order == null ? 0 : order.workTicks();
        return new TradeSnapshotPayload.Offer(index, itemId, available, result.getCount(), WorkSource.WORLD.id(),
                0, workTicks, available < result.getCount() ? "awaiting_stock" : "", List.of());
    }

    private static WorkOrderCatalog effectiveCatalog(ServerLevel level, Villager villager, MerchantOffers offers) {
        var professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        String professionId = professionKey == null ? "minecraft:none" : professionKey.toString();
        return WorkOrderCatalogs.effectiveFor(WorkOrderDefinitions.catalog(), professionId, offers, level);
    }

    private static List<TradeSnapshotPayload.WorkInventorySlot> inventorySnapshot(ServerLevel level, Villager villager) {
        List<ItemStack> slots = VillagerWorkInventorySavedData.forServer(level.getServer()).snapshot(villager.getUUID());
        return java.util.stream.IntStream.range(0, slots.size())
                .mapToObj(index -> {
                    ItemStack stack = slots.get(index);
                    if (stack.isEmpty()) {
                        return null;
                    }
                    return new TradeSnapshotPayload.WorkInventorySlot(index,
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static List<TradeSnapshotPayload.ReservedMaterial> protectedMaterials(ServerLevel level, Villager villager) {
        return ManagedVillageSavedData.forServer(level.getServer()).getByGuard(villager.getUUID())
                .flatMap(state -> state.construction())
                .map(construction -> construction.reservedInputs().stream()
                        .map(material -> new TradeSnapshotPayload.ReservedMaterial(material.itemId(), material.count()))
                        .toList())
                .orElseGet(List::of);
    }

    private static Optional<TradeSnapshotPayload.WorkZoneStatus> workZoneFeedback(ServerLevel level, Villager villager) {
        var professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        String professionId = professionKey == null ? "minecraft:none" : professionKey.toString();
        BlockCoordinate position = new BlockCoordinate(villager.getBlockX(), villager.getBlockY(), villager.getBlockZ());
        WorkerAssignmentSavedData assignments = WorkerAssignmentSavedData.forServer(level.getServer());
        return WorkZoneFeedback.evaluate(villager.getUUID(), professionId, level.dimension().identifier().toString(), position,
                        assignments.getAssignment(villager.getUUID()), assignments.zoneSnapshot())
                .map(feedback -> new TradeSnapshotPayload.WorkZoneStatus(feedback.roleId(), feedback.state().id(),
                        feedback.zoneId().map(Object::toString).orElse(""), feedback.zone().map(zone ->
                        new TradeSnapshotPayload.WorkZoneBoundary(zone.dimensionId(),
                                zone.minimum().x(), zone.minimum().y(), zone.minimum().z(),
                                zone.maximum().x(), zone.maximum().y(), zone.maximum().z()))));
    }

    private static Optional<TradeSnapshotPayload.GuardPostStatus> guardPostFeedback(ServerLevel level, Villager villager) {
        var professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        String professionId = professionKey == null ? "minecraft:none" : professionKey.toString();
        var village = ManagedVillageSavedData.forServer(level.getServer()).getByGuard(villager.getUUID());
        if (village.isEmpty() && !"totem:guard".equals(professionId)) {
            return Optional.empty();
        }
        GuardPostFeedback feedback = village.map(value -> GuardPostFeedback.evaluate(level.getServer(), value))
                .orElseGet(GuardPostFeedback::unregistered);
        return Optional.of(new TradeSnapshotPayload.GuardPostStatus(feedback.state().id(), feedback.managedGolems(),
                feedback.demand().targetGolems(), feedback.demand().nearbyThreatCount(), feedback.post().map(post -> {
            BlockPos pad = BlockPos.of(post.packedConstructionPad());
            return new TradeSnapshotPayload.GuardPostLocation(post.villageId().toString(), post.dimensionId(),
                    pad.getX(), pad.getY(), pad.getZ());
        }), feedback.construction().map(progress -> new TradeSnapshotPayload.GuardConstructionProgress(
                progress.orderId(), progress.placedSteps(), progress.totalSteps()))));
    }
}
