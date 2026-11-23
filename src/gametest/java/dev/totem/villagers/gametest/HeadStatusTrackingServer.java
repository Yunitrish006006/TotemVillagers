package dev.totem.villagers.gametest;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class HeadStatusTrackingServer implements ModInitializer {
    private Villager worker;
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("totem.villagers.trackingTest")) return;
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                dispatcher.register(Commands.literal("supply_tracking_fixture").requires(source -> source.getEntity() == null)
                        .executes(context -> {
                            var inventory = VillagerWorkInventorySavedData.forServer(context.getSource().getServer()).inventory(worker.getUUID());
                            return inventory.insertExact(new ItemStack(Items.GLOWSTONE_DUST, 48)) ? 1 : 0;
                        })));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var level = server.overworld();
            var stand = new BlockPos(2, 100, 4);
            level.setChunkForced(0, 0, true);
            for (var pos : BlockPos.betweenClosed(-2, 99, -9, 3, 99, 6))
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
            level.setBlockAndUpdate(stand, Blocks.BREWING_STAND.defaultBlockState());
            var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:villager"));
            worker = (Villager) type.create(level, EntitySpawnReason.COMMAND);
            worker.snapTo(.5, 100, 4.5);
            worker.setNoAi(true);
            worker.setVillagerData(worker.getVillagerData().withProfession(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                    BuiltInRegistries.VILLAGER_PROFESSION.getValue(Identifier.parse("minecraft:cleric")))));
            worker.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), stand));
            level.addFreshEntity(worker);
            VillagerNutrition.setFoodLevel(worker, 20);
            var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(worker.getUUID());
            inventory.insertExact(new ItemStack(Items.GLOWSTONE_DUST, 2));
            WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.ENFORCED);
            com.mojang.logging.LogUtils.getLogger().info("Tracking fixture ready");
        });
    }
}
