package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Only entries in this store are eligible for Guard-owned golem accounting or spawn suppression. */
public final class ManagedVillageSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<ManagedVillageSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(ManagedVillageSavedData::dataVersion),
            ManagedVillageState.CODEC.listOf().optionalFieldOf("villages", List.of()).forGetter(ManagedVillageSavedData::villageList)
    ).apply(instance, ManagedVillageSavedData::new));
    public static final SavedDataType<ManagedVillageSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "managed_villages"),
            ManagedVillageSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, ManagedVillageState> villages = new LinkedHashMap<>();

    public ManagedVillageSavedData() {
        this(DATA_VERSION, List.of());
    }

    private ManagedVillageSavedData(int dataVersion, List<ManagedVillageState> persistedVillages) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedVillages.forEach(state -> villages.putIfAbsent(state.post().villageId(), state));
    }

    public static ManagedVillageSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized Optional<ManagedVillageState> get(UUID villageId) {
        return Optional.ofNullable(villages.get(villageId));
    }

    /** A Guard has at most one managed village, avoiding overlapping construction authority. */
    public synchronized Optional<ManagedVillageState> getByGuard(UUID guardVillagerId) {
        return villages.values().stream()
                .filter(state -> state.guardVillagerId().equals(guardVillagerId))
                .findFirst();
    }

    public synchronized boolean registerOrUpdate(ManagedVillageState state, UUID actorId) {
        Objects.requireNonNull(state, "state");
        if (!state.post().ownerId().equals(actorId)) {
            return false;
        }
        ManagedVillageState previous = villages.get(state.post().villageId());
        if (previous != null && !previous.post().ownerId().equals(actorId)) {
            return false;
        }
        if (!state.equals(previous)) {
            villages.put(state.post().villageId(), state);
            setDirty();
        }
        return true;
    }

    public synchronized boolean remove(UUID villageId, UUID actorId) {
        ManagedVillageState existing = villages.get(villageId);
        if (existing == null || !existing.post().ownerId().equals(actorId)) {
            return false;
        }
        villages.remove(villageId);
        setDirty();
        return true;
    }

    public synchronized Map<UUID, ManagedVillageState> snapshot() {
        return Map.copyOf(villages);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<ManagedVillageState> villageList() {
        return List.copyOf(villages.values());
    }
}
