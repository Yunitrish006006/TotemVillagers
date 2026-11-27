package dev.totem.villagers.gametest;

import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.mixin.AbstractVillagerOffersAccessor;
import dev.totem.villagers.runtime.VillagerMaterialLogisticsRuntime;
import dev.totem.villagers.runtime.VillagerWorkshopRuntime;
import dev.totem.villagers.runtime.VillagerHeadStatus;
import dev.totem.villagers.runtime.VillagerStarterSupplySavedData;
import dev.totem.villagers.work.VillagerWorkSavedData;
import dev.totem.villagers.work.WorkOrder;
import dev.totem.villagers.work.WorkOrderDefinitions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;

/** Proves that nearby villagers buy physical recipe materials at their live vanilla emerald rates. */
public final class VillagerMaterialLogisticsGameTest {
    @GameTest(environment = "totem:farmer_restock", maxTicks = 350)
    public void weedingLeavesYoungCropAndHoeIntactAndTradeViewShowsClaimedBed(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        var plot = new BlockPos(3, 2, 3);
        var crop = h.absolutePos(plot);
        h.setBlock(plot.west().below(), Blocks.DIRT);
        h.setBlock(plot.east(), Blocks.COMPOSTER);
        h.setBlock(plot.below(), Blocks.FARMLAND);
        h.setBlock(plot, Blocks.WHEAT);
        var farmer = spawnVillager(h, plot.west(), "minecraft:farmer");
        prepareBreadSeller(h, farmer, crop.east());
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        stock.insertExact(new ItemStack(Items.WOODEN_HOE));
        VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(farmer.getUUID());
        var bed = GlobalPos.of(level.dimension(), crop.north(7));
        farmer.getBrain().setMemory(MemoryModuleType.HOME, bed);
        require(h, dev.totem.villagers.trade.TradeSnapshotSender.snapshot(level, farmer, new MerchantOffers(), 1)
                .home().orElseThrow().equals(bed), "Trade UI missing actual HOME position");
        farmer.getBrain().eraseMemory(MemoryModuleType.HOME);
        require(h, dev.totem.villagers.trade.TradeSnapshotSender.snapshot(level, farmer, new MerchantOffers(), 1)
                .home().isEmpty(), "Trade UI kept previous bed after release");
        h.runBeforeTestEnd(() -> { farmer.discard(); settings.setMode(previous); });
        h.succeedWhen(() -> require(h, VillagerHeadStatus.current(farmer) == VillagerHeadStatus.Kind.WEEDING
                && level.getBlockState(crop).is(Blocks.WHEAT) && count(stock, Items.WHEAT) == 0
                && dev.totem.villagers.world.FarmerWorldWorkAction.bestHoe(stock).orElseThrow().getDamageValue() == 0,
                "Waiting for cosmetic weeding at the young crop"));
    }

    @GameTest(environment = "totem:farmer_restock")
    public void harvestRequiresAnUnobstructedAdjacentWorker(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var origin = new BlockPos(3, 2, 3);
        var crop = h.absolutePos(origin.east(2));
        var farmer = spawnVillager(h, origin, "minecraft:farmer");
        var inventory = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        inventory.insertExact(new ItemStack(Items.WOODEN_HOE));
        var wheat = (net.minecraft.world.level.block.CropBlock) Blocks.WHEAT;
        level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(crop, wheat.getStateForAge(wheat.getMaxAge()));
        var action = new dev.totem.villagers.world.FarmerWorldWorkAction();
        var order = WorkOrderDefinitions.catalog().snapshot().get("totem:farmer_wheat");
        require(h, !action.complete(level, farmer, crop, order, inventory), "Harvested from two blocks away");
        var beside = crop.west().south();
        farmer.setPos(beside.getX() + .05, beside.getY(), beside.getZ() + .05);
        level.setBlockAndUpdate(crop.west(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(crop.west().above(), Blocks.STONE.defaultBlockState());
        require(h, !action.complete(level, farmer, crop, order, inventory), "Harvested through a solid corner");
        require(h, wheat.isMaxAge(level.getBlockState(crop)) && count(inventory, Items.WHEAT) == 0,
                "Rejected harvest changed crop or stock");
        level.setBlockAndUpdate(crop.west(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(crop.west().above(), Blocks.AIR.defaultBlockState());
        require(h, action.complete(level, farmer, crop, order, inventory), "Missed unobstructed adjacent harvest");
        farmer.discard();
        h.succeed();
    }

    @GameTest(structure = "minecraft:village/plains/houses/plains_large_farm_1",
            environment = "totem:plains_farm", maxTicks = 1200)
    public void noviceWorksVanillaPlainsFarmAfterWheatRipens(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var station = h.absolutePos(new BlockPos(1, 1, 1));
        var crop = h.absolutePos(new BlockPos(1, 1, 2));
        require(h, level.getBlockState(station).is(Blocks.COMPOSTER) && level.getBlockState(crop).is(Blocks.WHEAT),
                "Missing original plains farm composter or wheat");
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        var farmer = (Villager) h.spawn(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager")), new BlockPos(1, 1, 0));
        farmer.setVillagerData(farmer.getVillagerData().withProfession(
                BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")))).withLevel(1));
        farmer.setVillagerDataFinalized(true);
        farmer.refreshBrain(level);
        // The structure contains a real composter. Reserve its native POI just like a villager joining a village.
        var ticket = level.getPoiManager().take(farmer.getVillagerData().profession().value().heldJobSite(),
                (type, position) -> position.equals(station), station, 1);
        require(h, ticket.isPresent(), "Plains farm has no available composter ticket");
        farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), ticket.orElseThrow()));
        farmer.setVillagerXp(1);
        VillagerNutrition.setFoodLevel(farmer, 20);
        VillagerStarterSupplySavedData.forServer(level.getServer()).markProfessionKit(farmer.getUUID());
        var stock = VillagerWorkInventorySavedData.forServer(level.getServer()).inventory(farmer.getUUID());
        stock.insertExact(new ItemStack(Items.WOODEN_HOE));
        var wheat = (net.minecraft.world.level.block.CropBlock) Blocks.WHEAT;
        // This structure contains several pre-aged plants. Make the plant under observation the first to ripen.
        for (var plot : BlockPos.betweenClosed(station.offset(-8, -1, -8), station.offset(8, 1, 8))) {
            if (level.getBlockState(plot).getBlock() instanceof net.minecraft.world.level.block.CropBlock planted)
                level.setBlockAndUpdate(plot, planted.getStateForAge(0));
        }
        h.runAfterDelay(5, () -> level.setBlockAndUpdate(crop, wheat.getStateForAge(wheat.getMaxAge())));
        h.runBeforeTestEnd(() -> {
            farmer.discard();
            settings.setMode(previous);
        });
        boolean[] misleading = {false};
        boolean[] climbed = {false};
        var trace = new StringBuilder();
        h.onEachTick(() -> {
            if (h.getTick() == 1 || h.getTick() % 50 == 0 && h.getTick() <= 250) {
                trace.append(" t=").append(h.getTick()).append(" delta=").append(farmer.blockPosition().subtract(station))
                    .append(" site=").append(farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent())
                    .append(" state=").append(dev.totem.villagers.work.VillagerWorkSavedData.forServer(level.getServer()).get(farmer.getUUID()).flatMap(state -> state.diagnostic()))
                    .append(" station=").append(level.getBlockState(station)).append(" age=").append(level.getBlockState(crop));
            }
        });
        h.succeedWhen(() -> {
            climbed[0] |= farmer.blockPosition().equals(station.above());
            misleading[0] |= VillagerHeadStatus.current(farmer) == VillagerHeadStatus.Kind.MATERIAL;
            require(h, h.getTick() > 5 && !misleading[0] && !climbed[0] && count(stock, Items.WHEAT) > 0
                    && level.getBlockState(crop).is(Blocks.WHEAT) && !wheat.isMaxAge(level.getBlockState(crop)),
                    "Plains farmer pos=" + farmer.blockPosition() + ", site=" + farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            + ", wheat=" + count(stock, Items.WHEAT) + ", crop=" + level.getBlockState(crop)
                            + ", trace=" + trace + ", climbed=" + climbed[0] + ", status=" + VillagerHeadStatus.current(farmer));
        });
    }

    @GameTest(environment = "totem:farmer_restock", maxTicks = 600)
    public void idleFarmerTendsWheatWithoutAskingForBakeryOrGoldenCarrotStock(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.ENFORCED);
        var site = new BlockPos(3, 3, 3);
        var crop = h.absolutePos(site.east().above());
        h.setBlock(site.south().below(), Blocks.DIRT);
        h.setBlock(site.south(2), Blocks.CRAFTING_TABLE);
        h.setBlock(site, Blocks.COMPOSTER);
        level.setBlockAndUpdate(crop.below(), Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState());
        var farmer = spawnVillager(h, site.south(), "minecraft:farmer");
        prepareBreadSeller(h, farmer, h.absolutePos(site));
        VillagerStarterSupplySavedData.forServer(server).markProfessionKit(farmer.getUUID());
        var stock = VillagerWorkInventorySavedData.forServer(server).inventory(farmer.getUUID());
        stock.insertExact(new ItemStack(Items.WOODEN_HOE));
        stock.insertExact(new ItemStack(Items.WHEAT_SEEDS, 4));
        h.runBeforeTestEnd(() -> {
            farmer.discard();
            settings.setMode(previous);
        });
        var wheat = (net.minecraft.world.level.block.CropBlock) Blocks.WHEAT;
        boolean[] misleading = {false};
        boolean[] harvested = {false};
        boolean[] baked = {false};
        h.succeedWhen(() -> {
            if (VillagerHeadStatus.current(farmer) == VillagerHeadStatus.Kind.MATERIAL) misleading[0] = true;
            if (h.getTick() == 100) {
                stock.insertExact(new ItemStack(Items.CARROT));
                level.setBlockAndUpdate(crop, wheat.getStateForAge(wheat.getMaxAge()));
            }
            if (!harvested[0] && count(stock, Items.WHEAT) > 0) {
                harvested[0] = level.getBlockState(crop).is(Blocks.WHEAT) && !wheat.isMaxAge(level.getBlockState(crop));
                stock.insertExact(new ItemStack(Items.WHEAT, 2));
            }
            baked[0] |= count(stock, Items.BREAD) > 0;
            require(h, h.getTick() >= 340 && harvested[0] && baked[0]
                    && count(stock, Items.GOLD_NUGGET) == 0 && !misleading[0],
                    "Harvest=" + harvested[0] + ", baked=" + baked[0] + ", falseShortage=" + misleading[0]
                            + ", crop=" + level.getBlockState(crop) + ", stock=" + stock.snapshot()
                            + ", pos=" + farmer.blockPosition() + ", job=" + farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE));
        });
    }

    @GameTest(maxTicks = 80)
    public void nearbyVillageMaterialsSupplyARecipeBackedFarmerTrade(GameTestHelper helper) {
        helper.setTime(3000);
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos relativeJobSite = new BlockPos(3, 2, 3);
        BlockPos jobSite = helper.absolutePos(relativeJobSite);
        helper.setBlock(relativeJobSite, Blocks.COMPOSTER);
        helper.setBlock(relativeJobSite.above().south(), Blocks.CRAFTING_TABLE);
        Villager baker = spawnVillager(helper, relativeJobSite.above(), "minecraft:farmer");
        Villager grower = spawnVillager(helper, relativeJobSite.above().east(3), "minecraft:unemployed");
        Villager full = spawnVillager(helper, relativeJobSite.above().east(), "minecraft:unemployed");
        try {
            baker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), jobSite));
            VillagerNutrition.setFoodLevel(baker, 20);
            VillagerNutrition.setFoodLevel(grower, 20);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 3), new ItemStack(Items.EMERALD), 12, 1, .05F));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) baker).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory bakerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(baker.getUUID());
            VillagerWorkInventory growerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(grower.getUUID());
            require(helper, growerInventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not seed the nearby grower's physical wheat");
            require(helper, bakerInventory.insertExact(new ItemStack(Items.EMERALD)), "Could not fund material buyer");

            var fullStock = VillagerWorkInventorySavedData.forServer(server).inventory(full.getUUID());
            VillagerNutrition.setFoodLevel(full, 20);
            require(helper, fullStock.insertExact(new ItemStack(Items.WHEAT, 64)), "Full supplier missing wheat");
            while (fullStock.insertExact(new ItemStack(Items.COBBLESTONE, 64))) {
                // Fill every other slot so the nearer supplier cannot receive an emerald.
            }
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            baker.setTradingPlayer(helper.makeMockServerPlayerInLevel());
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(bakerInventory, Items.WHEAT) == 0 && count(bakerInventory, Items.EMERALD) == 1,
                    "A worker purchased workshop materials while trading with a player");
            baker.setTradingPlayer(null);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(bakerInventory, Items.WHEAT) == 3,
                    "Village logistics did not deliver the Farmer's exact live-recipe wheat inputs");
            require(helper, count(fullStock, Items.WHEAT) == 64 && count(fullStock, Items.EMERALD) == 0,
                    "Closest supplier lost goods despite having no room for payment");
            require(helper, count(growerInventory, Items.WHEAT) == 0,
                    "Village logistics duplicated or retained delivered wheat at its source");
            require(helper, count(bakerInventory, Items.EMERALD) == 0 && count(growerInventory, Items.EMERALD) == 1,
                    "Direct material purchase did not transfer its exact one-emerald price");

            WorkOrder bread = WorkOrderDefinitions.catalog().require("totem:farmer_bread");
            for (int tick = 0; tick <= bread.workTicks(); tick++) {
                VillagerWorkshopRuntime.tickForGameTest(server);
            }
            int stock = count(bakerInventory, Items.BREAD);
            require(helper, stock == 1,
                    "Delivered wheat did not become work-backed bread stock: " + stock);
            require(helper, count(bakerInventory, Items.WHEAT) == 0,
                    "The Baker did not consume the delivered recipe materials");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(baker.getUUID());
            states.remove(grower.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(baker.getUUID());
            inventories.drain(grower.getUUID());
            baker.discard();
            grower.discard();
            inventories.drain(full.getUUID());
            full.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void supplierRetainsIngredientsForItsOwnNextLiveRecipe(GameTestHelper helper) {
        helper.setTime(3000);
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos recipientJobSite = new BlockPos(3, 2, 3);
        BlockPos supplierJobSite = new BlockPos(6, 2, 3);
        helper.setBlock(recipientJobSite, Blocks.COMPOSTER);
        helper.setBlock(supplierJobSite, Blocks.COMPOSTER);
        Villager recipient = spawnVillager(helper, recipientJobSite.above(), "minecraft:farmer");
        Villager supplier = spawnVillager(helper, supplierJobSite.above(), "minecraft:farmer");
        try {
            prepareBreadSeller(helper, recipient, helper.absolutePos(recipientJobSite));
            prepareBreadSeller(helper, supplier, helper.absolutePos(supplierJobSite));
            VillagerWorkInventory recipientInventory = VillagerWorkInventorySavedData.forServer(server).inventory(recipient.getUUID());
            VillagerWorkInventory supplierInventory = VillagerWorkInventorySavedData.forServer(server).inventory(supplier.getUUID());
            require(helper, supplierInventory.insertExact(new ItemStack(Items.WHEAT, 4)),
                    "Could not seed the supplier's own recipe inputs and surplus");
            require(helper, recipientInventory.insertExact(new ItemStack(Items.EMERALD)), "Could not fund requester");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(recipientInventory, Items.WHEAT) == 1,
                    "The requester did not receive the supplier's one-wheat surplus");
            require(helper, count(supplierInventory, Items.WHEAT) == 3,
                    "The supplier did not retain its own next bread recipe inputs");
            require(helper, count(recipientInventory, Items.EMERALD) == 0 && count(supplierInventory, Items.EMERALD) == 1,
                    "The supplier did not receive the live material-offer emerald payment");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(recipient.getUUID());
            states.remove(supplier.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(recipient.getUUID());
            inventories.drain(supplier.getUUID());
            recipient.discard();
            supplier.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void fletcherBuysOnlyTheOfferedPotionAndSupplierKeepsItsOwnVariant(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        var a = new BlockPos(3,2,3);
        var b = a.east(2);
        h.setBlock(a, Blocks.FLETCHING_TABLE);
        h.setBlock(b, Blocks.FLETCHING_TABLE);
        var buyer = spawnVillager(h,a.above(),"minecraft:fletcher");
        var seller = spawnVillager(h,b.above(),"minecraft:fletcher");
        try {
            for (var worker : java.util.List.of(buyer,seller)) {
                worker.getBrain().setMemory(MemoryModuleType.JOB_SITE,GlobalPos.of(level.dimension(),h.absolutePos(worker == buyer ? a : b)));
                var offers = worker.getOffers();
                offers.clear();
                var desired = worker == buyer ? net.minecraft.world.item.alchemy.Potions.SWIFTNESS : net.minecraft.world.item.alchemy.Potions.SLOWNESS;
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD,5),
                        net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW,desired).copyWithCount(5),12,1,.05F));
                if (worker == buyer) offers.add(new MerchantOffer(new ItemCost(Items.LINGERING_POTION),new ItemStack(Items.EMERALD),12,1,.05F));
            }
            var accounts = VillagerWorkInventorySavedData.forServer(server);
            var wallet = accounts.inventory(buyer.getUUID());
            var stock = accounts.inventory(seller.getUUID());
            var wanted = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.LINGERING_POTION,net.minecraft.world.item.alchemy.Potions.SWIFTNESS);
            var other = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.LINGERING_POTION,net.minecraft.world.item.alchemy.Potions.SLOWNESS);
            wallet.insertExact(new ItemStack(Items.ARROW,8));
            wallet.insertExact(new ItemStack(Items.EMERALD));
            wallet.insertExact(other.copy());
            stock.insertExact(wanted.copy());
            buyer.getOffers().removeLast();
            var slowOnly = new ItemCost(Items.LINGERING_POTION).withComponents(builder -> builder.expect(
                    net.minecraft.core.component.DataComponents.POTION_CONTENTS, other.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)));
            buyer.getOffers().add(new MerchantOffer(slowOnly,new ItemStack(Items.EMERALD),12,1,.05F));
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(h, stock.countMatchingItem(wanted) == 1 && wallet.countMatchingItem(new ItemStack(Items.EMERALD)) == 1,
                    "Used a slowness-only price for a swiftness purchase");
            buyer.getOffers().removeLast();
            buyer.getOffers().add(new MerchantOffer(new ItemCost(Items.LINGERING_POTION),new ItemStack(Items.EMERALD),12,1,.05F));
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(h,wallet.countMatchingItem(wanted) == 1 && wallet.countMatchingItem(other) == 1
                    && stock.countMatchingItem(wanted) == 0 && stock.countMatchingItem(new ItemStack(Items.EMERALD)) == 1,
                    "Fletcher mistook one potion variant for another when buying or reserving stock");
            h.succeed();
        } finally {
            settings.setMode(previous);
            buyer.discard();
            seller.discard();
        }
    }

    @GameTest(maxTicks = 40)
    public void materialPurchaseRequiresBuyerEmeralds(GameTestHelper helper) {
        helper.setTime(3000);
        var server = helper.getLevel().getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        BlockPos jobSite = new BlockPos(3, 2, 3);
        helper.setBlock(jobSite, Blocks.COMPOSTER);
        Villager buyer = spawnVillager(helper, jobSite.above(), "minecraft:farmer");
        Villager supplier = spawnVillager(helper, jobSite.above().east(), "minecraft:unemployed");
        try {
            buyer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), helper.absolutePos(jobSite)));
            VillagerNutrition.setFoodLevel(buyer, 20);
            VillagerNutrition.setFoodLevel(supplier, 20);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 3), new ItemStack(Items.EMERALD), 12, 1, .05F));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
            ((AbstractVillagerOffersAccessor) (Object) buyer).totemVillagers$setExistingOffers(offers);
            VillagerWorkInventory buyerInventory = VillagerWorkInventorySavedData.forServer(server).inventory(buyer.getUUID());
            VillagerWorkInventory supplierInventory = VillagerWorkInventorySavedData.forServer(server).inventory(supplier.getUUID());
            require(helper, supplierInventory.insertExact(new ItemStack(Items.WHEAT, 3)),
                    "Could not seed physical wheat for the unfunded purchase test");

            settings.setMode(WorkBackedTradingMode.ENFORCED);
            VillagerMaterialLogisticsRuntime.tickForGameTest(server);
            require(helper, count(buyerInventory, Items.WHEAT) == 0 && count(supplierInventory, Items.WHEAT) == 3,
                    "An unfunded villager material purchase moved physical wheat");
            require(helper, count(buyerInventory, Items.EMERALD) == 0,
                    "An unfunded material purchase created emeralds");
            helper.succeed();
        } finally {
            settings.setMode(WorkBackedTradingMode.DISABLED);
            VillagerWorkSavedData states = VillagerWorkSavedData.forServer(server);
            states.remove(buyer.getUUID());
            states.remove(supplier.getUUID());
            VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
            inventories.drain(buyer.getUUID());
            inventories.drain(supplier.getUUID());
            buyer.discard();
            supplier.discard();
        }
    }

    private static void prepareBreadSeller(GameTestHelper helper, Villager villager, BlockPos jobSite) {
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), jobSite));
        VillagerNutrition.setFoodLevel(villager, 20);
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.WHEAT, 1), new ItemStack(Items.EMERALD), 12, 1, .05F));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 12, 1, .05F));
        ((AbstractVillagerOffersAccessor) (Object) villager).totemVillagers$setExistingOffers(offers);
    }

    private static int count(VillagerWorkInventory inventory, net.minecraft.world.item.Item item) {
        return inventory.snapshot().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    @SuppressWarnings("unchecked")
    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePosition, String professionId) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "villager"));
        require(helper, type != null, "Missing minecraft:villager entity type");
        Villager villager = helper.spawnWithNoFreeWill((EntityType<Villager>) type, relativePosition);
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(professionId));
        if (profession == null) {
            throw new IllegalStateException("Missing villager profession " + professionId);
        }
        villager.setVillagerData(villager.getVillagerData()
                .withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(profession))
                .withLevel(VillagerData.MAX_VILLAGER_LEVEL));
        villager.setVillagerXp(VillagerData.getMinXpPerLevel(VillagerData.MAX_VILLAGER_LEVEL));
        return villager;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
