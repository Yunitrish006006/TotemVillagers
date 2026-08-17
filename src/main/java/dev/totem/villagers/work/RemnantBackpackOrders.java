package dev.totem.villagers.work;

import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Optional TotemRemnant integration for the four player-craftable backpack tiers. */
public final class RemnantBackpackOrders {
    public static final String BASIC = "totem:remnant/backpack_basic";
    public static final String STANDARD = "totem:remnant/backpack_standard";
    public static final String ADVANCED = "totem:remnant/backpack_advanced";
    public static final String NETHERITE = "totem:remnant/backpack_netherite";
    private static final String TOOLSMITH = "minecraft:toolsmith";
    private static final int STOCK_CAP = 4;
    private static final Map<String, Integer> EMERALD_PRICES = Map.of(
            BASIC, 8,
            STANDARD, 16,
            ADVANCED, 32,
            NETHERITE, 64
    );

    private RemnantBackpackOrders() {
    }

    /** Adds no entries when TotemRemnant is absent, preserving standalone operation. */
    public static WorkOrderCatalog extend(WorkOrderCatalog baseCatalog, ServerLevel level) {
        List<WorkOrder> all = new ArrayList<>(baseCatalog.snapshot().values());
        definitions().stream()
                .filter(definition -> !baseCatalog.snapshot().containsKey("totem:toolsmith_remnant_" + definition.name()))
                .filter(definition -> registeredItem(definition.outputId()).isPresent())
                .filter(definition -> definition.smithingItems().stream().allMatch(id -> registeredItem(id).isPresent()))
                .map(RemnantBackpackOrders::order)
                .forEach(all::add);
        return new WorkOrderCatalog(all);
    }

    public static boolean isBackpackOrder(WorkOrder order) {
        return order != null && TOOLSMITH.equals(order.professionId()) && EMERALD_PRICES.containsKey(order.output().itemId());
    }

    public static int emeraldPrice(String outputId) {
        return EMERALD_PRICES.getOrDefault(outputId, 0);
    }

    /** The exact pristine stacks placed in the Smithing Table's template, base and addition slots. */
    public static Optional<List<ItemStack>> pristineSmithingStacks(WorkOrder order) {
        Definition definition = definitions().stream()
                .filter(candidate -> candidate.outputId().equals(order.output().itemId()))
                .filter(candidate -> candidate.requiredInputs().equals(order.requiredInputs()))
                .findFirst().orElse(null);
        if (definition == null) {
            return Optional.empty();
        }
        List<ItemStack> stacks = new ArrayList<>(3);
        for (String itemId : definition.smithingItems()) {
            Item item = registeredItem(itemId).orElse(null);
            if (item == null) {
                return Optional.empty();
            }
            stacks.add(new ItemStack(item));
        }
        return Optional.of(List.copyOf(stacks));
    }

    public static boolean canReservePristineInputs(WorkOrder order, VillagerWorkInventory inventory) {
        return pristineSmithingStacks(order).filter(inventory::canReserveExactMatching).isPresent();
    }

    public static Optional<dev.totem.villagers.inventory.WorkInventory.Reservation> reservePristineInputs(
            WorkOrder order, VillagerWorkInventory inventory
    ) {
        return pristineSmithingStacks(order).flatMap(inventory::reserveExactMatching);
    }

    private static WorkOrder order(Definition definition) {
        return new WorkOrder(
                "totem:toolsmith_remnant_" + definition.name(), TOOLSMITH,
                new ItemAmount(definition.outputId(), 1), definition.requiredInputs(), Set.of(WorkSource.WORKSHOP),
                "", "", "", "", definition.workTicks(), STOCK_CAP
        );
    }

    private static List<Definition> definitions() {
        return List.of(
                new Definition("backpack_basic", BASIC,
                        List.of("minecraft:bundle", "minecraft:bundle", "minecraft:leather"),
                        List.of(new ItemAmount("minecraft:bundle", 2), new ItemAmount("minecraft:leather", 1)), 100),
                new Definition("backpack_standard", STANDARD,
                        List.of("minecraft:bundle", BASIC, "minecraft:iron_ingot"),
                        List.of(new ItemAmount("minecraft:bundle", 1), new ItemAmount(BASIC, 1),
                                new ItemAmount("minecraft:iron_ingot", 1)), 120),
                new Definition("backpack_advanced", ADVANCED,
                        List.of("minecraft:bundle", STANDARD, "minecraft:diamond"),
                        List.of(new ItemAmount("minecraft:bundle", 1), new ItemAmount(STANDARD, 1),
                                new ItemAmount("minecraft:diamond", 1)), 160),
                new Definition("backpack_netherite", NETHERITE,
                        List.of("minecraft:netherite_upgrade_smithing_template", ADVANCED, "minecraft:netherite_ingot"),
                        List.of(new ItemAmount("minecraft:netherite_upgrade_smithing_template", 1),
                                new ItemAmount(ADVANCED, 1), new ItemAmount("minecraft:netherite_ingot", 1)), 200)
        );
    }

    private static Optional<Item> registeredItem(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        return item == null || item == Items.AIR ? Optional.empty() : Optional.of(item);
    }

    private record Definition(String name, String outputId, List<String> smithingItems,
                              List<ItemAmount> requiredInputs, int workTicks) {
    }
}
