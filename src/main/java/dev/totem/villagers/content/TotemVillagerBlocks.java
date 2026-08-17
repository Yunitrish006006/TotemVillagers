package dev.totem.villagers.content;

import dev.totem.villagers.TotemVillagers;
import dev.totem.villagers.woodcutter.WoodcutterBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Canonical registration for physical stations owned by Totem Villagers. */
public final class TotemVillagerBlocks {
    public static final Identifier WOODCUTTER_ID = Identifier.fromNamespaceAndPath("totem", "woodcutter");
    public static final Block WOODCUTTER = registerWoodcutter();
    public static final Item WOODCUTTER_ITEM = registerBlockItem(WOODCUTTER_ID, WOODCUTTER);

    private TotemVillagerBlocks() {
    }

    /** Forces class initialisation from the mod entry point. */
    public static void register() {
        // Static registration above intentionally owns the registry lifetime.
    }

    private static Block registerWoodcutter() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, WOODCUTTER_ID);
        return Registry.register(BuiltInRegistries.BLOCK, key, new WoodcutterBlock(
                BlockBehaviour.Properties.ofFullCopy(Blocks.STONECUTTER).setId(key)
        ));
    }

    private static Item registerBlockItem(Identifier id, Block block) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id);
        BlockItem item = Registry.register(BuiltInRegistries.ITEM, key,
                new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
        item.registerBlocks(Item.BY_BLOCK, item);
        return item;
    }
}
