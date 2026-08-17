package dev.totem.villagers.runtime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent remaining Campfire cookings; one real coal or charcoal follows vanilla's eight-item fuel budget. */
public final class FishermanCampfireFuelSavedData extends SavedData {
    public static final int COOKINGS_PER_COAL = 8;
    private static final Codec<FuelEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(FuelEntry::villager),
            Codec.INT.fieldOf("remaining_cookings").forGetter(FuelEntry::remainingCookings)
    ).apply(instance, FuelEntry::new));
    private static final Codec<FishermanCampfireFuelSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRY_CODEC.listOf().optionalFieldOf("fishermen", List.of()).forGetter(FishermanCampfireFuelSavedData::entries)
    ).apply(instance, FishermanCampfireFuelSavedData::new));
    public static final SavedDataType<FishermanCampfireFuelSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "fisherman_campfire_fuel"),
            FishermanCampfireFuelSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Integer> remaining = new LinkedHashMap<>();

    public FishermanCampfireFuelSavedData() {
    }

    private FishermanCampfireFuelSavedData(List<FuelEntry> entries) {
        entries.forEach(entry -> {
            int safe = Math.max(0, Math.min(COOKINGS_PER_COAL - 1, entry.remainingCookings()));
            if (safe > 0) {
                remaining.putIfAbsent(entry.villager(), safe);
            }
        });
    }

    public static FishermanCampfireFuelSavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized int remainingCookings(UUID fisherman) {
        return remaining.getOrDefault(fisherman, 0);
    }

    /** Commits one cooked catch only after the inventory transaction containing any new coal succeeded. */
    public synchronized boolean consumeCooking(UUID fisherman, boolean consumedNewCoal) {
        int current = remainingCookings(fisherman);
        if (!consumedNewCoal && current < 1) {
            return false;
        }
        int next = consumedNewCoal ? COOKINGS_PER_COAL - 1 : current - 1;
        if (next > 0) {
            remaining.put(fisherman, next);
        } else {
            remaining.remove(fisherman);
        }
        setDirty();
        return true;
    }

    public synchronized void remove(UUID fisherman) {
        if (remaining.remove(fisherman) != null) {
            setDirty();
        }
    }

    private synchronized List<FuelEntry> entries() {
        return remaining.entrySet().stream().map(entry -> new FuelEntry(entry.getKey(), entry.getValue())).toList();
    }

    private record FuelEntry(UUID villager, int remainingCookings) {
    }
}
