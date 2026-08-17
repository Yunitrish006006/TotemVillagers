package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.trade.VillagerTradeStockAuthority;
import dev.totem.villagers.work.StockVariantKey;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.VillagerWorkState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Map;
import java.util.Optional;

/** Legacy generated book offers are removed; only the Librarian's real table output can be listed. */
public final class PlayerRecipeAuthorityGameTest {
    @GameTest(maxTicks = 20)
    public void legacyEnchantedBookOfferIsRemovedDespiteExactVariantStock(GameTestHelper helper) {
        Villager librarian = spawnLibrarian(helper, new BlockPos(3, 2, 3));
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(
                    enchantments.getOrThrow(Enchantments.EFFICIENCY), 3));
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 16), book, 12, 0, 0.05F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(offer);
            ((AbstractVillagerOffersAccessor) (Object) librarian).totemVillagers$setExistingOffers(offers);
            StockVariantKey key = StockVariantKey.fromStack(book, helper.getLevel().registryAccess());
            states.put(new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION, librarian.getUUID(),
                    Map.of(), Map.of(key, 1), Optional.empty(), Optional.empty()));
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerTradeStockAuthority.refreshOffers(librarian, offers);

            require(helper, offers.isEmpty(),
                    "A legacy enchanted-book offer survived instead of being replaced by real table output");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(librarian.getUUID());
            librarian.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void legacyEnchantedEquipmentOfferMovesFromItsOldProfessionToLibrarianWork(GameTestHelper helper) {
        Villager fletcher = spawnVillager(helper, new BlockPos(5, 2, 3), "fletcher");
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            var enchantments = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            ItemStack fishingRod = new ItemStack(Items.FISHING_ROD);
            fishingRod.enchant(enchantments.getOrThrow(Enchantments.UNBREAKING), 1);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 3), fishingRod, 3, 10, 0.2F));
            ((AbstractVillagerOffersAccessor) (Object) fletcher).totemVillagers$setExistingOffers(offers);
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerTradeStockAuthority.refreshOffers(fletcher, offers);

            require(helper, offers.isEmpty(),
                    "A generated enchanted-equipment offer survived on its old profession instead of moving to Librarian work");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(fletcher.getUUID());
            fletcher.discard();
        }
    }

    @GameTest(maxTicks = 20)
    public void legacyExplorerMapOfferIsRemovedUntilTheCartographerMakesThatExactMap(GameTestHelper helper) {
        Villager cartographer = spawnVillager(helper, new BlockPos(7, 2, 3), "cartographer");
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var states = VillagerWorkSavedData.forServer(server);
        try {
            BlockPos destination = helper.absolutePos(new BlockPos(10, 2, 10));
            ItemStack explorerMap = MapItem.create(helper.getLevel(), destination.getX(), destination.getZ(), (byte) 2, true, true);
            MapItemSavedData.addTargetDecoration(explorerMap, destination, "+", MapDecorationTypes.WOODLAND_MANSION);
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, 14), Optional.of(new ItemCost(Items.COMPASS, 1)),
                    explorerMap, 12, 30, 0.2F);
            MerchantOffers offers = new MerchantOffers();
            offers.add(offer);
            ((AbstractVillagerOffersAccessor) (Object) cartographer).totemVillagers$setExistingOffers(offers);
            StockVariantKey key = StockVariantKey.fromStack(explorerMap, helper.getLevel().registryAccess());
            states.put(new VillagerWorkState(VillagerWorkState.CURRENT_SCHEMA_VERSION, cartographer.getUUID(),
                    Map.of(), Map.of(key, 1), Optional.empty(), Optional.empty()));
            settings.setMode(WorkBackedTradingMode.ENFORCED);

            VillagerTradeStockAuthority.refreshOffers(cartographer, offers);

            require(helper, offers.isEmpty(),
                    "A pre-generated explorer-map offer survived instead of awaiting Cartographer map work");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            states.remove(cartographer.getUUID());
            cartographer.discard();
        }
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnLibrarian(GameTestHelper helper, BlockPos relativePosition) {
        return spawnVillager(helper, relativePosition, "librarian");
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(
                Identifier.fromNamespaceAndPath("minecraft", professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing minecraft profession " + professionId);
        }
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
