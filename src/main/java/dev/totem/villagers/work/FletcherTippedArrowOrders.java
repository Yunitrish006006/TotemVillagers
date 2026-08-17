package dev.totem.villagers.work;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
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
 * Creates a Fletcher work order only for a live tipped-arrow offer that can be
 * reproduced by the vanilla eight-arrows-around-one-lingering-potion recipe.
 * The selected lingering potion is component-sensitive: a different effect,
 * duration, or custom potion payload is never an interchangeable input.
 */
public final class FletcherTippedArrowOrders {
    public static final String ORDER_ID_PREFIX = "totem:fletcher_tipped_arrow_";
    private static final String FLETCHER = "minecraft:fletcher";
    private static final String TIPPED_ARROW = "minecraft:tipped_arrow";
    private static final List<ItemAmount> ORDINARY_INPUTS = List.of(new ItemAmount("minecraft:arrow", 8));
    private static final List<ItemAmount> REQUIRED_INPUTS = List.of(
            new ItemAmount("minecraft:lingering_potion", 1), new ItemAmount("minecraft:arrow", 8)
    );
    private static final int CRAFTED_ARROW_COUNT = 8;
    private static final int WORK_TICKS = 100;
    private static final int STOCK_CAP = 32;

    private FletcherTippedArrowOrders() {
    }

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
     * Accepts only an offered arrow variant that the current data-pack's
     * crafting recipe produces from the corresponding lingering potion.
     */
    public static Optional<WorkOrder> orderFor(MerchantOffer offer, ServerLevel level) {
        ItemStack result = offer.getResult();
        ItemStack potion = lingeringPotionFor(offer).orElse(null);
        if (result.isEmpty() || !result.is(Items.TIPPED_ARROW) || potion == null) {
            return Optional.empty();
        }
        ItemStack crafted = assemble(level, craftingInput(potion));
        StockVariantKey offerKey = StockVariantKey.fromStack(result, level.registryAccess());
        if (crafted.isEmpty() || !offerKey.equals(StockVariantKey.fromStack(crafted, level.registryAccess()))) {
            return Optional.empty();
        }
        String id = ORDER_ID_PREFIX + UUID.nameUUIDFromBytes(offerKey.persistentString().getBytes(StandardCharsets.UTF_8));
        return Optional.of(new WorkOrder(
                id, FLETCHER, new ItemAmount(TIPPED_ARROW, CRAFTED_ARROW_COUNT), REQUIRED_INPUTS,
                java.util.Set.of(WorkSource.WORKSHOP), "", "", "", offerKey.componentPatch(), WORK_TICKS, STOCK_CAP
        ));
    }

    public static boolean isOfferBoundTippedArrowOrder(WorkOrder order) {
        return order.id().startsWith(ORDER_ID_PREFIX)
                && FLETCHER.equals(order.professionId())
                && TIPPED_ARROW.equals(order.output().itemId())
                && order.output().count() == CRAFTED_ARROW_COUNT
                && REQUIRED_INPUTS.equals(order.requiredInputs())
                && !order.outputComponentPatch().isBlank();
    }

    /** Builds the exact lingering-potion input represented by one tipped-arrow offer. */
    public static Optional<ItemStack> lingeringPotionFor(MerchantOffer offer) {
        ItemStack result = offer.getResult();
        if (result.isEmpty() || !result.is(Items.TIPPED_ARROW)) {
            return Optional.empty();
        }
        PotionContents potionContents = result.get(DataComponents.POTION_CONTENTS);
        if (potionContents == null) {
            return Optional.empty();
        }
        ItemStack potion = new ItemStack(Items.LINGERING_POTION);
        potion.set(DataComponents.POTION_CONTENTS, potionContents);
        return Optional.of(potion);
    }

    /** Finds the live offer for an already generated order. */
    public static MerchantOffer matchingOffer(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        if (offers == null) {
            return null;
        }
        for (MerchantOffer offer : offers) {
            if (orderFor(offer, level).filter(order::equals).isPresent()
                    && StockVariantKey.fromStack(offer.getResult(), level.registryAccess()).equals(order.outputKey())) {
                return offer;
            }
        }
        return null;
    }

    /** Checks for arrows plus the exact lingering-potion component payload needed by this live offer. */
    public static boolean canReserve(
            WorkOrder order, VillagerWorkInventory inventory, MerchantOffers offers, ServerLevel level
    ) {
        ItemStack potion = potionForOrder(order, offers, level);
        return potion != null
                && inventory.snapshot().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, potion)
                        && stack.getCount() >= potion.getCount())
                && inventory.canReserveExact(ORDINARY_INPUTS);
    }

    /** Atomically takes the exact potion and eight ordinary arrows for a validated order. */
    public static Optional<dev.totem.villagers.inventory.WorkInventory.Reservation> reserve(
            WorkOrder order, VillagerWorkInventory inventory, MerchantOffers offers, ServerLevel level
    ) {
        ItemStack potion = potionForOrder(order, offers, level);
        return potion == null ? Optional.empty() : inventory.reserveExactMatching(potion, ORDINARY_INPUTS);
    }

    public static CraftingInput craftingInput(ItemStack potion) {
        List<ItemStack> grid = new ArrayList<>(9);
        for (int index = 0; index < 9; index++) {
            grid.add(index == 4 ? potion.copyWithCount(1) : new ItemStack(Items.ARROW));
        }
        return CraftingInput.of(3, 3, grid);
    }

    private static ItemStack potionForOrder(WorkOrder order, MerchantOffers offers, ServerLevel level) {
        MerchantOffer offer = matchingOffer(order, offers, level);
        return offer == null ? null : lingeringPotionFor(offer).orElse(null);
    }

    private static ItemStack assemble(ServerLevel level, CraftingInput input) {
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level).orElse(null);
        if (recipe == null || !recipe.value().matches(input, level)) {
            return ItemStack.EMPTY;
        }
        return recipe.value().assemble(input);
    }
}
