package dev.totem.villagers.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/** Refreshes palette lookup caches after the fixed village utility jigsaw is attached. */
@Mixin(StructureTemplate.Palette.class)
public interface StructureTemplatePaletteAccessor {
    @Accessor("blocks")
    List<StructureTemplate.StructureBlockInfo> totemVillagers$blocks();

    @Accessor("cache")
    Map<Block, List<StructureTemplate.StructureBlockInfo>> totemVillagers$cache();

    @Mutable
    @Accessor("cachedJigsaws")
    void totemVillagers$setCachedJigsaws(List<StructureTemplate.JigsawBlockInfo> jigsaws);
}
