package dev.totem.villagers.work;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The vanilla profession rows whose generated enchanted equipment is instead
 * made at a Librarian's actual enchanting table.  A Librarian needs an
 * untouched, unenchanted base item in its work inventory; its tier controls
 * the enchanting strength, while this table preserves the normal village
 * progression for which equipment may be accepted.
 */
public final class LibrarianEnchantingEquipmentRules {
    private static final List<EquipmentDefinition> DEFINITIONS = List.of(
            definition("fishing_rod", Items.FISHING_ROD, 3, 3),
            definition("bow", Items.BOW, 4, 2),
            definition("crossbow", Items.CROSSBOW, 5, 3),
            definition("iron_sword", Items.IRON_SWORD, 1, 2),
            definition("iron_axe", Items.IRON_AXE, 3, 1),
            definition("iron_pickaxe", Items.IRON_PICKAXE, 3, 3),
            definition("iron_shovel", Items.IRON_SHOVEL, 3, 2),
            definition("diamond_boots", Items.DIAMOND_BOOTS, 4, 8),
            definition("diamond_leggings", Items.DIAMOND_LEGGINGS, 4, 14),
            definition("diamond_helmet", Items.DIAMOND_HELMET, 5, 8),
            definition("diamond_chestplate", Items.DIAMOND_CHESTPLATE, 5, 16),
            definition("diamond_sword", Items.DIAMOND_SWORD, 5, 8),
            definition("diamond_axe", Items.DIAMOND_AXE, 4, 12),
            definition("diamond_pickaxe", Items.DIAMOND_PICKAXE, 5, 13),
            definition("diamond_shovel", Items.DIAMOND_SHOVEL, 4, 5)
    );

    private LibrarianEnchantingEquipmentRules() {
    }

    public static List<EquipmentDefinition> definitions() {
        return DEFINITIONS;
    }

    public static Optional<EquipmentDefinition> definitionForOrder(String orderId) {
        return DEFINITIONS.stream().filter(definition -> definition.orderId().equals(orderId)).findFirst();
    }

    public static Optional<EquipmentDefinition> definitionFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return DEFINITIONS.stream().filter(definition -> stack.is(definition.item())).findFirst();
    }

    /** Only pristine ordinary equipment can enter this autonomous production path. */
    public static boolean isAcceptedInput(ItemStack stack, EquipmentDefinition definition, int librarianLevel) {
        return definition != null
                && librarianLevel >= definition.minimumLibrarianLevel()
                && stack != null
                && stack.getCount() == 1
                && !stack.isEnchanted()
                && ItemStack.isSameItemSameComponents(stack, definition.baseStack());
    }

    /** Base vanilla trade price plus the exact quality surcharge of the produced enchantments. */
    public static int emeraldPrice(ItemStack enchantedEquipment) {
        EquipmentDefinition definition = definitionFor(enchantedEquipment)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Librarian enchanted equipment"));
        return Mth.clamp(definition.baseEmeraldPrice() + LibrarianEnchantingRules.enchantmentSurcharge(enchantedEquipment),
                definition.baseEmeraldPrice(), 64);
    }

    private static EquipmentDefinition definition(String key, Item item, int minimumLibrarianLevel, int baseEmeraldPrice) {
        return new EquipmentDefinition(key, item, minimumLibrarianLevel, baseEmeraldPrice,
                "minecraft:" + key, "totem:librarian_enchanting_equipment_" + key);
    }

    public record EquipmentDefinition(
            String key, Item item, int minimumLibrarianLevel, int baseEmeraldPrice, String itemId, String orderId
    ) {
        public EquipmentDefinition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(orderId, "orderId");
            if (minimumLibrarianLevel < 1 || minimumLibrarianLevel > 5 || baseEmeraldPrice < 1
                    || !BuiltInRegistries.ITEM.getKey(item).toString().equals(itemId)) {
                throw new IllegalArgumentException("Invalid Librarian enchanted-equipment definition: " + key);
            }
        }

        public ItemStack baseStack() {
            return new ItemStack(item);
        }
    }
}
