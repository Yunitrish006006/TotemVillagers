package dev.totem.villagers.client;

import dev.totem.villagers.worldgen.VillageUtilityFeature;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/** Captures a first-person view of the real generated 5 x 5 Miner starter shaft. */
@SuppressWarnings("UnstableApiUsage")
public final class MineShaftClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos furnace = new BlockPos(0, 10, 0);
            singleplayer.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (int x = -10; x <= 10; x++) {
                    for (int y = -12; y <= 8; y++) {
                        for (int z = -10; z <= 10; z++) {
                            level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                        }
                    }
                    for (int z = -10; z <= 10; z++) {
                        level.setBlock(new BlockPos(x, 9, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                    }
                }
                if (!VillageUtilityFeature.placeMine(level, furnace, BoundingBox.infinite())) {
                    throw new AssertionError("Could not generate the visual Mine fixture");
                }
                if (!level.getBlockState(new BlockPos(2, 10, 0)).is(Blocks.OAK_FENCE)) {
                    throw new AssertionError("Generated mine screenshot setup lacked the first oak guard rail");
                }
            });
            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set day");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run effect give @a minecraft:night_vision 60 0 true");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run gamemode spectator @a");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a 1.35 10.50 0.5 -90 0");
            context.waitTicks(12);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Mine screenshot player was unavailable");
                }
                client.player.setPosRaw(1.35D, 10.50D, .5D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(2.5D, 10.2D, .5D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-mineshaft-5x5-spiral");
            context.waitTicks(2);

            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a -8.5 15.5 -10.5");
            context.waitTicks(8);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Mine-head screenshot player was unavailable");
                }
                client.player.setPosRaw(-8.5D, 15.5D, -10.5D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(3.0D, 12.0D, 0.0D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-minehead-surface");
            context.waitTicks(2);
        }
    }
}
