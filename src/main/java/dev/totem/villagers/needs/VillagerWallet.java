package dev.totem.villagers.needs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Objects;
import java.util.UUID;

/** Server-authoritative emerald balance earned from real player trades and spent on food. */
public record VillagerWallet(UUID villagerId, int emeralds) {
    public static final Codec<VillagerWallet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("villager").forGetter(VillagerWallet::villagerId),
            Codec.INT.fieldOf("emeralds").forGetter(VillagerWallet::emeralds)
    ).apply(instance, VillagerWallet::new));

    public VillagerWallet {
        Objects.requireNonNull(villagerId, "villagerId");
        if (emeralds < 0) {
            throw new IllegalArgumentException("emeralds must not be negative");
        }
    }
}
