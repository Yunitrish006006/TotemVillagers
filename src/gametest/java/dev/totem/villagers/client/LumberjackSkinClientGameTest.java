package dev.totem.villagers.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Stable in-game proof that the Lumberjack has its own forest cap, leather apron and work axe. */
@SuppressWarnings("UnstableApiUsage")
public final class LumberjackSkinClientGameTest implements FabricClientGameTest {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            "totem", "textures/entity/villager/profession/lumberjack.png");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set day");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run fill -8 3 -8 8 3 12 minecraft:oak_planks");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a 0.5 4.0 0.5 0 0");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run summon minecraft:villager 0.5 4.0 3.5 "
                    + "{NoAI:1b,NoGravity:1b,Rotation:[180.0f,0.0f],VillagerData:{type:\"minecraft:plains\",profession:\"totem:lumberjack\",level:1}}");
            context.waitTicks(20);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null || client.getResourceManager().getResource(TEXTURE).isEmpty()) {
                    throw new AssertionError("Dedicated Lumberjack profession texture was unavailable");
                }
                client.player.setPosRaw(0.5D, 4.0D, 0.5D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5D, 5.65D, 3.5D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
                Villager lumberjack = client.level.getEntitiesOfClass(Villager.class,
                                new AABB(-1.0D, 3.0D, 2.0D, 2.0D, 7.0D, 5.0D)).stream()
                        .findFirst().orElseThrow(() -> new AssertionError("Lumberjack showcase entity was unavailable"));
                lumberjack.swingingArm = InteractionHand.MAIN_HAND;
                lumberjack.oAttackAnim = 0.45F;
                lumberjack.attackAnim = 0.55F;
            });
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-lumberjack-dedicated-skin");
        }
    }
}
