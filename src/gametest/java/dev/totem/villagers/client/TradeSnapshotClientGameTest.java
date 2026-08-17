package dev.totem.villagers.client;

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
            singleplayer.getClientLevel().waitForChunksRender();
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
            context.waitTicks(3);
            Path screenshot = context.takeScreenshot("totem-villagers-trade-snapshot");
            GuiSize guiSize = context.computeOnClient(client -> new GuiSize(
                    client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()));
            assertPersonalInventoryPanelWasRendered(screenshot, guiSize);
        } finally {
            TradeSnapshotClient.forget(CONTAINER_ID);
            context.setScreen(() -> null);
        }
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
                List.of(new TradeSnapshotPayload.ReservedMaterial("minecraft:iron_ingot", 3))
        );
    }

    private static void assertPersonalInventoryPanelWasRendered(Path screenshot, GuiSize guiSize) {
        try {
            BufferedImage image = ImageIO.read(screenshot.toFile());
            require(image != null, "Client GameTest did not write a readable screenshot");
            int merchantLeft = (guiSize.width() - 276) / 2;
            int merchantTop = (guiSize.height() - 166) / 2;
            int outsidePanel = merchantLeft + 280;
            int panelX = outsidePanel + 170 <= guiSize.width() - 4
                    ? outsidePanel
                    : Math.max(4, Math.min(guiSize.width() - 170 - 4, merchantLeft + 102));
            // Use an intentionally empty cell near the end of the first row,
            // rather than the title text, so font anti-aliasing cannot affect
            // this visual presence check.
            int pixelX = (panelX + 149) * image.getWidth() / guiSize.width();
            int pixelY = (merchantTop + 25) * image.getHeight() / guiSize.height();
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
