package dev.totem.villagers.worldgen;

import dev.totem.villagers.mixin.StructureTemplateAccessor;
import dev.totem.villagers.mixin.StructureTemplatePaletteAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.List;
import java.util.Set;

/** Adds the non-random Lumberyard and Mine connectors to every vanilla village town-centre template. */
public final class VillageTownCenterUtilityInjector {
    public static final Identifier LUMBERYARD_POOL_ID = Identifier.fromNamespaceAndPath("totem", "village_lumberyard");
    public static final Identifier MINE_POOL_ID = Identifier.fromNamespaceAndPath("totem", "village_mine");
    public static final Identifier LUMBERYARD_ANCHOR_NAME = Identifier.fromNamespaceAndPath("totem", "village_lumberyard_anchor");
    public static final Identifier MINE_ANCHOR_NAME = Identifier.fromNamespaceAndPath("totem", "village_mine_anchor");
    private static final Identifier FEATURE_POOL_TARGET = Identifier.fromNamespaceAndPath("minecraft", "bottom");
    private static final Set<String> VANILLA_VILLAGE_TYPES = Set.of("plains", "desert", "savanna", "snowy", "taiga");

    private VillageTownCenterUtilityInjector() {
    }

    public static void attachToTownCenter(Identifier templateId, StructureTemplate template) {
        if (!isVanillaTownCenter(templateId)) {
            return;
        }
        // Keep both buildings clear of the town-centre piece even after the
        // centre is rotated by the village jigsaw. They have independent
        // anchors and pools, so a village always receives two distinct sites.
        BlockPos lumberyardPosition = new BlockPos(template.getSize().getX() / 2, 0, template.getSize().getZ() + 20);
        BlockPos minePosition = lumberyardPosition.east(20);
        for (StructureTemplate.Palette palette : ((StructureTemplateAccessor) (Object) template).totemVillagers$palettes()) {
            StructureTemplatePaletteAccessor accessor = (StructureTemplatePaletteAccessor) (Object) palette;
            List<StructureTemplate.StructureBlockInfo> blocks = accessor.totemVillagers$blocks();
            boolean changed = false;
            if (!containsAnchor(blocks, LUMBERYARD_ANCHOR_NAME)) {
                blocks.add(anchor(lumberyardPosition, LUMBERYARD_ANCHOR_NAME, LUMBERYARD_POOL_ID));
                changed = true;
            }
            if (!containsAnchor(blocks, MINE_ANCHOR_NAME)) {
                blocks.add(anchor(minePosition, MINE_ANCHOR_NAME, MINE_POOL_ID));
                changed = true;
            }
            if (changed) {
                accessor.totemVillagers$cache().clear();
                accessor.totemVillagers$setCachedJigsaws(null);
            }
        }
    }

    public static boolean isVanillaTownCenter(Identifier templateId) {
        if (!"minecraft".equals(templateId.getNamespace())) {
            return false;
        }
        return VANILLA_VILLAGE_TYPES.stream().anyMatch(type -> {
            String root = "village/" + type + "/";
            return templateId.getPath().startsWith(root + "town_centers/")
                    || templateId.getPath().startsWith(root + "zombie/town_centers/");
        });
    }

    private static boolean containsAnchor(List<StructureTemplate.StructureBlockInfo> blocks, Identifier anchorName) {
        return blocks.stream().anyMatch(info -> info.nbt() != null
                && anchorName.toString().equals(info.nbt().getStringOr(JigsawBlockEntity.NAME, "")));
    }

    private static BlockState anchorState() {
        return Blocks.JIGSAW.defaultBlockState().setValue(JigsawBlock.ORIENTATION,
                // Feature-pool elements expose their single "bottom" connector
                // facing down.  The town centre must face up so vanilla's
                // JigsawBlock.canAttach accepts this as a real child piece.
                FrontAndTop.fromFrontAndTop(Direction.UP, Direction.SOUTH));
    }

    private static StructureTemplate.StructureBlockInfo anchor(BlockPos position, Identifier anchorName, Identifier poolId) {
        return new StructureTemplate.StructureBlockInfo(position, anchorState(), anchorNbt(anchorName, poolId));
    }

    private static CompoundTag anchorNbt(Identifier anchorName, Identifier poolId) {
        CompoundTag tag = new CompoundTag();
        tag.store(JigsawBlockEntity.NAME, Identifier.CODEC, anchorName);
        tag.store(JigsawBlockEntity.TARGET, Identifier.CODEC, FEATURE_POOL_TARGET);
        tag.store(JigsawBlockEntity.POOL, JigsawBlockEntity.POOL_CODEC,
                ResourceKey.create(Registries.TEMPLATE_POOL, poolId));
        tag.store(JigsawBlockEntity.JOINT, JigsawBlockEntity.JointType.CODEC,
                JigsawBlockEntity.JointType.ROLLABLE);
        tag.putString(JigsawBlockEntity.FINAL_STATE, "minecraft:air");
        return tag;
    }
}
