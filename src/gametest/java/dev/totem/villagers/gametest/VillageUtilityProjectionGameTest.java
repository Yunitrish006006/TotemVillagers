package dev.totem.villagers.gametest;

import dev.totem.villagers.worldgen.VillageTownCenterUtilityInjector;
import dev.totem.villagers.worldgen.VillageUtilityPoolElement;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/** Runtime registry coverage for the terrain-matching village utility pools. */
public final class VillageUtilityProjectionGameTest {
    @GameTest(maxTicks = 20)
    public void utilityPoolsDecodeAsTerrainMatching(GameTestHelper helper) {
        var pools = helper.getLevel().registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
        requireTerrainMatching(helper, pools.getValue(VillageTownCenterUtilityInjector.LUMBERYARD_POOL_ID),
                VillageTownCenterUtilityInjector.LUMBERYARD_POOL_ID);
        requireTerrainMatching(helper, pools.getValue(VillageTownCenterUtilityInjector.MINE_POOL_ID),
                VillageTownCenterUtilityInjector.MINE_POOL_ID);
        helper.succeed();
    }

    private static void requireTerrainMatching(
            GameTestHelper helper,
            StructureTemplatePool pool,
            Identifier poolId) {
        if (pool == null || pool.getTemplates().size() != 1) {
            helper.fail("Expected one registered utility element in " + poolId);
            return;
        }
        StructurePoolElement element = pool.getTemplates().getFirst().getFirst();
        if (!(element instanceof VillageUtilityPoolElement)) {
            helper.fail("Utility pool did not decode to VillageUtilityPoolElement: " + poolId);
            return;
        }
        if (element.getProjection() != StructureTemplatePool.Projection.TERRAIN_MATCHING) {
            helper.fail("Utility pool did not retain terrain_matching projection at runtime: " + poolId);
        }
    }
}
