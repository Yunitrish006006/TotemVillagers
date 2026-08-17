package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.trade.OfferStockDecision;
import dev.totem.villagers.trade.TradeStockPolicy;
import dev.totem.villagers.work.ItemAmount;
import dev.totem.villagers.work.MerchantStock;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.work.WorkOrderDefinitions;
import dev.totem.villagers.work.WorkSource;
import dev.totem.villagers.world.ShepherdWorldWorkAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Runs with a bootstrapped registry so the ItemStack component codec is exercised for real. */
public final class ComponentAwareStockGameTest {
    @GameTest(maxTicks = 40)
    public void dyedOffersRequireTheExactProducedComponentVariant(GameTestHelper helper) {
        ItemStack redHelmet = dyedHelmet(0xB02E26);
        ItemStack blueHelmet = dyedHelmet(0x3C44AA);
        StockVariantKey red = StockVariantKey.fromStack(redHelmet, helper.getLevel().registryAccess());
        StockVariantKey blue = StockVariantKey.fromStack(blueHelmet, helper.getLevel().registryAccess());
        require(helper, !red.isBaseItem(), "Dyed leather did not create a component-bearing stock key");
        require(helper, !red.equals(blue), "Two leather dye colours collapsed into one stock key");

        WorkOrder order = new WorkOrder(
                "totem:leatherworker_red_helmet",
                "minecraft:leatherworker",
                new ItemAmount("minecraft:leather_helmet", 1),
                List.of(new ItemAmount("minecraft:leather", 5)),
                Set.of(WorkSource.WORKSHOP),
                "", "", "", red.componentPatch(), 20, 4
        );
        VillagerWorkState state = new VillagerWorkState(
                VillagerWorkState.CURRENT_SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-000000000801"),
                Map.of(), Map.of(red, 1), Optional.empty(), Optional.empty(), Optional.empty()
        );
        TradeStockPolicy policy = new TradeStockPolicy();
        WorkOrderCatalog catalog = new WorkOrderCatalog(List.of(order));
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, redHelmet, helper.getLevel().registryAccess()) == OfferStockDecision.AVAILABLE,
                "The exact dyed output was not sellable from variant stock");
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, blueHelmet, helper.getLevel().registryAccess()) == OfferStockDecision.UNMAPPED,
                "A different dye colour was accepted for the red output order");

        MerchantStock stock = new MerchantStock(state.merchantStock(), state.variantMerchantStock());
        require(helper, stock.debitForTrade(redHelmet, helper.getLevel().registryAccess()), "The matching dyed output could not debit stock");
        require(helper, stock.available(red) == 0 && stock.available(blue) == 0,
                "A dyed trade left incorrect variant stock behind");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void enchantedBooksKeepEnchantmentAndLevelInTheirStockKey(GameTestHelper helper) {
        var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var efficiency = enchantments.getOrThrow(Enchantments.EFFICIENCY);
        ItemStack efficiencyThree = EnchantmentHelper.createBook(new EnchantmentInstance(efficiency, 3));
        ItemStack efficiencyFour = EnchantmentHelper.createBook(new EnchantmentInstance(efficiency, 4));
        StockVariantKey three = StockVariantKey.fromStack(efficiencyThree, helper.getLevel().registryAccess());
        StockVariantKey four = StockVariantKey.fromStack(efficiencyFour, helper.getLevel().registryAccess());

        require(helper, !three.isBaseItem(), "Enchanted book did not create a component-bearing stock key");
        require(helper, !three.equals(four), "Different enchanted-book levels collapsed into one stock key");
        MerchantStock stock = new MerchantStock(Map.of(), Map.of(three, 1));
        require(helper, stock.debitForTrade(efficiencyThree, helper.getLevel().registryAccess()), "The exact enchanted book could not debit variant stock");
        require(helper, !stock.debitForTrade(efficiencyFour, helper.getLevel().registryAccess()), "A different enchanted-book level consumed stock");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void shepherdOrdersCoverEveryVanillaWoolColour(GameTestHelper helper) {
        WorkOrderCatalog catalog = WorkOrderDefinitions.catalog();
        for (DyeColor colour : DyeColor.VALUES) {
            String outputId = "minecraft:" + colour.getName() + "_wool";
            WorkOrder order = catalog.require("totem:shepherd_" + colour.getName() + "_wool");
            require(helper, outputId.equals(order.output().itemId()), "Wrong Shepherd output for " + colour.getName());
            require(helper, ShepherdWorldWorkAction.outputColour(order).orElse(null) == colour,
                    "Shepherd order did not match its sheep colour: " + colour.getName());
        }
        helper.succeed();
    }

    private static ItemStack dyedHelmet(int colour) {
        ItemStack stack = new ItemStack(Items.LEATHER_HELMET);
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
        return stack;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
