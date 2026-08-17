package dev.totem.villagers.work;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The librarian's enchanting-table policy. Profession tier determines the
 * vanilla enchanting power, while a small tiered roll may use the non-curse
 * treasure pool instead of the ordinary enchanting-table pool.
 */
public final class LibrarianEnchantingRules {
    private static final int[] ENCHANTING_POWER_BY_VILLAGER_LEVEL = {6, 12, 18, 24, 30};
    private static final int[] TREASURE_PERCENT_BY_VILLAGER_LEVEL = {0, 1, 2, 4, 8};
    private static final int[] LAPIS_COST_BY_VILLAGER_LEVEL = {1, 2, 2, 3, 3};
    private static final int TREASURE_PRICE_PREMIUM = 8;

    private LibrarianEnchantingRules() {
    }

    public static int enchantingPower(int villagerLevel) {
        return ENCHANTING_POWER_BY_VILLAGER_LEVEL[index(villagerLevel)];
    }

    public static int treasurePercent(int villagerLevel) {
        return TREASURE_PERCENT_BY_VILLAGER_LEVEL[index(villagerLevel)];
    }

    public static int lapisCost(int villagerLevel) {
        return LAPIS_COST_BY_VILLAGER_LEVEL[index(villagerLevel)];
    }

    public static boolean rollsTreasure(RandomSource random, int villagerLevel) {
        Objects.requireNonNull(random, "random");
        int chance = treasurePercent(villagerLevel);
        return chance > 0 && random.nextInt(100) < chance;
    }

    /**
     * Runs the same server enchantment selection used by a player enchantment
     * table at the tier's configured power. Treasure success replaces the
     * ordinary pool; it never adds a treasure book on top of a normal roll.
     */
    public static ItemStack enchantBook(RandomSource random, RegistryAccess registries, int villagerLevel) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(registries, "registries");
        var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Stream<Holder<Enchantment>> candidates = candidates(random, enchantments, villagerLevel);
        ItemStack result = EnchantmentHelper.enchantItem(random, new ItemStack(Items.BOOK), enchantingPower(villagerLevel), candidates);
        ItemEnchantments stored = result.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        return result.is(Items.ENCHANTED_BOOK) && !stored.isEmpty() ? result.copyWithCount(1) : ItemStack.EMPTY;
    }

    /** Enchants one ordinary table-compatible item under the same tier and treasure policy as books. */
    public static ItemStack enchantEquipment(RandomSource random, ItemStack input, RegistryAccess registries, int villagerLevel) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(registries, "registries");
        if (input.isEmpty() || input.is(Items.BOOK) || input.is(Items.ENCHANTED_BOOK)
                || input.isEnchanted() || !input.isEnchantable()) {
            return ItemStack.EMPTY;
        }
        var enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack result = EnchantmentHelper.enchantItem(random, input.copyWithCount(1), enchantingPower(villagerLevel),
                candidates(random, enchantments, villagerLevel));
        return result.isEnchanted() ? result.copyWithCount(1) : ItemStack.EMPTY;
    }

    /** A deterministic price lets the existing exact-component stock ledger remain batch-free. */
    public static int emeraldPrice(ItemStack book) {
        if (!book.is(Items.ENCHANTED_BOOK)) {
            throw new IllegalArgumentException("Only enchanted books have a librarian enchanting price");
        }
        return Mth.clamp(4 + enchantmentSurcharge(book), 8, 64);
    }

    /** Enchantment-only part of a price; callers add the physical item's own value. */
    public static int enchantmentSurcharge(ItemStack enchantedItem) {
        Objects.requireNonNull(enchantedItem, "enchantedItem");
        ItemEnchantments stored = enchantments(enchantedItem);
        if (stored.isEmpty()) {
            throw new IllegalArgumentException("An enchanted item must contain enchantments");
        }
        int price = 0;
        int entries = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stored.entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            price += rarityPriceWeight(enchantment.value().getWeight()) * entry.getIntValue();
            if (enchantment.is(EnchantmentTags.TREASURE)) {
                price += TREASURE_PRICE_PREMIUM;
            }
            entries++;
        }
        price += 5 * Math.max(0, entries - 1);
        return price;
    }

    public static boolean hasTreasureEnchantment(ItemStack book) {
        ItemEnchantments stored = enchantments(book);
        return stored.entrySet().stream().anyMatch(entry -> entry.getKey().is(EnchantmentTags.TREASURE));
    }

    private static Stream<Holder<Enchantment>> candidates(
            RandomSource random, net.minecraft.core.Registry<Enchantment> enchantments, int villagerLevel
    ) {
        return rollsTreasure(random, villagerLevel)
                ? tag(enchantments.get(EnchantmentTags.TREASURE)).filter(holder -> !holder.is(EnchantmentTags.CURSE))
                : tag(enchantments.get(EnchantmentTags.IN_ENCHANTING_TABLE));
    }

    private static ItemEnchantments enchantments(ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK)
                ? stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)
                : stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    private static Stream<Holder<Enchantment>> tag(Optional<HolderSet.Named<Enchantment>> tag) {
        return tag.map(HolderSet.Named::stream).orElseGet(Stream::empty);
    }

    private static int rarityPriceWeight(int vanillaWeight) {
        if (vanillaWeight <= 1) {
            return 10;
        }
        if (vanillaWeight <= 2) {
            return 6;
        }
        if (vanillaWeight <= 5) {
            return 4;
        }
        return 3;
    }

    private static int index(int villagerLevel) {
        if (villagerLevel < 1 || villagerLevel > ENCHANTING_POWER_BY_VILLAGER_LEVEL.length) {
            throw new IllegalArgumentException("Villager profession level must be in 1..5: " + villagerLevel);
        }
        return villagerLevel - 1;
    }
}
