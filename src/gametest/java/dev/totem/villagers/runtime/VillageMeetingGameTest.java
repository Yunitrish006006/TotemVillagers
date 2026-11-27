package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.work.WorkOrder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class VillageMeetingGameTest {
    @GameTest(environment = "totem:smith_trials")
    @SuppressWarnings("unchecked")
    public void realBellAgreementSurvivesDispersalUntilPaid(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var seller = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var residents = List.of(buyer, seller);
        var personals = VillagerWorkInventorySavedData.forServer(level.getServer());
        var wallet = personals.inventory(buyer.getUUID());
        var stock = personals.inventory(seller.getUUID());
        var professions = BuiltInRegistries.VILLAGER_PROFESSION;
        seller.setVillagerData(seller.getVillagerData().withProfession(professions.wrapAsHolder(
                professions.getValue(Identifier.parse("minecraft:farmer")))));
        var bell = buyer.blockPosition().north();
        var place = GlobalPos.of(level.dimension(), bell);
        var demands = Map.of(buyer.getUUID(), Optional.<WorkOrder>empty(), seller.getUUID(), Optional.<WorkOrder>empty());
        try {
            for (var v : residents) {
                v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, place);
                v.getBrain().setActiveActivityIfPossible(Activity.MEET);
            }
            VillagerNutrition.setFoodLevel(buyer, 17);
            VillagerNutrition.setFoodLevel(seller, 20);
            var emerald = new ItemStack(Items.EMERALD);
            var bread = new ItemStack(Items.BREAD);
            wallet.insertExact(emerald);
            stock.insertExact(bread.copyWithCount(12));
            VillageMeeting.resolve(level, residents, personals, demands);
            h.assertTrue(VillageMeeting.supplier(buyer, VillageMeeting.RATION) == null, "Agreed without a bell");
            level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
            VillageMeeting.resolve(level, residents, personals, demands);
            h.assertTrue(seller.getUUID().equals(VillageMeeting.supplier(buyer, VillageMeeting.RATION))
                    && VillageMeeting.preparingFood(seller), "Meeting did not create both sides of the agreement");
            h.assertTrue(!VillageMeeting.availableForSale(seller, bread.copyWithCount(4), stock), "Selling bread would consume the promised ration");
            h.assertTrue(wallet.countMatchingItem(emerald) == 1 && stock.countMatchingItem(bread) == 12,
                    "Talking moved stock before delivery");
            VillageMeeting.fulfilled(buyer, buyer, VillageMeeting.RATION, 1);
            VillageMeeting.fulfilled(buyer, seller, VillageMeeting.RATION, 0);
            h.assertTrue(VillageMeeting.preparingFood(seller), "Wrong supplier or partial delivery cancelled agreement");
            for (var v : residents) v.getBrain().setActiveActivityIfPossible(Activity.IDLE);
            VillageMeeting.resolve(level, residents, personals, demands);
            h.assertTrue(VillageMeeting.preparingFood(seller), "Leaving the meeting forgot the buyer");
            h.assertTrue(VillagerFoodEconomyRuntime.tryPurchaseFromFoodProducer(level, buyer, seller), "No physical delivery");
            h.assertTrue(!VillageMeeting.preparingFood(seller) && VillageMeeting.supplier(buyer, VillageMeeting.RATION) == null
                    && stock.countMatchingItem(emerald) == 1 && wallet.countMatchingItem(bread) > 0, "Settlement not cleared or unpaid");
            h.succeed();
        } finally {
            buyer.discard();
            seller.discard();
        }
    }

    @GameTest(environment = "totem:smith_trials")
    @SuppressWarnings("unchecked")
    public void meetingFindsPhysicalWheatForRealBakeryOrder(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        var supplier = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var residents = List.of(buyer, supplier);
        var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
        var wallet = accounts.inventory(buyer.getUUID());
        var shelves = accounts.inventory(supplier.getUUID());
        try {
            var professions = BuiltInRegistries.VILLAGER_PROFESSION;
            buyer.setVillagerData(buyer.getVillagerData().withProfession(professions.wrapAsHolder(
                    professions.getValue(Identifier.parse("minecraft:farmer")))));
            var station = buyer.blockPosition().west();
            var bell = buyer.blockPosition().north();
            level.setBlockAndUpdate(station, Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
            buyer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), station));
            for (var v : residents) {
                VillagerNutrition.setFoodLevel(v, 20);
                v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
                v.getBrain().setActiveActivityIfPossible(Activity.MEET);
            }
            var offers = new net.minecraft.world.item.trading.MerchantOffers();
            offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.WHEAT, 3),
                    new ItemStack(Items.EMERALD), 12, 1, .05F));
            ((dev.totem.villagers.mixin.AbstractVillagerOffersAccessor)(Object)buyer).totemVillagers$setExistingOffers(offers);
            var demand = VillagerWorkshopRuntime.materialDemandFor(level, buyer);
            h.assertTrue(demand.isPresent(), "No bakery work");
            var needs = Map.of(buyer.getUUID(), demand, supplier.getUUID(), Optional.<WorkOrder>empty());
            wallet.insertExact(new ItemStack(Items.EMERALD));
            VillageMeeting.resolve(level, residents, accounts, needs);
            h.assertTrue(VillageMeeting.supplier(buyer, "minecraft:wheat") == null, "Promised absent material");
            shelves.insertExact(new ItemStack(Items.WHEAT, 3));
            VillageMeeting.resolve(level, residents, accounts, needs);
            h.assertTrue(supplier.getUUID().equals(VillageMeeting.supplier(buyer, "minecraft:wheat")), "No wheat agreement");
            h.assertTrue(!VillageMeeting.availableForSale(supplier, new ItemStack(Items.WHEAT), shelves), "Promised wheat could be sold to a player");
            VillagerMaterialLogisticsRuntime.tickForGameTest(level.getServer());
            h.assertTrue(wallet.countMatchingItem(new ItemStack(Items.WHEAT)) == 0, "Material delivery interrupted meeting");
            h.setTime(3000);
            for (var v : List.of(buyer, supplier)) v.getBrain().setActiveActivityIfPossible(Activity.IDLE);
            LoadedVillagerCache.invalidate(level);
            VillagerMaterialLogisticsRuntime.tickForGameTest(level.getServer());
            h.assertTrue(wallet.countMatchingItem(new ItemStack(Items.WHEAT)) == 3
                    && shelves.countMatchingItem(new ItemStack(Items.EMERALD)) == 1
                    && VillageMeeting.supplier(buyer, "minecraft:wheat") == null, "Wheat delivery did not settle");
            h.succeed();
        } finally {
            buyer.discard();
            supplier.discard();
        }
    }

    @GameTest(environment = "totem:smith_trials")
    @SuppressWarnings("unchecked")
    public void noticeSignPaysOnceForAnUnmatchedMeetingRequest(GameTestHelper h) {
        var level = h.getLevel();
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(3, 3, 3));
        try {
            var bell = buyer.blockPosition().north();
            var board = bell.east();
            level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
            level.setBlockAndUpdate(board.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(board, Blocks.OAK_SIGN.defaultBlockState());
            var sign = (net.minecraft.world.level.block.entity.SignBlockEntity)level.getBlockEntity(board);
            var lines = List.of(net.minecraft.network.chat.Component.literal("村莊布告欄"),
                    net.minecraft.network.chat.Component.empty(), net.minecraft.network.chat.Component.empty(),
                    net.minecraft.network.chat.Component.empty());
            sign.setText(new net.minecraft.world.level.block.entity.SignText(
                    List.copyOf(lines), List.copyOf(lines), net.minecraft.world.item.DyeColor.BLACK, false),
                    net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            buyer.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
            buyer.getBrain().setActiveActivityIfPossible(Activity.MEET);
            VillagerNutrition.setFoodLevel(buyer, 17);
            var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
            var stock = accounts.inventory(buyer.getUUID());
            var emerald = new ItemStack(Items.EMERALD);
            var bread = new ItemStack(Items.BREAD);
            stock.insertExact(emerald.copyWithCount(2));
            VillageMeeting.resolve(level, List.of(buyer), accounts, Map.of(buyer.getUUID(), Optional.empty()));
            var player = h.makeMockServerPlayerInLevel();
            var hand = net.minecraft.world.InteractionHand.MAIN_HAND;
            player.setItemInHand(hand, bread.copyWithCount(12));
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(board), net.minecraft.core.Direction.NORTH, board, false);
            var use = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker();
            use.interact(player, level, hand, hit);
            h.assertTrue(player.getMainHandItem().getCount() == 12, "Reading sold the stack");
            player.setShiftKeyDown(true);
            stock.takeExactMatchingItem(emerald.copyWithCount(2));
            use.interact(player, level, hand, hit);
            h.assertTrue(player.getMainHandItem().getCount() == 12, "Unfunded request consumed bread");
            stock.insertExact(emerald.copyWithCount(2));
            use.interact(player, level, hand, hit);
            use.interact(player, level, hand, hit);
            h.assertTrue(player.getMainHandItem().getCount() == 7 && stock.countMatchingItem(bread) == 5
                    && stock.countMatchingItem(emerald) == 1 && player.getInventory().countItem(Items.EMERALD) == 1,
                    "Board failed to settle exactly once");
            h.succeed();
        } finally {
            buyer.discard();
        }
    }
}
