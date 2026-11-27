package dev.totem.villagers.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.totem.villagers.network.TradeSnapshotPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.ClientSideMerchant;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * End-to-end client rendering check for the server-owned trade diagnostics
 * and the villager's 27-slot personal work inventory.
 */
@SuppressWarnings("UnstableApiUsage")
public final class TradeSnapshotClientGameTest implements FabricClientGameTest {
    private static final int CONTAINER_ID = 701;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            verifyLiveTradeSnapshot(context, singleplayer);
            context.setScreen(() -> {
                Minecraft client = Minecraft.getInstance();
                require(client.player != null, "Client test player was unavailable");

                TradeSnapshotPayload snapshot = snapshot();
                TradeSnapshotClient.accept(snapshot);
                require(TradeSnapshotClient.snapshot(CONTAINER_ID).equals(Optional.of(snapshot)),
                        "Client did not retain the server trade snapshot");
                require(TradeSnapshotClient.selectedOffer(CONTAINER_ID, 0).equals(Optional.of(snapshot.offers().getFirst())),
                        "Client did not select the snapshot offer for the open merchant menu");

                ClientSideMerchant merchant = new ClientSideMerchant(client.player);
                MerchantOffers offers = new MerchantOffers();
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 5), new ItemStack(Items.BREAD), 0, 12, 0.05F));
                merchant.overrideOffers(offers);
                MerchantMenu menu = new MerchantMenu(CONTAINER_ID, client.player.getInventory(), merchant);
                menu.setOffers(offers);
                menu.setSelectionHint(0);
                return new MerchantScreen(menu, client.player.getInventory(), Component.literal("Snapshot Villager"));
            });

            context.waitForScreen(MerchantScreen.class);
            context.computeOnClient(client -> {
                TradeSnapshotPayload snapshot = snapshot();
                require(TradeSnapshotClient.snapshot(CONTAINER_ID).equals(Optional.of(snapshot)),
                        "Client did not retain the server trade snapshot");
                require(TradeSnapshotClient.selectedOffer(CONTAINER_ID, 0).equals(Optional.of(snapshot.offers().getFirst())),
                        "Client did not select the snapshot offer for the open merchant menu");
                return null;
            });
            context.takeScreenshot("totem-villagers-trade-main");
            context.runOnClient(client -> {
                var screen = (MerchantScreen)client.gui.screen();
                var click = new net.minecraft.client.input.MouseButtonEvent((screen.width - 276) / 2 + 42,
                        (screen.height - 166) / 2 + 9, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                require(screen.mouseClicked(click, false) && ((TradeViewSelection)screen).detailsOpen(), "Info tab did not open");
            });
            context.waitTicks(3);
            Path screenshot = context.takeScreenshot("totem-villagers-trade-snapshot");
            GuiSize guiSize = context.computeOnClient(client -> new GuiSize(
                    client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()));
            assertPersonalInventoryPanelWasRendered(screenshot, guiSize);
            var stops = new java.util.concurrent.atomic.AtomicInteger();
            var handle = context.computeOnClient(client -> {
                var provider = net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getEntrypoints("totem:observer_screen_provider", dev.totem.core.api.v1.client.observer.ObserverScreenProvider.class)
                        .stream().filter(p -> p.familyId().equals("villagers_trade")).findFirst().orElseThrow();
                var original = provider.capture(client.gui.screen(), 1).orElseThrow();
                var projection = provider.create(new dev.totem.core.api.v1.client.observer.ObserverScreenContext(
                        UUID.randomUUID(), "Target", stops::incrementAndGet), original);
                require(TradeSnapshotClient.snapshot(-1).orElseThrow().home().equals(snapshot().home()), "Observed bed missing");
                var data = snapshot();
                TradeSnapshotClient.accept(new TradeSnapshotPayload(CONTAINER_ID, data.villagerId(), data.offers(),
                        data.workInventory(), data.reservedMaterials(), data.workZone(), data.guardPost(), Optional.empty()));
                projection.applySnapshot(provider.capture(client.gui.screen(), 2).orElseThrow());
                projection.applySnapshot(original);
                require(TradeSnapshotClient.snapshot(-1).orElseThrow().home().isEmpty(), "Older bed replaced released bed");
                return projection;
            });
            context.runOnClient(client -> {
                client.setScreenAndShow(handle.screen());
                var remote = (MerchantScreen)handle.screen();
                handle.applyCursor(new dev.totem.core.api.v1.client.observer.ObserverRemoteCursor(2, 24, 30, 276, 166, new ItemStack(Items.WHEAT)));
                handle.applyCursor(new dev.totem.core.api.v1.client.observer.ObserverRemoteCursor(1, 0, 0, 276, 166, ItemStack.EMPTY));
                require(remote.getMenu().getCarried().is(Items.WHEAT), "Stale cursor replaced carried item");
                ObserverPacketProbe.reset();
                boolean observingDetails = ((TradeViewSelection)remote).detailsOpen();
                require(remote.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(
                        (remote.width - 276) / 2 + 42, (remote.height - 166) / 2 + 9,
                        new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)
                        && ((TradeViewSelection)remote).detailsOpen() == observingDetails
                        && remote.keyPressed(new net.minecraft.client.input.KeyEvent(65, 0, 0))
                        && ObserverPacketProbe.sends() == 0, "Observer input generated a trade packet");
            });
            context.waitTicks(2);
            context.takeScreenshot("totem-villagers-trade-observed");
            context.runOnClient(client -> {
                handle.screen().onClose();
                require(stops.get() == 1, "Observer close failed");
                client.setScreenAndShow(null);
                require(TradeSnapshotClient.snapshot(-1).isEmpty(), "Closed observer retained trade state");
            });
        } finally {
            TradeSnapshotClient.forget(CONTAINER_ID);
            context.setScreen(() -> null);
        }
    }

    private static void verifyLiveTradeSnapshot(ClientGameTestContext context, TestSingleplayerContext world) {
        var expected = world.getServer().computeOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            dev.totem.villagers.config.WorkBackedTradingSettingsSavedData.forServer(server)
                    .setMode(dev.totem.villagers.config.WorkBackedTradingMode.DISABLED);
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            var level = server.overworld();
            var villager = (net.minecraft.world.entity.npc.villager.Villager)
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
                            net.minecraft.resources.Identifier.parse("minecraft:villager"))
                            .create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            villager.snapTo(player.getX() + 1, player.getY(), player.getZ());
            villager.setNoAi(true);
            villager.setNoGravity(true);
            var professions = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION;
            var fisherman = professions.getValue(net.minecraft.resources.Identifier.parse("minecraft:fisherman"));
            villager.setVillagerData(villager.getVillagerData().withProfession(professions.wrapAsHolder(fisherman)));
            level.addFreshEntity(villager);
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 12, 1, .05F));
            ((dev.totem.villagers.mixin.AbstractVillagerOffersAccessor)(Object)villager).totemVillagers$setExistingOffers(offers);
            require(!villager.getOffers().isEmpty(), "Fixture trade offer was removed");
            villager.mobInteract(player, net.minecraft.world.InteractionHand.MAIN_HAND);
            require(player.containerMenu instanceof MerchantMenu, "Fisherman did not start trading");
            return villager.getUUID();
        });
        context.waitFor(client -> client.gui.screen() instanceof MerchantScreen screen
                && TradeSnapshotClient.snapshot(screen.getMenu().containerId)
                        .filter(value -> expected.equals(value.villagerId())).isPresent());
        context.runOnClient(client -> {
            var screen = (MerchantScreen) client.gui.screen();
            var click = new net.minecraft.client.input.MouseButtonEvent((screen.width - 276) / 2 + 42,
                    (screen.height - 166) / 2 + 9, new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(click, false) && ((TradeViewSelection)screen).detailsOpen(),
                    "Info tab on a real server trade did not open");
            client.player.closeContainer();
        });
    }

    private static TradeSnapshotPayload snapshot() {
        return new TradeSnapshotPayload(
                CONTAINER_ID,
                UUID.fromString("7c361cd1-6db0-4401-876f-ae5e7eb5e5c9"),
                List.of(new TradeSnapshotPayload.Offer(0, "minecraft:bread", 2, 4,
                        "workshop", 30, 80, "inputs_unavailable",
                        List.of(new TradeSnapshotPayload.RecipeInput("minecraft:wheat", 3)))),
                List.of(
                        new TradeSnapshotPayload.WorkInventorySlot(0, "minecraft:wheat", 12),
                        new TradeSnapshotPayload.WorkInventorySlot(10, "minecraft:iron_ingot", 3),
                        new TradeSnapshotPayload.WorkInventorySlot(26, "minecraft:oak_log", 5)
                ),
                List.of(new TradeSnapshotPayload.ReservedMaterial("minecraft:iron_ingot", 3)), Optional.empty(), Optional.empty(),
                Optional.of(net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new net.minecraft.core.BlockPos(-34, 65, 108)))
        );
    }

    private static void assertPersonalInventoryPanelWasRendered(Path screenshot, GuiSize guiSize) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            require(image != null, "Client GameTest did not write a readable screenshot");
            int merchantLeft = (guiSize.width() - 276) / 2;
            int merchantTop = (guiSize.height() - 166) / 2;
            int panelX = merchantLeft + 8;
            // Use an intentionally empty cell near the end of the first row,
            // rather than the title text, so font anti-aliasing cannot affect
            // this visual presence check.
            int pixelX = (panelX + 149) * image.getWidth() / guiSize.width();
            int pixelY = (merchantTop + 39) * image.getHeight() / guiSize.height();
            int panelPixel = image.getRGB(pixelX, pixelY);
            int red = (panelPixel >>> 16) & 0xFF;
            int green = (panelPixel >>> 8) & 0xFF;
            int blue = panelPixel & 0xFF;
            require(red < 96 && green < 96 && blue < 96,
                    "Personal work-inventory panel was not rendered at its trade-screen position");
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect client GameTest screenshot", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record GuiSize(int width, int height) {
    }
}
