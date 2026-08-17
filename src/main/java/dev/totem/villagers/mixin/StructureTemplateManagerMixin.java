package dev.totem.villagers.mixin;

import dev.totem.villagers.worldgen.VillageTownCenterUtilityInjector;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Hooks template loading before the village jigsaw builder reads town-centre connectors. */
@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Inject(method = "get", at = @At("RETURN"))
    private void totemVillagers$attachFixedUtilityPool(Identifier id,
                                                         CallbackInfoReturnable<Optional<StructureTemplate>> callback) {
        callback.getReturnValue().ifPresent(template -> VillageTownCenterUtilityInjector.attachToTownCenter(id, template));
    }
}
