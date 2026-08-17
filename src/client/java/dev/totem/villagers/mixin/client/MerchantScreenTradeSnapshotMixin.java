package dev.totem.villagers.mixin.client;

import dev.totem.villagers.client.TradeSnapshotClient;
import dev.totem.villagers.network.TradeSnapshotPayload;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.stream.Collectors;

/** Renders a compact, localised read-only work summary inside the vanilla trade screen. */
@Mixin(MerchantScreen.class)
abstract class MerchantScreenTradeSnapshotMixin extends AbstractContainerScreen<MerchantMenu> {
    private static final int PANEL_WIDTH = 164;
    private static final int PANEL_HEIGHT = 45;
    private static final int INVENTORY_PANEL_WIDTH = 170;
    private static final int INVENTORY_PANEL_BASE_HEIGHT = 91;
    private static final int SLOT_SIZE = 18;

    @Shadow
    private int shopItem;

    private MerchantScreenTradeSnapshotMixin() {
        super(null, null, null);
    }

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void totemVillagers$drawWorkSummary(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta,
                                                CallbackInfo callback) {
        TradeSnapshotClient.snapshot(getMenu().containerId).ifPresent(snapshot -> {
            TradeSnapshotClient.selectedOffer(getMenu().containerId, shopItem)
                    .ifPresent(offer -> drawWorkSummary(graphics, offer));
            drawPersonalInventory(graphics, snapshot, mouseX, mouseY);
        });
    }

    private void drawWorkSummary(GuiGraphicsExtractor graphics, TradeSnapshotPayload.Offer offer) {
        int panelX = leftPos + 102;
        int panelY = topPos + 98;
        graphics.fill(panelX - 3, panelY - 3, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xB0182028);

        Component stock = Component.translatable("gui.totem_villagers.trade.stock",
                offer.availableStock(), offer.requiredStock());
        graphics.text(font, trim(stock, PANEL_WIDTH - 3), panelX, panelY, stockColour(offer));

        Component activity = offer.totalWorkTicks() > 0
                ? Component.translatable("gui.totem_villagers.trade.work", source(offer.source()),
                        offer.progressTicks(), offer.totalWorkTicks())
                : Component.translatable("gui.totem_villagers.trade.work.unmapped");
        graphics.text(font, trim(activity, PANEL_WIDTH - 3), panelX, panelY + 10, 0xFFE0E0E0);

        Component status = offer.blockedReason().isBlank()
                ? Component.translatable("gui.totem_villagers.trade.ready")
                : Component.translatable("gui.totem_villagers.trade.blocked", blockedReason(offer.blockedReason()));
        graphics.text(font, trim(status, PANEL_WIDTH - 3), panelX, panelY + 20,
                offer.blockedReason().isBlank() ? 0xFF8CFF8C : 0xFFFFA0A0);

        if (!offer.recipeInputs().isEmpty()) {
            Component ingredients = Component.translatable("gui.totem_villagers.trade.ingredients",
                    offer.recipeInputs().stream()
                            .map(input -> shortItemName(input.itemId()) + " ×" + input.count())
                            .collect(Collectors.joining(", ")));
            graphics.text(font, trim(ingredients, PANEL_WIDTH - 3), panelX, panelY + 30, 0xFFE0E0E0);
        }
    }

    private Component source(String id) {
        return id == null || id.isBlank()
                ? Component.translatable("gui.totem_villagers.trade.source.unknown")
                : Component.translatable("gui.totem_villagers.trade.source." + id);
    }

    private Component blockedReason(String code) {
        return Component.translatable("gui.totem_villagers.trade.reason." + code);
    }

    private Component trim(Component text, int maximumWidth) {
        return Component.literal(font.plainSubstrByWidth(text.getString(), maximumWidth));
    }

    private static int stockColour(TradeSnapshotPayload.Offer offer) {
        return offer.availableStock() >= offer.requiredStock() ? 0xFF8CFF8C : 0xFFFFD080;
    }

    private void drawPersonalInventory(GuiGraphicsExtractor graphics, TradeSnapshotPayload snapshot, int mouseX, int mouseY) {
        int panelX = inventoryPanelX();
        int panelY = topPos + 8;
        graphics.fill(panelX - 3, panelY - 3, panelX + INVENTORY_PANEL_WIDTH,
                panelY + inventoryPanelHeight(snapshot), 0xC0182028);
        graphics.text(font, Component.translatable("gui.totem_villagers.work_inventory.title"), panelX, panelY, 0xFFE0E0E0);

        Map<Integer, TradeSnapshotPayload.WorkInventorySlot> slots = snapshot.workInventory().stream()
                .collect(Collectors.toMap(TradeSnapshotPayload.WorkInventorySlot::index, slot -> slot));
        TradeSnapshotPayload.WorkInventorySlot hovered = null;
        for (int index = 0; index < 27; index++) {
            int slotX = panelX + (index % 9) * SLOT_SIZE;
            int slotY = panelY + 12 + (index / 9) * SLOT_SIZE;
            TradeSnapshotPayload.WorkInventorySlot slot = slots.get(index);
            graphics.fill(slotX, slotY, slotX + 17, slotY + 17, slot == null ? 0xFF2C333A : 0xFF526A46);
            graphics.fill(slotX + 1, slotY + 1, slotX + 16, slotY + 16, slot == null ? 0xFF172027 : 0xFF263A22);
            if (slot != null) {
                ItemStack visualStack = visualStack(slot);
                graphics.item(visualStack, slotX + 1, slotY + 1);
                graphics.itemDecorations(font, visualStack, slotX + 1, slotY + 1);
                if (mouseX >= slotX && mouseX < slotX + 17 && mouseY >= slotY && mouseY < slotY + 17) {
                    hovered = slot;
                }
            }
        }

        Component reserved = snapshot.reservedMaterials().isEmpty()
                ? Component.translatable("gui.totem_villagers.work_inventory.reserved.none")
                : Component.translatable("gui.totem_villagers.work_inventory.reserved",
                        snapshot.reservedMaterials().stream()
                                .map(material -> shortItemName(material.itemId()) + " ×" + material.count())
                                .collect(Collectors.joining(", ")));
        graphics.text(font, trim(reserved, INVENTORY_PANEL_WIDTH - 3), panelX, panelY + 68, 0xFFFFD080);
        int nextLine = panelY + 79;
        if (snapshot.workZone().isPresent()) {
            drawWorkZoneStatus(graphics, snapshot.workZone().orElseThrow(), panelX, nextLine);
            nextLine += 22;
        }
        if (snapshot.guardPost().isPresent()) {
            drawGuardPostStatus(graphics, snapshot.guardPost().orElseThrow(), panelX, nextLine);
        }
        if (hovered != null) {
            Component detail = Component.translatable("gui.totem_villagers.work_inventory.slot", hovered.itemId(), hovered.count());
            graphics.setTooltipForNextFrame(font, detail, mouseX, mouseY);
        }
    }

    private void drawWorkZoneStatus(GuiGraphicsExtractor graphics, TradeSnapshotPayload.WorkZoneStatus status, int panelX, int panelY) {
        Component state = Component.translatable("gui.totem_villagers.work_zone.status." + status.state());
        Component summary = Component.translatable("gui.totem_villagers.work_zone.title", shortRoleName(status.roleId()), state);
        int colour = "inside".equals(status.state()) ? 0xFF8CFF8C : 0xFFFFD080;
        graphics.text(font, trim(summary, INVENTORY_PANEL_WIDTH - 3), panelX, panelY, colour);
        status.boundary().ifPresent(boundary -> {
            Component bounds = Component.translatable("gui.totem_villagers.work_zone.bounds", boundary.dimensionId(),
                    boundary.minimumX(), boundary.minimumY(), boundary.minimumZ(),
                    boundary.maximumX(), boundary.maximumY(), boundary.maximumZ());
            graphics.text(font, trim(bounds, INVENTORY_PANEL_WIDTH - 3), panelX, panelY + 10, 0xFFE0E0E0);
        });
    }

    private void drawGuardPostStatus(GuiGraphicsExtractor graphics, TradeSnapshotPayload.GuardPostStatus status,
                                     int panelX, int panelY) {
        Component state = Component.translatable("gui.totem_villagers.guard_post.status." + status.state());
        int colour = "defended".equals(status.state()) ? 0xFF8CFF8C
                : "constructing".equals(status.state()) ? 0xFFFFD080 : 0xFFFFA0A0;
        graphics.text(font, trim(Component.translatable("gui.totem_villagers.guard_post.title", state),
                INVENTORY_PANEL_WIDTH - 3), panelX, panelY, colour);
        Component demand = Component.translatable("gui.totem_villagers.guard_post.demand", status.managedGolems(),
                status.defenceDemand(), status.nearbyThreats());
        graphics.text(font, trim(demand, INVENTORY_PANEL_WIDTH - 3), panelX, panelY + 10, 0xFFE0E0E0);
        status.post().ifPresent(post -> {
            Component location = Component.translatable("gui.totem_villagers.guard_post.location", post.dimensionId(),
                    post.padX(), post.padY(), post.padZ());
            graphics.text(font, trim(location, INVENTORY_PANEL_WIDTH - 3), panelX, panelY + 20, 0xFFE0E0E0);
        });
        status.construction().ifPresent(progress -> {
            Component construction = progress.totalSteps() == 0
                    ? Component.translatable("gui.totem_villagers.guard_post.construction.unknown", progress.orderId(),
                    progress.placedSteps())
                    : Component.translatable("gui.totem_villagers.guard_post.construction", progress.orderId(),
                    progress.placedSteps(), progress.totalSteps());
            graphics.text(font, trim(construction, INVENTORY_PANEL_WIDTH - 3), panelX, panelY + 30, 0xFFFFD080);
        });
    }

    private static int inventoryPanelHeight(TradeSnapshotPayload snapshot) {
        int height = INVENTORY_PANEL_BASE_HEIGHT;
        if (snapshot.workZone().isPresent()) {
            height += 22;
        }
        if (snapshot.guardPost().isPresent()) {
            height += snapshot.guardPost().orElseThrow().construction().isPresent() ? 42 : 32;
        }
        return height;
    }

    /**
     * Uses the empty space to the right of the vanilla screen when available.
     * At the default GUI scale that space can be narrower than 170 pixels, so
     * retain the entire 27-slot panel inside the merchant screen instead of
     * drawing an unusable clipped panel off-screen.
     */
    private int inventoryPanelX() {
        int outsideScreen = leftPos + 280;
        if (outsideScreen + INVENTORY_PANEL_WIDTH <= width - 4) {
            return outsideScreen;
        }
        return Math.max(4, Math.min(width - INVENTORY_PANEL_WIDTH - 4, leftPos + 102));
    }

    private static String shortItemName(String itemId) {
        int namespace = itemId.indexOf(':');
        String path = namespace >= 0 ? itemId.substring(namespace + 1) : itemId;
        return path.replace('_', ' ');
    }

    private static String shortRoleName(String roleId) {
        int namespace = roleId.indexOf(':');
        String path = namespace >= 0 ? roleId.substring(namespace + 1) : roleId;
        return path.replace('_', ' ');
    }

    private static ItemStack visualStack(TradeSnapshotPayload.WorkInventorySlot slot) {
        Identifier id = Identifier.tryParse(slot.itemId());
        Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, slot.count());
    }
}
