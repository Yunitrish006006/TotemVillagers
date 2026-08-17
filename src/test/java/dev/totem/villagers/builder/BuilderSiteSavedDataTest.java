package dev.totem.villagers.builder;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderSiteSavedDataTest {
    @Test
    void onlyTheOwnerCanReplaceOrCancelTheirBuildersSite() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000811");
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000812");
        UUID builder = UUID.fromString("00000000-0000-0000-0000-000000000813");
        BuilderSiteSavedData data = new BuilderSiteSavedData();
        BuilderSite site = site(owner, builder, 0);

        assertTrue(data.registerOrReplace(site, owner));
        assertFalse(data.registerOrReplace(site(other, builder, 0), other));
        assertFalse(data.removeByBuilder(builder, other));
        assertTrue(data.removeByBuilder(builder, owner));
    }

    @Test
    void progressOnlyUpdatesTheMatchingPersistedSite() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000821");
        UUID builder = UUID.fromString("00000000-0000-0000-0000-000000000822");
        BuilderSiteSavedData data = new BuilderSiteSavedData();
        BuilderSite site = site(owner, builder, 0);
        data.registerOrReplace(site, owner);

        data.updateProgress(site.withNextBlockIndex(7));
        assertEquals(7, data.getByBuilder(builder).orElseThrow().nextBlockIndex());
        data.updateProgress(site(UUID.fromString("00000000-0000-0000-0000-000000000823"), builder, 4));
        assertEquals(7, data.getByBuilder(builder).orElseThrow().nextBlockIndex());
    }

    private static BuilderSite site(UUID owner, UUID builder, int progress) {
        return new BuilderSite(UUID.randomUUID(), owner, builder,
                "minecraft:village/plains/houses/plains_small_house_1", "minecraft:overworld",
                new net.minecraft.core.BlockPos(10, 64, 10).asLong(), progress);
    }
}
