package dev.totem.villagers.worker;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Minimal server-neutral coordinate used by durable work-zone assignments. */
public record BlockCoordinate(int x, int y, int z) {
    public static final Codec<BlockCoordinate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(BlockCoordinate::x),
            Codec.INT.fieldOf("y").forGetter(BlockCoordinate::y),
            Codec.INT.fieldOf("z").forGetter(BlockCoordinate::z)
    ).apply(instance, BlockCoordinate::new));
}
