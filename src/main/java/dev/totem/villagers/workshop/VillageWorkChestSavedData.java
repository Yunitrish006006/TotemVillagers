package dev.totem.villagers.workshop;

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

/** Durable, owner-controlled registrations for every Village Work Chest in a world. */
public final class VillageWorkChestSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<VillageWorkChestSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(VillageWorkChestSavedData::dataVersion),
            VillageWorkChest.CODEC.listOf().optionalFieldOf("chests", List.of()).forGetter(VillageWorkChestSavedData::chestList)
    ).apply(instance, VillageWorkChestSavedData::new));
    public static final SavedDataType<VillageWorkChestSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "village_work_chests"),
            VillageWorkChestSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<WorkChestKey, VillageWorkChest> chests = new LinkedHashMap<>();

    public VillageWorkChestSavedData() {
        this(DATA_VERSION, List.of());
    }

    private VillageWorkChestSavedData(int dataVersion, List<VillageWorkChest> persistedChests) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedChests.forEach(chest -> chests.putIfAbsent(chest.key(), chest));
    }

    public static VillageWorkChestSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized Optional<VillageWorkChest> get(WorkChestKey key) {
        return Optional.ofNullable(chests.get(key));
    }

    /** A chest cannot be claimed over another owner's existing registration. */
    public synchronized boolean register(VillageWorkChest chest, UUID actorId) {
        Objects.requireNonNull(chest, "chest");
        if (!chest.ownerId().equals(actorId)) {
            return false;
        }
        VillageWorkChest existing = chests.get(chest.key());
        if (existing != null && !existing.ownerId().equals(actorId)) {
            return false;
        }
        if (!chest.equals(existing)) {
            chests.put(chest.key(), chest);
            setDirty();
        }
        return true;
    }

    public synchronized boolean linkVillager(WorkChestKey key, UUID actorId, UUID villagerId, boolean linked) {
        VillageWorkChest existing = chests.get(key);
        if (existing == null || !existing.ownerId().equals(actorId)) {
            return false;
        }
        VillageWorkChest updated = existing.withVillager(villagerId, linked);
        if (!updated.equals(existing)) {
            chests.put(key, updated);
            setDirty();
        }
        return true;
    }

    public synchronized boolean linkJobSite(WorkChestKey key, UUID actorId, long jobSitePosition, boolean linked) {
        VillageWorkChest existing = chests.get(key);
        if (existing == null || !existing.ownerId().equals(actorId)) {
            return false;
        }
        VillageWorkChest updated = existing.withJobSite(jobSitePosition, linked);
        if (!updated.equals(existing)) {
            chests.put(key, updated);
            setDirty();
        }
        return true;
    }

    public synchronized boolean setAcceptedInput(WorkChestKey key, UUID actorId, String itemId, boolean accepted) {
        VillageWorkChest existing = chests.get(key);
        if (existing == null || !existing.ownerId().equals(actorId)) {
            return false;
        }
        VillageWorkChest updated = existing.withAcceptedInput(itemId, accepted);
        if (!updated.equals(existing)) {
            chests.put(key, updated);
            setDirty();
        }
        return true;
    }

    public synchronized boolean unregister(WorkChestKey key, UUID actorId) {
        VillageWorkChest existing = chests.get(key);
        if (existing == null || !existing.ownerId().equals(actorId)) {
            return false;
        }
        chests.remove(key);
        setDirty();
        return true;
    }

    public synchronized Map<WorkChestKey, VillageWorkChest> snapshot() {
        return Map.copyOf(chests);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<VillageWorkChest> chestList() {
        return List.copyOf(chests.values());
    }
}
