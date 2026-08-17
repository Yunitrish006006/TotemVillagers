package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.trade.InventoryDrivenProfessionTrades;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.RemnantBackpackOrders;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderCatalogs;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.workshop.RecipeBackedWorkshopAction;
import dev.totem.villagers.workshop.WorkshopCommitResult;
import dev.totem.villagers.workshop.WorkshopCommitService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** Cross-module proof that a Toolsmith really performs and sells all four Remnant smithing recipes. */
public final class ToolsmithBackpackGameTest {
    private static final WorkshopCommitService COMMITS = new WorkshopCommitService();

    @GameTest(maxTicks = 100)
    public void toolsmithSmithsOnlyPristineRemnantBackpacksAndExposesPhysicalStock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relativeTable = new BlockPos(3, 2, 3);
        BlockPos table = helper.absolutePos(relativeTable);
        helper.setBlock(relativeTable, Blocks.SMITHING_TABLE);
        Villager toolsmith = spawnToolsmith(helper, relativeTable.above());
        VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(level.getServer())
                .inventory(toolsmith.getUUID());
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            WorkOrderCatalog catalog = WorkOrderCatalogs.effectiveFor(
                    WorkOrderDefinitions.catalog(), "minecraft:toolsmith", new MerchantOffers(), level);
            for (String orderId : List.of(
                    "totem:toolsmith_remnant_backpack_basic",
                    "totem:toolsmith_remnant_backpack_standard",
                    "totem:toolsmith_remnant_backpack_advanced",
                    "totem:toolsmith_remnant_backpack_netherite"
            )) {
                require(helper, catalog.snapshot().containsKey(orderId),
                        "TotemRemnant was loaded but its Toolsmith order is missing: " + orderId);
            }
            MerchantOffers materialOffers = toolsmith.getOffers();
            VillagerTradeStockAuthority.refreshOffers(toolsmith, materialOffers);
            require(helper, hasPurchase(materialOffers, Items.BUNDLE, 2, 1)
                            && hasPurchase(materialOffers, Items.LEATHER, 4, 1)
                            && hasPurchase(materialOffers, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 8)
                            && hasPurchase(materialOffers, Items.NETHERITE_INGOT, 1, 8),
                    "Toolsmith did not expose all backpack materials through ordinary purchase rows");

            WorkOrder basic = catalog.require("totem:toolsmith_remnant_backpack_basic");
            ItemStack namedBundle = new ItemStack(Items.BUNDLE);
            namedBundle.set(DataComponents.CUSTOM_NAME, Component.literal("not pristine"));
            require(helper, inventory.insertExact(namedBundle)
                            && inventory.insertExact(new ItemStack(Items.BUNDLE))
                            && inventory.insertExact(new ItemStack(Items.LEATHER)),
                    "Could not stage the component-sensitive backpack inputs");
            require(helper, !RemnantBackpackOrders.canReservePristineInputs(basic, inventory),
                    "A renamed Bundle was accepted as a pristine backpack ingredient");
            VillagerWorkInventorySavedData.forServer(level.getServer()).drain(toolsmith.getUUID());

            make(helper, level, toolsmith, inventory, table, basic,
                    List.of(new ItemStack(Items.BUNDLE, 2), new ItemStack(Items.LEATHER)));
            assertSellRow(helper, level, toolsmith, inventory, RemnantBackpackOrders.BASIC, 8);

            make(helper, level, toolsmith, inventory, table,
                    catalog.require("totem:toolsmith_remnant_backpack_standard"),
                    List.of(new ItemStack(Items.BUNDLE), new ItemStack(Items.IRON_INGOT)));
            assertSellRow(helper, level, toolsmith, inventory, RemnantBackpackOrders.STANDARD, 16);

            make(helper, level, toolsmith, inventory, table,
                    catalog.require("totem:toolsmith_remnant_backpack_advanced"),
                    List.of(new ItemStack(Items.BUNDLE), new ItemStack(Items.DIAMOND)));
            assertSellRow(helper, level, toolsmith, inventory, RemnantBackpackOrders.ADVANCED, 32);

            make(helper, level, toolsmith, inventory, table,
                    catalog.require("totem:toolsmith_remnant_backpack_netherite"),
                    List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                            new ItemStack(Items.NETHERITE_INGOT)));
            assertSellRow(helper, level, toolsmith, inventory, RemnantBackpackOrders.NETHERITE, 64);
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkInventorySavedData.forServer(level.getServer()).drain(toolsmith.getUUID());
            toolsmith.discard();
        }
    }

    private static void make(GameTestHelper helper, ServerLevel level, Villager toolsmith,
                             VillagerWorkInventory inventory, BlockPos table, WorkOrder order,
                             List<ItemStack> supplied) {
        for (ItemStack stack : supplied) {
            require(helper, inventory.insertExact(stack), "Could not supply " + stack + " for " + order.id());
        }
        var reservation = RemnantBackpackOrders.reservePristineInputs(order, inventory).orElse(null);
        require(helper, reservation != null, "Toolsmith could not reserve pristine inputs for " + order.id());
        var action = new RecipeBackedWorkshopAction(level, toolsmith, table, order);
        require(helper, RecipeBackedWorkshopAction.supports(order, level, table),
                "Smithing Table rejected the backpack order " + order.id());
        WorkshopCommitResult result = COMMITS.completePhysical(reservation, order, action, completed ->
                new ItemStack(item(completed.output().itemId()), completed.output().count()));
        require(helper, result == WorkshopCommitResult.COMPLETED,
                "Live Remnant smithing recipe did not complete " + order.id() + ": " + result);
    }

    private static void assertSellRow(GameTestHelper helper, ServerLevel level, Villager toolsmith,
                                      VillagerWorkInventory inventory, String itemId, int emeraldPrice) {
        ItemStack backpack = new ItemStack(item(itemId));
        require(helper, inventory.countMatchingItem(backpack) == 1,
                "The crafted backpack is not physical Toolsmith stock: " + itemId);
        MerchantOffers offers = toolsmith.getOffers();
        VillagerTradeStockAuthority.refreshOffers(toolsmith, offers);
        MerchantOffer offer = offers.stream().filter(row -> row.getResult().is(backpack.getItem())).findFirst().orElse(null);
        require(helper, offer != null && InventoryDrivenProfessionTrades.isManagedOffer(toolsmith, offer, level),
                "The physical backpack did not create a managed vanilla trade row: " + itemId);
        require(helper, offer.getBaseCostA().is(Items.EMERALD)
                        && offer.getBaseCostA().getCount() == emeraldPrice && !offer.isOutOfStock(),
                "The backpack trade has the wrong price or stock state: " + itemId);
    }

    private static Item item(String itemId) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        if (item == null || item == Items.AIR) {
            throw new IllegalStateException("Missing registered item " + itemId);
        }
        return item;
    }

    private static boolean hasPurchase(MerchantOffers offers, Item input, int inputCount, int emeraldPayout) {
        return offers.stream().anyMatch(offer -> offer.getResult().is(Items.EMERALD)
                && offer.getResult().getCount() == emeraldPayout
                && offer.getBaseCostA().is(input)
                && (offer.getCostB().isEmpty() || offer.getCostB().is(input))
                && offer.getBaseCostA().getCount() + offer.getCostB().getCount() == inputCount
                && offer.getBaseCostA().getCount() <= offer.getBaseCostA().getMaxStackSize()
                && (offer.getCostB().isEmpty()
                    || offer.getCostB().getCount() <= offer.getCostB().getMaxStackSize()));
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnToolsmith(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.parse("minecraft:toolsmith"));
        require(helper, profession != null, "Missing minecraft:toolsmith profession");
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
