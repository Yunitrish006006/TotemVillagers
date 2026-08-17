package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** One durable component-bearing stock entry. Base item stock stays in the legacy map. */
public record StockVariantAmount(StockVariantKey key, int count) {
    public static final Codec<StockVariantAmount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StockVariantKey.CODEC.fieldOf("key").forGetter(StockVariantAmount::key),
            Codec.INT.fieldOf("count").forGetter(StockVariantAmount::count)
    ).apply(instance, StockVariantAmount::new));

    public StockVariantAmount {
        if (key == null || key.isBaseItem()) {
            throw new IllegalArgumentException("Variant stock requires a component-bearing key");
        }
        new ItemAmount(key.itemId(), count);
    }
}
