package dev.totem.villagers.woodcutter;

import dev.totem.villagers.content.TotemVillagerBlocks;
import dev.totem.villagers.inventory.VillagerWorkInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Server-side processing at a physical Woodcutter for a Lumberjack's personal
 * inventory. It re-resolves the player crafting recipe immediately before the
 * atomic material exchange so a data-pack reload can never leave a stale
 * conversion active.
 */
public final class LumberjackWoodcutterAction {
    private static final double WORK_REACH_SQUARED = 16.0D;

    public boolean complete(ServerLevel level, Villager lumberjack, BlockPos station,
                            WoodcutterRecipes.Match selected, VillagerWorkInventory inventory) {
        if (!level.isLoaded(station)
                || !level.getBlockState(station).is(TotemVillagerBlocks.WOODCUTTER)
                || lumberjack.distanceToSqr(Vec3.atCenterOf(station)) > WORK_REACH_SQUARED) {
            return false;
        }
        ItemStack input = uniformInput(selected).orElse(null);
        if (input == null || !WoodcutterRecipes.acceptsInput(input)) {
            return false;
        }
        WoodcutterRecipes.Match live = WoodcutterRecipes.matching(level, input).stream()
                .filter(match -> match.id().equals(selected.id()))
                .filter(match -> match.inputCount() == selected.inputCount())
                .filter(match -> ItemStack.matches(match.output(), selected.output()))
                .findFirst()
                .orElse(null);
        if (live == null || !inventory.exchangeExactMatching(input, live.output())) {
            return false;
        }
        lumberjack.playWorkSound();
        level.playSound(null, station, SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private static Optional<ItemStack> uniformInput(WoodcutterRecipes.Match match) {
        ItemStack material = match.input().items().stream()
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .orElse(null);
        if (material == null || match.input().items().stream()
                .filter(stack -> !stack.isEmpty())
                .anyMatch(stack -> !ItemStack.isSameItemSameComponents(stack, material))) {
            return Optional.empty();
        }
        return Optional.of(material.copyWithCount(match.inputCount()));
    }
}
