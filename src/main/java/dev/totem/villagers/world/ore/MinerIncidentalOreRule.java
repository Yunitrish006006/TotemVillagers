package dev.totem.villagers.world.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/** Data-pack rule pairing one vanilla replaceable base tag with one matching ore-block variant. */
public record MinerIncidentalOreRule(
        String id,
        String dimension,
        String substrateTag,
        String biomeTag,
        String oreBlock,
        List<MinerIncidentalOreBand> bands
) {
    public static final Codec<MinerIncidentalOreRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(MinerIncidentalOreRule::id),
            Codec.STRING.fieldOf("dimension").forGetter(MinerIncidentalOreRule::dimension),
            Codec.STRING.fieldOf("substrate_tag").forGetter(MinerIncidentalOreRule::substrateTag),
            Codec.STRING.optionalFieldOf("biome_tag", "").forGetter(MinerIncidentalOreRule::biomeTag),
            Codec.STRING.fieldOf("ore_block").forGetter(MinerIncidentalOreRule::oreBlock),
            MinerIncidentalOreBand.CODEC.listOf().fieldOf("bands").forGetter(MinerIncidentalOreRule::bands)
    ).apply(instance, MinerIncidentalOreRule::new));

    public MinerIncidentalOreRule {
        requireIdentifier(id, "id");
        requireIdentifier(dimension, "dimension");
        requireIdentifier(substrateTag, "substrate_tag");
        biomeTag = biomeTag == null ? "" : biomeTag;
        if (!biomeTag.isBlank()) {
            requireIdentifier(biomeTag, "biome_tag");
        }
        requireIdentifier(oreBlock, "ore_block");
        bands = List.copyOf(bands);
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("An incidental-ore rule needs at least one height band");
        }
        int minimum = bands.stream().mapToInt(MinerIncidentalOreBand::minY).min().orElseThrow();
        int maximum = bands.stream().mapToInt(MinerIncidentalOreBand::maxY).max().orElseThrow();
        for (int y = minimum; y <= maximum; y++) {
            int exactY = y;
            int combinedChance = bands.stream().mapToInt(band -> band.chanceAt(exactY)).sum();
            if (combinedChance > MinerIncidentalOreCatalog.ROLL_SCALE) {
                throw new IllegalArgumentException("One incidental-ore rule exceeds 100% chance at Y=" + y);
            }
        }
    }

    public int chanceAt(int y) {
        return bands.stream().mapToInt(band -> band.chanceAt(y)).sum();
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException(field + " must be a namespaced identifier");
        }
    }
}
