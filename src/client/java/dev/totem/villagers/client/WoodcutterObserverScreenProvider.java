package dev.totem.villagers.client;

import dev.totem.core.api.v1.client.observer.ObserverRemoteCursor;
import dev.totem.core.api.v1.client.observer.ObserverScreenContext;
import dev.totem.core.api.v1.client.observer.ObserverScreenHandle;
import dev.totem.core.api.v1.client.observer.ObserverScreenProvider;
import dev.totem.core.api.v1.client.observer.ObserverScreenSnapshot;
import dev.totem.villagers.woodcutter.WoodcutterMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.Set;
import java.util.Optional;
import java.util.Map;

/** Villagers-owned production Woodcutter screen Observer factory. */
public final class WoodcutterObserverScreenProvider implements ObserverScreenProvider {
    @Override public String familyId() { return "villagers_woodcutter"; }
    @Override public int protocolVersion() { return 1; }
    @Override public Set<String> variants() { return Set.of(""); }

    @Override public Optional<ObserverScreenSnapshot> capture(Screen candidate, long sequence) {
        if (!(candidate instanceof WoodcutterScreen screen) || screen.totem$isObserverReadOnly()) return Optional.empty();
        WoodcutterMenu menu = screen.getMenu();
        return Optional.of(new ObserverScreenSnapshot(familyId(), "", protocolVersion(), sequence,
                screen.getTitle(), menu.getItems(), new int[]{menu.selectedRecipeIndex(), menu.recipeCount(),
                menu.requiredInputCount()}, Map.of(), new byte[0]));
    }

    @Override public ObserverScreenHandle create(ObserverScreenContext context, ObserverScreenSnapshot snapshot) {
        if (!supports(snapshot)) throw new IllegalArgumentException("Incompatible Woodcutter Observer snapshot");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) throw new IllegalStateException("Observer player is unavailable");
        Inventory detachedInventory = new Inventory(minecraft.player, new EntityEquipment());
        WoodcutterMenu menu = new WoodcutterMenu(-1, detachedInventory);
        WoodcutterScreen screen = new WoodcutterScreen(menu, detachedInventory, snapshot.title(),
                true, context.stopObserving());
        return new Handle(screen, menu, snapshot);
    }

    private final class Handle implements ObserverScreenHandle {
        private final WoodcutterScreen screen; private final WoodcutterMenu menu; private long sequence = -1;
        private long cursorSequence = -1;
        private Handle(WoodcutterScreen screen, WoodcutterMenu menu, ObserverScreenSnapshot initial) {
            this.screen = screen; this.menu = menu; applySnapshot(initial);
        }
        @Override public Screen screen() { return screen; }
        @Override public void applySnapshot(ObserverScreenSnapshot snapshot) {
            if (!WoodcutterObserverScreenProvider.this.supports(snapshot)
                    || snapshot.sequence() <= sequence) return;
            var items = new ArrayList<ItemStack>(menu.slots.size());
            var remoteSlots = snapshot.slots();
            for (int i = 0; i < menu.slots.size(); i++)
                items.add(i < remoteSlots.size() ? remoteSlots.get(i).copy() : ItemStack.EMPTY);
            menu.initializeContents((int)Math.min(Integer.MAX_VALUE, snapshot.sequence()), items, menu.getCarried());
            int[] data = snapshot.data();
            for (int i = 0; i < Math.min(3, data.length); i++) menu.setData(i, data[i]);
            sequence = snapshot.sequence();
        }
        @Override public void applyCursor(ObserverRemoteCursor cursor) {
            if (cursor.sequence() <= cursorSequence) return;
            cursorSequence = cursor.sequence();
            menu.setCarried(cursor.carriedStack());
        }
    }
}
