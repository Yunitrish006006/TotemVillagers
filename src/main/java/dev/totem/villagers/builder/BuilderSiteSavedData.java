package dev.totem.villagers.builder;

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

/** Persists at most one active blueprint per Builder, without making unloaded chunks load. */
public final class BuilderSiteSavedData extends SavedData {
    public static final int DATA_VERSION = 1;
    public static final Codec<BuilderSiteSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(BuilderSiteSavedData::dataVersion),
            BuilderSite.CODEC.listOf().optionalFieldOf("sites", List.of()).forGetter(BuilderSiteSavedData::siteList)
    ).apply(instance, BuilderSiteSavedData::new));
    public static final SavedDataType<BuilderSiteSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "builder_sites"),
            BuilderSiteSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, BuilderSite> sites = new LinkedHashMap<>();

    public BuilderSiteSavedData() {
        this(DATA_VERSION, List.of());
    }

    private BuilderSiteSavedData(int dataVersion, List<BuilderSite> persistedSites) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedSites.forEach(site -> sites.putIfAbsent(site.builderVillagerId(), site));
    }

    public static BuilderSiteSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized Optional<BuilderSite> getByBuilder(UUID builderId) {
        return Optional.ofNullable(sites.get(builderId));
    }

    /** The same owner can deliberately replace their Builder's unfinished site; other owners cannot. */
    public synchronized boolean registerOrReplace(BuilderSite site, UUID actorId) {
        Objects.requireNonNull(site, "site");
        if (!site.ownerId().equals(actorId)) {
            return false;
        }
        BuilderSite existing = sites.get(site.builderVillagerId());
        if (existing != null && !existing.ownerId().equals(actorId)) {
            return false;
        }
        if (!site.equals(existing)) {
            sites.put(site.builderVillagerId(), site);
            setDirty();
        }
        return true;
    }

    public synchronized void updateProgress(BuilderSite site) {
        BuilderSite existing = sites.get(site.builderVillagerId());
        if (existing == null || !existing.id().equals(site.id())) {
            return;
        }
        if (!site.equals(existing)) {
            sites.put(site.builderVillagerId(), site);
            setDirty();
        }
    }

    public synchronized boolean removeByBuilder(UUID builderId, UUID actorId) {
        BuilderSite existing = sites.get(builderId);
        if (existing == null || !existing.ownerId().equals(actorId)) {
            return false;
        }
        sites.remove(builderId);
        setDirty();
        return true;
    }

    public synchronized Map<UUID, BuilderSite> snapshot() {
        return Map.copyOf(sites);
    }

    private int dataVersion() {
        return dataVersion;
    }

    private synchronized List<BuilderSite> siteList() {
        return List.copyOf(sites.values());
    }
}
