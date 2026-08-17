package dev.totem.villagers.gametest;

import dev.totem.villagers.builder.VanillaVillageBlueprints;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Confirms Builder blueprints resolve from Minecraft's shipped village-house structures. */
public final class BuilderBlueprintGameTest {
    @GameTest(maxTicks = 40)
    public void resolvesAShippedVanillaPlainsHouseIntoMaterialBackedBlocks(GameTestHelper helper) {
        Identifier template = Identifier.fromNamespaceAndPath("minecraft", "village/plains/houses/plains_small_house_1");
        var blueprint = VanillaVillageBlueprints.resolve(helper.getLevel().getServer(), template,
                helper.absolutePos(new BlockPos(8, 2, 8))).orElse(null);
        require(helper, blueprint != null, "Vanilla plains house was not resolved as a Builder blueprint");
        require(helper, !blueprint.blocks().isEmpty(), "Vanilla plains house contains no placeable blocks");
        require(helper, blueprint.blocks().stream().allMatch(VanillaVillageBlueprints.BlueprintBlock::hasPlaceableMaterial),
                "Builder blueprint contains a block without a material-backed placement");
        var pairedDoorStates = blueprint.blocks().stream()
                .filter(block -> block.state().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)).toList();
        require(helper, !pairedDoorStates.isEmpty(), "Vanilla plains house did not contain its expected paired door states");
        require(helper, pairedDoorStates.stream().anyMatch(VanillaVillageBlueprints.BlueprintBlock::consumesMaterial)
                        && pairedDoorStates.stream().anyMatch(block -> !block.consumesMaterial()),
                "Builder would charge both halves of one vanilla door");
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException(message);
        }
    }
}
