package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.trade.OfferStockDecision;
import dev.totem.villagers.trade.TradeStockPolicy;
import dev.totem.villagers.work.LeatherworkerDyedArmorOrders;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkState;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderCatalog;
import dev.totem.villagers.workshop.LeatherworkerDyedArmorWorkshopAction;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Covers offer-derived Leatherworker dye work and exact component stock identity. */
public final class LeatherworkerDyedArmorGameTest {
    @GameTest(maxTicks = 40)
    public void leatherworkerRecreatesItsExactLiveDyedArmorOffer(GameTestHelper helper) {
        ItemStack dyedHelmet = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_HELMET), List.of(DyeColor.RED, DyeColor.BLUE));
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 7), dyedHelmet, 12, 1, 0.2F);
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer);
        WorkOrder order = LeatherworkerDyedArmorOrders.orderFor(offer, helper.getLevel().registryAccess()).orElseThrow();
        StockVariantKey key = StockVariantKey.fromStack(dyedHelmet, helper.getLevel().registryAccess());
        require(helper, order.outputKey().equals(key), "Dyed Leatherworker order lost its live offer component patch");
        require(helper, order.requiredInputs().stream().anyMatch(input -> input.itemId().equals("minecraft:leather") && input.count() == 5),
                "Dyed helmet order did not require the vanilla five leather inputs");
        require(helper, LeatherworkerDyedArmorOrders.dyesFor(dyedHelmet).isPresent(),
                "Live Leatherworker colour was not reconstructible from vanilla dye inputs");

        VillagerWorkState state = new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION,
                UUID.fromString("00000000-0000-0000-0000-000000000a01"), Map.of(), Map.of(key, 1),
                Optional.empty(), Optional.empty(), Optional.empty());
        WorkOrderCatalog catalog = LeatherworkerDyedArmorOrders.extend(new WorkOrderCatalog(List.of()), offers,
                helper.getLevel().registryAccess());
        TradeStockPolicy policy = new TradeStockPolicy();
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, dyedHelmet,
                helper.getLevel().registryAccess()) == OfferStockDecision.AVAILABLE,
                "Exact dyed Leatherworker offer was not available from variant stock");
        ItemStack otherColour = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_HELMET), List.of(DyeColor.GREEN));
        require(helper, policy.decide(WorkBackedTradingMode.ENFORCED, catalog, state, otherColour,
                helper.getLevel().registryAccess()) == OfferStockDecision.UNMAPPED,
                "Different dyed armour colour was accepted for the live offer");

        BlockPos relativeCauldron = new BlockPos(2, 2, 2);
        BlockPos cauldron = helper.absolutePos(relativeCauldron);
        helper.setBlock(relativeCauldron, Blocks.CAULDRON);
        Villager leatherworker = spawnVillager(helper, relativeCauldron.above());
        try {
            setLeatherworker(leatherworker);
            LeatherworkerDyedArmorWorkshopAction action = new LeatherworkerDyedArmorWorkshopAction(
                    helper.getLevel(), leatherworker, cauldron, order, offers
            );
            require(helper, LeatherworkerDyedArmorWorkshopAction.supports(order, helper.getLevel(), cauldron, offers),
                    "Live dyed Leatherworker order was not accepted at a Cauldron");
            require(helper, action.complete(), "Cauldron action did not recreate and verify exact dyed leather armour");
            helper.succeed();
        } finally {
            leatherworker.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void setLeatherworker(Villager villager) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "leatherworker")
        );
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft:leatherworker profession");
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
