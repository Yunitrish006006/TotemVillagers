package dev.totem.villagers.runtime;

import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;

public class VillageBoardStandClientGameTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().adjustSettings(settings -> {
            settings.setWorldType(settings.getNormalPresetList().stream().filter(entry -> entry.preset()
                    .is(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL)).findFirst().orElseThrow());
            settings.setSeed("42");
            settings.setGenerateStructures(true);
        }).create()) {
            var host = world.getServer();
            var center = new BlockPos[1];
            host.runOnServer(server -> {
                var level = server.overworld();
                center[0] = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, BlockPos.ZERO, 48, false);
                if (center[0] == null) throw new AssertionError("Seed did not yield a village");
            });
            host.runCommand("gamemode spectator @a");
            host.runCommand("tp @a " + center[0].getX() + " 90 " + center[0].getZ());
            host.runCommand("time set 1000");
            world.getConnection().waitForChunksRender();
            var camera = new String[1];
            for (int attempt = 0; attempt < 12 && camera[0] == null; attempt++) {
                context.waitTicks(30);
                host.runOnServer(server -> {
                    var level = server.overworld();
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                        var chunk = level.getChunkSource().getChunkNow((center[0].getX() >> 4) + dx, (center[0].getZ() >> 4) + dz);
                        if (chunk == null) continue;
                        for (var entity : chunk.getBlockEntities().values()) {
                            if (!(entity instanceof SignBlockEntity sign)) continue;
                            var bell = ((net.fabricmc.fabric.api.attachment.v1.AttachmentTarget)sign).getAttached(VillageBoardStand.BELL);
                            if (bell == null) continue;
                            var p = sign.getBlockPos();
                            var front = level.getBlockState(p).getValue(WallSignBlock.FACING);
                            if (!level.getBlockState(p.relative(front.getClockWise())).is(Blocks.BIRCH_WALL_SIGN)
                                    || !level.getBlockState(p.relative(front.getCounterClockWise())).is(Blocks.BIRCH_WALL_SIGN)) continue;
                            var residents = level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(48), v -> !v.isBaby());
                            if (residents.isEmpty()) continue;
                            var buyer = residents.getFirst();
                            buyer.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
                            // The village and kiosk are natural. Stage a single unmet request for the photograph.
                            var accounts = VillagerWorkInventorySavedData.forServer(server);
                            accounts.drain(buyer.getUUID());
                            VillagerNutrition.setFoodLevel(buyer, 17);
                            accounts.inventory(buyer.getUUID()).insertExact(new ItemStack(Items.EMERALD));
                            VillageNoticeBoard.postNeed(level, buyer, true, null);
                            VillageBoardStand.refresh(level, bell);
                            var panel = (SignBlockEntity)level.getBlockEntity(p.relative(front.getClockWise()));
                            if (panel.getText(SignTextSlot.FRONT).getMessages(false).stream().noneMatch(c -> c.getString().contains("5")))
                                throw new AssertionError("Missing funded food request");
                            // Stand just in front of the sign plane, square to the writing.
                            double eyeX = p.getX() + 0.5 + 2.2 * front.getStepX();
                            double eyeZ = p.getZ() + 0.5 + 2.2 * front.getStepZ();
                            camera[0] = "tp @a " + eyeX + " " + (p.getY() - 1.05) + " " + eyeZ
                                    + " " + front.getOpposite().toYRot() + " 2";
                            return;
                        }
                    }
                });
            }
            if (camera[0] == null) throw new AssertionError("Naturally generated village at " + center[0] + " has no kiosk");
            host.runCommand("gamemode spectator @a");
            host.runCommand(camera[0]);
            world.getConnection().waitForChunksRender();
            context.waitTicks(18);
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F1);
            context.waitTicks(2);
            context.takeScreenshot("village-notice-stand");
            context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_F1);
        }
    }
}
