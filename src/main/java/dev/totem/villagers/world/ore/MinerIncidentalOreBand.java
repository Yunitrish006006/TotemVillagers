package dev.totem.villagers.world.ore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;

/** One vanilla-inspired height band expressed as an exact chance out of 10,000 mined base blocks. */
public record MinerIncidentalOreBand(
        String shape,
        int minY,
        int maxY,
        int peakY,
        int chancePer10000
) {
    public static final String UNIFORM = "uniform";
    public static final String TRIANGLE = "triangle";

    public static final Codec<MinerIncidentalOreBand> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("shape").forGetter(MinerIncidentalOreBand::shape),
            Codec.INT.fieldOf("min_y").forGetter(MinerIncidentalOreBand::minY),
            Codec.INT.fieldOf("max_y").forGetter(MinerIncidentalOreBand::maxY),
            Codec.INT.optionalFieldOf("peak_y", 0).forGetter(MinerIncidentalOreBand::peakY),
            Codec.INT.fieldOf("chance_per_10000").forGetter(MinerIncidentalOreBand::chancePer10000)
    ).apply(instance, MinerIncidentalOreBand::new));

    public MinerIncidentalOreBand {
        shape = shape == null ? "" : shape.toLowerCase(Locale.ROOT);
        if (!UNIFORM.equals(shape) && !TRIANGLE.equals(shape)) {
            throw new IllegalArgumentException("Incidental-ore height shape must be uniform or triangle");
        }
        if (minY > maxY || minY < -4_096 || maxY > 4_096) {
            throw new IllegalArgumentException("Incidental-ore height range must be ordered and stay within -4096..4096");
        }
        if (TRIANGLE.equals(shape) && (peakY < minY || peakY > maxY)) {
            throw new IllegalArgumentException("A triangular incidental-ore peak_y must be inside its height range");
        }
        if (chancePer10000 < 1 || chancePer10000 > MinerIncidentalOreCatalog.ROLL_SCALE) {
            throw new IllegalArgumentException("chance_per_10000 must be between 1 and 10000");
        }
    }

    /** Returns this band's contribution at one block Y, preserving exact endpoints and peak values. */
    public int chanceAt(int y) {
        if (y < minY || y > maxY) {
            return 0;
        }
        if (UNIFORM.equals(shape) || y == peakY) {
            return chancePer10000;
        }
        if (y < peakY) {
            return scaledChance(y - minY, peakY - minY);
        }
        return scaledChance(maxY - y, maxY - peakY);
    }

    private int scaledChance(int distanceFromEdge, int distanceToPeak) {
        if (distanceToPeak <= 0 || distanceFromEdge <= 0) {
            return 0;
        }
        return (int) ((long) chancePer10000 * distanceFromEdge / distanceToPeak);
    }
}
