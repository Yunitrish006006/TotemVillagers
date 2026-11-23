package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.ProductionParcel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.List;

public final class ButcherRationGameTest {
    @SuppressWarnings("unchecked")
    @GameTest(environment = "totem:butcher_trials", structure = "totem:river_households", padding = 40)
    public void marketSkipsUnsellableFoodAndSmokersServeSeparateHouseholds(GameTestHelper h) {
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var first = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 4));
        var second = h.spawnWithNoFreeWill(type, new BlockPos(6, 3, 4));
        var a = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 5));
        var b = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 6));
        var group = List.of(first, second, a, b);
        var accounts = VillagerWorkInventorySavedData.forServer(server);
        var role = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:butcher"));
        var bread = new ItemStack(Items.BREAD);
        var coin = new ItemStack(Items.EMERALD);
        var raw = new ItemStack(Items.PORKCHOP);
        var shovel = new ItemStack(Items.WOODEN_SHOVEL);
        var near = accounts.inventory(first.getUUID());
        var far = accounts.inventory(second.getUUID());
        var wallet = accounts.inventory(a.getUUID());
        try {
            for (int i = 0; i < group.size(); i++) {
                var v = group.get(i);
                var home = h.absolutePos(new BlockPos(2 + i * 2, 3, 8));
                level.setBlock(home, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), home));
                VillagerNutrition.setFoodLevel(v, i < 2 ? 20 : 18);
            }
            for (var v : List.of(first, second)) {
                var site = v.blockPosition().north();
                level.setBlockAndUpdate(site, Blocks.SMOKER.defaultBlockState());
                VillagerResourceWorkforceRuntime.claimExactJobSite(level, role, site).orElseThrow();
                v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(role)));
                v.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), site));
            }
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            wallet.insertExact(coin.copyWithCount(2));
            accounts.inventory(b.getUUID()).insertExact(coin);
            near.insertExact(bread.copyWithCount(10));
            far.insertExact(bread.copyWithCount(10));
            while (near.insertExact(shovel)) { }
            long now = level.getGameTime();
            VillagerFoodEconomyRuntime.marketTurn(level, a, accounts, now);
            h.assertTrue(near.countMatchingItem(bread) == 10 && near.countMatchingItem(coin) == 0
                    && far.countMatchingItem(coin) == 1 && wallet.countMatchingItem(bread) == 5, "An unsellable quote blocked the other seller");
            wallet.takeExactMatchingItem(bread.copyWithCount(5)).orElseThrow();
            for (var cook : List.of(first, second)) {
                var stock = accounts.inventory(cook.getUUID());
                while (stock.takeExactMatchingItem(shovel).isPresent()) { }
                stock.takeExactMatchingItem(bread.copyWithCount(stock.countMatchingItem(bread))).orElseThrow();
                stock.insertExact(raw.copyWithCount(8));
                stock.insertExact(new ItemStack(Items.COAL));
            }
            VillagerFoodEconomyRuntime.marketTurn(level, a, accounts, now + 100);
            VillagerFoodEconomyRuntime.marketTurn(level, b, accounts, now + 100);
            h.assertTrue(ButcherRationOrders.reservedForSmoking(level, first) && ButcherRationOrders.reservedForSmoking(level, second),
                    "Two funded households failed to occupy separate working smokers");
            VillagerWorkshopRuntime.tickForGameTest(server);
            h.assertTrue(dev.totem.villagers.work.VillagerWorkSavedData.forServer(server).get(first.getUUID())
                    .flatMap(state -> state.activeWork()).isEmpty(), "Ordinary workshop occupied a ration cook");
            var oven = (SmokerBlockEntity)level.getBlockEntity(first.blockPosition().north());
            oven.setItem(0, new ItemStack(Items.IRON_INGOT));
            ButcherRationOrders.workTick(level, now + 101);
            h.assertTrue(!ButcherRationOrders.reservedForSmoking(level, first) && ButcherRationOrders.reservedForSmoking(level, second)
                    && near.countMatchingItem(raw) == 8 && near.countMatchingItem(new ItemStack(Items.COAL)) == 1
                    && oven.getItem(0).is(Items.IRON_INGOT) && wallet.countMatchingItem(coin) == 1, "Occupied stove did not cancel cleanly");
            h.succeed();
        } finally {
            settings.setMode(previous);
            group.forEach(Villager::discard);
        }
    }

    @SuppressWarnings("unchecked")
    @GameTest(environment = "totem:butcher_trials", structure = "totem:river_households", padding = 40)
    public void householdFundsSmokedRationsFromAStableMeatSupply(GameTestHelper h) {
        var level = h.getLevel();
        var settings = WorkBackedTradingSettingsSavedData.forServer(level.getServer());
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var cook = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 4));
        var buyer = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 5));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(6, 3, 5));
        var shepherd = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 3));
        var fullSeller = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 3));
        var residents = List.of(cook, buyer, farmer, shepherd, fullSeller);
        var accounts = VillagerWorkInventorySavedData.forServer(level.getServer());
        var pantry = accounts.inventory(cook.getUUID());
        var wallet = accounts.inventory(buyer.getUUID());
        var raw = new ItemStack(Items.PORKCHOP);
        var pork = new ItemStack(Items.COOKED_PORKCHOP);
        var coal = new ItemStack(Items.CHARCOAL);
        var coin = new ItemStack(Items.EMERALD);
        var smoker = cook.blockPosition().west();
        var role = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:butcher"));
        try {
            for (int i = 0; i < residents.size(); i++) {
                var v = residents.get(i);
                var bed = cook.blockPosition().offset(i == 0 ? 35 : 6 + i * 2, 0, 3);
                var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
                level.setBlock(bed.below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.south().below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.south(), state.setValue(BedBlock.PART, BedPart.FOOT), 2);
                level.setBlock(bed, state.setValue(BedBlock.PART, BedPart.HEAD), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(v, 20);
            }
            var home = cook.getBrain().getMemory(MemoryModuleType.HOME);
            farmer.setVillagerData(farmer.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:farmer")))));
            var compost = GlobalPos.of(level.dimension(), farmer.blockPosition().east());
            var soil = compost.pos().south().below();
            level.setBlockAndUpdate(compost.pos(), Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, compost);
            var farm = accounts.inventory(farmer.getUUID());
            farm.insertExact(new ItemStack(Items.BREAD, 4));
            farm.insertExact(new ItemStack(Items.WOODEN_HOE));
            h.assertTrue(ProductionParcel.get(level.getServer()).acquire(level, farmer, compost, soil, true), "Missing food producer");
            level.setBlockAndUpdate(smoker, Blocks.SMOKER.defaultBlockState());
            VillagerNutrition.setFoodLevel(buyer, 18);
            wallet.insertExact(coin.copyWithCount(2));
            pantry.insertExact(raw.copyWithCount(15));
            pantry.insertExact(coal.copyWithCount(2));
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            long start = level.getGameTime() + 48200;
            long end = start + 48800 + 2 * 12100;
            for (long t = start - 48200; t < start; t += 100) ButcherRationOrders.observe(level, buyer, accounts, t);
            h.assertTrue(pantry.countMatchingItem(raw) == 15 && !cook.getVillagerData().profession().is(VillagerProfession.BUTCHER),
                    "A single incomplete reserve founded a butcher");
            pantry.takeExactMatchingItem(raw.copyWithCount(15)).orElseThrow();
            pantry.takeExactMatchingItem(coal.copyWithCount(2)).orElseThrow();
            var supplier = accounts.inventory(shepherd.getUUID());
            var shepherdRole = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:shepherd"));
            shepherd.setVillagerData(shepherd.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(shepherdRole)));
            var pasture = h.absolutePos(new BlockPos(5, 3, 2));
            var loom = shepherd.blockPosition().east();
            level.setBlockAndUpdate(loom, Blocks.LOOM.defaultBlockState());
            level.setBlockAndUpdate(pasture.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            shepherd.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), loom));
            h.assertTrue(ProductionParcel.get(level.getServer()).reservePasture(level, shepherd, loom, pasture.below()),
                    "Missing household pasture");
            var player = h.makeMockServerPlayerInLevel();
            var pigType = (EntityType<net.minecraft.world.entity.animal.pig.Pig>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:pig"));
            var playerPig = h.spawnWithNoFreeWill(pigType, new BlockPos(5, 3, 2));
            playerPig.hurtServer(level, level.damageSources().playerAttack(player), 100);
            var area = playerPig.getBoundingBox().inflate(1);
            var loose = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area,
                    e -> e.getItem().is(Items.PORKCHOP));
            h.assertTrue(!loose.isEmpty() && supplier.countMatchingItem(raw) == 0, "Player's kill entered the shepherd's inventory");
            var outside = h.spawnWithNoFreeWill(pigType, new BlockPos(4, 3, 2));
            level.setBlockAndUpdate(outside.blockPosition().below(), Blocks.STONE.defaultBlockState());
            outside.hurtServer(level, level.damageSources().fall(), 100);
            h.assertTrue(supplier.countMatchingItem(raw) == 0, "Shepherd collected a death outside the pasture");
            for (int i = 0; i < 16 && supplier.countMatchingItem(raw) < 16; i++) {
                var pig = h.spawnWithNoFreeWill(pigType, new BlockPos(5, 3, 2));
                pig.hurtServer(level, level.damageSources().fall(), 100);
            }
            int harvested = supplier.countMatchingItem(raw);
            h.assertTrue(harvested >= 16 && loose.stream().allMatch(e -> e.isAlive() && e.getItem().is(Items.PORKCHOP)),
                    "Native livestock drops did not enter the pasture owner's stock or consumed player loot");

            supplier.insertExact(new ItemStack(Items.BREAD, 4));
            supplier.insertExact(coal.copyWithCount(2));
            farm.insertExact(coin);
            h.assertTrue(WorkshopLocalMarket.exchange(accounts, supplier, farm, raw.copyWithCount(8), coin),
                    "Could not distribute the first eight harvested porkchops");
            fullSeller.setVillagerData(fullSeller.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(shepherdRole)));
            var full = accounts.inventory(fullSeller.getUUID());
            full.insertExact(raw.copyWithCount(32));
            full.insertExact(new ItemStack(Items.BREAD, 4));
            while (full.insertExact(new ItemStack(Items.WOODEN_SHOVEL))) { }

            pantry.insertExact(coin.copyWithCount(3));
            VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, start - 500);
            h.assertTrue(pantry.countMatchingItem(raw) == 0 && pantry.countMatchingItem(coin) == 3,
                    "Shopping committed funds needed for the cook's next meal");
            pantry.insertExact(coin);
            for (long t : List.of(start - 400, start - 300, start - 200)) VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, t);
            h.assertTrue(pantry.countMatchingItem(raw) == 16 && pantry.countMatchingItem(coal) == 1
                    && pantry.countMatchingItem(coin) == 1 && supplier.countMatchingItem(coin) == 3
                    && supplier.countMatchingItem(raw) == harvested - 16 && supplier.countMatchingItem(coal) == 1
                    && farm.countMatchingItem(raw) == 0 && farm.countMatchingItem(coin) == 1
                    && full.countMatchingItem(raw) == 32 && full.countMatchingItem(coin) == 0,
                    "Empty pantry could not buy the required meat and fuel while preserving both reserves");
            var oven = (SmokerBlockEntity)level.getBlockEntity(smoker);
            oven.setItem(0, new ItemStack(Items.IRON_INGOT));
            ButcherRationOrders.observe(level, buyer, accounts, start - 100);
            h.assertTrue(oven.getItem(0).is(Items.IRON_INGOT), "A player's occupied smoker was modified");
            oven.removeItemNoUpdate(0);
            long marketTime = start;
            for (int active = 0; active < 48800; active += 100) {
                if (active == 12000 || active == 24000) {
                    var sleeper = active == 12000 ? buyer : cook;
                    var bed = sleeper.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow().pos();
                    if (sleeper == cook) {
                        cook.snapTo(bed.getX() + .5, bed.getY(), bed.getZ() + .5);
                        VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, marketTime);
                    }
                    sleeper.startSleeping(bed);
                    for (int asleep = 0; asleep < 12000; asleep += 100) {
                        VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, marketTime);
                        marketTime += 100;
                    }
                    sleeper.stopSleeping();
                    var initial = sleeper == buyer ? new BlockPos(4, 3, 5) : new BlockPos(4, 3, 4);
                    var pos = h.absolutePos(initial);
                    sleeper.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
                    VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, marketTime);
                    marketTime += 100;
                }
                VillagerFoodEconomyRuntime.marketTurn(level, buyer, accounts, marketTime);
                if (active >= 48000) {
                    h.assertTrue(ButcherRationOrders.reservedForSmoking(level, cook), "Cook has no exclusive batch");
                    for (long tick = marketTime + 1; tick <= marketTime + 100; tick++) ButcherRationOrders.workTick(level, tick);
                }
                marketTime += 100;
            }
            h.assertTrue(!cook.getVillagerData().profession().is(VillagerProfession.BUTCHER)
                    && pantry.countMatchingItem(raw) == 16 && wallet.countMatchingItem(coin) == 2, "Smoking completed before its final tick");
            VillagerFoodEconomyRuntime.seekAndBuyFood(level, buyer, accounts, end);
            h.assertTrue(cook.getVillagerData().profession().is(VillagerProfession.BUTCHER)
                    && cook.getBrain().getMemory(MemoryModuleType.HOME).equals(home)
                    && cook.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow().pos().equals(smoker)
                    && VillagerResourceWorkforceRuntime.claimExactJobSite(level, role, smoker).isEmpty()
                    && pantry.countMatchingItem(raw) == 8 && pantry.countMatchingItem(coal) == 0
                    && pantry.countMatchingItem(pork) == 4 && pantry.countMatchingItem(coin) == 2
                    && wallet.countMatchingItem(pork) == 4 && wallet.countMatchingItem(coin) == 1 && oven.isEmpty(),
                    "Market did not deliver or appoint: raw=" + pantry.countMatchingItem(raw) + ", buyer cooked=" + wallet.countMatchingItem(pork));
            // With an affordable farmer pack, the existing market should transact before another smoker starts.
            wallet.takeExactMatchingItem(pork.copyWithCount(4)).orElseThrow();
            farm.insertExact(new ItemStack(Items.BREAD, 5));
            VillagerFoodEconomyRuntime.seekAndBuyFood(level, buyer, accounts, end);
            h.assertTrue(wallet.countMatchingItem(coin) == 0 && pantry.countMatchingItem(coal) == 0, "Existing food required a second smoking run");
            h.succeed();
        } finally {
            settings.setMode(previous);
            residents.forEach(Villager::discard);
        }
    }
}
