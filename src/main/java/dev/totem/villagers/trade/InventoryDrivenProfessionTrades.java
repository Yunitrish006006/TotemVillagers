package dev.totem.villagers.trade;

import dev.totem.villagers.TotemVillagers;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerProfessionEquipment;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.RemnantBackpackOrders;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Complete-mode sell authority for ordinary profession output. The work-order catalogue decides what this profession
 * may legitimately sell; the physical inventory decides which rows exist. Vanilla's complete level 1-5 trade data is
 * consulted only for prices/cost shapes, never as a random product-selection gate.
 */
public final class InventoryDrivenProfessionTrades {
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP_PER_SALE = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;
    private static final int FOOD_RESERVE_NUTRITION = VillagerNutrition.MAX_FOOD_LEVEL;

    private InventoryDrivenProfessionTrades() {
    }

    public static void syncOffers(Villager villager, MerchantOffers offers, VillagerWorkInventory inventory,
                                  ServerLevel level) {
        String professionId = professionId(villager);
        if (professionId.startsWith("totem:")) {
            return;
        }
        Map<StockVariantKey, WorkOrder> legal = legalBaseOrders(professionId, offers, level);
        if (legal.isEmpty()) {
            return;
        }
        Map<StockVariantKey, MerchantOffer> vanillaPrices = vanillaSellTemplates(villager, level);

        offers.removeIf(offer -> {
            if (!VillagerOfferSides.isVillagerSellOffer(offer) || offer.getResult().isEmpty()) {
                return false;
            }
            StockVariantKey key = StockVariantKey.fromStack(offer.getResult(), level.registryAccess());
            boolean depleted = legal.containsKey(key) && !externallyManagedResult(professionId, offer.getResult())
                    && saleableCount(professionId, inventory, offer.getResult()) < offer.getResult().getCount();
            if (depleted) {
                offer.setToOutOfStock();
            }
            return depleted;
        });

        for (Map.Entry<StockVariantKey, WorkOrder> entry : legal.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StockVariantKey::persistentString))).toList()) {
            ItemStack physical = matchingPhysicalStack(inventory, entry.getKey(), level).orElse(null);
            if (physical == null || externallyManagedResult(professionId, physical)) {
                continue;
            }
            MerchantOffer vanilla = vanillaPrices.get(entry.getKey());
            ItemStack result = vanilla == null
                    ? physical.copyWithCount(entry.getValue().output().count())
                    : vanilla.getResult().copy();
            if (saleableCount(professionId, inventory, result) < result.getCount()
                    || offers.stream().anyMatch(existing -> sameResult(existing, result, level))) {
                continue;
            }
            offers.add(vanilla == null ? fallbackOffer(entry.getValue(), result) : vanilla);
        }
    }

    public static boolean isManagedOffer(Villager villager, MerchantOffer offer, ServerLevel level) {
        if (offer == null || offer.getResult().isEmpty() || !VillagerOfferSides.isVillagerSellOffer(offer)) {
            return false;
        }
        StockVariantKey key = StockVariantKey.fromStack(offer.getResult(), level.registryAccess());
        return legalBaseOrders(professionId(villager), null, level).containsKey(key);
    }

    public static OfferStockDecision decision(Villager villager, MerchantOffer offer,
                                               VillagerWorkInventory inventory, ServerLevel level) {
        if (!isManagedOffer(villager, offer, level)) {
            return OfferStockDecision.UNMAPPED;
        }
        return saleableCount(professionId(villager), inventory, offer.getResult()) >= offer.getResult().getCount()
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }

    /** Deterministic finite profession stock; no generated random offer is consulted. */
    public static Optional<ItemStack> starterStock(Villager villager, ServerLevel level) {
        String professionId = professionId(villager);
        if ("totem:miner".equals(professionId)) {
            return Optional.of(MinerLapisTrades.result());
        }
        if ("totem:lumberjack".equals(professionId)) {
            return Optional.of(new ItemStack(Items.OAK_LOG, 8));
        }
        Map<StockVariantKey, MerchantOffer> prices = vanillaSellTemplates(villager, level);
        return legalBaseOrders(professionId, null, level).values().stream()
                .sorted(Comparator.comparing(WorkOrder::id))
                .filter(order -> !isFarmerGatheredCropOrder(professionId, order))
                .map(order -> starterStack(professionId, order, prices.get(order.outputKey())))
                .filter(stack -> !stack.isEmpty())
                .findFirst();
    }

    /**
     * Complete deterministic sell catalogue used by audits and player-facing
     * documentation. It follows the same live vanilla price templates and
     * fallback rule as {@link #syncOffers} without requiring stock to be seeded.
     */
    public static List<MerchantOffer> catalogSellOffers(Villager villager, ServerLevel level) {
        return catalogSellOffers(villager, null, level);
    }

    public static List<MerchantOffer> catalogSellOffers(Villager villager, MerchantOffers observedOffers,
                                                        ServerLevel level) {
        String professionId = professionId(villager);
        if (professionId.startsWith("totem:")) {
            return List.of();
        }
        Map<StockVariantKey, MerchantOffer> vanillaPrices = vanillaSellTemplates(villager, level);
        if (observedOffers != null) {
            observedOffers.stream().filter(VillagerOfferSides::isVillagerSellOffer)
                    .filter(offer -> !offer.getResult().isEmpty())
                    .forEach(offer -> vanillaPrices.put(
                            StockVariantKey.fromStack(offer.getResult(), level.registryAccess()), offer));
        }
        return legalBaseOrders(professionId, observedOffers, level).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StockVariantKey::persistentString)))
                .filter(entry -> {
                    Item item = BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.Identifier.parse(entry.getValue().output().itemId()));
                    return item != null && item != Items.AIR
                            && !externallyManagedResult(professionId, new ItemStack(item));
                })
                .map(entry -> {
                    WorkOrder order = entry.getValue();
                    MerchantOffer vanilla = vanillaPrices.get(entry.getKey());
                    Item item = BuiltInRegistries.ITEM.getValue(
                            net.minecraft.resources.Identifier.parse(order.output().itemId()));
                    ItemStack result = vanilla == null
                            ? new ItemStack(item, order.output().count())
                            : vanilla.getResult().copy();
                    return vanilla == null ? fallbackOffer(order, result) : vanilla;
                })
                .toList();
    }

    private static ItemStack starterStack(String professionId, WorkOrder order, MerchantOffer vanilla) {
        Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(order.output().itemId()));
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack result = vanilla == null ? new ItemStack(item, order.output().count()) : vanilla.getResult().copy();
        int reserve = reservedCount(professionId, result);
        return result.copyWithCount(Math.min(result.getMaxStackSize(), result.getCount() + reserve));
    }

    private static Map<StockVariantKey, WorkOrder> legalBaseOrders(
            String professionId, MerchantOffers offers, ServerLevel level
    ) {
        Map<StockVariantKey, WorkOrder> result = new LinkedHashMap<>();
        WorkOrderCatalogs.effectiveFor(WorkOrderDefinitions.catalog(), professionId, offers, level)
                .snapshot().values().stream()
                .filter(order -> order.professionId().equals(professionId))
                .filter(order -> order.outputComponentPatch().isEmpty())
                .sorted(Comparator.comparing(WorkOrder::id))
                .forEach(order -> result.putIfAbsent(order.outputKey(), order));
        return result;
    }

    private static Map<StockVariantKey, MerchantOffer> vanillaSellTemplates(Villager villager, ServerLevel level) {
        Map<StockVariantKey, MerchantOffer> result = new LinkedHashMap<>();
        var profession = villager.getVillagerData().profession().value();
        var tradeSets = level.registryAccess().lookupOrThrow(Registries.TRADE_SET);
        for (int tradeLevel = 1; tradeLevel <= 5; tradeLevel++) {
            ResourceKey<TradeSet> key = profession.getTrades(tradeLevel);
            if (key == null) {
                continue;
            }
            TradeSet set = tradeSets.getOptional(key).orElse(null);
            if (set == null) {
                continue;
            }
            LootContext context = tradeContext(level, villager, set);
            for (var trade : set.getTrades()) {
                try {
                    MerchantOffer offer = trade.value().getOffer(context);
                    if (offer == null || !VillagerOfferSides.isVillagerSellOffer(offer) || offer.getResult().isEmpty()) {
                        continue;
                    }
                    StockVariantKey output = StockVariantKey.fromStack(offer.getResult(), level.registryAccess());
                    result.putIfAbsent(output, offer);
                } catch (RuntimeException exception) {
                    TotemVillagers.LOGGER.warn("Could not resolve complete-mode trade {} for villager {}: {}",
                            trade.unwrapKey().map(resourceKey -> resourceKey.identifier().toString()).orElse("inline"),
                            villager.getUUID(), exception.getMessage());
                }
            }
        }
        return result;
    }

    private static LootContext tradeContext(ServerLevel level, Villager villager, TradeSet set) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);
        return new LootContext.Builder(params).create(set.randomSequence());
    }

    private static Optional<ItemStack> matchingPhysicalStack(VillagerWorkInventory inventory, StockVariantKey key,
                                                             ServerLevel level) {
        return inventory.snapshot().stream().filter(stack -> !stack.isEmpty())
                .filter(stack -> key.equals(StockVariantKey.fromStack(stack, level.registryAccess())))
                .findFirst().map(ItemStack::copy);
    }

    private static int saleableCount(String professionId, VillagerWorkInventory inventory, ItemStack result) {
        int available = inventory.countMatchingItem(result);
        if (VillagerProfessionEquipment.tool(professionId).filter(result::is).isPresent()) {
            available--;
        }
        int nutrition = VillagerNutrition.nutrition(result);
        if (nutrition > 0) {
            int totalNutrition = inventory.snapshot().stream()
                    .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount()).sum();
            available = Math.min(available,
                    Math.max(0, totalNutrition - FOOD_RESERVE_NUTRITION) / nutrition);
        }
        return Math.max(0, available);
    }

    private static int reservedCount(String professionId, ItemStack result) {
        return VillagerProfessionEquipment.tool(professionId).filter(result::is).isPresent() ? 1 : 0;
    }

    private static MerchantOffer fallbackOffer(WorkOrder order, ItemStack result) {
        int backpackPrice = RemnantBackpackOrders.emeraldPrice(order.output().itemId());
        int price = backpackPrice > 0 ? backpackPrice : result.getMaxStackSize() == 1
                ? Math.max(2, Math.min(64, Math.ceilDiv(order.requiredInputs().stream()
                        .mapToInt(input -> input.count()).sum(), 2))) : 1;
        return new MerchantOffer(new ItemCost(Items.EMERALD, price), result, MAX_USES,
                VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER);
    }

    private static boolean externallyManagedResult(String professionId, ItemStack result) {
        return "minecraft:farmer".equals(professionId) && FarmerGatheredCropTrades.isManagedResult(result)
                || "minecraft:toolsmith".equals(professionId) && result.is(Items.BUCKET);
    }

    private static boolean isFarmerGatheredCropOrder(String professionId, WorkOrder order) {
        if (!"minecraft:farmer".equals(professionId)) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(order.output().itemId()));
        return item != null && FarmerGatheredCropTrades.isManagedResult(new ItemStack(item));
    }

    private static boolean sameResult(MerchantOffer offer, ItemStack result, ServerLevel level) {
        return offer != null && !offer.getResult().isEmpty()
                && offer.getResult().getCount() == result.getCount()
                && StockVariantKey.fromStack(offer.getResult(), level.registryAccess())
                .equals(StockVariantKey.fromStack(result, level.registryAccess()));
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }
}
