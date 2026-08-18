package dev.totem.villagers.worldgen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageUtilityPoolResourceTest {
    @Test
    void fixedVillageUtilitiesFollowTheirLocalTerrain() throws IOException {
        assertTerrainMatching("data/totem/worldgen/template_pool/village_lumberyard.json");
        assertTerrainMatching("data/totem/worldgen/template_pool/village_mine.json");
    }

    private static void assertTerrainMatching(String path) throws IOException {
        try (InputStream stream = VillageUtilityPoolResourceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Missing worldgen template pool resource: " + path);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"projection\": \"terrain_matching\""),
                    "Village utility pool must use terrain_matching projection: " + path);
        }
    }
}
