package dev.totem.villagers.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Evaluate fuel at the actual smoker without reading or changing its slots. */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceFuelInvoker {
    @Invoker("getBurnDuration")
    int totemVillagers$burnDuration(ServerLevel level, ItemStack fuel);

    @Invoker("getSpeedMultiplier")
    float totemVillagers$cookingSpeed(ServerLevel level, ItemStack fuel);
}
