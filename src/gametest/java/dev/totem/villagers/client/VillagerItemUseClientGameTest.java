package dev.totem.villagers.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/** Visual regression stage for eating, off-hand blocking, and drawing a bow. */
@SuppressWarnings("UnstableApiUsage")
public final class VillagerItemUseClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runCommand("execute in minecraft:overworld run time set 1000");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run weather clear");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run fill -8 3 -8 8 3 12 minecraft:smooth_stone");
            singleplayer.getServer().runCommand("execute in minecraft:overworld run tp @a 0.5 4.0 -0.5 0 0");
            summon(singleplayer, -2.0D);
            summon(singleplayer, 0.5D);
            summon(singleplayer, 3.0D);
            context.waitTicks(4);
            singleplayer.getClientLevel().waitForChunksRender();
            context.runOnClient(client -> {
                if (client.player == null) {
                    throw new AssertionError("Item-use showcase player was unavailable");
                }
                client.player.setPosRaw(0.5D, 4.0D, -0.5D);
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5D, 5.35D, 5.0D));
                client.player.xo = client.player.getX();
                client.player.yo = client.player.getY();
                client.player.zo = client.player.getZ();
                client.player.yRotO = client.player.getYRot();
                client.player.xRotO = client.player.getXRot();
            });

            singleplayer.getServer().runOnServer(server -> {
                List<Villager> villagers = server.overworld().getEntitiesOfClass(Villager.class,
                                new AABB(-4.0D, 3.0D, 3.0D, 5.0D, 7.0D, 7.0D))
                        .stream().sorted(Comparator.comparingDouble(Villager::getX)).toList();
                if (villagers.size() != 3) {
                    throw new AssertionError("Item-use showcase did not create exactly three villagers");
                }

                Villager eating = villagers.get(0);
                eating.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE));

                Villager blocking = villagers.get(1);
                blocking.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));

                Villager drawing = villagers.get(2);
                drawing.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            });

            context.waitTicks(2);
            singleplayer.getServer().runOnServer(server -> {
                List<Villager> villagers = server.overworld().getEntitiesOfClass(Villager.class,
                                new AABB(-4.0D, 3.0D, 3.0D, 5.0D, 7.0D, 7.0D))
                        .stream().sorted(Comparator.comparingDouble(Villager::getX)).toList();
                villagers.get(0).startUsingItem(InteractionHand.MAIN_HAND);
                villagers.get(1).startUsingItem(InteractionHand.OFF_HAND);
                villagers.get(2).startUsingItem(InteractionHand.MAIN_HAND);
            });

            context.waitTicks(2);
            context.runOnClient(client -> {
                List<Villager> clientVillagers = client.level.getEntitiesOfClass(Villager.class,
                                new AABB(-4.0D, 3.0D, 3.0D, 5.0D, 7.0D, 7.0D), Villager::isAlive)
                        .stream().sorted(Comparator.comparingDouble(Villager::getX)).toList();
                if (clientVillagers.size() != 3) {
                    throw new AssertionError("Client did not receive exactly three item-use villagers");
                }
                requireUsing(clientVillagers.get(0), InteractionHand.MAIN_HAND, Items.APPLE, "eating");
                requireUsing(clientVillagers.get(1), InteractionHand.OFF_HAND, Items.SHIELD, "blocking");
                requireUsing(clientVillagers.get(2), InteractionHand.MAIN_HAND, Items.BOW, "drawing");
            });
            context.takeScreenshot("totem-villagers-player-like-item-use");
            context.waitTicks(2);
        }
    }

    private static void requireUsing(Villager villager, InteractionHand hand,
                                     net.minecraft.world.item.Item item, String stage) {
        if (!villager.getItemInHand(hand).is(item)) {
            throw new AssertionError("Client " + stage + " villager did not receive its held item");
        }
        if (!villager.isUsingItem() || villager.getUsedItemHand() != hand || !villager.getUseItem().is(item)) {
            throw new AssertionError("Client " + stage + " villager did not receive its active-use state");
        }
    }

    private static void summon(TestSingleplayerContext singleplayer, double x) {
        singleplayer.getServer().runCommand("execute in minecraft:overworld run summon minecraft:villager "
                + x + " 4.0 5.0 {NoAI:1b,NoGravity:1b,Rotation:[180.0f,0.0f],"
                + "VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:none\",level:1}}");
    }
}
