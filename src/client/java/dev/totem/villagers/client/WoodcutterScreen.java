package dev.totem.villagers.client;

import dev.totem.villagers.woodcutter.WoodcutterMenu;
import dev.totem.core.api.v1.client.observer.ObserverReadOnlyScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Compact, Stonecutter-inspired front end for the server-authoritative
 * Woodcutter menu. It renders only synchronised state; recipe buttons never
 * calculate or fabricate an output on the client.
 */
public final class WoodcutterScreen extends AbstractContainerScreen<WoodcutterMenu>
        implements ObserverReadOnlyScreen {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;
    private static final int PREVIOUS_X = 56;
    private static final int NEXT_X = 105;
    private static final int SELECTOR_Y = 27;
    private static final int SELECTION_STATUS_Y = 52;
    private final boolean observerReadOnly;
    private final Runnable observerStop;

    public WoodcutterScreen(WoodcutterMenu menu, Inventory inventory, Component title) {
        this(menu, inventory, title, false, () -> { });
    }

    public WoodcutterScreen(WoodcutterMenu menu, Inventory inventory, Component title,
                            boolean observerReadOnly, Runnable observerStop) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.observerReadOnly = observerReadOnly;
        this.observerStop = observerStop;
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        drawBackground(graphics);
        super.extractContents(graphics, mouseX, mouseY, tickDelta);
        drawSelector(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (observerReadOnly) return true;
        if (event.button() == 0 && menu.recipeCount() > 1) {
            int next = selectedRecipeAt(event.x(), event.y());
            if (next >= 0 && minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, next);
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return observerReadOnly || super.mouseDragged(event, dragX, dragY);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        return observerReadOnly || super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return observerReadOnly || super.mouseScrolled(x, y, horizontal, vertical);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!observerReadOnly) return super.keyPressed(event);
        if (event.key() == 256) onClose();
        return true;
    }
    @Override public boolean charTyped(CharacterEvent event) {
        return observerReadOnly || super.charTyped(event);
    }
    @Override public boolean preeditUpdated(PreeditEvent event) {
        return observerReadOnly || super.preeditUpdated(event);
    }
    @Override public void onClose() {
        if (observerReadOnly) observerStop.run(); else super.onClose();
    }
    @Override public boolean totem$isObserverReadOnly() { return observerReadOnly; }

    private void drawBackground(GuiGraphicsExtractor graphics) {
        graphics.fill(leftPos, topPos, leftPos + WIDTH, topPos + HEIGHT, 0xFFB9A47A);
        graphics.fill(leftPos + 3, topPos + 3, leftPos + WIDTH - 3, topPos + HEIGHT - 3, 0xFFE1D5B4);
        graphics.fill(leftPos + 45, topPos + 17, leftPos + 132, topPos + 62, 0xFFC3AF83);
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF715D41);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF4E4230);
        }
    }

    private void drawSelector(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int count = menu.recipeCount();
        if (count <= 0) {
            graphics.text(font, Component.translatable("gui.totem_villagers.woodcutter.no_recipe"),
                    leftPos + 51, topPos + 50, 0xFF6B5140);
            return;
        }
        int selected = menu.selectedRecipeIndex();
        Component status = Component.translatable("gui.totem_villagers.woodcutter.selection",
                selected + 1, count, menu.requiredInputCount());
        graphics.centeredText(font, status, leftPos + 88, topPos + SELECTION_STATUS_Y, 0xFF493825);
        drawSelectorButton(graphics, PREVIOUS_X, selected > 0, "‹", mouseX, mouseY);
        drawSelectorButton(graphics, NEXT_X, selected + 1 < count, "›", mouseX, mouseY);
    }

    private void drawSelectorButton(GuiGraphicsExtractor graphics, int x, boolean enabled, String label, int mouseX, int mouseY) {
        int buttonX = leftPos + x;
        int buttonY = topPos + SELECTOR_Y;
        boolean hovered = enabled && mouseX >= buttonX && mouseX < buttonX + 16
                && mouseY >= buttonY && mouseY < buttonY + 16;
        int border = enabled ? (hovered ? 0xFFFFE6A3 : 0xFF806643) : 0xFF8F8067;
        int fill = enabled ? (hovered ? 0xFF9E7B48 : 0xFF765E3F) : 0xFF9A8C72;
        graphics.fill(buttonX, buttonY, buttonX + 16, buttonY + 16, border);
        graphics.fill(buttonX + 1, buttonY + 1, buttonX + 15, buttonY + 15, fill);
        graphics.centeredText(font, label, buttonX + 8, buttonY + 4, enabled ? 0xFFFFFFFF : 0xFFB3A995);
    }

    private int selectedRecipeAt(double mouseX, double mouseY) {
        int selected = menu.selectedRecipeIndex();
        if (insideButton(mouseX, mouseY, PREVIOUS_X) && selected > 0) {
            return selected - 1;
        }
        if (insideButton(mouseX, mouseY, NEXT_X) && selected + 1 < menu.recipeCount()) {
            return selected + 1;
        }
        return -1;
    }

    private boolean insideButton(double mouseX, double mouseY, int x) {
        return mouseX >= leftPos + x && mouseX < leftPos + x + 16
                && mouseY >= topPos + SELECTOR_Y && mouseY < topPos + SELECTOR_Y + 16;
    }
}
