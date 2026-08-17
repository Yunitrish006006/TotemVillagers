package dev.totem.villagers.client;

import dev.totem.villagers.worldgen.MangroveVillageFeature;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Captures the complete production Mangrove-village layout from a stable aerial camera. */
@SuppressWarnings("UnstableApiUsage")
public final class MangroveVillageClientGameTest implements FabricClientGameTest {
    private static final long SHOWCASE_WORLD_SEED = 0x4D414E47524F5645L;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            BlockPos origin = new BlockPos(0, 62, 0);
            long showcaseWorldSeed = showcaseWorldSeed(origin);
            singleplayer.getServer().runOnServer(server -> {
                var level = server.overworld();
                for (int x = -50; x <= 50; x++) {
                    for (int z = -50; z <= 50; z++) {
                        level.setBlock(new BlockPos(x, 61, z), Blocks.MUD.defaultBlockState(), 2);
                        level.setBlock(new BlockPos(x, 62, z), Blocks.WATER.defaultBlockState(), 2);
                    }
                }
                for (BlockPos pad : new BlockPos[]{
                        new BlockPos(-42, 63, -5), new BlockPos(-39, 63, 25),
                        new BlockPos(39, 63, 25), new BlockPos(43, 63, 3),
                        new BlockPos(21, 63, -42), new BlockPos(-24, 63, -43)
                }) {
                    level.setBlock(pad, Blocks.LILY_PAD.defaultBlockState(), 2);
                }
                showcaseTree(level, new BlockPos(-45, 63, 35));
                showcaseTree(level, new BlockPos(44, 63, 37));
                showcaseTree(level, new BlockPos(-46, 63, -35));
                if (!MangroveVillageFeature.placeForWorldSeed(
                        level, origin, BoundingBox.infinite(), showcaseWorldSeed)) {
                    throw new AssertionError("Could not generate the Mangrove-village showcase");
                }
            });

            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set 1000");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run gamemode spectator @a");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a 30 87 -38");
            // Let the spectator-mode notification fade before taking a clean
            // showcase image, then wait once more for the nearer camera's
            // complete five-chunk render radius.
            context.waitTicks(130);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Mangrove-village showcase player was unavailable");
                }
                client.player.setPosRaw(30.0D, 87.0D, -38.0D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.0D, 67.0D, 2.0D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });
            context.waitTicks(4);
            context.getInput().pressKey(GLFW.GLFW_KEY_F1);
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-mangrove-village-natural-residences");
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Mangrove-village plan-view player was unavailable");
                }
                client.player.setPosRaw(0.0D, 125.0D, 0.0D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.0D, 65.0D, 0.01D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });
            context.waitTicks(6);
            singleplayer.getClientLevel().waitForChunksRender();
            context.takeScreenshot("totem-villagers-mangrove-village-natural-plan");
            context.getInput().pressKey(GLFW.GLFW_KEY_F1);
            context.waitTicks(2);
        }
    }

    private static long showcaseWorldSeed(BlockPos origin) {
        long seed = SHOWCASE_WORLD_SEED;
        while (MangroveVillageFeature.optionalResidenceCount(seed, origin) < 6
                || MangroveVillageFeature.residenceAppearanceVariantCount(seed, origin) < 3) {
            seed++;
        }
        return seed;
    }

    private static void showcaseTree(net.minecraft.server.level.ServerLevel level, BlockPos base) {
        for (int y = 0; y <= 1; y++) {
            level.setBlock(base.above(y), Blocks.MANGROVE_ROOTS.defaultBlockState(), 2);
        }
        for (int y = 2; y <= 7; y++) {
            level.setBlock(base.above(y), Blocks.MANGROVE_LOG.defaultBlockState(), 2);
        }
        var leaves = Blocks.MANGROVE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockPos canopy = base.above(8);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3) {
                    level.setBlock(canopy.offset(x, 0, z), leaves, 2);
                }
            }
        }
        level.setBlock(canopy.above(), leaves, 2);
    }
}
