package dev.totem.villagers.client;

import dev.totem.villagers.runtime.VillageBellSchedule;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

public final class VillageBellScheduleClientGameTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var host = world.getServer();
            host.runCommand("fill -3 3 -3 3 3 8 minecraft:grass_block");
            host.runCommand("setblock 0 4 5 minecraft:bell");
            host.runCommand("time set 3000");
            host.runCommand("tp @a 0.5 4 0.5 0 12");
            world.getConnection().waitForChunksRender();
            context.waitTicks(30);
            expect(context, "work");
            host.runCommand("time set 9500");
            context.waitTicks(30);
            expect(context, "meet");
            context.takeScreenshot("village-bell-schedule");
            host.runCommand("tp @a 0.5 4 0.5 180 0");
            context.waitTicks(30);
            expect(context, null);
        }
    }

    private static void expect(ClientGameTestContext context, String expected) {
        context.runOnClient(client -> {
            var label = VillageBellSchedule.current(client.player);
            if (!java.util.Objects.equals(expected, label == null ? null : label.activity()))
                throw new AssertionError("Expected " + expected + " at the bell, got " + label);
        });
    }
}
