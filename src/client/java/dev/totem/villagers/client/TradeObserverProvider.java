package dev.totem.villagers.client;

import dev.totem.core.api.v1.client.observer.*;
import dev.totem.villagers.network.TradeSnapshotPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.input.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.*;

import java.util.*;

public final class TradeObserverProvider implements ObserverScreenProvider, java.util.function.Predicate<Screen> {
    public String familyId() { return "villagers_trade"; }
    public int protocolVersion() { return 1; }
    public Set<String> variants() { return Set.of("merchant"); }
    public boolean test(Screen screen) {
        return screen instanceof MerchantScreen merchant && !(screen instanceof ObserverReadOnlyScreen)
                && TradeSnapshotClient.snapshot(merchant.getMenu().containerId).isPresent();
    }
    public Optional<ObserverScreenSnapshot> capture(Screen screen, long sequence) {
        if (!test(screen)) return Optional.empty();
        var menu = ((MerchantScreen)screen).getMenu();
        var selection = (TradeViewSelection)screen;
        var diagnostics = TradeSnapshotClient.snapshot(menu.containerId).orElseThrow();
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            int count = Math.min(menu.getOffers().size(), 64);
            bytes.writeVarInt(count);
            for (var offer : menu.getOffers().subList(0, count)) {
                for (var stack : List.of(offer.getCostA(), offer.getCostB(), offer.getResult())) {
                    bytes.writeVarInt(BuiltInRegistries.ITEM.getId(stack.getItem()));
                    bytes.writeVarInt(stack.getCount());
                }
                bytes.writeVarInt(offer.getUses());
                bytes.writeVarInt(offer.getMaxUses());
                bytes.writeVarInt(offer.getXp());
            }
            TradeSnapshotPayload.CODEC.encode(bytes, diagnostics);
            if (bytes.readableBytes() > ObserverScreenSnapshot.MAX_OWNER_PAYLOAD) return Optional.empty();
            byte[] encoded = new byte[bytes.readableBytes()];
            bytes.readBytes(encoded);
            return Optional.of(new ObserverScreenSnapshot(familyId(), "merchant", 1, sequence,
                    Component.translatable("entity.minecraft.villager"), menu.slots.stream().limit(39).map(slot -> slot.getItem())
                    .map(stack -> stack.isEmpty() ? ItemStack.EMPTY : new ItemStack(stack.getItem(), stack.getCount())).toList(),
                    new int[]{selection.selectedTrade(), selection.scrollOffset(), menu.getTraderLevel(), menu.getTraderXp(),
                            menu.showProgressBar() ? 1 : 0, menu.canRestock() ? 1 : 0, selection.detailsOpen() ? 1 : 0}, Map.of(), encoded));
        } finally { bytes.release(); }
    }
    public ObserverScreenHandle create(ObserverScreenContext context, ObserverScreenSnapshot initial) {
        if (!supports(initial)) throw new IllegalArgumentException("Unexpected merchant snapshot");
        var inventory = new Inventory(Minecraft.getInstance().player, new EntityEquipment());
        var screen = new ReadOnly(new MerchantMenu(-1, inventory), inventory, initial.title(), context.stopObserving());
        var handle = new ObserverScreenHandle() {
            long last = -1, lastCursor = -1;
            public Screen screen() { return screen; }
            public void applySnapshot(ObserverScreenSnapshot state) {
                if (!supports(state) || state.sequence() <= last) return;
                var menu = screen.getMenu();
                var data = state.data();
                if (data.length != 7 || state.slots().size() != Math.min(39, menu.slots.size())) return;
                var bytes = new FriendlyByteBuf(Unpooled.wrappedBuffer(state.ownerPayload()));
                try {
                    int count = bytes.readVarInt();
                    if (count < 0 || count > 64) return;
                    var offers = new MerchantOffers();
                    for (int i = 0; i < count; i++) {
                        var first = item(bytes);
                        var second = item(bytes);
                        var result = item(bytes);
                        offers.add(new MerchantOffer(new ItemCost(first.getItem(), first.getCount()),
                                second.isEmpty() ? Optional.empty() : Optional.of(new ItemCost(second.getItem(), second.getCount())),
                                result, bytes.readVarInt(), bytes.readVarInt(), bytes.readVarInt(), 0f));
                    }
                    var feedback = TradeSnapshotPayload.CODEC.decode(bytes);
                    menu.setOffers(offers);
                    menu.setMerchantLevel(data[2]); menu.setXp(data[3]);
                    menu.setShowProgressBar(data[4] != 0); menu.setCanRestock(data[5] != 0);
                    menu.setSelectionHint(data[0]);
                    ((TradeViewSelection)(Object)screen).setTradeView(data[0], data[1], data[6] != 0);
                    var visible = state.slots();
                    for (int i = 0; i < visible.size(); i++) menu.getSlot(i).set(visible.get(i));
                    TradeSnapshotClient.acceptProjection(menu.containerId, feedback);
                    last = state.sequence();
                } finally { bytes.release(); }
            }
            public void applyCursor(ObserverRemoteCursor cursor) {
                if (cursor.sequence() <= lastCursor) return;
                screen.getMenu().setCarried(cursor.carriedStack());
                lastCursor = cursor.sequence();
            }
        };
        handle.applySnapshot(initial);
        return handle;
    }
    private static ItemStack item(FriendlyByteBuf bytes) {
        return new ItemStack(BuiltInRegistries.ITEM.byId(bytes.readVarInt()), bytes.readVarInt());
    }

    private static final class ReadOnly extends MerchantScreen implements ObserverReadOnlyScreen {
        private final Runnable stop;
        ReadOnly(MerchantMenu menu, Inventory inv, Component title, Runnable stop) {
            super(menu, inv, title); this.stop = stop;
        }
        public boolean totem$isObserverReadOnly() { return true; }
        public boolean mouseClicked(MouseButtonEvent e, boolean doubled) { return true; }
        public boolean mouseDragged(MouseButtonEvent e, double x, double y) { return true; }
        public boolean mouseReleased(MouseButtonEvent e) { return true; }
        public boolean mouseScrolled(double x, double y, double h, double v) { return true; }
        public boolean keyPressed(KeyEvent e) { if (e.key() == 256) onClose(); return true; }
        public boolean charTyped(CharacterEvent e) { return true; }
        public boolean preeditUpdated(PreeditEvent e) { return true; }
        public void onClose() { stop.run(); }
    }
}
