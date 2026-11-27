package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.guard.GuardPost;
import dev.totem.villagers.guard.ManagedVillageSavedData;
import dev.totem.villagers.guard.ManagedVillageState;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.worker.ProductionParcel;
import dev.totem.villagers.worker.WorkerAssignment;
import dev.totem.villagers.worker.WorkerAssignmentSavedData;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GuardEquipmentGameTest {
    @GameTest(environment = "totem:smith_trials", structure = "totem:river_households", padding = 48)
    public void responsibleGuardPaysForHelmetAndWearsIt(GameTestHelper h) { purchase(h, true); }

    @GameTest(environment = "totem:smith_trials")
    public void responsibleGuardBuysSwordAndKeepsOtherSmithWorking(GameTestHelper h) { purchase(h, false); }

    @SuppressWarnings("unchecked")
    private static void purchase(GameTestHelper h, boolean armor) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var settings = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = settings.settings().mode();
        settings.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var smith = h.spawnWithNoFreeWill(type, new BlockPos(3,3,3));
        var guard = h.spawnWithNoFreeWill(type, new BlockPos(4,3,3));
        var relief = h.spawnWithNoFreeWill(type, new BlockPos(5,3,4));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(3,3,5));
        var fisher = h.spawnWithNoFreeWill(type, new BlockPos(4,3,4));
        var residents = List.of(smith, guard, relief, farmer);
        var inventories = VillagerWorkInventorySavedData.forServer(server);
        var buyer = inventories.inventory(guard.getUUID());
        var seller = inventories.inventory(smith.getUUID());
        var backup = inventories.inventory(relief.getUUID());
        var assignments = WorkerAssignmentSavedData.forServer(server);
        var villages = ManagedVillageSavedData.forServer(server);
        var id = UUID.randomUUID();
        var owner = UUID.randomUUID();
        var origin = smith.blockPosition();
        var first = origin.west();
        var spare = relief.blockPosition().east();
        var next = origin.north();
        var smithType = BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:toolsmith"));
        int price = armor ? 7 : 4;
        try {
            for (int i = 0; i < residents.size(); i++) {
                var v = residents.get(i);
                var home = origin.offset(i * 2 - 2, 0, 4);
                level.setBlock(home, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), home));
                VillagerNutrition.setFoodLevel(v, 20);
                inventories.inventory(v.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            var farm = farmer.blockPosition().west();
            var soil = farm.north().below();
            level.setBlockAndUpdate(farm, Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            setRole(farmer, "minecraft:farmer");
            var compost = GlobalPos.of(level.dimension(), farm);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, compost);
            h.assertTrue(ProductionParcel.get(server).acquire(level, farmer, compost, soil, true), "Food source unavailable");
            inventories.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.WOODEN_HOE));
            setRole(guard, "totem:guard");
            villages.registerOrUpdate(new ManagedVillageState(new GuardPost(id, owner, level.dimension().identifier().toString(),
                    origin.asLong()), guard.getUUID(), Set.of(), Optional.empty()), owner);
            assignments.putAssignment(new WorkerAssignment(guard.getUUID(), "totem:guard", Optional.empty(), Optional.of(id)));
            for (var pos : List.of(first, spare)) level.setBlockAndUpdate(pos, Blocks.SMITHING_TABLE.defaultBlockState());
            for (var v : List.of(smith, relief)) {
                var pos = v == smith ? first : spare;
                var ticket = VillagerResourceWorkforceRuntime.claimExactJobSite(level, smithType, pos).orElseThrow();
                setRole(v, "minecraft:toolsmith");
                v.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), ticket));
            }
            level.setBlockAndUpdate(smith.blockPosition().south(), Blocks.CRAFTING_TABLE.defaultBlockState());
            var home = smith.getBrain().getMemory(MemoryModuleType.HOME);
            level.setBlockAndUpdate(next, armor ? Blocks.BLAST_FURNACE.defaultBlockState() : Blocks.GRINDSTONE.defaultBlockState());
            buyer.insertExact(new ItemStack(Items.EMERALD, price));
            if (armor) buyer.insertExact(new ItemStack(Items.IRON_SWORD));
            seller.insertExact(new ItemStack(Items.IRON_INGOT, armor ? 5 : 2));
            if (!armor) seller.insertExact(new ItemStack(Items.STICK));
            settings.setMode(WorkBackedTradingMode.ENFORCED);
            for (int tick = 0; tick <= 48000; tick += 100) GuardEquipmentOrders.observe(level, guard, tick);
            h.assertTrue(smith.getVillagerData().profession().is(VillagerProfession.TOOLSMITH), "Legacy job was overwritten");
            VillagerResourceWorkforceRuntime.establishVanillaProfession(level, smith, smithType, first);
            VillagerCareerHistory.observe(smith, 72000);
            GuardEquipmentOrders.observe(level, guard, 48100);
            h.assertTrue(seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 0, "An unequipped backup released the last smith");
            backup.insertExact(new ItemStack(Items.COBBLESTONE, 3));
            backup.insertExact(new ItemStack(Items.STICK, 2));
            setRole(fisher, "minecraft:fisherman");
            inventories.inventory(fisher.getUUID()).insertExact(new ItemStack(Items.EMERALD, 3));
            GuardEquipmentOrders.observe(level, guard, 48200);
            h.assertTrue(seller.countMatchingItem(new ItemStack(Items.EMERALD)) == 0, "Backup could not supply fishing customer");
            backup.insertExact(new ItemStack(Items.FISHING_ROD));
            GuardEquipmentOrders.observe(level, guard, 48300);
            var goal = armor ? VillagerProfession.ARMORER : VillagerProfession.WEAPONSMITH;
            h.assertTrue(smith.getVillagerData().profession().is(goal) && smith.getBrain().getMemory(MemoryModuleType.HOME).equals(home)
                    && smith.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(j -> j.pos().equals(next)).isPresent()
                    && relief.getVillagerData().profession().is(VillagerProfession.TOOLSMITH), "Paid trial did not assign the right workshop");
            var free = VillagerResourceWorkforceRuntime.claimExactJobSite(level, smithType, first).orElseThrow();
            level.getPoiManager().release(free);
            h.assertTrue(VillagerResourceWorkforceRuntime.claimExactJobSite(level, smithType, spare).isEmpty(), "Backup lost its workstation");
            GuardEquipmentOrders.observe(level, guard, 48400);
            h.assertTrue(seller.countMatchingItem(new ItemStack(Items.IRON_INGOT)) == 0 && seller.countMatchingItem(new ItemStack(Items.STICK)) == 0
                    && buyer.countMatchingItem(new ItemStack(Items.EMERALD)) == 0 && seller.countMatchingItem(new ItemStack(Items.EMERALD)) == price
                    && (armor ? guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && buyer.countMatchingItem(new ItemStack(Items.IRON_HELMET)) == 0
                    : buyer.countMatchingItem(new ItemStack(Items.IRON_SWORD)) == 1), "Missing paid guard equipment");
            if (armor) buyBoots(h, guard, type, inventories);
            h.succeed();
        } finally {
            settings.setMode(previous);
            villages.remove(id, owner);
            fisher.discard();
            residents.forEach(Villager::discard);
        }
    }

    private static void buyBoots(GameTestHelper h, Villager guard, EntityType<Villager> type, VillagerWorkInventorySavedData accounts) {
        h.setTime(3000);
        var level = h.getLevel();
        var apprentice = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 3));
        var shepherd = h.spawnWithNoFreeWill(type, new BlockPos(5, 3, 2));
        var station = apprentice.blockPosition().east();
        var purse = accounts.inventory(guard.getUUID());
        var materials = accounts.inventory(shepherd.getUUID());
        var stock = accounts.inventory(apprentice.getUUID());
        var coin = new ItemStack(Items.EMERALD);
        var leather = new ItemStack(Items.LEATHER);
        var boots = new ItemStack(Items.LEATHER_BOOTS);
        try {
            setRole(shepherd, "minecraft:shepherd");
            for (var v : List.of(apprentice, shepherd)) {
                var bed = v.blockPosition().east(v == apprentice ? 5 : 8);
                var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH);
                level.setBlock(bed.below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.south().below(), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(bed.south(), state.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 2);
                level.setBlock(bed, state.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(v, 20);
                accounts.inventory(v.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            var home = apprentice.getBrain().getMemory(MemoryModuleType.HOME);
            level.setBlockAndUpdate(station, Blocks.WATER_CAULDRON.defaultBlockState());
            apprentice.getBrain().setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, GlobalPos.of(level.dimension(), station));
            h.assertTrue(VillageWorkshopApprenticeship.reservesStationForTrial(level, apprentice), "Water cauldron bypassed the career trial");
            apprentice.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
            purse.insertExact(coin.copyWithCount(3));
            stock.insertExact(coin.copyWithCount(3));
            materials.insertExact(leather.copyWithCount(4));
            for (int t = 48500; t <= 97000; t += 100) GuardEquipmentOrders.observe(level, guard, t);
            h.assertTrue(stock.countMatchingItem(leather) == 4 && purse.countMatchingItem(coin) == 3
                    && !apprentice.getVillagerData().profession().is(VillagerProfession.LEATHERWORKER),
                    "Unexpected first leather purchase: stock=" + stock.snapshot().stream().filter(x -> !x.isEmpty()).toList()
                            + ", supplier=" + materials.countMatchingItem(leather) + ", guard coins=" + purse.countMatchingItem(coin)
                            + ", village=" + VillageWorkshopApprenticeship.households(level, guard, guard.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow()).size()
                            + ", ready=" + VillageFoodForecast.coversNextMeal(level,
                                VillageWorkshopApprenticeship.households(level, guard, guard.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow()), accounts, guard, 3, false)
                            + ", candidate home=" + home + ", pot=" + level.getPoiManager().getType(station)
                            + ", available=" + VillageWorkshopApprenticeship.atStation(level, apprentice, station));
            materials.insertExact(leather.copyWithCount(4));
            for (int t = 97100; t <= 97500; t += 100) GuardEquipmentOrders.observe(level, guard, t);
            h.assertTrue(guard.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS)
                    && purse.countMatchingItem(coin) == 0 && stock.countMatchingItem(leather) == 4
                    && materials.countMatchingItem(leather) == 0 && materials.countMatchingItem(coin) == 2
                    && stock.countMatchingItem(coin) == 4 && purse.countMatchingItem(boots) == 0
                    && apprentice.getVillagerData().profession().is(VillagerProfession.LEATHERWORKER)
                    && apprentice.getBrain().getMemory(MemoryModuleType.HOME).equals(home)
                    && apprentice.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .filter(j -> j.pos().equals(station)).isPresent(), "Leather trial failed to supply and equip the guard");
            guard.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            purse.insertExact(coin.copyWithCount(3));
            var states = dev.totem.villagers.work.VillagerWorkSavedData.forServer(level.getServer());
            var before = states.getOrCreate(apprentice.getUUID());
            var active = new dev.totem.villagers.work.ActiveWork("totem:leatherworker_leather_boots",
                    dev.totem.villagers.work.WorkSource.WORKSHOP, 97500, 20);
            states.put(before.withActiveWork(Optional.of(active), Optional.empty()));
            GuardEquipmentOrders.observe(level, guard, 97600);
            h.assertTrue(stock.countMatchingItem(leather) == 4 && purse.countMatchingItem(coin) == 3
                    && guard.getItemBySlot(EquipmentSlot.FEET).isEmpty(), "Guard took leather committed to another workshop order");
            states.put(before);
            for (int t = 97700; t <= 97800; t += 100) GuardEquipmentOrders.observe(level, guard, t);
            h.assertTrue(guard.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS)
                    && stock.countMatchingItem(leather) == 0 && stock.countMatchingItem(coin) == 7
                    && purse.countMatchingItem(coin) == 0, "Established leatherworker failed to supply replacement boots");
        } finally {
            apprentice.discard();
            shepherd.discard();
        }
    }

    @GameTest(environment = "totem:butcher_trials", structure = "totem:river_households", padding = 60)
    @SuppressWarnings("unchecked")
    public void openPostAppointsItsFirstGuardAndBuildsOneGolem(GameTestHelper h) {
        h.setTime(3000);
        var level = h.getLevel();
        var server = level.getServer();
        var mode = WorkBackedTradingSettingsSavedData.forServer(server);
        var previous = mode.settings().mode();
        mode.setMode(WorkBackedTradingMode.DISABLED);
        var type = (EntityType<Villager>) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
        var candidate = h.spawnWithNoFreeWill(type, new BlockPos(4, 3, 4));
        var farmer = h.spawnWithNoFreeWill(type, new BlockPos(6, 3, 5));
        var pad = candidate.blockPosition().north(2);
        var villages = ManagedVillageSavedData.forServer(server);
        var jobs = WorkerAssignmentSavedData.forServer(server);
        var accounts = VillagerWorkInventorySavedData.forServer(server);
        var stock = accounts.inventory(candidate.getUUID());
        var pumpkin = new ItemStack(Items.CARVED_PUMPKIN);
        var iron = new ItemStack(Items.IRON_BLOCK);
        var player = h.makeMockServerPlayerInLevel();
        var owner = player.getUUID();
        var source = player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
        var command = "totemvillagers guard-post recruit " + pad.getX() + " " + pad.getY() + " " + pad.getZ();
        var residents = List.of(candidate, farmer);
        var posted = new java.util.ArrayList<GuardPost>();
        try {
            for (var v : residents) {
                var bed = v.blockPosition().south(3);
                level.setBlock(bed, BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:red_bed")).defaultBlockState(), 2);
                v.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bed));
                VillagerNutrition.setFoodLevel(v, 20);
                accounts.inventory(v.getUUID()).insertExact(new ItemStack(Items.BREAD, 4));
            }
            var home = candidate.getBrain().getMemory(MemoryModuleType.HOME);
            setRole(farmer, "minecraft:farmer");
            var compost = GlobalPos.of(level.dimension(), farmer.blockPosition().east());
            level.setBlockAndUpdate(compost.pos(), Blocks.COMPOSTER.defaultBlockState());
            var soil = compost.pos().south().below();
            level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(soil.above(), Blocks.CARROTS.defaultBlockState());
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE, compost);
            accounts.inventory(farmer.getUUID()).insertExact(new ItemStack(Items.WOODEN_HOE));
            h.assertTrue(ProductionParcel.get(server).acquire(level, farmer, compost, soil, true), "Missing household harvest");
            mode.setMode(WorkBackedTradingMode.ENFORCED);
            server.getCommands().performPrefixedCommand(source, command);
            var post = villages.vacancies().stream().map(ManagedVillageSavedData.Vacancy::post)
                    .filter(p -> p.ownerId().equals(owner) && p.packedConstructionPad() == pad.asLong()).findFirst().orElseThrow();
            posted.add(post);
            h.assertTrue(!villages.recruit(new GuardPost(UUID.randomUUID(), owner, post.dimensionId(), post.packedConstructionPad()), owner)
                    && !villages.cancelRecruitment(post.villageId(), UUID.randomUUID()), "Vacancy ownership or duplicate pad protection failed");
            stock.insertExact(iron.copyWithCount(4));
            GuardConstructionRuntime.recruit(level, villages, accounts, jobs, 0);
            GuardConstructionRuntime.recruit(level, villages, accounts, jobs, 48000);
            h.assertTrue(villages.vacancy(post.villageId()).orElseThrow().ticks() == 0, "Unobserved time completed a vacancy");
            for (long t = 48100; t <= 96000; t += 100) GuardConstructionRuntime.recruit(level, villages, accounts, jobs, t);
            h.assertTrue(jobs.getAssignment(candidate.getUUID()).isEmpty() && stock.countMatchingItem(iron) == 4, "A post appointed a guard without a pumpkin");
            stock.insertExact(pumpkin);
            var wall = candidate.blockPosition().north();
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            GuardConstructionRuntime.recruit(level, villages, accounts, jobs, 96100);
            h.assertTrue(jobs.getAssignment(candidate.getUUID()).isEmpty() && stock.countMatchingItem(pumpkin) == 1,
                    "A guard signed and paid for a post through a wall");
            level.removeBlock(wall.above(), false);
            level.removeBlock(wall, false);
            GuardConstructionRuntime.recruit(level, villages, accounts, jobs, 96200);
            var village = villages.get(post.villageId()).orElseThrow();
            h.assertTrue(village.guardVillagerId().equals(candidate.getUUID()) && village.construction().isPresent()
                    && villages.vacancy(post.villageId()).isEmpty() && stock.countMatchingItem(iron) == 0 && stock.countMatchingItem(pumpkin) == 0
                    && jobs.getAssignment(candidate.getUUID()).filter(a -> a.automaticallyAssigned() && a.managedVillageId().filter(post.villageId()::equals).isPresent()).isPresent()
                    && candidate.getBrain().getMemory(MemoryModuleType.HOME).equals(home), "Recruitment did not reserve the first build and retain the home");
            for (int t = 1; t <= 100; t++) GuardConstructionRuntime.tickForGameTest(server, t);
            var completed = villages.get(post.villageId()).orElseThrow();
            h.assertTrue(completed.construction().isEmpty() && completed.managedGolemIds().size() == 1
                    && level.getEntity(completed.managedGolemIds().iterator().next()) instanceof net.minecraft.world.entity.animal.golem.IronGolem,
                    "Recruited guard did not finish the paid golem");
            level.getEntity(completed.managedGolemIds().iterator().next()).discard();
            h.succeed();
        } finally {
            for (var post : posted) {
                villages.cancelRecruitment(post.villageId(), owner);
                villages.remove(post.villageId(), owner);
            }
            jobs.removeAssignment(candidate.getUUID());
            mode.setMode(previous);
            residents.forEach(Villager::discard);
        }
    }

    private static void setRole(Villager v, String role) {
        v.setVillagerData(v.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse(role)))));
    }
}
