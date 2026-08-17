package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.Optional;

/** Covers the physical work-inventory receipt for normal player-to-villager sales. */
public final class PlayerPurchaseWorkInventoryGameTest {
    @GameTest(maxTicks = 40)
    public void acceptedPlayerPurchaseStoresBothExactInputCosts(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        Villager villager = spawn(helper, new BlockPos(3, 2, 3));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
            MerchantOffer purchase = wheatAndCarrotPurchase();
            require(helper, inventory.insertExact(purchase.getResult()), "Could not fund the villager's emerald payout");
            villager.setTradingPlayer(player);

            // notifyTrade is invoked only after the vanilla menu has consumed
            // the exact displayed costs. Calling it here exercises the same
            // mixin tail that a real player trade uses.
            villager.notifyTrade(purchase);

            require(helper, count(inventory, Items.WHEAT) == 20 && count(inventory, Items.CARROT) == 3,
                    "Accepted player purchase did not store both exact input costs in the work inventory");
            helper.succeed();
        } finally {
            villager.setTradingPlayer(null);
            VillagerWorkInventorySavedData.forServer(server).drain(villager.getUUID());
            villager.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fullWorkInventoryLocksPlayerPurchaseBeforeItCanConsumeItems(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        Villager villager = spawn(helper, new BlockPos(3, 2, 3));
        try {
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerWorkInventory inventory = VillagerWorkInventorySavedData.forServer(server).inventory(villager.getUUID());
            for (int slot = 0; slot < VillagerWorkInventorySavedData.SLOT_COUNT; slot++) {
                require(helper, inventory.insertExact(new ItemStack(Items.COBBLESTONE, 64)),
                        "Could not fill personal work-inventory slot " + slot);
            }
            MerchantOffer purchase = wheatAndCarrotPurchase();
            MerchantOffers offers = new MerchantOffers();
            offers.add(purchase);
            ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);

            VillagerTradeStockAuthority.refreshOffers(villager, offers);

            require(helper, purchase.isOutOfStock(),
                    "A full work inventory left a player purchase offer available to consume items");
            helper.succeed();
        } finally {
            VillagerWorkInventorySavedData.forServer(server).drain(villager.getUUID());
            villager.discard();
        }
    }

    private static MerchantOffer wheatAndCarrotPurchase() {
        return new MerchantOffer(new ItemCost(Items.WHEAT, 20), Optional.of(new ItemCost(Items.CARROT, 3)),
                new ItemStack(Items.EMERALD), 12, 1, 0.05F);
    }

    private static int count(VillagerWorkInventory inventory, Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("unchecked")
    private static Villager spawn(GameTestHelper helper, BlockPos position) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, position);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "farmer"));
        require(helper, profession != null, "Missing minecraft:farmer profession");
        villager.setVillagerData(villager.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession)));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
