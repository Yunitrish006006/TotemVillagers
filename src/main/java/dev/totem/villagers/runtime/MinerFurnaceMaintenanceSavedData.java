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

/** Persistent charcoal-batch wear for each Miner's linked Furnace. */
public final class MinerFurnaceMaintenanceSavedData extends SavedData {
    public static final int BATCHES_PER_REPLACEMENT = 8;
    private static final Codec<MaintenanceEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(MaintenanceEntry::villager),
            Codec.INT.fieldOf("completed_batches").forGetter(MaintenanceEntry::completedBatches)
    ).apply(instance, MaintenanceEntry::new));
    private static final Codec<MinerFurnaceMaintenanceSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRY_CODEC.listOf().optionalFieldOf("miners", List.of()).forGetter(MinerFurnaceMaintenanceSavedData::entries)
    ).apply(instance, MinerFurnaceMaintenanceSavedData::new));
    public static final SavedDataType<MinerFurnaceMaintenanceSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "miner_furnace_maintenance"),
            MinerFurnaceMaintenanceSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Integer> completed = new LinkedHashMap<>();

    public MinerFurnaceMaintenanceSavedData() {
    }

    private MinerFurnaceMaintenanceSavedData(List<MaintenanceEntry> entries) {
        entries.forEach(entry -> {
            int safe = Math.max(0, Math.min(BATCHES_PER_REPLACEMENT, entry.completedBatches()));
            if (safe > 0) {
                completed.putIfAbsent(entry.villager(), safe);
            }
        });
    }

    public static MinerFurnaceMaintenanceSavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized int completedBatches(UUID miner) {
        return completed.getOrDefault(miner, 0);
    }

    public synchronized boolean requiresReplacement(UUID miner) {
        return completedBatches(miner) >= BATCHES_PER_REPLACEMENT;
    }

    public synchronized void recordBatch(UUID miner) {
        completed.put(miner, Math.min(BATCHES_PER_REPLACEMENT, completedBatches(miner) + 1));
        setDirty();
    }

    public synchronized void recordReplacement(UUID miner) {
        if (completed.remove(miner) != null) {
            setDirty();
        }
    }

    public synchronized void remove(UUID miner) {
        if (completed.remove(miner) != null) {
            setDirty();
        }
    }

    private synchronized List<MaintenanceEntry> entries() {
        return completed.entrySet().stream()
                .map(entry -> new MaintenanceEntry(entry.getKey(), entry.getValue())).toList();
    }

    private record MaintenanceEntry(UUID villager, int completedBatches) {
    }
}
