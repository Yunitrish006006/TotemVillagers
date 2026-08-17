package dev.totem.villagers.world.ore;

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

/** Persistent drought protection for the iron needed by renewable shears. */
public final class MinerOreSafetySavedData extends SavedData {
    /** Fifteen misses are allowed; an eligible sixteenth mine uses the live iron rule. */
    public static final int MAX_CONSECUTIVE_NON_IRON_MINES = 15;
    private static final Codec<OreSafetyEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(OreSafetyEntry::villager),
            Codec.INT.fieldOf("non_iron_mines").forGetter(OreSafetyEntry::nonIronMines)
    ).apply(instance, OreSafetyEntry::new));
    private static final Codec<MinerOreSafetySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ENTRY_CODEC.listOf().optionalFieldOf("miners", List.of()).forGetter(MinerOreSafetySavedData::entries)
    ).apply(instance, MinerOreSafetySavedData::new));
    public static final SavedDataType<MinerOreSafetySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "miner_ore_safety"),
            MinerOreSafetySavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<UUID, Integer> nonIronMines = new LinkedHashMap<>();

    public MinerOreSafetySavedData() {
    }

    private MinerOreSafetySavedData(List<OreSafetyEntry> entries) {
        entries.forEach(entry -> {
            int safe = Math.max(0, Math.min(MAX_CONSECUTIVE_NON_IRON_MINES, entry.nonIronMines()));
            if (safe > 0) {
                nonIronMines.putIfAbsent(entry.villager(), safe);
            }
        });
    }

    public static MinerOreSafetySavedData forServer(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized int consecutiveNonIronMines(UUID miner) {
        return nonIronMines.getOrDefault(miner, 0);
    }

    public synchronized boolean requiresSafetyIron(UUID miner) {
        return consecutiveNonIronMines(miner) >= MAX_CONSECUTIVE_NON_IRON_MINES;
    }

    /** Records only a successfully committed base-block mine. */
    public synchronized void recordMine(UUID miner, boolean producedIron) {
        if (producedIron) {
            if (nonIronMines.remove(miner) != null) {
                setDirty();
            }
            return;
        }
        int current = consecutiveNonIronMines(miner);
        int next = Math.min(MAX_CONSECUTIVE_NON_IRON_MINES, current + 1);
        if (next != current) {
            nonIronMines.put(miner, next);
            setDirty();
        }
    }

    public synchronized void remove(UUID miner) {
        if (nonIronMines.remove(miner) != null) {
            setDirty();
        }
    }

    private synchronized List<OreSafetyEntry> entries() {
        return nonIronMines.entrySet().stream()
                .map(entry -> new OreSafetyEntry(entry.getKey(), entry.getValue())).toList();
    }

    private record OreSafetyEntry(UUID villager, int nonIronMines) {
    }
}
