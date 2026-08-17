package dev.totem.villagers.worldgen;

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

/** One-time bootstrap ledger for proven world-generated vanilla villages. */
public final class GeneratedVillageSavedData extends SavedData {
    public static final int DATA_VERSION = 5;
    public static final Codec<GeneratedVillageSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(GeneratedVillageSavedData::dataVersion),
            GeneratedVillageState.CODEC.listOf().optionalFieldOf("villages", List.of()).forGetter(GeneratedVillageSavedData::villageList)
    ).apply(instance, GeneratedVillageSavedData::new));
    public static final SavedDataType<GeneratedVillageSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "generated_village_bootstrap"),
            GeneratedVillageSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<String, GeneratedVillageState> villages = new LinkedHashMap<>();

    public GeneratedVillageSavedData() {
        this(DATA_VERSION, List.of());
    }

    private GeneratedVillageSavedData(int dataVersion, List<GeneratedVillageState> persistedVillages) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedVillages.forEach(village -> {
            GeneratedVillageState migrated = dataVersion < 4 && !village.capitalGranted()
                    ? village.withEndowmentLedger()
                    : village;
            villages.putIfAbsent(migrated.id(), migrated);
        });
    }

    public static GeneratedVillageSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Returns the persisted record, keeping the first observed generated structure bounds authoritative. */
    public synchronized GeneratedVillageState discover(GeneratedVillageState village) {
        Objects.requireNonNull(village, "village");
        GeneratedVillageState existing = villages.putIfAbsent(village.id(), village);
        if (existing == null) {
            setDirty();
            return village;
        }
        return existing;
    }

    public synchronized List<GeneratedVillageState> snapshot() {
        return List.copyOf(villages.values());
    }

    public synchronized void markCapitalGranted(String villageId) {
        replace(villageId, GeneratedVillageState::withCapitalGranted);
    }

    public synchronized void markResidentEndowed(String villageId, java.util.UUID villagerId) {
        replace(villageId, village -> village.withEndowedResident(villagerId));
    }

    public synchronized void markLumberjackZone(String villageId, java.util.UUID zoneId) {
        replace(villageId, village -> village.withLumberjackZone(zoneId));
    }

    public synchronized void markWoodcutter(String villageId, dev.totem.villagers.worker.BlockCoordinate position) {
        replace(villageId, village -> village.withWoodcutter(position));
    }

    public synchronized void markMinerZone(String villageId, java.util.UUID zoneId) {
        replace(villageId, village -> village.withMinerZone(zoneId));
    }

    public synchronized void markFoundingPopulationSpawned(String villageId) {
        replace(villageId, GeneratedVillageState::withFoundingPopulationSpawned);
    }

    /** Test maintenance hook; live generated-village identity is intentionally permanent. */
    public synchronized void remove(String villageId) {
        if (villages.remove(villageId) != null) {
            setDirty();
        }
    }

    private void replace(String villageId, java.util.function.UnaryOperator<GeneratedVillageState> operation) {
        GeneratedVillageState existing = villages.get(villageId);
        if (existing == null) {
            return;
        }
        GeneratedVillageState updated = operation.apply(existing);
        if (!updated.equals(existing)) {
            villages.put(villageId, updated);
            setDirty();
        }
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<GeneratedVillageState> villageList() {
        return List.copyOf(villages.values());
    }
}
