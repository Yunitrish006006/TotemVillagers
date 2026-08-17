package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Objects;
import java.util.regex.Pattern;

/** A positive, namespaced item amount used by work-order input and output records. */
public record ItemAmount(String itemId, int count) {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static final Codec<ItemAmount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("item").forGetter(ItemAmount::itemId),
            Codec.INT.fieldOf("count").forGetter(ItemAmount::count)
    ).apply(instance, ItemAmount::new));

    public ItemAmount {
        Objects.requireNonNull(itemId, "itemId");
        if (!ITEM_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("itemId must be a namespaced identifier: " + itemId);
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
