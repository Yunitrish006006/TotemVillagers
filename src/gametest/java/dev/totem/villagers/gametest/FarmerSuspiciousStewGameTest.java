package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.trade.OfferStockDecision;
import dev.totem.villagers.trade.TradeStockPolicy;
import dev.totem.villagers.work.FarmerSuspiciousStewOrders;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.workshop.FarmerSuspiciousStewWorkshopAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ensures a Farmer's special stew remains bound to its real flower recipe and live offer component. */
public final class FarmerSuspiciousStewGameTest {
    @GameTest(maxTicks = 40)
    public void farmerRecreatesItsExactLiveSuspiciousStewOffer(GameTestHelper helper) {
        CraftingInput dandelionInput = FarmerSuspiciousStewOrders.craftingInputForFlower("minecraft:dandelion").orElseThrow();
        var recipe = helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, dandelionInput, helper.getLevel()).orElseThrow();
        ItemStack stew = recipe.value().assemble(dandelionInput);
        require(helper, stew.is(Items.SUSPICIOUS_STEW) && !stew.getComponentsPatch().isEmpty(),
                "Vanilla dandelion recipe did not create a component-bearing suspicious stew");
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 1), stew, 12, 1, 0.05F);
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer);
        WorkOrder order = FarmerSuspiciousStewOrders.orderFor(offer, helper.getLevel()).orElseThrow();
        StockVariantKey key = StockVariantKey.fromStack(stew, helper.getLevel().registryAccess());
        require(helper, order.outputKey().equals(key), "Farmer stew order lost the live offer component patch");
        require(helper, order.requiredInputs().stream().anyMatch(input -> input.itemId().equals("minecraft:dandelion")),
                "Farmer stew order did not retain its exact vanilla flower input");

        VillagerWorkState state = new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-000000000d01"), Map.of(), Map.of(key, 1),
                Optional.empty(), Optional.empty(), Optional.empty());
        WorkOrderCatalog catalog = FarmerSuspiciousStewOrders.extend(new WorkOrderCatalog(List.of()), offers, helper.getLevel());
        TradeStockPolicy policy = new TradeStockPolicy();
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, stew,
                helper.getLevel().registryAccess()) == OfferStockDecision.AVAILABLE,
                "Exact Farmer suspicious-stew offer was not available from matching variant stock");
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, new ItemStack(Items.SUSPICIOUS_STEW),
                helper.getLevel().registryAccess()) == OfferStockDecision.UNMAPPED,
                "Plain suspicious stew was accepted for a component-bearing Farmer offer");

        BlockPos relativeComposter = new BlockPos(2, 2, 2);
        BlockPos composter = helper.absolutePos(relativeComposter);
        helper.setBlock(relativeComposter, Blocks.COMPOSTER);
        Villager farmer = spawnVillager(helper, relativeComposter.above());
        try {
            setFarmer(farmer);
            FarmerSuspiciousStewWorkshopAction action = new FarmerSuspiciousStewWorkshopAction(
                    helper.getLevel(), farmer, composter, order, offers
            );
            require(helper, FarmerSuspiciousStewWorkshopAction.supports(order, helper.getLevel(), composter, offers),
                    "Live Farmer suspicious-stew order was not accepted at a Composter");
            require(helper, action.complete(), "Composter action did not recreate and verify the exact live suspicious stew");
            helper.succeed();
        } finally {
            farmer.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setFarmer(Villager villager) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer")
        );
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:farmer profession");
        }
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)
        ));
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
