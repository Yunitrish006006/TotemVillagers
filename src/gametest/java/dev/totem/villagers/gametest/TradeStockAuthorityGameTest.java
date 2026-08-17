package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Map;
import java.util.Optional;

/** Verifies the server authority cannot debit the same produced stock twice. */
public final class TradeStockAuthorityGameTest {
    @GameTest(maxTicks = 20)
    public void duplicateSuccessfulTradeNotificationCannotDuplicateASale(GameTestHelper helper) {
        Villager villager = spawnVillager(helper, new BlockPos(3, 2, 3));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            MerchantOffer bread = new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 0, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(bread);
            net.minecraft.world.entity.npc.villager.VillagerProfession farmer = BuiltInRegistries.VILLAGER_PROFESSION
                    .getValue(Identifier.fromNamespaceAndPath("minecraft", "farmer"));
            villager.setVillagerData(villager.getVillagerData().withProfession(
                    BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(farmer)));
            ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);
            var inventory = dev.totem.villagers.inventory.VillagerWorkInventorySavedData.forServer(server)
                    .inventory(villager.getUUID());
            require(helper, inventory.insertExact(new ItemStack(Items.BREAD, 5)),
                    "Could not seed one sale plus the physical survival bread reserve");
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerTradeStockAuthority.refreshOffers(villager, offers);
            require(helper, !bread.isOutOfStock(), "One produced bread was not made available for its first trade");
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(villager, bread);
            require(helper, bread.isOutOfStock(), "Bread offer remained available after its only stock was sold");
            require(helper, inventory.countMatchingItem(new ItemStack(Items.BREAD)) == 4,
                    "First sale did not consume exactly one bread or preserve the survival reserve");

            // The server may never turn a duplicated late callback into another
            // sale, even though the original offer object is still referenced.
            VillagerTradeStockAuthority.debitAfterSuccessfulTrade(villager, bread);
            require(helper, inventory.countMatchingItem(new ItemStack(Items.BREAD)) == 4,
                    "Duplicate trade notification changed depleted stock");
            require(helper, bread.isOutOfStock(), "Duplicate trade notification reopened a depleted offer");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(villager.getUUID());
            villager.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        return helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
