package dev.totem.villagers.work;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds per-Leatherworker orders only from that villager's already-generated
 * dyed vanilla offers. Vanilla creates those colours from one to three random
 * dyes, so this class searches that deliberately bounded space and records the
 * exact recipe inputs needed to reproduce the offer's component patch.
 */
public final class LeatherworkerDyedArmorOrders {
    public static final String ORDER_ID_PREFIX = "totem:leatherworker_dyed_";
    private static final String LEATHERWORKER = "minecraft:leatherworker";
    private static final int WORK_TICKS = 120;
    private static final int STOCK_CAP = 12;
    private static final Map<String, Optional<List<DyeColor>>> DYE_RECIPES = new ConcurrentHashMap<>();

    private LeatherworkerDyedArmorOrders() {
    }

    public static WorkOrderCatalog extend(WorkOrderCatalog baseCatalog, MerchantOffers offers, HolderLookup.Provider registries) {
        List<WorkOrder> all = new ArrayList<>(baseCatalog.snapshot().values());
        all.addAll(ordersFor(offers, registries));
        return new WorkOrderCatalog(all);
    }

    public static List<WorkOrder> ordersFor(MerchantOffers offers, HolderLookup.Provider registries) {
        if (offers == null || offers.isEmpty()) {
            return List.of();
        }
        Map<String, WorkOrder> unique = new LinkedHashMap<>();
        for (MerchantOffer offer : offers) {
            orderFor(offer, registries).ifPresent(order -> unique.putIfAbsent(order.id(), order));
        }
        return List.copyOf(unique.values());
    }

    public static Optional<WorkOrder> orderFor(MerchantOffer offer, HolderLookup.Provider registries) {
        ItemStack result = offer.getResult();
        String itemId = result.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        int leather = leatherCount(itemId);
        List<DyeColor> dyes = dyesFor(result).orElse(null);
        if (result.isEmpty() || result.getCount() != 1 || leather == 0 || dyes == null) {
            return Optional.empty();
        }
        StockVariantKey key = StockVariantKey.fromStack(result, registries);
        Map<String, Integer> ingredients = new LinkedHashMap<>();
        ingredients.put("minecraft:leather", leather);
        for (DyeColor dye : dyes) {
            ingredients.merge(dyeItemId(dye), 1, Math::addExact);
        }
        String id = ORDER_ID_PREFIX + UUID.nameUUIDFromBytes(key.persistentString().getBytes(StandardCharsets.UTF_8));
        return Optional.of(new WorkOrder(
                id,
                LEATHERWORKER,
                new ItemAmount(itemId, 1),
                ingredients.entrySet().stream().map(entry -> new ItemAmount(entry.getKey(), entry.getValue())).toList(),
                java.util.Set.of(WorkSource.WORKSHOP),
                "", "", "", key.componentPatch(), WORK_TICKS, STOCK_CAP
        ));
    }

    public static boolean isOfferBoundDyedOrder(WorkOrder order) {
        return order.id().startsWith(ORDER_ID_PREFIX)
                && LEATHERWORKER.equals(order.professionId())
                && leatherCount(order.output().itemId()) > 0
                && !order.outputComponentPatch().isBlank();
    }

    /** Returns a deterministic one-to-three-dye recipe that recreates the target's exact vanilla RGB. */
    public static Optional<List<DyeColor>> dyesFor(ItemStack target) {
        if (target.isEmpty()) {
            return Optional.empty();
        }
        String itemId = BuiltInRegistries.ITEM.getKey(target.getItem()).toString();
        DyedItemColor colour = target.get(DataComponents.DYED_COLOR);
        if (leatherCount(itemId) == 0 || colour == null) {
            return Optional.empty();
        }
        return DYE_RECIPES.computeIfAbsent(itemId + ":" + colour.rgb(), ignored -> findDyes(target));
    }

    private static Optional<List<DyeColor>> findDyes(ItemStack target) {
        for (int count = 1; count <= 3; count++) {
            Optional<List<DyeColor>> match = findDyes(target, new ArrayList<>(), count);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<DyeColor>> findDyes(ItemStack target, List<DyeColor> current, int remaining) {
        if (remaining == 0) {
            ItemStack produced = DyedItemColor.applyDyes(new ItemStack(target.getItem()), current);
            DyedItemColor expected = target.get(DataComponents.DYED_COLOR);
            return expected.equals(produced.get(DataComponents.DYED_COLOR)) ? Optional.of(List.copyOf(current)) : Optional.empty();
        }
        for (DyeColor dye : DyeColor.VALUES) {
            current.add(dye);
            Optional<List<DyeColor>> match = findDyes(target, current, remaining - 1);
            current.removeLast();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    public static int leatherCount(String itemId) {
        return switch (itemId) {
            case "minecraft:leather_helmet" -> 5;
            case "minecraft:leather_chestplate" -> 8;
            case "minecraft:leather_leggings", "minecraft:leather_horse_armor" -> 7;
            case "minecraft:leather_boots" -> 4;
            default -> 0;
        };
    }

    public static String dyeItemId(DyeColor dye) {
        return "minecraft:" + dye.getName() + "_dye";
    }

    public static ItemStack dyeStack(DyeColor dye) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(dyeItemId(dye)));
        if (item == null) {
            throw new IllegalStateException("Missing vanilla dye item for " + dye.getName());
        }
        return new ItemStack(item);
    }
}
