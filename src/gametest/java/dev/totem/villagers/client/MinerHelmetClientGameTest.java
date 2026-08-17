package dev.totem.villagers.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Creates a stable, player-eye-level showcase screenshot for the Miner profession skin and safety helmet. */
@SuppressWarnings("UnstableApiUsage")
public final class MinerHelmetClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set day");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            // The GameTest world floor is at y=-60. Build a small stage so both the
            // player and the posed villager remain at the camera's intended height.
            singleplayer.getServer().runCommand("execute in minecraft:overworld run fill -8 3 -8 8 3 12 minecraft:stone");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a 0.5 4.0 0.5 0 0");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run summon minecraft:villager 0.5 4.0 3.5 "
                    + "{NoAI:1b,NoGravity:1b,Rotation:[180.0f,0.0f],VillagerData:{type:\"minecraft:plains\",profession:\"totem:miner\",level:1}}");
            context.waitTicks(20);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Miner showcase player was unavailable");
                }
                // Teleport packets can retain the loading-screen camera pose.
                // Set the local first-person camera explicitly on the Miner's head.
                client.player.setPosRaw(0.5, 4.0, 0.5);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5, 5.7, 3.5));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });
            context.waitTicks(1);
            context.takeScreenshot("totem-villagers-miner-idle");
            context.waitTicks(2);
            context.runOnClient(client -> {
                Villager miner = client.level.getEntitiesOfClass(Villager.class,
                                new AABB(-1.0, 3.0, 2.0, 2.0, 7.0, 5.0)).stream()
                        .findFirst().orElseThrow(() -> new AssertionError("Miner showcase entity was unavailable"));
                // Pin a mid-swing interpolation frame so slow software rendering cannot
                // skip the six-tick vanilla swing before the screenshot is submitted.
                miner.swingingArm = InteractionHand.MAIN_HAND;
                miner.oAttackAnim = 0.45F;
                miner.attackAnim = 0.55F;
            });
            context.takeScreenshot("totem-villagers-miner-working");
            context.waitTicks(2);
        }
    }
}
