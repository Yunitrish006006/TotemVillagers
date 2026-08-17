package dev.totem.villagers.work;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Identity for a sell-side stock entry.  Vanilla item identifiers alone are not
 * sufficient for enchanted books, dyed equipment, potions, maps, or any other
 * ItemStack whose data components change what the player receives.
 *
 * <p>The component patch is the canonical SNBT produced by
 * {@link DataComponentPatch#CODEC}; an empty patch denotes an ordinary,
 * component-free item and deliberately remains compatible with the original
 * {@code merchant_stock} map.</p>
 */
public record StockVariantKey(String itemId, String componentPatch) {
    public static final int MAX_COMPONENT_PATCH_LENGTH = 32_768;
    public static final Codec<StockVariantKey> CODEC = Codec.STRING.xmap(
            StockVariantKey::fromPersistentString,
            StockVariantKey::persistentString
    );

    public StockVariantKey {
        new ItemAmount(itemId, 1);
        componentPatch = componentPatch == null ? "" : componentPatch;
        if (componentPatch.length() > MAX_COMPONENT_PATCH_LENGTH) {
            throw new IllegalArgumentException("componentPatch is too large");
        }
    }

    public static StockVariantKey base(String itemId) {
        return new StockVariantKey(itemId, "");
    }

    /**
     * Derives the key from an actual server ItemStack. Registry context is
     * required because components such as stored enchantments refer to dynamic
     * registries and cannot safely be encoded against bare NBT operations.
     */
    public static StockVariantKey fromStack(ItemStack stack, HolderLookup.Provider registries) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(registries, "registries");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("An empty ItemStack has no stock identity");
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.isEmpty()) {
            return base(itemId);
        }
        return new StockVariantKey(itemId, encodePatch(patch, registries));
    }

    public boolean isBaseItem() {
        return componentPatch.isEmpty();
    }

    /** Stable compact persistence form used as a Codec key, never exposed as a user-facing identifier. */
    public String persistentString() {
        return itemId + "\n" + componentPatch;
    }

    private static StockVariantKey fromPersistentString(String value) {
        int separator = value == null ? -1 : value.indexOf('\n');
        if (separator < 1) {
            throw new IllegalArgumentException("Malformed stock variant key");
        }
        return new StockVariantKey(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String encodePatch(DataComponentPatch patch, HolderLookup.Provider registries) {
        var encoded = DataComponentPatch.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), patch);
        Tag tag = encoded.result().orElseThrow(() -> new IllegalArgumentException(
                encoded.error().map(error -> "Could not encode ItemStack components: " + error.message())
                        .orElse("Could not encode ItemStack components")
        ));
        return tag.toString();
    }
}
