package dev.totem.villagers.workshop;

import dev.totem.villagers.trade.CartographerExplorerMapTrades;
import dev.totem.villagers.work.CartographerExplorerMapRules;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;

import java.util.Objects;

/** Replaces a completed Cartographer empty-map recipe with an exact explorer map on a tiered roll. */
public final class CartographerExplorerMapWorkshopAction implements ValidatedWorkshopAction {
    public static final String EMPTY_MAP_ORDER_ID = "totem:cartographer_empty_map";

    private final ServerLevel level;
    private final Villager villager;
    private final BlockPos jobSite;
    private final WorkOrder order;
    private final MerchantOffers offers;
    private ItemStack produced = ItemStack.EMPTY;

    public CartographerExplorerMapWorkshopAction(
            ServerLevel level, Villager villager, BlockPos jobSite, WorkOrder order, MerchantOffers offers
    ) {
        this.level = Objects.requireNonNull(level, "level");
        this.villager = Objects.requireNonNull(villager, "villager");
        this.jobSite = Objects.requireNonNull(jobSite, "jobSite");
        this.order = Objects.requireNonNull(order, "order");
        this.offers = Objects.requireNonNull(offers, "offers");
    }

    public static boolean supports(WorkOrder order, ServerLevel level, BlockPos jobSite) {
        return isEmptyMapOrder(order) && RecipeBackedWorkshopAction.supports(order, level, jobSite);
    }

    @Override
    public boolean complete() {
        if (!new RecipeBackedWorkshopAction(level, villager, jobSite, order).complete()) {
            return false;
        }
        var definition = CartographerExplorerMapRules.chooseExplorerMap(villager.getRandom(), villager.getVillagerData().level())
                .orElse(null);
        if (definition == null || !CartographerExplorerMapTrades.hasCapacity(offers)) {
            return true;
        }
        BlockPos destination = level.findNearestMapStructure(definition.destination(), villager.blockPosition(),
                CartographerExplorerMapRules.searchRadius(), true);
        if (destination == null) {
            return true;
        }
        ItemStack explorerMap = MapItem.create(level, destination.getX(), destination.getZ(), (byte) 2, true, true);
        if (explorerMap.get(DataComponents.MAP_ID) == null) {
            return true;
        }
        MapDecorationType decoration = BuiltInRegistries.MAP_DECORATION_TYPE.getValue(
                Identifier.tryParse(definition.decorationId()));
        if (decoration == null) {
            return true;
        }
        MapItem.renderBiomePreviewMap(level, explorerMap);
        MapItemSavedData.addTargetDecoration(explorerMap, destination, "+",
                BuiltInRegistries.MAP_DECORATION_TYPE.wrapAsHolder(decoration));
        explorerMap.set(DataComponents.ITEM_NAME, Component.translatable(definition.nameTranslationKey()));
        if (!CartographerExplorerMapTrades.registerProducedOffer(offers, explorerMap, definition)) {
            return true;
        }
        produced = explorerMap;
        return true;
    }

    @Override
    public WorkOrder completedOrder(WorkOrder scheduledOrder) {
        return produced.isEmpty() ? scheduledOrder
                : scheduledOrder.withOutputVariant(new ItemAmount("minecraft:filled_map", 1),
                StockVariantKey.fromStack(produced, level.registryAccess()).componentPatch());
    }

    static boolean isExplorerOutputVariant(WorkOrder scheduledOrder, WorkOrder completedOrder) {
        return isEmptyMapOrder(scheduledOrder)
                && "minecraft:map".equals(scheduledOrder.output().itemId())
                && scheduledOrder.output().count() == 1
                && "minecraft:filled_map".equals(completedOrder.output().itemId())
                && completedOrder.output().count() == 1
                && !completedOrder.outputComponentPatch().isBlank();
    }

    private static boolean isEmptyMapOrder(WorkOrder order) {
        return EMPTY_MAP_ORDER_ID.equals(order.id()) && "minecraft:cartographer".equals(order.professionId());
    }
}
