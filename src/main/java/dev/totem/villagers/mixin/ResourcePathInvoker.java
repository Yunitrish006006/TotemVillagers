package dev.totem.villagers.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.Set;

@Mixin(PathNavigation.class)
public interface ResourcePathInvoker {
    @Invoker("createPath")
    Path totemVillagers$pathToStances(Set<BlockPos> targets, int padding, boolean above, int accuracy, float range);
}
