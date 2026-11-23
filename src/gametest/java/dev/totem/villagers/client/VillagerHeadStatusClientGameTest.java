package dev.totem.villagers.client;

import dev.totem.villagers.config.WorkBackedTradingMode;
import dev.totem.villagers.config.WorkBackedTradingSettingsSavedData;
import dev.totem.villagers.inventory.VillagerWorkInventorySavedData;
import dev.totem.villagers.needs.VillagerNutrition;
import dev.totem.villagers.runtime.VillagerHeadStatus;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public final class VillagerHeadStatusClientGameTest implements FabricClientGameTest {
    private static final AABB STAGE = new AABB(-2, 3, 1, 2, 7, 6);

    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var host = world.getServer();
            host.runCommand("time set day");
            host.runCommand("fill -6 3 -6 6 3 9 minecraft:grass_block");
            host.runCommand("tp @a 0.5 4 0.5 0 0");
            host.runCommand("summon villager 0.5 4 4.5 {NoAI:1b,Rotation:[180.0f,0.0f]}");
            host.runOnServer(server -> {
                WorkBackedTradingSettingsSavedData.forServer(server).setMode(WorkBackedTradingMode.ENFORCED);
                var resident = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerNutrition.setFoodLevel(resident, 5);
            });
            context.waitTicks(330);
            world.getConnection().waitForChunksRender();
            context.runOnClient(client -> {
                var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                if (VillagerHeadStatus.current(resident) != VillagerHeadStatus.Kind.FOOD)
                    throw new AssertionError("Client did not receive food shortage");
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, resident.getEyePosition());
            });
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-needs-food");
            context.runOnClient(client -> {
                var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                var near = label(client, resident);
                if (near == null || !near.getString().contains(net.minecraft.network.chat.Component
                        .translatable(VillagerHeadStatus.Kind.FOOD.translation()).getString()))
                    throw new AssertionError("Focused status omitted localized label");
                client.player.setYRot(client.player.getYRot() + 180);
                var averted = label(client, resident);
                if (averted == null || !averted.getSiblings().isEmpty())
                    throw new AssertionError("Averted gaze should display only the icon");
            });
            host.runCommand("tp @a 0.5 4 -6.5 0 0");
            context.waitTicks(5);
            context.runOnClient(client -> {
                var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, resident.getEyePosition());
                var distant = label(client, resident);
                if (distant == null || !distant.getSiblings().isEmpty())
                    throw new AssertionError("Distant viewer should see the icon alone");
            });
            host.runCommand("tp @a 0.5 4 -22.5 0 0");
            context.waitTicks(5);
            context.runOnClient(client -> {
                var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                if (label(client, resident) != null) throw new AssertionError("Status visible outside viewing range");
            });
            host.runCommand("tp @a 0.5 4 0.5 0 0");
            host.runCommand("fill -1 4 2 1 7 2 minecraft:stone");
            context.waitTicks(10);
            context.runOnClient(client -> {
                var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                if (label(client, resident) != null) throw new AssertionError("Status visible through solid wall");
            });
            host.runCommand("fill -1 4 2 1 7 2 air");
            host.runOnServer(server -> {
                var resident = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerWorkInventorySavedData.forServer(server).inventory(resident.getUUID()).insertExact(new ItemStack(Items.BREAD, 5));
            });
            context.waitTicks(130);
            context.runOnClient(client -> {
                if (VillagerHeadStatus.current(client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst()) != null)
                    throw new AssertionError("Recovered resident still requested food");
            });
            host.runOnServer(server -> {
                var v = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                v.setVillagerData(v.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.resources.Identifier.parse("minecraft:shepherd")))));
                VillagerNutrition.setFoodLevel(v, 20);
            });
            context.waitTicks(130);
            expectStatus(context, VillagerHeadStatus.Kind.LOOM);
            context.takeScreenshot("totem-villagers-needs-loom");
            host.runCommand("setblock 2 4 4 minecraft:loom");
            host.runCommand("setblock 2 4 7 minecraft:red_bed[part=foot,facing=north]");
            host.runCommand("setblock 2 4 6 minecraft:red_bed[part=head,facing=north]");
            host.runCommand("summon sheep 1.5 4 5.5 {NoAI:1b}");
            host.runOnServer(server -> {
                var level = server.overworld();
                var v = level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                // A newly appointed vanilla shepherd can already own a pair from the profession kit.
                var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID());
                for (var stack : inventory.snapshot()) {
                    if (stack.is(Items.SHEARS) || stack.is(Items.IRON_INGOT)) inventory.takeExactMatchingItem(stack).orElseThrow();
                }
                v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME,
                        net.minecraft.core.GlobalPos.of(level.dimension(), new net.minecraft.core.BlockPos(2, 4, 6)));
                v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                        net.minecraft.core.GlobalPos.of(level.dimension(), new net.minecraft.core.BlockPos(2, 4, 4)));
            });
            context.waitTicks(130);
            expectStatus(context, VillagerHeadStatus.Kind.SHEARS);
            context.takeScreenshot("totem-villagers-needs-shears");
            host.runCommand("setblock 2 4 4 minecraft:brewing_stand");
            host.runOnServer(server -> {
                var level = server.overworld();
                var v = level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                v.setVillagerData(v.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.resources.Identifier.parse("minecraft:cleric")))));
                v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                        net.minecraft.core.GlobalPos.of(level.dimension(), new net.minecraft.core.BlockPos(2, 4, 4)));
                var inventory = VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID());
                for (var stack : inventory.snapshot()) if (!stack.isEmpty()) inventory.takeExactMatchingItem(stack).orElseThrow();
                inventory.insertExact(new ItemStack(Items.GLOWSTONE_DUST, 2));
                VillagerNutrition.setFoodLevel(v, 20);
            });
            context.waitTicks(240);
            expectDust(context, 2);
            context.takeScreenshot("totem-villagers-missing-material");
            host.runOnServer(server -> {
                var v = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID()).insertExact(new ItemStack(Items.GLOWSTONE_DUST));
            });
            context.waitTicks(40);
            expectDust(context, 1);
            host.runOnServer(server -> {
                var v = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID()).insertExact(new ItemStack(Items.GLOWSTONE_DUST, 48));
            });
            context.waitTicks(40);
            context.runOnClient(client -> {
                var v = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                if (VillagerHeadStatus.current(v) == VillagerHeadStatus.Kind.MATERIAL)
                    throw new AssertionError("Workshop still reported a supplied ingredient");
            });
            host.runCommand("setblock 2 4 4 minecraft:fletching_table");
            host.runOnServer(server -> {
                var level = server.overworld();
                var v = level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                v.setVillagerData(v.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.resources.Identifier.parse("minecraft:fletcher")))));
                v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE,
                        net.minecraft.core.GlobalPos.of(level.dimension(), new net.minecraft.core.BlockPos(2, 4, 4)));
                var offers = v.getOffers();
                offers.clear();
                offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, 5),
                        net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.TIPPED_ARROW, net.minecraft.world.item.alchemy.Potions.STRONG_SWIFTNESS)
                                .copyWithCount(5), 12, 1, .2F));
                var stock = VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID());
                stock.insertExact(new ItemStack(Items.ARROW, 32));
                stock.insertExact(net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.LINGERING_POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS));
                VillagerNutrition.setFoodLevel(v, 20);
            });
            context.waitTicks(250);
            context.runOnClient(client -> {
                var v = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                client.player.lookAt(EntityAnchorArgument.Anchor.EYES, v.getEyePosition());
                var display = VillagerHeadStatus.display(v);
                var label = label(client, v);
                if (display == null || !display.potion().equals(java.util.Optional.of("minecraft:strong_swiftness"))
                        || label == null || !label.getString().contains("II"))
                    throw new AssertionError("Missing strong swiftness label: " + display + " / " + label);
            });
            context.takeScreenshot("totem-villagers-missing-potion");
            host.runOnServer(server -> {
                var v = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID()).insertExact(
                        net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.LINGERING_POTION,
                                net.minecraft.world.item.alchemy.Potions.STRONG_SWIFTNESS).copyWithCount(1));
            });
            context.waitTicks(40);
            context.runOnClient(client -> {
                var v = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                var display = VillagerHeadStatus.display(v);
                if (display != null && display.potion().isPresent()) throw new AssertionError("Supplied potion still requested");
            });
            host.runOnServer(server -> {
                var level = server.overworld();
                var v = level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                v.setVillagerData(v.getVillagerData().withProfession(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(
                        net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getValue(net.minecraft.resources.Identifier.parse("totem:miner")))));
                v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
                VillagerNutrition.setFoodLevel(v, 20);
            });
            context.waitTicks(240);
            expectStatus(context, VillagerHeadStatus.Kind.MINE_ZONE);
            host.runCommand("setblock 2 4 4 minecraft:air");
            host.runOnServer(server -> {
                var level = server.overworld();
                var v = level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
                var jobs = dev.totem.villagers.worker.WorkerAssignmentSavedData.forServer(server);
                var zone = jobs.createZone("totem:miner", new dev.totem.villagers.worker.WorkZone(java.util.UUID.randomUUID(),
                        level.dimension().identifier().toString(), new dev.totem.villagers.worker.BlockCoordinate(1,4,4),
                        new dev.totem.villagers.worker.BlockCoordinate(2,4,4)));
                jobs.putAssignment(new dev.totem.villagers.worker.WorkerAssignment(v.getUUID(),"totem:miner",java.util.Optional.of(zone.id()),java.util.Optional.empty()));
                var stock = VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID());
                for (var item : stock.snapshot()) if (!item.isEmpty()) stock.takeExactMatchingItem(item).orElseThrow();
            });
            context.waitTicks(110);
            expectStatus(context, VillagerHeadStatus.Kind.FURNACE);
            host.runCommand("setblock 2 4 4 minecraft:furnace");
            host.runCommand("setblock 1 4 4 minecraft:diamond_ore");
            context.waitTicks(110);
            expectStatus(context, VillagerHeadStatus.Kind.PICKAXE);
            host.runOnServer(server -> {
                var v = server.overworld().getEntitiesOfClass(Villager.class, STAGE).getFirst();
                VillagerWorkInventorySavedData.forServer(server).inventory(v.getUUID()).insertExact(new ItemStack(Items.STONE_PICKAXE));
            });
            context.waitTicks(110);
            expectStatus(context, VillagerHeadStatus.Kind.STRONG_PICKAXE);
            host.runCommand("setblock 1 4 4 minecraft:air");
            context.waitTicks(110);
            expectStatus(context, VillagerHeadStatus.Kind.NO_MINE_FACE);
        }
    }

    private static void expectDust(ClientGameTestContext context, int count) {
        context.runOnClient(client -> {
            var v = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, v.getEyePosition());
            var actual = label(client, v);
            var expected = net.minecraft.network.chat.Component.translatable(VillagerHeadStatus.Kind.MATERIAL.translation(),
                    count, new ItemStack(Items.GLOWSTONE_DUST).getHoverName()).getString();
            if (actual == null || !actual.getString().contains(expected))
                throw new AssertionError("Expected " + expected + ", got " + actual + ", synced=" + VillagerHeadStatus.display(v));
        });
    }

    private static void expectStatus(ClientGameTestContext context, VillagerHeadStatus.Kind expected) {
        context.runOnClient(client -> {
            var resident = client.level.getEntitiesOfClass(Villager.class, STAGE).getFirst();
            client.player.lookAt(EntityAnchorArgument.Anchor.EYES, resident.getEyePosition());
            var actual = label(client, resident);
            var message = net.minecraft.network.chat.Component.translatable(expected.translation()).getString();
            if (VillagerHeadStatus.current(resident) != expected || actual == null || !actual.getString().contains(message))
                throw new AssertionError("Expected " + expected + " label, got " + actual);
        });
    }

    private static net.minecraft.network.chat.Component label(net.minecraft.client.Minecraft client, Villager resident) {
        var renderState = client.getEntityRenderDispatcher().getRenderer(resident).createRenderState(resident, 1.0F);
        return ((dev.totem.villagers.client.render.VillagerWorkAnimationRenderState)renderState).totemVillagers$status();
    }
}
