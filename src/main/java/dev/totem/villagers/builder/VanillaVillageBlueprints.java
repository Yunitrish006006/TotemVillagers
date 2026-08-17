package dev.totem.villagers.builder;

import dev.totem.villagers.mixin.StructureTemplateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Resolves only shipped, final vanilla village house templates into material-backed block placements. */
public final class VanillaVillageBlueprints {
    private static final Set<String> VILLAGE_TYPES = Set.of("plains", "desert", "savanna", "taiga", "snowy");

    private VanillaVillageBlueprints() {
    }

    public static boolean isAllowedTemplateId(String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        return id != null && isAllowedTemplateId(id);
    }

    public static boolean isAllowedTemplateId(Identifier id) {
        if (!"minecraft".equals(id.getNamespace())) {
            return false;
        }
        String[] path = id.getPath().split("/");
        return path.length >= 4
                && "village".equals(path[0])
                && VILLAGE_TYPES.contains(path[1])
                && "houses".equals(path[2])
                && !path[3].isBlank()
                && java.util.Arrays.stream(path).noneMatch("zombie"::equals);
    }

    public static Optional<Blueprint> resolve(MinecraftServer server, Identifier templateId, BlockPos anchor) {
        if (!isAllowedTemplateId(templateId)) {
            return Optional.empty();
        }
        StructureTemplate template = server.getStructureManager().get(templateId).orElse(null);
        if (template == null) {
            return Optional.empty();
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(Mirror.NONE)
                .setRotation(Rotation.NONE)
                .setIgnoreEntities(true);
        List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) (Object) template).totemVillagers$palettes();
        if (palettes.isEmpty()) {
            return Optional.empty();
        }
        List<BlueprintBlock> blocks = settings.getRandomPalette(palettes, anchor).blocks().stream()
                .filter(info -> !info.state().isAir())
                .filter(info -> !info.state().is(Blocks.STRUCTURE_VOID))
                // Final house files retain connector markers for vanilla worldgen.
                // A standalone Builder house must leave those invisible markers out.
                .filter(info -> !info.state().is(Blocks.JIGSAW))
                .filter(info -> !info.state().is(Blocks.STRUCTURE_BLOCK))
                .map(info -> blockAt(settings, anchor, info))
                .sorted(Comparator.comparingInt((BlueprintBlock block) -> block.position().getY())
                        .thenComparingInt(block -> block.position().getX())
                        .thenComparingInt(block -> block.position().getZ()))
                .toList();
        if (blocks.isEmpty() || blocks.stream().anyMatch(block -> !block.hasPlaceableMaterial())) {
            return Optional.empty();
        }
        return Optional.of(new Blueprint(templateId, blocks));
    }

    private static BlueprintBlock blockAt(StructurePlaceSettings settings, BlockPos anchor,
                                          StructureTemplate.StructureBlockInfo source) {
        BlockPos relative = StructureTemplate.calculateRelativePosition(settings, source.pos());
        BlockState state = source.state().rotate(settings.getRotation());
        return new BlueprintBlock(anchor.offset(relative), state,
                BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString());
    }

    public record Blueprint(Identifier templateId, List<BlueprintBlock> blocks) {
        public Blueprint {
            blocks = List.copyOf(blocks);
        }
    }

    /** NBT is deliberately excluded: a built chest is empty and no template entity or loot table is copied. */
    public record BlueprintBlock(BlockPos position, BlockState state, String materialItemId) {
        public boolean hasPlaceableMaterial() {
            return !state.is(Blocks.JIGSAW)
                    && !state.is(Blocks.STRUCTURE_BLOCK)
                    && !state.is(Blocks.STRUCTURE_VOID)
                    && !state.isAir()
                    && !Items.AIR.equals(state.getBlock().asItem())
                    && Identifier.tryParse(materialItemId) != null;
        }

        /** A vanilla door or bed item creates both stored structure states, so charge only its root half. */
        public boolean consumesMaterial() {
            if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            }
            if (state.hasProperty(BlockStateProperties.BED_PART)) {
                return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
            }
            return true;
        }
    }
}
