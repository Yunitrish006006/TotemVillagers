package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * World-level durable store for merchant stock and scheduling state.  Keeping it in
 * SavedData avoids silently granting stock when vanilla serialisation changes and
 * lets a missing/unloaded villager retain no live scheduled task.
 */
public final class VillagerWorkSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<VillagerWorkSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(VillagerWorkSavedData::dataVersion),
            VillagerWorkState.CODEC.listOf().optionalFieldOf("villagers", List.of()).forGetter(VillagerWorkSavedData::stateList)
    ).apply(instance, VillagerWorkSavedData::new));
    public static final SavedDataType<VillagerWorkSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "villager_work"),
            VillagerWorkSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, VillagerWorkState> states = new LinkedHashMap<>();

    public VillagerWorkSavedData() {
        this(DATA_VERSION, List.of());
    }

    private VillagerWorkSavedData(int dataVersion, List<VillagerWorkState> persistedStates) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        for (VillagerWorkState state : persistedStates) {
            states.putIfAbsent(state.villagerId(), state);
        }
    }

    public static VillagerWorkSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized Optional<VillagerWorkState> get(UUID villagerId) {
        return Optional.ofNullable(states.get(villagerId));
    }

    /** Existing villagers begin with empty stock; the module never imports free vanilla stock. */
    public synchronized VillagerWorkState getOrCreate(UUID villagerId) {
        return states.computeIfAbsent(villagerId, id -> {
            VillagerWorkState created = VillagerWorkState.empty(id);
            setDirty();
            return created;
        });
    }

    public synchronized void put(VillagerWorkState state) {
        Objects.requireNonNull(state, "state");
        VillagerWorkState previous = states.put(state.villagerId(), state);
        if (!state.equals(previous)) {
            setDirty();
        }
    }

    /** Called when an entity is permanently discarded, never merely because its chunk unloaded. */
    public synchronized void remove(UUID villagerId) {
        if (states.remove(villagerId) != null) {
            setDirty();
        }
    }

    public synchronized Map<UUID, VillagerWorkState> snapshot() {
        return Map.copyOf(states);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<VillagerWorkState> stateList() {
        return List.copyOf(new ArrayList<>(states.values()));
    }
}
