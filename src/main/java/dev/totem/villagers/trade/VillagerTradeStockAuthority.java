package dev.totem.villagers.trade;

import dev.totem.villagers.TotemVillagers;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.inventory.VillagerPhysicalStock;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.mixin.MerchantMenuTraderAccessor;
import dev.totem.villagers.work.TradeDiagnostic;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-only bridge between vanilla offers and durable merchant stock. It gates
 * unmapped offers as well as empty stock, so a partially loaded catalogue can never
 * accidentally create free sell stock.
 */
public final class VillagerTradeStockAuthority {
    private VillagerTradeStockAuthority() {
    }

    /**
     * Custom specialist professions intentionally have no vanilla trade set.
     * Seed their server-owned material row before {@link Villager#mobInteract}
     * checks whether an offer list is empty, otherwise vanilla refuses to open
     * a merchant menu for them.
     */
    public static void ensureSpecialistTradeMenu(Villager villager) {
        if (!(villager.level() instanceof ServerLevel) || (!isMiner(villager) && !isLumberjack(villager))) {
            return;
        }
        AbstractVillagerOffersAccessor accessor = (AbstractVillagerOffersAccessor) (Object) villager;
        MerchantOffers offers = accessor.totemVillagers$existingOffers();
        if (offers == null) {
            offers = new MerchantOffers();
            accessor.totemVillagers$setExistingOffers(offers);
        }
        refreshOffers(villager, offers);
    }

    public static void refreshOffers(AbstractVillager merchant, MerchantOffers offers) {
        if (!(merchant instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode();
        if (!mode.enforcesWorkBackedTrading()) {
            // These custom professions deliberately have no vanilla trade set.
            // Keep one locked specialist row so normal interaction still opens
            // a merchant menu, without allowing a disabled/rollback economy to
            // produce free material stock.
            if (isMiner(villager)) {
                MinerLapisTrades.ensureOffer(offers);
                offers.forEach(MerchantOffer::setToOutOfStock);
                return;
            }
            if (isLumberjack(villager)) {
                LumberjackAppleTrades.ensureOffer(offers);
                offers.forEach(MerchantOffer::setToOutOfStock);
                return;
            }
            if (mode == WorkBackedTradingMode.VANILLA_ROLLBACK) {
                offers.forEach(MerchantOffer::resetUses);
            }
            return;
        }

        VillagerWorkState state = VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(villager.getUUID());
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID());
        if (isToolsmith(villager)) {
            ToolsmithBucketTrades.syncOffer(offers, inventory);
            ToolsmithBackpackMaterialTrades.syncOffers(offers, level);
        }
        if (isMiner(villager)) {
            GatheredMaterialTrades.syncMinerOffers(offers, inventory);
        }
        if (isLumberjack(villager)) {
            GatheredMaterialTrades.syncLumberjackOffers(offers, inventory);
        }
        if (isFarmer(villager)) {
            FarmerGatheredCropTrades.syncOffers(offers, inventory);
        }
        if (isCartographer(villager)) {
            CartographerExplorerMapTrades.removeLegacyOffers(offers);
            CartographerExplorerMapTrades.pruneEmptyOffers(offers,
                    VillagerPhysicalStock.snapshot(inventory, level.registryAccess()), level.registryAccess());
        }
        if (isLibrarian(villager)) {
            LibrarianEnchantedBookTrades.removeLegacyOffers(offers);
            LibrarianEnchantedBookTrades.pruneEmptyOffers(offers,
                    VillagerPhysicalStock.snapshot(inventory, level.registryAccess()), level.registryAccess());
        }
        LibrarianEnchantedEquipmentTrades.removeLegacyOffers(offers);
        if (isLibrarian(villager)) {
            LibrarianEnchantedEquipmentTrades.pruneEmptyOffers(offers,
                    VillagerPhysicalStock.snapshot(inventory, level.registryAccess()), level.registryAccess());
        }
        InventoryDrivenProfessionTrades.syncOffers(villager, offers, inventory, level);

        // Complete mode exposes only real, lawful stock. Random vanilla sell rows with no work authority disappear;
        // player-facing purchase rows remain unchanged.
        List<MerchantOffer> unmappedSellRows = offers.stream()
                .filter(VillagerOfferSides::isVillagerSellOffer)
                .filter(offer -> decide(mode, state, level, villager, offers, offer) == OfferStockDecision.UNMAPPED)
                .toList();
        unmappedSellRows.forEach(MerchantOffer::setToOutOfStock);
        offers.removeAll(unmappedSellRows);
        for (MerchantOffer offer : offers) {
            if (!VillagerOfferSides.isVillagerSellOffer(offer)) {
                if (canStorePlayerPurchaseInputs(offer, inventory)) {
                    offer.resetUses();
                } else {
                    offer.setToOutOfStock();
                }
                continue;
            }
            OfferStockDecision decision = decide(mode, state, level, villager, offers, offer);
            if (decision == OfferStockDecision.AVAILABLE && canStorePlayerPayment(offer, inventory)) {
                offer.resetUses();
            } else {
                offer.setToOutOfStock();
                recordBlockedDiagnostic(level, state, itemId(offer.getResult()), decision);
            }
        }
    }

    /**
     * Explicit rollback path for currently loaded villagers. It restores only
     * vanilla offer use counters and deliberately leaves durable merchant stock
     * untouched for a later re-enable.
     */
    public static boolean restoreVanillaRestocking(Villager villager) {
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers == null) {
            return false;
        }
        offers.forEach(MerchantOffer::resetUses);
        return true;
    }

    /** Called only after vanilla accepted a trade; the pre-trade offer gate makes this debit single-threaded and exact. */
    public static void debitAfterSuccessfulTrade(AbstractVillager merchant, MerchantOffer offer) {
        if (!(merchant instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (!VillagerOfferSides.isVillagerSellOffer(offer)) {
            return;
        }
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode();
        if (!mode.enforcesWorkBackedTrading()) {
            return;
        }
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers == null) {
            return;
        }
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID());
        if (isMiner(villager) && MinerLapisTrades.isManagedOffer(offer)) {
            OfferStockDecision decision = MinerLapisTrades.decision(offer, inventory);
            debitPhysicalMaterialSale(villager, offers, offer, decision,
                    decision == OfferStockDecision.AVAILABLE && MinerLapisTrades.debit(inventory));
            return;
        }
        if (isMiner(villager) && GatheredMaterialTrades.isMinerManagedOffer(offer)) {
            OfferStockDecision decision = GatheredMaterialTrades.minerDecision(offer, inventory);
            debitPhysicalMaterialSale(villager, offers, offer, decision,
                    decision == OfferStockDecision.AVAILABLE && GatheredMaterialTrades.debitMiner(offer, inventory));
            return;
        }
        if (isLumberjack(villager) && LumberjackAppleTrades.isManagedOffer(offer)) {
            OfferStockDecision decision = LumberjackAppleTrades.decision(offer, inventory);
            debitPhysicalMaterialSale(villager, offers, offer, decision,
                    decision == OfferStockDecision.AVAILABLE && LumberjackAppleTrades.debit(inventory));
            return;
        }
        if (isLumberjack(villager) && GatheredMaterialTrades.isLumberjackManagedOffer(offer)) {
            OfferStockDecision decision = GatheredMaterialTrades.lumberjackDecision(offer, inventory);
            debitPhysicalMaterialSale(villager, offers, offer, decision,
                    decision == OfferStockDecision.AVAILABLE && GatheredMaterialTrades.debitLumberjack(offer, inventory));
            return;
        }
        if (isFarmer(villager) && FarmerGatheredCropTrades.isManagedOffer(offer)) {
            OfferStockDecision decision = FarmerGatheredCropTrades.decision(offer, inventory);
            debitPhysicalMaterialSale(villager, offers, offer, decision,
                    decision == OfferStockDecision.AVAILABLE && FarmerGatheredCropTrades.debit(offer, inventory));
            return;
        }

        VillagerWorkSavedData saved = VillagerWorkSavedData.forServer(level.getServer());
        VillagerWorkState state = saved.getOrCreate(villager.getUUID());
        if (decide(mode, state, level, villager, offers, offer) != OfferStockDecision.AVAILABLE) {
            TotemVillagers.LOGGER.error("Rejected post-trade stock debit mismatch for villager {} offer {}", villager.getUUID(), itemId(offer.getResult()));
            offer.setToOutOfStock();
            return;
        }

        ItemStack result = offer.getResult();
        if (inventory.takeExactMatchingItem(result).isEmpty()) {
            throw new IllegalStateException("Trade stock changed after a successful server-side offer gate");
        }
        refreshOffers(villager, offers);
    }

    /**
     * Records the actual materials a player sold through a vanilla purchase
     * offer. Vanilla consumes the menu inputs before it calls notifyTrade, so
     * this is the only authoritative moment to move that exact cost into the
     * villager's separate persistent work inventory.
     */
    public static void creditAfterSuccessfulPlayerPurchase(AbstractVillager merchant, MerchantOffer offer) {
        if (!(merchant instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)
                || villager.getTradingPlayer() == null || VillagerOfferSides.isVillagerSellOffer(offer)) {
            return;
        }
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode();
        if (!mode.enforcesWorkBackedTrading()) {
            return;
        }
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID());
        List<ItemStack> paidInputs = playerPurchaseInputs(offer);
        if (paidInputs.isEmpty()) {
            return;
        }
        ItemStack emeraldPayout = offer.getResult().is(net.minecraft.world.item.Items.EMERALD)
                ? offer.getResult().copy() : ItemStack.EMPTY;
        if (!emeraldPayout.isEmpty() && inventory.takeExactMatchingItem(emeraldPayout).isEmpty()) {
            TotemVillagers.LOGGER.error("Could not debit accepted emerald payout for villager {}", villager.getUUID());
            return;
        }
        if (!inventory.insertAllExact(paidInputs)) {
            // refreshOffers preflights this exact batch before vanilla permits
            // the trade. If an external hook changes the inventory afterwards,
            // fail loudly rather than silently treating consumed player items as
            // virtual stock.
            TotemVillagers.LOGGER.error("Could not store accepted player purchase inputs for villager {} offer {}",
                    villager.getUUID(), itemId(offer.getResult()));
            MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
            if (offers != null) {
                offer.setToOutOfStock();
                refreshOffers(villager, offers);
            }
            if (!emeraldPayout.isEmpty() && !inventory.insertExact(emeraldPayout)) {
                villager.spawnAtLocation(level, emeraldPayout);
            }
        }
    }

    /** Debits one work-backed output for a server-controlled villager-to-villager purchase. */
    public static boolean debitForAutonomousPurchase(Villager villager, MerchantOffer offer) {
        if (!(villager.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!VillagerOfferSides.isVillagerSellOffer(offer)) {
            return false;
        }
        WorkBackedTradingMode mode = WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode();
        if (!mode.enforcesWorkBackedTrading()) {
            return false;
        }
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers == null || !offers.contains(offer)) {
            return false;
        }
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID());
        if (isMiner(villager) && MinerLapisTrades.isManagedOffer(offer)) {
            if (MinerLapisTrades.decision(offer, inventory) != OfferStockDecision.AVAILABLE
                    || !MinerLapisTrades.debit(inventory)) {
                refreshOffers(villager, offers);
                return false;
            }
            refreshOffers(villager, offers);
            return true;
        }
        if (isMiner(villager) && GatheredMaterialTrades.isMinerManagedOffer(offer)) {
            if (GatheredMaterialTrades.minerDecision(offer, inventory) != OfferStockDecision.AVAILABLE
                    || !GatheredMaterialTrades.debitMiner(offer, inventory)) {
                refreshOffers(villager, offers);
                return false;
            }
            refreshOffers(villager, offers);
            return true;
        }
        if (isLumberjack(villager) && LumberjackAppleTrades.isManagedOffer(offer)) {
            if (LumberjackAppleTrades.decision(offer, inventory) != OfferStockDecision.AVAILABLE
                    || !LumberjackAppleTrades.debit(inventory)) {
                refreshOffers(villager, offers);
                return false;
            }
            refreshOffers(villager, offers);
            return true;
        }
        if (isLumberjack(villager) && GatheredMaterialTrades.isLumberjackManagedOffer(offer)) {
            if (GatheredMaterialTrades.lumberjackDecision(offer, inventory) != OfferStockDecision.AVAILABLE
                    || !GatheredMaterialTrades.debitLumberjack(offer, inventory)) {
                refreshOffers(villager, offers);
                return false;
            }
            refreshOffers(villager, offers);
            return true;
        }
        if (isFarmer(villager) && FarmerGatheredCropTrades.isManagedOffer(offer)) {
            if (FarmerGatheredCropTrades.decision(offer, inventory) != OfferStockDecision.AVAILABLE
                    || !FarmerGatheredCropTrades.debit(offer, inventory)) {
                refreshOffers(villager, offers);
                return false;
            }
            refreshOffers(villager, offers);
            return true;
        }
        VillagerWorkSavedData saved = VillagerWorkSavedData.forServer(level.getServer());
        VillagerWorkState state = saved.getOrCreate(villager.getUUID());
        if (decide(mode, state, level, villager, offers, offer) != OfferStockDecision.AVAILABLE) {
            refreshOffers(villager, offers);
            return false;
        }
        if (inventory.takeExactMatchingItem(offer.getResult()).isEmpty()) {
            return false;
        }
        refreshOffers(villager, offers);
        return true;
    }

    /**
     * Refreshes the vanilla offer packet only while this exact villager is the
     * merchant behind the player's currently open menu. The packet-tail mixin
     * then pairs it with a server-owned work snapshot for the same container.
     */
    public static void syncOpenTrade(Villager villager, ServerPlayer player, MerchantMenu menu) {
        if (villager.getTradingPlayer() != player
                || player.containerMenu != menu
                || ((MerchantMenuTraderAccessor) menu).totemVillagers$trader() != villager) {
            return;
        }
        MerchantOffers offers = ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$existingOffers();
        if (offers == null) {
            return;
        }
        refreshOffers(villager, offers);
        player.sendMerchantOffers(menu.containerId, offers, menu.getTraderLevel(), menu.getTraderXp(),
                menu.showProgressBar(), menu.canRestock());
    }

    private static OfferStockDecision decide(
            WorkBackedTradingMode mode,
            VillagerWorkState state,
            ServerLevel level,
            Villager villager,
            MerchantOffers offers,
            MerchantOffer offer
    ) {
        ItemStack result = offer.getResult();
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(villager.getUUID());
        if (isMiner(villager) && MinerLapisTrades.isManagedOffer(offer)) {
            return MinerLapisTrades.decision(offer, inventory);
        }
        if (isMiner(villager) && GatheredMaterialTrades.isMinerManagedOffer(offer)) {
            return GatheredMaterialTrades.minerDecision(offer, inventory);
        }
        if (isLumberjack(villager) && LumberjackAppleTrades.isManagedOffer(offer)) {
            return LumberjackAppleTrades.decision(offer, inventory);
        }
        if (isLumberjack(villager) && GatheredMaterialTrades.isLumberjackManagedOffer(offer)) {
            return GatheredMaterialTrades.lumberjackDecision(offer, inventory);
        }
        if (isFarmer(villager) && FarmerGatheredCropTrades.isManagedOffer(offer)) {
            return FarmerGatheredCropTrades.decision(offer, inventory);
        }
        if ((isLibrarian(villager) && (LibrarianEnchantedBookTrades.isManagedOffer(offer)
                || LibrarianEnchantedEquipmentTrades.isManagedOffer(offer)))
                || (isCartographer(villager) && CartographerExplorerMapTrades.isManagedOffer(offer))) {
            return inventory.countMatchingItem(result) >= result.getCount()
                    ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
        }
        if (InventoryDrivenProfessionTrades.isManagedOffer(villager, offer, level)) {
            return InventoryDrivenProfessionTrades.decision(villager, offer, inventory, level);
        }
        WorkOrderCatalog catalog = WorkOrderCatalogs.effectiveFor(
                WorkOrderDefinitions.catalog(), professionId(villager), offers, level);
        dev.totem.villagers.work.StockVariantKey resultKey =
                dev.totem.villagers.work.StockVariantKey.fromStack(result, level.registryAccess());
        boolean mapped = catalog.snapshot().values().stream()
                .filter(order -> order.professionId().equals(professionId(villager)))
                .anyMatch(order -> order.outputKey().equals(resultKey));
        if (!mapped) {
            return OfferStockDecision.UNMAPPED;
        }
        return inventory.countMatchingItem(result) >= result.getCount()
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }

    private static void recordBlockedDiagnostic(ServerLevel level, VillagerWorkState state, String outputId, OfferStockDecision decision) {
        String reason = switch (decision) {
            case UNMAPPED -> "unmapped sell offer";
            case INSUFFICIENT_STOCK -> "awaiting work stock";
            default -> "";
        };
        if (reason.isBlank()) {
            return;
        }
        TradeDiagnostic diagnostic = new TradeDiagnostic("totem:trade_stock", WorkSource.WORKSHOP, 0,
                outputId + ": " + reason);
        if (state.diagnostic().filter(diagnostic::equals).isPresent()) {
            return;
        }
        VillagerWorkState updated = state.withMerchantStock(state.merchantStock(), java.util.Optional.of(diagnostic));
        VillagerWorkSavedData.forServer(level.getServer()).put(updated);
    }

    private static void debitPhysicalMaterialSale(Villager villager, MerchantOffers offers, MerchantOffer offer,
                                                  OfferStockDecision decision, boolean debited) {
        if (decision != OfferStockDecision.AVAILABLE || !debited) {
            TotemVillagers.LOGGER.error("Rejected post-trade physical-material debit mismatch for villager {} offer {}",
                    villager.getUUID(), itemId(offer.getResult()));
            offer.setToOutOfStock();
            return;
        }
        refreshOffers(villager, offers);
    }

    private static boolean canStorePlayerPurchaseInputs(MerchantOffer offer, VillagerWorkInventory inventory) {
        List<ItemStack> paidInputs = playerPurchaseInputs(offer);
        return !paidInputs.isEmpty()
                && (!offer.getResult().is(net.minecraft.world.item.Items.EMERALD)
                    || inventory.countMatchingItem(offer.getResult()) >= offer.getResult().getCount())
                && inventory.canInsertAllExact(paidInputs);
    }

    private static boolean canStorePlayerPayment(MerchantOffer offer, VillagerWorkInventory inventory) {
        return inventory.canInsertAllExact(playerPurchaseInputs(offer));
    }

    private static List<ItemStack> playerPurchaseInputs(MerchantOffer offer) {
        List<ItemStack> inputs = new ArrayList<>(2);
        ItemStack first = offer.getCostA();
        ItemStack second = offer.getCostB();
        if (!first.isEmpty()) {
            inputs.add(first.copy());
        }
        if (!second.isEmpty()) {
            inputs.add(second.copy());
        }
        return List.copyOf(inputs);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    private static boolean isLeatherworker(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:leatherworker".equals(id.toString());
    }

    private static boolean isFarmer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:farmer".equals(id.toString());
    }

    private static boolean isFletcher(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:fletcher".equals(id.toString());
    }

    private static boolean isLibrarian(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:librarian".equals(id.toString());
    }

    private static boolean isToolsmith(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:toolsmith".equals(id.toString());
    }

    private static boolean isCartographer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:cartographer".equals(id.toString());
    }

    private static boolean isMiner(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "totem:miner".equals(id.toString());
    }

    private static boolean isLumberjack(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "totem:lumberjack".equals(id.toString());
    }
}
