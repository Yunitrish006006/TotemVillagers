package dev.totem.villagers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedServerMetadataTest {
    @Test
    void commonEntrypointIsAvailableOnDedicatedServers() throws IOException {
        try (var input = DedicatedServerMetadataTest.class.getResourceAsStream("/fabric.mod.json")) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(metadata.contains("dev.totem.villagers.TotemVillagers"));
            assertTrue(metadata.contains("\"environment\": \"*\""));
        }
    }
}
