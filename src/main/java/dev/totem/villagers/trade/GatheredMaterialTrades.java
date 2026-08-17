package dev.totem.villagers.trade;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.needs.VillagerNutrition;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Dynamic physical-material rows for outputs gathered by the two resource
 * specialists.  These are deliberately finite offers: an entry exists only
 * while the owner's personal work inventory contains its full sale batch.
 */
public final class GatheredMaterialTrades {
    private static final int MAX_USES = 16;
    private static final int VILLAGER_XP_PER_SALE = 1;
    private static final float PRICE_MULTIPLIER = 0.05F;
    private static final int DYNAMIC_PRICE_SCALE = 16;
    private static final int DYNAMIC_TIER_LARGE = 16;
    private static final int DYNAMIC_TIER_MEDIUM = 8;
    private static final int DYNAMIC_TIER_SMALL = 4;
    private static final Set<Item> MINER_TOOLS = Set.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE,
            Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.DIAMOND_PICKAXE,
            Items.NETHERITE_PICKAXE);
    private static final Set<Item> LUMBERJACK_TOOLS = Set.of(Items.WOODEN_AXE, Items.STONE_AXE,
            Items.COPPER_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);

    /* Common stone and ore-drop materials, followed by Silk Touch ore blocks. */
    private static final List<MaterialSale> MINER_SALES = List.of(
            sale(Items.COBBLESTONE, 16, 1), sale(Items.COBBLED_DEEPSLATE, 16, 1),
            sale(Items.STONE, 16, 1), sale(Items.DEEPSLATE, 16, 1),
            sale(Items.COAL, 8, 1), sale(Items.RAW_COPPER, 8, 1),
            sale(Items.RAW_IRON, 4, 1), sale(Items.IRON_INGOT, 4, 1), sale(Items.COPPER_INGOT, 8, 1),
            sale(Items.GOLD_INGOT, 3, 1), sale(Items.QUARTZ, 4, 1),
            sale(Items.RAW_GOLD, 3, 1), sale(Items.REDSTONE, 3, 1), sale(Items.LAPIS_LAZULI, 3, 1),
            sale(Items.GOLD_NUGGET, 16, 1),
            sale(Items.DIAMOND, 1, 6), sale(Items.EMERALD, 1, 6), sale(Items.ANCIENT_DEBRIS, 1, 12),
            sale(Items.COAL_ORE, 1, 2), sale(Items.DEEPSLATE_COAL_ORE, 1, 2),
            sale(Items.COPPER_ORE, 1, 3), sale(Items.DEEPSLATE_COPPER_ORE, 1, 3),
            sale(Items.IRON_ORE, 1, 3), sale(Items.DEEPSLATE_IRON_ORE, 1, 3),
            sale(Items.NETHER_QUARTZ_ORE, 1, 3), sale(Items.NETHER_GOLD_ORE, 1, 3),
            sale(Items.GOLD_ORE, 1, 4), sale(Items.DEEPSLATE_GOLD_ORE, 1, 4),
            sale(Items.REDSTONE_ORE, 1, 4), sale(Items.DEEPSLATE_REDSTONE_ORE, 1, 4),
            sale(Items.LAPIS_ORE, 1, 4), sale(Items.DEEPSLATE_LAPIS_ORE, 1, 4),
            sale(Items.DIAMOND_ORE, 1, 8), sale(Items.DEEPSLATE_DIAMOND_ORE, 1, 8),
            sale(Items.EMERALD_ORE, 1, 8), sale(Items.DEEPSLATE_EMERALD_ORE, 1, 8)
    );

    /* All native log and sapling variants keep data-pack-extended forest work useful without free stock. */
    private static final List<MaterialSale> LUMBERJACK_SALES = List.of(
            sale(Items.OAK_LOG, 8, 1), sale(Items.SPRUCE_LOG, 8, 1), sale(Items.BIRCH_LOG, 8, 1),
            sale(Items.JUNGLE_LOG, 8, 1), sale(Items.ACACIA_LOG, 8, 1), sale(Items.DARK_OAK_LOG, 8, 1),
            sale(Items.MANGROVE_LOG, 8, 1), sale(Items.CHERRY_LOG, 8, 1), sale(Items.PALE_OAK_LOG, 8, 1),
            sale(Items.CRIMSON_STEM, 8, 1), sale(Items.WARPED_STEM, 8, 1), sale(Items.STICK, 32, 1),
            sale(Items.APPLE, 4, 1),
            sale(Items.OAK_SAPLING, 8, 1), sale(Items.SPRUCE_SAPLING, 8, 1), sale(Items.BIRCH_SAPLING, 8, 1),
            sale(Items.JUNGLE_SAPLING, 8, 1), sale(Items.ACACIA_SAPLING, 8, 1), sale(Items.DARK_OAK_SAPLING, 8, 1),
            sale(Items.MANGROVE_PROPAGULE, 8, 1), sale(Items.CHERRY_SAPLING, 8, 1), sale(Items.PALE_OAK_SAPLING, 8, 1)
    );

    private GatheredMaterialTrades() {
    }

    /** Adds available Miner rows and removes depleted dynamic rows, including lapis. */
    public static void syncMinerOffers(MerchantOffers offers, VillagerWorkInventory inventory) {
        sync(offers, inventory, MINER_SALES, MINER_TOOLS);
    }

    /** Adds available Lumberjack rows and removes depleted dynamic rows, including apples. */
    public static void syncLumberjackOffers(MerchantOffers offers, VillagerWorkInventory inventory) {
        sync(offers, inventory, LUMBERJACK_SALES, LUMBERJACK_TOOLS);
    }

    public static List<MerchantOffer> minerCatalogOffers() {
        return MINER_SALES.stream().map(MaterialSale::offer).toList();
    }

    public static List<MerchantOffer> lumberjackCatalogOffers() {
        return LUMBERJACK_SALES.stream().map(MaterialSale::offer).toList();
    }

    public static boolean isMinerManagedOffer(MerchantOffer offer) {
        return isDynamicOffer(offer);
    }

    public static boolean isLumberjackManagedOffer(MerchantOffer offer) {
        return isDynamicOffer(offer);
    }

    public static OfferStockDecision minerDecision(MerchantOffer offer, VillagerWorkInventory inventory) {
        return decision(offer, inventory, MINER_TOOLS);
    }

    public static OfferStockDecision lumberjackDecision(MerchantOffer offer, VillagerWorkInventory inventory) {
        return decision(offer, inventory, LUMBERJACK_TOOLS);
    }

    public static boolean debitMiner(MerchantOffer offer, VillagerWorkInventory inventory) {
        return debit(offer, inventory);
    }

    public static boolean debitLumberjack(MerchantOffer offer, VillagerWorkInventory inventory) {
        return debit(offer, inventory);
    }

    private static void sync(MerchantOffers offers, VillagerWorkInventory inventory,
                             List<MaterialSale> configuredSales, Set<Item> reservedTools) {
        offers.removeIf(offer -> {
            boolean depleted = isDynamicOffer(offer)
                    && saleableCount(inventory, offer.getResult(), reservedTools) < offer.getResult().getCount();
            if (depleted) {
                // Menus can retain this offer instance until their next sync.
                // Mark it unavailable before removing it so a just-depleted
                // material batch can never be used through that stale view.
                offer.setToOutOfStock();
            }
            return depleted;
        });

        for (ItemStack stack : inventory.snapshot()) {
            if (stack.isEmpty() || reservedTools.contains(stack.getItem())) {
                continue;
            }
            Optional<MaterialSale> configured = configuredSales.stream().filter(sale -> sale.item.equals(stack.getItem()))
                    .filter(sale -> saleableCount(inventory, stack, reservedTools) >= sale.count())
                .findFirst();
            MaterialSale sale = configured.orElseGet(() -> fallbackSale(stack, inventory, reservedTools));
            if (sale.count() > 0 && sale.emeraldPrice() > 0
                    && offers.stream().noneMatch(existing -> sale.matches(existing))) {
                offers.add(sale.offer());
            }
        }
    }

    private static OfferStockDecision decision(MerchantOffer offer, VillagerWorkInventory inventory,
                                               Set<Item> reservedTools) {
        if (!isDynamicOffer(offer) || offer.getResult().isEmpty()) {
            return OfferStockDecision.UNMAPPED;
        }
        return saleableCount(inventory, offer.getResult(), reservedTools) >= offer.getResult().getCount()
                ? OfferStockDecision.AVAILABLE : OfferStockDecision.INSUFFICIENT_STOCK;
    }

    private static boolean debit(MerchantOffer offer, VillagerWorkInventory inventory) {
        return isDynamicOffer(offer)
                && offer.getResult().getCount() > 0
                && inventory.takeExactMatchingItem(offer.getResult()).isPresent();
    }

    private static boolean isDynamicOffer(MerchantOffer offer) {
        return offer != null && !offer.getResult().isEmpty()
                && offer.getBaseCostA().is(Items.EMERALD)
                && offer.getBaseCostA().getCount() > 0
                && offer.getItemCostB().isEmpty()
                && offer.getMaxUses() == MAX_USES
                && offer.getPriceMultiplier() == PRICE_MULTIPLIER;
    }

    private static MaterialSale fallbackSale(ItemStack stack, VillagerWorkInventory inventory, Set<Item> reservedTools) {
        int available = saleableCount(inventory, stack, reservedTools);
        if (available <= 0) {
            return new MaterialSale(stack.getItem(), 0, 0);
        }
        int batch = dynamicBatch(available);
        int emeraldPrice = Math.max(1, Math.ceilDiv(DYNAMIC_PRICE_SCALE, batch));
        return new MaterialSale(stack.getItem(), batch, emeraldPrice);
    }

    private static int saleableCount(VillagerWorkInventory inventory, ItemStack stack, Set<Item> reservedTools) {
        if (reservedTools.contains(stack.getItem())) {
            return 0;
        }
        int available = inventory.countMatchingItem(stack);
        int nutrition = VillagerNutrition.nutrition(stack);
        if (nutrition > 0) {
            int totalNutrition = inventory.snapshot().stream()
                    .mapToInt(food -> VillagerNutrition.nutrition(food) * food.getCount()).sum();
            available = Math.min(available,
                    Math.max(0, totalNutrition - VillagerNutrition.MAX_FOOD_LEVEL) / nutrition);
        }
        return Math.max(0, available);
    }

    private static int dynamicBatch(int available) {
        if (available >= DYNAMIC_TIER_LARGE) {
            return DYNAMIC_TIER_LARGE;
        }
        if (available >= DYNAMIC_TIER_MEDIUM) {
            return DYNAMIC_TIER_MEDIUM;
        }
        if (available >= DYNAMIC_TIER_SMALL) {
            return DYNAMIC_TIER_SMALL;
        }
        return Math.max(1, available);
    }

    private static MaterialSale sale(Item item, int count, int emeraldPrice) {
        return new MaterialSale(item, count, emeraldPrice);
    }

    private record MaterialSale(Item item, int count, int emeraldPrice) {
        private ItemStack result() {
            return new ItemStack(item, count);
        }

        private MerchantOffer offer() {
            return new MerchantOffer(new ItemCost(Items.EMERALD, emeraldPrice), result(), MAX_USES,
                    VILLAGER_XP_PER_SALE, PRICE_MULTIPLIER);
        }

        private boolean matches(MerchantOffer offer) {
            return offer != null
                    && offer.getResult().is(item)
                    && offer.getResult().getCount() == count
                    && offer.getBaseCostA().is(Items.EMERALD)
                    && offer.getBaseCostA().getCount() == emeraldPrice
                    && offer.getItemCostB().isEmpty()
                    && offer.getMaxUses() == MAX_USES
                    && offer.getPriceMultiplier() == PRICE_MULTIPLIER;
        }
    }
}
