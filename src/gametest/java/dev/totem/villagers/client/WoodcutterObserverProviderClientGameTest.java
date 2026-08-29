package dev.totem.villagers.client;

import dev.totem.core.api.v1.client.observer.ObserverRemoteCursor;
import dev.totem.core.api.v1.client.observer.ObserverScreenContext;
import dev.totem.core.api.v1.client.observer.ObserverScreenHandle;
import dev.totem.core.api.v1.client.observer.ObserverScreenProvider;
import dev.totem.core.api.v1.client.observer.ObserverScreenSnapshot;
import dev.totem.villagers.woodcutter.WoodcutterMenu;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Owner-local runtime proof for the production Woodcutter Observer screen. */
@SuppressWarnings("UnstableApiUsage")
public final class WoodcutterObserverProviderClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            context.getInput().resizeWindow(1280, 720);
            WoodcutterObserverScreenProvider provider = context.computeOnClient(client -> {
                boolean registered = FabricLoader.getInstance()
                        .getEntrypoints(ObserverScreenProvider.ENTRYPOINT, ObserverScreenProvider.class).stream()
                        .anyMatch(WoodcutterObserverScreenProvider.class::isInstance);
                if (!registered) throw new AssertionError("Villagers Observer provider entrypoint is missing");
                return new WoodcutterObserverScreenProvider();
            });
            ObserverScreenSnapshot initial = capture(context, provider, source(context, Items.OAK_LOG, 1, 1), 1);
            ObserverScreenSnapshot update = capture(context, provider, source(context, Items.SPRUCE_LOG, 4, 3), 2);
            AtomicInteger stops = new AtomicInteger();
            ObserverScreenHandle handle = context.computeOnClient(client -> provider.create(
                    new ObserverScreenContext(UUID.randomUUID(), "Target", stops::incrementAndGet), initial));
            context.runOnClient(client -> client.setScreenAndShow(handle.screen()));
            context.waitForScreen(WoodcutterScreen.class);
            context.runOnClient(client -> {
                WoodcutterScreen screen = (WoodcutterScreen) handle.screen();
                require(screen.totem$isObserverReadOnly(), "Woodcutter did not enter Observer mode");
                require(screen.getMenu().getSlot(0).getItem().is(Items.OAK_LOG),
                        "Initial Woodcutter snapshot was not applied");
                handle.applySnapshot(foreign(update, "villagers_woodcutter", "wrong", 1, 90));
                handle.applySnapshot(foreign(update, "villagers_woodcutter", "", 2, 91));
                handle.applySnapshot(foreign(update, "foreign", "", 1, 92));
                handle.applySnapshot(update);
                handle.applySnapshot(initial);
                require(screen.getMenu().getSlot(0).getItem().is(Items.SPRUCE_LOG)
                                && screen.getMenu().requiredInputCount() == 3,
                        "Exact monotonic Woodcutter snapshot policy failed");
                ItemStack carried = new ItemStack(Items.DIAMOND, 2);
                handle.applyCursor(new ObserverRemoteCursor(2, 88, 83, 176, 166, carried));
                handle.applyCursor(new ObserverRemoteCursor(1, 0, 0, 176, 166, ItemStack.EMPTY));
                require(ItemStack.matches(carried, screen.getMenu().getCarried()),
                        "Stale Woodcutter cursor replaced the carried stack");
                ObserverPacketProbe.reset();
                require(screen.mouseClicked(new MouseButtonEvent(1, 1,
                                new MouseButtonInfo(0, 0)), false), "Observer mouse input was not consumed");
                require(screen.keyPressed(new KeyEvent(65, 0, 0)),
                        "Observer keyboard input was not consumed");
                require(ObserverPacketProbe.sends() == 0, "Woodcutter Observer input attempted a packet");
            });
            context.waitTicks(2);
            context.takeScreenshot("villagers-observer-owner-production-screen");
            context.runOnClient(client -> {
                ObserverPacketProbe.reset();
                require(handle.screen().keyPressed(new KeyEvent(256, 0, 0)), "Escape was not consumed");
                require(stops.get() == 1, "Escape did not request stop-observing exactly once");
                require(ObserverPacketProbe.sends() == 0, "Closing Observer mode attempted a packet");
                client.setScreenAndShow(null);
            });
            context.waitForScreen(null);
        }
    }

    private static WoodcutterScreen source(ClientGameTestContext context, net.minecraft.world.item.Item item,
                                           int count, int requiredInput) {
        return context.computeOnClient(client -> {
            WoodcutterMenu menu = new WoodcutterMenu(count, client.player.getInventory());
            menu.getSlot(0).set(new ItemStack(item, count));
            menu.setData(0, 0);
            menu.setData(1, 4);
            menu.setData(2, requiredInput);
            return new WoodcutterScreen(menu, client.player.getInventory(), Component.literal("Woodcutter"));
        });
    }

    private static ObserverScreenSnapshot capture(ClientGameTestContext context,
                                                  ObserverScreenProvider provider,
                                                  WoodcutterScreen screen, long sequence) {
        return context.computeOnClient(client -> provider.capture(screen, sequence).orElseThrow());
    }

    private static ObserverScreenSnapshot foreign(ObserverScreenSnapshot source, String family,
                                                   String variant, int protocol, long sequence) {
        return new ObserverScreenSnapshot(family, variant, protocol, sequence, source.title(), source.slots(),
                source.data(), source.metadata(), source.ownerPayload());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
