package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.runtime.VillagerLibrarianEnchantingRuntime;
import dev.totem.villagers.trade.LibrarianEnchantedBookTrades;
import dev.totem.villagers.trade.LibrarianEnchantedEquipmentTrades;
import dev.totem.villagers.work.LibrarianEnchantingEquipmentRules;
import dev.totem.villagers.work.LibrarianEnchantingRules;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

/** End-to-end proof that a Librarian's own table work creates the only book offer it can sell. */
public final class LibrarianEnchantingGameTest {
    @GameTest(maxTicks = 180)
    public void librarianCreatesStockBackedEnchantingTableOffer(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeTable = new BlockPos(3, 2, 3);
        helper.setBlock(relativeTable, Blocks.ENCHANTING_TABLE);
        Villager librarian = spawnLibrarian(helper, relativeTable.above());
        try {
            librarian.setVillagerData(librarian.getVillagerData().withLevel(VillagerData.MAX_VILLAGER_LEVEL));
            VillagerNutrition.setFoodLevel(librarian, 20);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(librarian.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.BOOK)), "Could not supply the Librarian's book");
            require(helper, inventory.insertExact(new ItemStack(Items.LAPIS_LAZULI, 3)), "Could not supply the Librarian's lapis");
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            for (int tick = 0; tick <= VillagerLibrarianEnchantingRuntime.WORK_TICKS; tick++) {
                VillagerLibrarianEnchantingRuntime.tickForGameTest(server);
            }

            MerchantOffers offers = librarian.getOffers();
            MerchantOffer dynamic = offers.stream().filter(LibrarianEnchantedBookTrades::isManagedOffer).findFirst().orElse(null);
            require(helper, dynamic != null, "Librarian did not publish the table-produced enchanted book");
            require(helper, dynamic.getCostB().is(Items.BOOK), "Dynamic enchanted-book sale did not retain the book payment");
            int expectedPrice = LibrarianEnchantingRules.emeraldPrice(dynamic.getResult());
            require(helper, dynamic.getBaseCostA().is(Items.EMERALD)
                    && dynamic.getBaseCostA().getCount() == expectedPrice,
                    "Dynamic enchanted-book price was not derived from its exact enchantments");
            int stock = inventory.countMatchingItem(dynamic.getResult());
            require(helper, stock == 1 && !dynamic.isOutOfStock(),
                    "Exact produced enchanted-book stock did not unlock its own dynamic offer");
            ItemEnchantments enchantments = dynamic.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS,
                    ItemEnchantments.EMPTY);
            require(helper, !enchantments.isEmpty(), "Librarian produced an enchanted-book offer without enchantments");
            require(helper, enchantments.entrySet().stream().noneMatch(entry -> entry.getKey().is(EnchantmentTags.CURSE)),
                    "Librarian's treasure exception was allowed to produce a curse");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(librarian.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(librarian.getUUID());
            librarian.discard();
        }
    }

    @GameTest(maxTicks = 180)
    public void librarianEnchantsAndSellsTheFormerVanillaEquipmentRows(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeTable = new BlockPos(3, 2, 3);
        helper.setBlock(relativeTable, Blocks.ENCHANTING_TABLE);
        Villager librarian = spawnLibrarian(helper, relativeTable.above());
        try {
            librarian.setVillagerData(librarian.getVillagerData().withLevel(VillagerData.MAX_VILLAGER_LEVEL));
            VillagerNutrition.setFoodLevel(librarian, 20);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(librarian.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.FISHING_ROD)), "Could not supply the Librarian's fishing rod");
            require(helper, inventory.insertExact(new ItemStack(Items.LAPIS_LAZULI, 3)), "Could not supply the Librarian's lapis");
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            for (int tick = 0; tick <= VillagerLibrarianEnchantingRuntime.WORK_TICKS; tick++) {
                VillagerLibrarianEnchantingRuntime.tickForGameTest(server);
            }

            MerchantOffer dynamic = librarian.getOffers().stream().filter(LibrarianEnchantedEquipmentTrades::isManagedOffer)
                    .findFirst().orElse(null);
            require(helper, dynamic != null, "Librarian did not publish its table-produced enchanted equipment");
            require(helper, dynamic.getResult().is(Items.FISHING_ROD) && dynamic.getResult().isEnchanted(),
                    "Librarian did not produce an enchanted fishing rod from the supplied base equipment");
            require(helper, dynamic.getItemCostB().isEmpty(), "Librarian enchanted-equipment sale unexpectedly needs a second player cost");
            int expectedPrice = LibrarianEnchantingEquipmentRules.emeraldPrice(dynamic.getResult());
            require(helper, dynamic.getBaseCostA().is(Items.EMERALD)
                            && dynamic.getBaseCostA().getCount() == expectedPrice,
                    "Librarian enchanted-equipment price was not derived from the exact output");
            int stock = inventory.countMatchingItem(dynamic.getResult());
            require(helper, stock == 1 && !dynamic.isOutOfStock(),
                    "Exact enchanted-equipment stock did not unlock its own Librarian offer");
            require(helper, inventory.snapshot().stream().noneMatch(stack -> stack.is(Items.FISHING_ROD) && !stack.isEnchanted())
                            && inventory.snapshot().stream().noneMatch(stack -> stack.is(Items.LAPIS_LAZULI)),
                    "Librarian enchanted equipment without consuming its pristine input and lapis");
            require(helper, dynamic.getResult().getEnchantments().entrySet().stream()
                            .noneMatch(entry -> entry.getKey().is(EnchantmentTags.CURSE)),
                    "Librarian's treasure exception was allowed to produce cursed equipment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData.forServer(server).remove(librarian.getUUID());
            VillagerWorkInventorySavedData.forServer(server).drain(librarian.getUUID());
            librarian.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnLibrarian(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "librarian"));
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:librarian profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
