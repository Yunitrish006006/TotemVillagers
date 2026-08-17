package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Optional;

/**
 * Adds a bounded physical food market around Totem's independent hunger value.
 * Every hungry adult first eats food already in its personal inventory. If it
 * has none, it pays a nearby food-producing villager for physical food. Internal food exchange
 * deliberately does not depend on that producer randomly rolling a player-facing
 * food offer; the items and emerald still move between the two inventories.
 */
public final class VillagerFoodEconomyRuntime {
    /** Four player-style exhaustion pulses per day preserve the established sustainable village energy budget. */
    public static final int DIGEST_INTERVAL_TICKS = 6_000;
    private static final int PURCHASE_INTERVAL_TICKS = 100;
    private static final int SEARCH_RADIUS = 32;
    private static final double PURCHASE_REACH_SQUARED = 16.0D;
    private static final int INTERNAL_FOOD_PRICE = 1;
    /** One emerald buys a persistent five-bread-equivalent ration pack. */
    private static final int INTERNAL_FOOD_NUTRITION = 25;
    private static final int SELLER_RESERVE_NUTRITION = VillagerNutrition.MAX_FOOD_LEVEL;
    /** A worker may carry its own reserve plus at most two pre-purchased order-financing rations. */
    private static final int WORK_ORDER_FOOD_RESERVE_CAP = SELLER_RESERVE_NUTRITION
            + INTERNAL_FOOD_NUTRITION * 2;

    private VillagerFoodEconomyRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(VillagerFoodEconomyRuntime::tick);
    }

    /** Stores every real player payment in the merchant's physical inventory. */
    public static void recordPlayerPayment(Villager merchant, MerchantOffer offer) {
        if (!(merchant.level() instanceof ServerLevel level)
                || merchant.getTradingPlayer() == null
                || WorkBackedTradingSettingsSavedData.forServer(level.getServer()).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        if (!dev.totem.villagers.trade.VillagerOfferSides.isVillagerSellOffer(offer)) {
            return;
        }
        java.util.List<ItemStack> payment = java.util.stream.Stream.of(offer.getCostA(), offer.getCostB())
                .filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        if (!payment.isEmpty()) {
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer())
                    .inventory(merchant.getUUID());
            if (!inventory.insertAllExact(payment)) {
                throw new IllegalStateException("Accepted player payment no longer fits villager inventory");
            }
        }
    }

    private static void tick(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        boolean digest = server.getTickCount() % DIGEST_INTERVAL_TICKS == 0;
        boolean foodMarket = server.getTickCount() % PURCHASE_INTERVAL_TICKS == 0;
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Villager villager : LoadedVillagerCache.loaded(level)) {
                if (!canBuyFood(villager)) {
                    continue;
                }
                if (digest) {
                    VillagerNutrition.digest(villager);
                }
                VillagerNutrition.tick(villager);
                if (!foodMarket) {
                    continue;
                }
                VillagerWorkInventory ownInventory = inventories.inventory(villager.getUUID());
                if (VillagerNutrition.isHungry(villager)) {
                    tryConsumeOwnStoredFood(villager, ownInventory);
                }
                if (needsFoodRestock(villager, ownInventory) && !inDanger(villager)
                        && !villager.isSleeping() && !level.isRaided(villager.blockPosition())) {
                    seekAndBuyFood(level, villager, inventories);
                }
            }
        }
    }

    private static void seekAndBuyFood(ServerLevel level, Villager buyer, VillagerWorkInventorySavedData inventories) {
        VillagerWorkInventory buyerInventory = inventories.inventory(buyer.getUUID());
        Optional<FoodSeller> seller = LoadedVillagerCache.loaded(level).stream()
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby() && candidate != buyer
                        && isFoodProducer(candidate)
                        && candidate.distanceToSqr(buyer) <= (double) SEARCH_RADIUS * SEARCH_RADIUS)
                .map(producer -> foodForSale(inventories.inventory(producer.getUUID()))
                        .map(food -> new FoodSeller(producer, inventories.inventory(producer.getUUID()), food)))
                .flatMap(Optional::stream)
                .filter(candidate -> buyer.getNavigation().createPath(candidate.producer().blockPosition(), 0) != null)
                .min(java.util.Comparator.comparingDouble(candidate -> buyer.distanceToSqr(candidate.producer())));
        if (seller.isEmpty()) {
            return;
        }
        FoodSeller foodSeller = seller.orElseThrow();
        if (buyer.distanceToSqr(foodSeller.producer()) > PURCHASE_REACH_SQUARED) {
            buyer.getNavigation().moveTo(foodSeller.producer().getX(), foodSeller.producer().getY(), foodSeller.producer().getZ(), .5D);
            return;
        }
        tryPurchaseFromFoodProducer(level, buyer, foodSeller, inventories);
    }

    /**
     * Compatibility entry point for the original Farmer self-ration test.
     */
    public static boolean tryConsumeOwnFood(ServerLevel level, Villager farmer) {
        return isFarmer(farmer) && tryConsumeOwnStoredFood(farmer,
                VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID()));
    }

    /** Any profession may eat its own physical food before attempting a purchase. */
    public static boolean tryConsumeOwnStoredFood(Villager villager, VillagerWorkInventory inventory) {
        if (!VillagerNutrition.isHungry(villager)) {
            return false;
        }
        boolean consumed = false;
        while (VillagerNutrition.foodLevel(villager) < VillagerNutrition.EAT_UNTIL) {
            ItemStack food = bestFood(inventory).orElse(null);
            if (food == null || VillagerNutrition.consumeStoredFood(villager, inventory, food) < 1) {
                break;
            }
            consumed = true;
        }
        return consumed;
    }

    /** Villagers restock before starvation when their carried ration has fallen below one reserve meal. */
    public static boolean needsFoodRestock(Villager villager, VillagerWorkInventory inventory) {
        if (!canBuyFood(villager) || VillagerNutrition.foodLevel(villager) > 18) {
            return false;
        }
        int carriedNutrition = inventory.snapshot().stream()
                .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount()).sum();
        return carriedNutrition <= SELLER_RESERVE_NUTRITION;
    }

    /** Public for GameTests; runtime callers have already constrained both villagers to loaded reachable adults. */
    public static boolean tryPurchaseFromFarmer(ServerLevel level, Villager buyer, Villager farmer) {
        return isFarmer(farmer) && tryPurchaseFromFoodProducer(level, buyer, farmer);
    }

    /** Farmer, Fisherman and Butcher may sell only food that physically exists beyond their own ration. */
    public static boolean tryPurchaseFromFoodProducer(ServerLevel level, Villager buyer, Villager producer) {
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        VillagerWorkInventory producerInventory = inventories.inventory(producer.getUUID());
        FoodSeller seller = foodForSale(producerInventory)
                .map(food -> new FoodSeller(producer, producerInventory, food)).orElse(null);
        return seller != null && tryPurchaseFromFoodProducer(level, buyer, seller, inventories);
    }

    /**
     * Lets a producer pre-sell real food to finance an essential work order. The buyer can hold no more than two
     * ration packs beyond its own meal, so this moves timing without creating an unbounded sink or bypassing payment.
     */
    public static boolean tryPurchaseWorkOrderFoodReserve(ServerLevel level, Villager buyer, Villager producer) {
        if (!canBuyFood(buyer) || buyer == producer || !isFoodProducer(producer)
                || buyer.distanceToSqr(producer) > (double) SEARCH_RADIUS * SEARCH_RADIUS) {
            return false;
        }
        if (buyer.distanceToSqr(producer) > PURCHASE_REACH_SQUARED) {
            buyer.getNavigation().moveTo(producer, .5D);
            return false;
        }
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(level.getServer());
        VillagerWorkInventory buyerInventory = inventories.inventory(buyer.getUUID());
        FoodSeller seller = foodForSale(inventories.inventory(producer.getUUID()))
                .map(food -> new FoodSeller(producer, inventories.inventory(producer.getUUID()), food))
                .orElse(null);
        if (seller == null || buyerInventory.countMatchingItem(new ItemStack(Items.EMERALD)) < INTERNAL_FOOD_PRICE) {
            return false;
        }
        int carried = storedNutrition(buyerInventory);
        int delivered = VillagerNutrition.nutrition(seller.food()) * seller.food().getCount();
        return carried + delivered <= WORK_ORDER_FOOD_RESERVE_CAP
                && exchangeFood(buyer, seller, buyerInventory);
    }

    private static boolean tryPurchaseFromFoodProducer(ServerLevel level, Villager buyer, FoodSeller seller,
                                                       VillagerWorkInventorySavedData inventories) {
        Villager producer = seller.producer();
        VillagerWorkInventory buyerInventory = inventories.inventory(buyer.getUUID());
        if (!canBuyFood(buyer) || !needsFoodRestock(buyer, buyerInventory) || !isFoodProducer(producer)
                || buyer == producer || buyer.distanceToSqr(producer) > PURCHASE_REACH_SQUARED) {
            return false;
        }
        if (buyerInventory.countMatchingItem(new ItemStack(Items.EMERALD)) >= INTERNAL_FOOD_PRICE) {
            return exchangeFood(buyer, seller, buyerInventory);
        }
        return VillagerNutrition.isHungry(buyer)
                && exchangeSponsoredFood(level, buyer, seller, buyerInventory, inventories);
    }

    private static boolean exchangeFood(Villager buyer, FoodSeller seller,
                                        VillagerWorkInventory buyerInventory) {
        ItemStack food = seller.food().copy();
        VillagerWorkInventory producerInventory = seller.inventory();
        ItemStack payment = new ItemStack(Items.EMERALD, INTERNAL_FOOD_PRICE);
        if (VillagerNutrition.nutrition(food) < 1 || !buyerInventory.canInsertExact(food)
                || !producerInventory.canInsertExact(payment)
                || buyerInventory.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = producerInventory.takeExactMatchingItem(food).orElse(null);
        if (delivered == null) {
            buyerInventory.insertExact(payment);
            return false;
        }
        if (!buyerInventory.insertExact(delivered) || !producerInventory.insertExact(payment)) {
            throw new IllegalStateException("Physical food trade changed during same-tick commit");
        }
        tryConsumeOwnStoredFood(buyer, buyerInventory);
        return true;
    }

    /** The richest nearby adult funds one meal only when the intended eater is already hungry and insolvent. */
    private static boolean exchangeSponsoredFood(ServerLevel level, Villager buyer, FoodSeller seller,
                                                 VillagerWorkInventory buyerInventory,
                                                 VillagerWorkInventorySavedData inventories) {
        Villager producer = seller.producer();
        Villager sponsor = LoadedVillagerCache.loaded(level).stream()
                .filter(candidate -> candidate.isAlive() && !candidate.isBaby()
                        && candidate != buyer && candidate != producer
                        && candidate.distanceToSqr(buyer) <= (double) SEARCH_RADIUS * SEARCH_RADIUS
                        && candidate.distanceToSqr(producer) <= (double) SEARCH_RADIUS * SEARCH_RADIUS)
                .filter(candidate -> inventories.inventory(candidate.getUUID())
                        .countMatchingItem(new ItemStack(Items.EMERALD)) >= INTERNAL_FOOD_PRICE)
                .max(java.util.Comparator.comparingInt(candidate -> inventories.inventory(candidate.getUUID())
                        .countMatchingItem(new ItemStack(Items.EMERALD))))
                .orElse(null);
        if (sponsor == null) {
            return false;
        }
        if (sponsor.distanceToSqr(producer) > PURCHASE_REACH_SQUARED) {
            sponsor.getNavigation().moveTo(producer, .5D);
            return false;
        }
        VillagerWorkInventory sponsorInventory = inventories.inventory(sponsor.getUUID());
        VillagerWorkInventory producerInventory = seller.inventory();
        ItemStack payment = new ItemStack(Items.EMERALD, INTERNAL_FOOD_PRICE);
        ItemStack food = seller.food().copy();
        if (VillagerNutrition.nutrition(food) < 1 || !buyerInventory.canInsertExact(food)
                || !producerInventory.canInsertExact(payment)
                || sponsorInventory.takeExactMatchingItem(payment).isEmpty()) {
            return false;
        }
        ItemStack delivered = producerInventory.takeExactMatchingItem(food).orElse(null);
        if (delivered == null) {
            sponsorInventory.insertExact(payment);
            return false;
        }
        if (!buyerInventory.insertExact(delivered)) {
            producerInventory.insertExact(delivered);
            sponsorInventory.insertExact(payment);
            return false;
        }
        if (!producerInventory.insertExact(payment)) {
            throw new IllegalStateException("Sponsored food payment capacity changed during same-tick commit");
        }
        tryConsumeOwnStoredFood(buyer, buyerInventory);
        return true;
    }

    private static int storedNutrition(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream()
                .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount()).sum();
    }

    private static Optional<ItemStack> bestFood(VillagerWorkInventory inventory) {
        return inventory.snapshot().stream().filter(stack -> VillagerNutrition.nutrition(stack) > 0)
                .max(java.util.Comparator.comparingInt(VillagerNutrition::nutrition))
                .map(ItemStack::copy);
    }

    private static Optional<ItemStack> foodForSale(VillagerWorkInventory inventory) {
        int totalNutrition = inventory.snapshot().stream()
                .mapToInt(stack -> VillagerNutrition.nutrition(stack) * stack.getCount()).sum();
        return inventory.snapshot().stream()
                .filter(stack -> VillagerNutrition.nutrition(stack) > 0)
                .map(stack -> transferableFood(stack, totalNutrition))
                .filter(stack -> !stack.isEmpty())
                .max(java.util.Comparator.comparingInt(VillagerNutrition::nutrition));
    }

    private static ItemStack transferableFood(ItemStack available, int totalNutrition) {
        int nutrition = VillagerNutrition.nutrition(available);
        if (nutrition < 1) {
            return ItemStack.EMPTY;
        }
        int ration = divideRoundUp(INTERNAL_FOOD_NUTRITION, nutrition);
        int deliveredNutrition = ration * nutrition;
        return available.getCount() >= ration
                && totalNutrition - deliveredNutrition >= SELLER_RESERVE_NUTRITION
                ? available.copyWithCount(ration) : ItemStack.EMPTY;
    }

    private static int divideRoundUp(int numerator, int denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private static boolean isFarmer(Villager villager) {
        return "minecraft:farmer".equals(professionId(villager));
    }

    private static boolean isFoodProducer(Villager villager) {
        return switch (professionId(villager)) {
            case "minecraft:farmer", "minecraft:fisherman", "minecraft:butcher" -> true;
            default -> false;
        };
    }

    private static String professionId(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id == null ? "minecraft:none" : id.toString();
    }

    /** Food is a universal adult-villager need; profession deliberately does not gate buying. */
    private static boolean canBuyFood(Villager villager) {
        return !villager.isBaby();
    }

    private static boolean inDanger(Villager villager) {
        return villager.getBrain().hasMemoryValue(MemoryModuleType.DANGER_DETECTED_RECENTLY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY_ENTITY)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                || villager.getBrain().hasMemoryValue(MemoryModuleType.AVOID_TARGET);
    }

    private record FoodSeller(Villager producer, VillagerWorkInventory inventory, ItemStack food) {
    }
}
