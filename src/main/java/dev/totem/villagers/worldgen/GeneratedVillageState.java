package dev.totem.villagers.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.totem.villagers.worker.BlockCoordinate;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persisted identity and one-time bootstrap progress for one generated vanilla village structure. */
public record GeneratedVillageState(
        String id,
        String dimensionId,
        BlockCoordinate minimum,
        BlockCoordinate maximum,
        boolean capitalGranted,
        Optional<UUID> lumberjackZoneId,
        Optional<BlockCoordinate> woodcutterPosition,
        Optional<UUID> minerZoneId,
        Optional<List<UUID>> endowedResidents,
        boolean foundingPopulationSpawned
) {
    public static final Codec<GeneratedVillageState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(GeneratedVillageState::id),
            Codec.STRING.fieldOf("dimension").forGetter(GeneratedVillageState::dimensionId),
            BlockCoordinate.CODEC.fieldOf("minimum").forGetter(GeneratedVillageState::minimum),
            BlockCoordinate.CODEC.fieldOf("maximum").forGetter(GeneratedVillageState::maximum),
            Codec.BOOL.optionalFieldOf("capital_granted", false).forGetter(GeneratedVillageState::capitalGranted),
            UUIDUtil.CODEC.optionalFieldOf("lumberjack_zone").forGetter(GeneratedVillageState::lumberjackZoneId),
            BlockCoordinate.CODEC.optionalFieldOf("woodcutter").forGetter(GeneratedVillageState::woodcutterPosition),
            UUIDUtil.CODEC.optionalFieldOf("miner_zone").forGetter(GeneratedVillageState::minerZoneId),
            UUIDUtil.CODEC.listOf().optionalFieldOf("endowed_residents").forGetter(GeneratedVillageState::endowedResidents),
            Codec.BOOL.optionalFieldOf("founding_population_spawned", false)
                    .forGetter(GeneratedVillageState::foundingPopulationSpawned)
    ).apply(instance, GeneratedVillageState::new));

    /** Compatibility constructor for the version-4 canonical record shape. */
    public GeneratedVillageState(String id, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum,
                                 boolean capitalGranted, Optional<UUID> lumberjackZoneId,
                                 Optional<BlockCoordinate> woodcutterPosition, Optional<UUID> minerZoneId,
                                 Optional<List<UUID>> endowedResidents) {
        this(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId, woodcutterPosition, minerZoneId,
                endowedResidents, false);
    }

    /** Backwards-compatible construction for callers that do not yet establish a Woodcutter. */
    public GeneratedVillageState(String id, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum,
                                 boolean capitalGranted, Optional<UUID> lumberjackZoneId) {
        this(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId, Optional.empty(), Optional.empty(),
                initialEndowmentLedger(capitalGranted), false);
    }

    /** Backwards-compatible construction for callers that establish a Woodcutter but no Miner starter. */
    public GeneratedVillageState(String id, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum,
                                 boolean capitalGranted, Optional<UUID> lumberjackZoneId,
                                 Optional<BlockCoordinate> woodcutterPosition) {
        this(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId, woodcutterPosition, Optional.empty(),
                initialEndowmentLedger(capitalGranted), false);
    }

    /** Backwards-compatible construction for callers that already establish both resource sites. */
    public GeneratedVillageState(String id, String dimensionId, BlockCoordinate minimum, BlockCoordinate maximum,
                                 boolean capitalGranted, Optional<UUID> lumberjackZoneId,
                                 Optional<BlockCoordinate> woodcutterPosition, Optional<UUID> minerZoneId) {
        this(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId, woodcutterPosition, minerZoneId,
                initialEndowmentLedger(capitalGranted), false);
    }

    public GeneratedVillageState {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Generated village id must not be blank");
        }
        if (dimensionId == null || !dimensionId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Generated village dimension must be a namespaced identifier");
        }
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        lumberjackZoneId = lumberjackZoneId == null ? Optional.empty() : lumberjackZoneId;
        woodcutterPosition = woodcutterPosition == null ? Optional.empty() : woodcutterPosition;
        minerZoneId = minerZoneId == null ? Optional.empty() : minerZoneId;
        endowedResidents = endowedResidents == null ? Optional.empty() : endowedResidents.map(List::copyOf);
        if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("Generated village minimum must not exceed maximum");
        }
    }

    public GeneratedVillageState withCapitalGranted() {
        return capitalGranted ? this : new GeneratedVillageState(id, dimensionId, minimum, maximum, true, lumberjackZoneId,
                woodcutterPosition, minerZoneId, endowedResidents, foundingPopulationSpawned);
    }

    /** Enables the version-4 per-resident ledger for a discovered village that has not paid anyone yet. */
    public GeneratedVillageState withEndowmentLedger() {
        return endowedResidents.isPresent() ? this : new GeneratedVillageState(id, dimensionId, minimum, maximum,
                capitalGranted, lumberjackZoneId, woodcutterPosition, minerZoneId, Optional.of(List.of()),
                foundingPopulationSpawned);
    }

    public boolean hasEndowed(UUID villagerId) {
        return endowedResidents.map(residents -> residents.contains(villagerId)).orElse(capitalGranted);
    }

    public GeneratedVillageState withEndowedResident(UUID villagerId) {
        Objects.requireNonNull(villagerId, "villagerId");
        if (endowedResidents.isEmpty()) {
            throw new IllegalStateException("Legacy generated-village record has no per-resident endowment ledger");
        }
        if (endowedResidents.orElseThrow().contains(villagerId)) {
            return withCapitalGranted();
        }
        List<UUID> next = new ArrayList<>(endowedResidents.orElseThrow());
        next.add(villagerId);
        return new GeneratedVillageState(id, dimensionId, minimum, maximum, true, lumberjackZoneId,
                woodcutterPosition, minerZoneId, Optional.of(next), foundingPopulationSpawned);
    }

    public GeneratedVillageState withLumberjackZone(UUID zoneId) {
        return new GeneratedVillageState(id, dimensionId, minimum, maximum, capitalGranted,
                Optional.of(Objects.requireNonNull(zoneId, "zoneId")), woodcutterPosition, minerZoneId, endowedResidents,
                foundingPopulationSpawned);
    }

    public GeneratedVillageState withWoodcutter(BlockCoordinate position) {
        return new GeneratedVillageState(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId,
                Optional.of(Objects.requireNonNull(position, "position")), minerZoneId, endowedResidents,
                foundingPopulationSpawned);
    }

    public GeneratedVillageState withMinerZone(UUID zoneId) {
        return new GeneratedVillageState(id, dimensionId, minimum, maximum, capitalGranted, lumberjackZoneId,
                woodcutterPosition, Optional.of(Objects.requireNonNull(zoneId, "zoneId")), endowedResidents,
                foundingPopulationSpawned);
    }

    public GeneratedVillageState withFoundingPopulationSpawned() {
        return foundingPopulationSpawned ? this : new GeneratedVillageState(id, dimensionId, minimum, maximum,
                capitalGranted, lumberjackZoneId, woodcutterPosition, minerZoneId, endowedResidents, true);
    }

    private static Optional<List<UUID>> initialEndowmentLedger(boolean capitalGranted) {
        return capitalGranted ? Optional.empty() : Optional.of(List.of());
    }
}
