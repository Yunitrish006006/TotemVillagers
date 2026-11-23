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
    public static final int DATA_VERSION = 2;
    public record Vacancy(GuardPost post, int ticks) {
        public static final int WAIT = 48_000;
        static final Codec<Vacancy> CODEC = RecordCodecBuilder.create(i -> i.group(
                GuardPost.CODEC.fieldOf("post").forGetter(Vacancy::post),
                Codec.intRange(0, WAIT).fieldOf("ticks").forGetter(Vacancy::ticks)).apply(i, Vacancy::new));
    }
    public static final Codec<ManagedVillageSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("data_version", DATA_VERSION).forGetter(ManagedVillageSavedData::dataVersion),
            ManagedVillageState.CODEC.listOf().optionalFieldOf("villages", List.of()).forGetter(ManagedVillageSavedData::villageList),
            Vacancy.CODEC.listOf().optionalFieldOf("vacancies", List.of()).forGetter(ManagedVillageSavedData::vacancies)
    ).apply(instance, ManagedVillageSavedData::new));
    public static final SavedDataType<ManagedVillageSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("totem-villagers", "managed_villages"),
            ManagedVillageSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final int dataVersion;
    private final Map<UUID, ManagedVillageState> villages = new LinkedHashMap<>();

    private final Map<UUID, Vacancy> open = new LinkedHashMap<>();

    public ManagedVillageSavedData() {
        this(DATA_VERSION, List.of(), List.of());
    }

    private ManagedVillageSavedData(int dataVersion, List<ManagedVillageState> persistedVillages, List<Vacancy> vacancies) {
        this.dataVersion = Math.max(DATA_VERSION, dataVersion);
        persistedVillages.forEach(state -> villages.putIfAbsent(state.post().villageId(), state));
        for (var vacancy : vacancies) {
            if (!villages.containsKey(vacancy.post().villageId())) open.putIfAbsent(vacancy.post().villageId(), vacancy);
        }
    }

    public static ManagedVillageSavedData forServer(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public synchronized List<Vacancy> vacancies() { return List.copyOf(open.values()); }

    public synchronized Optional<Vacancy> vacancy(UUID id) { return Optional.ofNullable(open.get(id)); }

    private static boolean overlaps(GuardPost a, GuardPost b) {
        if (!a.dimensionId().equals(b.dimensionId())) return false;
        var left = net.minecraft.core.BlockPos.of(a.packedConstructionPad());
        var right = net.minecraft.core.BlockPos.of(b.packedConstructionPad());
        return Math.abs(left.getX() - right.getX()) <= 2 && Math.abs(left.getY() - right.getY()) <= 2 && left.getZ() == right.getZ();
    }

    public synchronized boolean recruit(GuardPost post, UUID actor) {
        if (!post.ownerId().equals(actor) || villages.containsKey(post.villageId()) || open.containsKey(post.villageId())
                || villages.values().stream().anyMatch(v -> overlaps(v.post(), post))
                || open.values().stream().anyMatch(v -> overlaps(v.post(), post))) return false;
        open.put(post.villageId(), new Vacancy(post, 0));
        setDirty();
        return true;
    }

    public synchronized void observe(Vacancy vacancy, int ticks) {
        if (!vacancy.equals(open.get(vacancy.post().villageId())) || vacancy.ticks() == ticks) return;
        open.put(vacancy.post().villageId(), new Vacancy(vacancy.post(), Math.clamp(ticks, 0, Vacancy.WAIT)));
        setDirty();
    }

    public synchronized boolean cancelRecruitment(UUID id, UUID actor) {
        var vacancy = open.get(id);
        if (vacancy == null || !vacancy.post().ownerId().equals(actor)) return false;
        open.remove(id);
        setDirty();
        return true;
    }

    public synchronized boolean staff(Vacancy vacancy, ManagedVillageState state) {
        if (!vacancy.equals(open.get(vacancy.post().villageId())) || !state.post().equals(vacancy.post())
                || vacancy.ticks() < Vacancy.WAIT || getByGuard(state.guardVillagerId()).isPresent()
                || villages.containsKey(vacancy.post().villageId())) return false;
        open.remove(vacancy.post().villageId());
        villages.put(state.post().villageId(), state);
        setDirty();
        return true;
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
        if (!state.post().ownerId().equals(actorId) || open.values().stream().anyMatch(v ->
                v.post().villageId().equals(state.post().villageId()) || overlaps(v.post(), state.post()))) {
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
