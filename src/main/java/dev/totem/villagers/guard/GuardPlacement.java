package dev.totem.villagers.guard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** One relative placement at a Guard Post construction pad. */
public record GuardPlacement(int x, int y, int z, String blockId) {
    public static final Codec<GuardPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(GuardPlacement::x),
            Codec.INT.fieldOf("y").forGetter(GuardPlacement::y),
            Codec.INT.fieldOf("z").forGetter(GuardPlacement::z),
            Codec.STRING.fieldOf("block").forGetter(GuardPlacement::blockId)
    ).apply(instance, GuardPlacement::new));

    public GuardPlacement {
        if (blockId == null || !blockId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("blockId must be a namespaced identifier");
        }
    }
}
