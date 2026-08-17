package dev.totem.villagers.runtime;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerWorkNeeds;
import dev.totem.villagers.work.VillagerWorkSavedData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Converts surplus Farmer seeds and wheat through the physical job-site Composter. */
public final class VillagerFarmerCompostingRuntime {
    private static final int PROCESS_INTERVAL_TICKS = 20;
    private static final int SEED_RESERVE = 64;
    private static final int WHEAT_RESERVE = 192;
    private static final double WORK_REACH_SQUARED = 4.0D * 4.0D;

    private VillagerFarmerCompostingRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % PROCESS_INTERVAL_TICKS == 0) {
                process(server);
            }
        });
    }

    /** Public for deterministic long-soak GameTests. */
    public static void tickForGameTest(MinecraftServer server) {
        process(server);
    }

    private static void process(MinecraftServer server) {
        if (WorkBackedTradingSettingsSavedData.forServer(server).settings().mode() != WorkBackedTradingMode.ENFORCED) {
            return;
        }
        VillagerWorkInventorySavedData inventories = VillagerWorkInventorySavedData.forServer(server);
        for (ServerLevel level : server.getAllLevels()) {
            LoadedVillagerCache.loaded(level).stream()
                    .filter(VillagerFarmerCompostingRuntime::isFarmer)
                    .forEach(farmer -> compostOne(level, farmer, inventories.inventory(farmer.getUUID())));
        }
    }

    private static void compostOne(ServerLevel level, Villager farmer, VillagerWorkInventory inventory) {
        if (!VillagerWorkNeeds.canWork(farmer)
                || VillagerWorkSavedData.forServer(level.getServer()).getOrCreate(farmer.getUUID()).activeWork().isPresent()) {
            return;
        }
        BlockPos composter = farmer.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension())).map(GlobalPos::pos)
                .filter(level::isLoaded).filter(pos -> level.getBlockState(pos).is(Blocks.COMPOSTER)).orElse(null);
        if (composter == null) {
            return;
        }
        if (farmer.distanceToSqr(Vec3.atCenterOf(composter)) > WORK_REACH_SQUARED) {
            farmer.getNavigation().moveTo(composter.getX() + .5D, composter.getY(), composter.getZ() + .5D, .5D);
            return;
        }
        BlockState state = level.getBlockState(composter);
        int compostLevel = state.getValue(ComposterBlock.LEVEL);
        if (compostLevel == ComposterBlock.READY) {
            ItemStack boneMeal = new ItemStack(Items.BONE_MEAL);
            if (inventory.canInsertExact(boneMeal)
                    && level.setBlock(composter, Blocks.COMPOSTER.defaultBlockState(), 3)
                    && !inventory.insertExact(boneMeal)) {
                level.setBlock(composter, state, 3);
                throw new IllegalStateException("Farmer Composter output capacity changed during commit");
            }
            return;
        }
        if (compostLevel == ComposterBlock.MAX_LEVEL) {
            level.setBlock(composter, state.setValue(ComposterBlock.LEVEL, ComposterBlock.READY), 3);
            return;
        }
        ItemStack compostable = surplusCompostable(inventory);
        if (compostable.isEmpty() || !ComposterBlock.COMPOSTABLES.containsKey(compostable.getItem())) {
            return;
        }
        var reservation = inventory.reserveExactMatchingItem(compostable).orElse(null);
        if (reservation == null) {
            return;
        }
        ItemStack input = compostable.copy();
        ComposterBlock.insertItem(farmer, state, level, input, composter);
        if (input.isEmpty()) {
            reservation.commit();
            farmer.playWorkSound();
        } else {
            reservation.rollback();
        }
    }

    private static ItemStack surplusCompostable(VillagerWorkInventory inventory) {
        if (inventory.countMatchingItem(new ItemStack(Items.WHEAT_SEEDS)) > SEED_RESERVE) {
            return new ItemStack(Items.WHEAT_SEEDS);
        }
        if (inventory.countMatchingItem(new ItemStack(Items.BEETROOT_SEEDS)) > SEED_RESERVE) {
            return new ItemStack(Items.BEETROOT_SEEDS);
        }
        if (inventory.countMatchingItem(new ItemStack(Items.WHEAT)) > WHEAT_RESERVE) {
            return new ItemStack(Items.WHEAT);
        }
        return ItemStack.EMPTY;
    }

    private static boolean isFarmer(Villager villager) {
        var id = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().profession().value());
        return id != null && "minecraft:farmer".equals(id.toString());
    }
}
