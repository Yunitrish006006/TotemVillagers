package dev.totem.villagers.woodcutter;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

/** Registers menus used by Totem-owned functional blocks. */
public final class TotemVillagerMenus {
    public static final Identifier WOODCUTTER_ID = Identifier.fromNamespaceAndPath("totem", "woodcutter");
    public static final ExtendedMenuType<WoodcutterMenu, Unit> WOODCUTTER = Registry.register(BuiltInRegistries.MENU,
            ResourceKey.create(Registries.MENU, WOODCUTTER_ID),
            new ExtendedMenuType<>((containerId, inventory, ignored) -> new WoodcutterMenu(containerId, inventory),
                    unitCodec()));

    private TotemVillagerMenus() {
    }

    /** Forces class initialisation from the mod entry point. */
    public static void register() {
        // Static registration above intentionally owns the registry lifetime.
    }

    private static StreamCodec<? super RegistryFriendlyByteBuf, Unit> unitCodec() {
        return Unit.STREAM_CODEC;
    }
}
