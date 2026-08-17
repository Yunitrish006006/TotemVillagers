package dev.totem.villagers.work;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Derives Farmer suspicious-stew work only when the villager's live special
 * offer can be reproduced by one exact vanilla flower recipe. The trade table
 * can also create effect/duration combinations that are not crafting recipes;
 * those offers intentionally receive no order and remain out of stock.
 */
public final class FarmerSuspiciousStewOrders {
    public static final String ORDER_ID_PREFIX = "totem:farmer_suspicious_stew_";
    private static final String FARMER = "minecraft:farmer";
    private static final String SUSPICIOUS_STEW = "minecraft:suspicious_stew";
    private static final int WORK_TICKS = 100;
    private static final int STOCK_CAP = 12;
    private static final List<StewInput> INPUTS = List.of(
            input("minecraft:dandelion", Items.DANDELION),
            input("minecraft:poppy", Items.POPPY),
            input("minecraft:blue_orchid", Items.BLUE_ORCHID),
            input("minecraft:allium", Items.ALLIUM),
            input("minecraft:azure_bluet", Items.AZURE_BLUET),
            input("minecraft:red_tulip", Items.RED_TULIP),
            input("minecraft:orange_tulip", Items.ORANGE_TULIP),
            input("minecraft:white_tulip", Items.WHITE_TULIP),
            input("minecraft:pink_tulip", Items.PINK_TULIP),
            input("minecraft:oxeye_daisy", Items.OXEYE_DAISY),
            input("minecraft:cornflower", Items.CORNFLOWER),
            input("minecraft:lily_of_the_valley", Items.LILY_OF_THE_VALLEY),
            input("minecraft:wither_rose", Items.WITHER_ROSE),
            input("minecraft:torchflower", Items.TORCHFLOWER),
            input("minecraft:open_eyeblossom", Items.OPEN_EYEBLOSSOM),
            input("minecraft:closed_eyeblossom", Items.CLOSED_EYEBLOSSOM),
            input("minecraft:golden_dandelion", Items.GOLDEN_DANDELION)
    );

    private FarmerSuspiciousStewOrders() {
    }

    /** Merges the static catalogue with the current Farmer's reproducible stew offer. */
    public static WorkOrderCatalog extend(WorkOrderCatalog baseCatalog, MerchantOffers offers, ServerLevel level) {
        List<WorkOrder> all = new ArrayList<>(baseCatalog.snapshot().values());
        all.addAll(ordersFor(offers, level));
        return new WorkOrderCatalog(all);
    }

    public static List<WorkOrder> ordersFor(MerchantOffers offers, ServerLevel level) {
        if (offers == null || offers.isEmpty()) {
            return List.of();
        }
        Map<String, WorkOrder> unique = new LinkedHashMap<>();
        for (MerchantOffer offer : offers) {
            orderFor(offer, level).ifPresent(order -> unique.putIfAbsent(order.id(), order));
        }
        return List.copyOf(unique.values());
    }

    /**
     * Finds the first deterministic vanilla flower recipe whose fully assembled
     * component key equals the already-generated offer result.
     */
    public static Optional<WorkOrder> orderFor(MerchantOffer offer, ServerLevel level) {
        ItemStack result = offer.getResult();
        if (result.isEmpty() || result.getCount() != 1 || !result.is(Items.SUSPICIOUS_STEW)) {
            return Optional.empty();
        }
        StockVariantKey offerKey = StockVariantKey.fromStack(result, level.registryAccess());
        for (StewInput input : INPUTS) {
            ItemStack crafted = assemble(level, input.craftingInput());
            if (crafted.isEmpty() || !offerKey.equals(StockVariantKey.fromStack(crafted, level.registryAccess()))) {
                continue;
            }
            String id = ORDER_ID_PREFIX + UUID.nameUUIDFromBytes(offerKey.persistentString().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new WorkOrder(
                    id, FARMER, new ItemAmount(SUSPICIOUS_STEW, 1), input.requiredInputs(),
                    java.util.Set.of(WorkSource.WORKSHOP), "", "", "", offerKey.componentPatch(), WORK_TICKS, STOCK_CAP
            ));
        }
        return Optional.empty();
    }

    public static boolean isOfferBoundSuspiciousStewOrder(WorkOrder order) {
        return order.id().startsWith(ORDER_ID_PREFIX)
                && FARMER.equals(order.professionId())
                && SUSPICIOUS_STEW.equals(order.output().itemId())
                && !order.outputComponentPatch().isBlank();
    }

    /** Exposed for the action and GameTest; only IDs backed by shipped vanilla recipes are accepted. */
    public static Optional<CraftingInput> craftingInput(WorkOrder order) {
        if (!isOfferBoundSuspiciousStewOrder(order)) {
            return Optional.empty();
        }
        return INPUTS.stream()
                .filter(input -> input.requiredInputs().equals(order.requiredInputs()))
                .map(StewInput::craftingInput)
                .findFirst();
    }

    /** Builds one exact shapeless input for a known vanilla suspicious-stew flower. */
    public static Optional<CraftingInput> craftingInputForFlower(String flowerId) {
        return INPUTS.stream().filter(input -> input.flowerId().equals(flowerId)).map(StewInput::craftingInput).findFirst();
    }

    private static ItemStack assemble(ServerLevel level, CraftingInput input) {
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return ItemStack.EMPTY;
        }
        return recipe.value().assemble(input);
    }

    private static StewInput input(String flowerId, Item flower) {
        Item registered = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(flowerId));
        if (registered != flower) {
            throw new IllegalStateException("Missing vanilla suspicious-stew flower " + flowerId);
        }
        return new StewInput(flowerId, flower);
    }

    private record StewInput(String flowerId, Item flower) {
        List<ItemAmount> requiredInputs() {
            return List.of(
                    new ItemAmount("minecraft:bowl", 1), new ItemAmount("minecraft:brown_mushroom", 1),
                    new ItemAmount("minecraft:red_mushroom", 1), new ItemAmount(flowerId, 1)
            );
        }

        CraftingInput craftingInput() {
            return CraftingInput.of(3, 3, List.of(
                    new ItemStack(Items.BOWL), new ItemStack(Items.BROWN_MUSHROOM), new ItemStack(Items.RED_MUSHROOM),
                    new ItemStack(flower), ItemStack.EMPTY, ItemStack.EMPTY,
                    ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
            ));
        }
    }
}
