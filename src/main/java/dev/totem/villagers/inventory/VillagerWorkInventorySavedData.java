package dev.totem.villagers.inventory;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Durable, isolated material inventory for each managed villager. */
public final class VillagerWorkInventorySavedData extends SavedData {
    public static final int SLOT_COUNT = 27;
    private static final int DATA_VERSION = 1;
    private static final Codec<InventoryEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(InventoryEntry::villagerId),
            ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("slots", List.of()).forGetter(InventoryEntry::slots)
    ).apply(instance, InventoryEntry::new));
    public static final Codec<VillagerWorkInventorySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(VillagerWorkInventorySavedData::dataVersion),
            ENTRY_CODEC.listOf().optionalFieldOf("inventories", List.of()).forGetter(VillagerWorkInventorySavedData::entries)
    ).apply(instance, VillagerWorkInventorySavedData::new));
    public static final SavedDataType<VillagerWorkInventorySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_work_inventories"),
            VillagerWorkInventorySavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, List<ItemStack>> inventories = new LinkedHashMap<>();

    public VillagerWorkInventorySavedData() {
        this(DATA_VERSION, List.of());
    }

    private VillagerWorkInventorySavedData(int dataVersion, List<InventoryEntry> entries) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        for (InventoryEntry entry : entries) {
            inventories.putIfAbsent(entry.villagerId(), normalise(entry.slots()));
        }
    }

    public static VillagerWorkInventorySavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Returns an adapter that mutates only this villager's 27 protected slots. */
    public VillagerWorkInventory inventory(UUID villagerId) {
        return new VillagerWorkInventory(this, villagerId);
    }

    synchronized List<ItemStack> copySlots(UUID villagerId) {
        return copy(inventories.getOrDefault(villagerId, emptySlots()));
    }

    synchronized void replaceSlots(UUID villagerId, List<ItemStack> slots) {
        List<ItemStack> next = normalise(slots);
        List<ItemStack> previous = inventories.get(villagerId);
        if (sameSlots(previous, next)) {
            return;
        }
        if (next.stream().allMatch(ItemStack::isEmpty)) {
            inventories.remove(villagerId);
        } else {
            inventories.put(villagerId, next);
        }
        setDirty();
    }

    /** Removes and returns every protected stack when a villager dies. */
    public synchronized List<ItemStack> drain(UUID villagerId) {
        List<ItemStack> removed = inventories.remove(villagerId);
        if (removed == null) {
            return List.of();
        }
        setDirty();
        return removed.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
    }

    public synchronized List<ItemStack> snapshot(UUID villagerId) {
        return copySlots(villagerId);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<InventoryEntry> entries() {
        return inventories.entrySet().stream()
                .map(entry -> new InventoryEntry(entry.getKey(), copy(entry.getValue())))
                .toList();
    }

    private static List<ItemStack> normalise(List<ItemStack> supplied) {
        List<ItemStack> result = emptySlots();
        if (supplied != null) {
            for (int index = 0; index < Math.min(SLOT_COUNT, supplied.size()); index++) {
                ItemStack stack = supplied.get(index);
                result.set(index, stack == null ? ItemStack.EMPTY : stack.copy());
            }
        }
        return result;
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> result = new ArrayList<>(SLOT_COUNT);
        for (int index = 0; index < SLOT_COUNT; index++) {
            result.add(ItemStack.EMPTY);
        }
        return result;
    }

    private static List<ItemStack> copy(List<ItemStack> slots) {
        return slots.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean sameSlots(List<ItemStack> first, List<ItemStack> second) {
        if (first == null || first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!ItemStack.matches(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private record InventoryEntry(UUID villagerId, List<ItemStack> slots) {
        private InventoryEntry {
            Objects.requireNonNull(villagerId, "villagerId");
            slots = copy(slots == null ? List.of() : slots);
        }
    }
}
