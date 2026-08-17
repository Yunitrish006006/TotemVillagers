package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned durable data owned by one villager, ready for SavedData/NBT serialization. */
public record VillagerWorkState(
        int schemaVersion,
        UUID villagerId,
        boolean nutritionBootstrapGranted,
        Map<String, Integer> merchantStock,
        Map<StockVariantKey, Integer> variantMerchantStock,
        Optional<ActiveWork> activeWork,
        Optional<TradeDiagnostic> diagnostic
) {
    public static final int CURRENT_SCHEMA_VERSION = 4;
    private static final Codec<Map<String, Integer>> STOCK_CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
            .xmap(Map::copyOf, Map::copyOf);

    public static final Codec<VillagerWorkState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", CURRENT_SCHEMA_VERSION).forGetter(VillagerWorkState::schemaVersion),
            UUIDUtil.CODEC.fieldOf("villager").forGetter(VillagerWorkState::villagerId),
            Codec.BOOL.optionalFieldOf("nutrition_bootstrap_granted", false)
                    .forGetter(VillagerWorkState::nutritionBootstrapGranted),
            STOCK_CODEC.optionalFieldOf("merchant_stock", Map.of()).forGetter(VillagerWorkState::merchantStock),
            StockVariantAmount.CODEC.listOf().optionalFieldOf("variant_merchant_stock", List.of())
                    .xmap(VillagerWorkState::variantStockMap, VillagerWorkState::variantStockEntries)
                    .forGetter(VillagerWorkState::variantMerchantStock),
            ActiveWork.CODEC.optionalFieldOf("active_work").forGetter(VillagerWorkState::activeWork),
            // Consume the v1/v2 field while deliberately omitting it from all current writes.
            VillageWorkChestLink.CODEC.optionalFieldOf("work_chest").forGetter(state -> Optional.empty()),
            TradeDiagnostic.CODEC.optionalFieldOf("diagnostic").forGetter(VillagerWorkState::diagnostic)
    ).apply(instance, (schemaVersion, villagerId, nutritionBootstrapGranted, merchantStock, variantMerchantStock, activeWork,
                       ignoredWorkChest, diagnostic) -> new VillagerWorkState(schemaVersion, villagerId, nutritionBootstrapGranted,
            merchantStock, variantMerchantStock, activeWork, diagnostic)));

    public VillagerWorkState {
        if (schemaVersion < 1 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported VillagerWorkState schema: " + schemaVersion);
        }
        Objects.requireNonNull(villagerId, "villagerId");
        merchantStock = Map.copyOf(merchantStock);
        merchantStock.forEach(ItemAmount::new);
        variantMerchantStock = Map.copyOf(variantMerchantStock);
        variantMerchantStock.forEach((key, count) -> new StockVariantAmount(key, count));
        activeWork = activeWork == null ? Optional.empty() : activeWork;
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic;
    }

    /** Source compatibility for the v3 state shape; missing bootstrap state is migrated as not yet granted. */
    public VillagerWorkState(
            int schemaVersion,
            UUID villagerId,
            Map<String, Integer> merchantStock,
            Map<StockVariantKey, Integer> variantMerchantStock,
            Optional<ActiveWork> activeWork,
            Optional<TradeDiagnostic> diagnostic
    ) {
        this(schemaVersion, villagerId, false, merchantStock, variantMerchantStock, activeWork, diagnostic);
    }

    /**
     * Source compatibility for callers still constructing the v2 shape. The
     * decoded link is intentionally discarded: v3 material ownership is the
     * villager's persistent personal inventory.
     */
    @Deprecated
    public VillagerWorkState(
            int schemaVersion,
            UUID villagerId,
            Map<String, Integer> merchantStock,
            Map<StockVariantKey, Integer> variantMerchantStock,
            Optional<ActiveWork> activeWork,
            Optional<VillageWorkChestLink> ignoredWorkChest,
            Optional<TradeDiagnostic> diagnostic
    ) {
        this(schemaVersion, villagerId, false, merchantStock, variantMerchantStock, activeWork, diagnostic);
    }

    public static VillagerWorkState empty(UUID villagerId) {
        return new VillagerWorkState(CURRENT_SCHEMA_VERSION, villagerId, false, Map.of(), Map.of(), Optional.empty(), Optional.empty());
    }

    public VillagerWorkState withActiveWork(Optional<ActiveWork> nextActiveWork, Optional<TradeDiagnostic> nextDiagnostic) {
        return new VillagerWorkState(CURRENT_SCHEMA_VERSION, villagerId, nutritionBootstrapGranted, merchantStock,
                variantMerchantStock, nextActiveWork, nextDiagnostic);
    }

    public VillagerWorkState withMerchantStock(Map<String, Integer> nextMerchantStock, Optional<TradeDiagnostic> nextDiagnostic) {
        return withStock(nextMerchantStock, variantMerchantStock, nextDiagnostic);
    }

    public VillagerWorkState withStock(
            Map<String, Integer> nextMerchantStock,
            Map<StockVariantKey, Integer> nextVariantMerchantStock,
            Optional<TradeDiagnostic> nextDiagnostic
    ) {
        return new VillagerWorkState(CURRENT_SCHEMA_VERSION, villagerId, nutritionBootstrapGranted, nextMerchantStock,
                nextVariantMerchantStock, activeWork, nextDiagnostic);
    }

    /** Marks the one finite start-of-work nutrition grant as consumed. */
    public VillagerWorkState withNutritionBootstrapGranted() {
        return nutritionBootstrapGranted ? this : new VillagerWorkState(CURRENT_SCHEMA_VERSION, villagerId, true,
                merchantStock, variantMerchantStock, activeWork, diagnostic);
    }

    private static Map<StockVariantKey, Integer> variantStockMap(List<StockVariantAmount> entries) {
        Map<StockVariantKey, Integer> result = new LinkedHashMap<>();
        for (StockVariantAmount entry : entries) {
            if (result.putIfAbsent(entry.key(), entry.count()) != null) {
                throw new IllegalArgumentException("Duplicate variant merchant stock key: " + entry.key().itemId());
            }
        }
        return Map.copyOf(result);
    }

    private static List<StockVariantAmount> variantStockEntries(Map<StockVariantKey, Integer> stock) {
        return stock.entrySet().stream().map(entry -> new StockVariantAmount(entry.getKey(), entry.getValue())).toList();
    }
}
