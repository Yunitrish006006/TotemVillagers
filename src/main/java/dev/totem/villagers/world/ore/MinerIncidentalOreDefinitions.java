package dev.totem.villagers.world.ore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.totem.villagers.TotemVillagers;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomically reloads the Miner's vanilla-substrate incidental-ore profiles. */
public final class MinerIncidentalOreDefinitions {
    private static final String DIRECTORY = "totem_villagers/incidental_ores";
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(
            "totem-villagers", "miner_incidental_ores");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile MinerIncidentalOreCatalog catalog = new MinerIncidentalOreCatalog(List.of());

    private MinerIncidentalOreDefinitions() {
    }

    public static void registerReloadListener() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RELOAD_LISTENER_ID;
            }

            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                MinerIncidentalOreDefinitions.reload(resourceManager);
            }
        });
    }

    public static MinerIncidentalOreCatalog catalog() {
        return catalog;
    }

    static MinerIncidentalOreCatalog parseAll(Map<Identifier, Resource> resources) {
        List<MinerIncidentalOreRule> loaded = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                MinerIncidentalOreRule rule = MinerIncidentalOreRule.CODEC.parse(JsonOps.INSTANCE, json)
                        .getOrThrow(message -> new IllegalArgumentException(entry.getKey() + ": " + message));
                validateOreBlock(entry.getKey(), rule);
                loaded.add(rule);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read incidental-ore rule " + entry.getKey(), exception);
            }
        }
        return new MinerIncidentalOreCatalog(loaded);
    }

    private static void validateOreBlock(Identifier resourceId, MinerIncidentalOreRule rule) {
        Identifier oreId = Identifier.tryParse(rule.oreBlock());
        Block block = oreId == null ? null : BuiltInRegistries.BLOCK.getValue(oreId);
        if (block == null || block == Blocks.AIR) {
            throw new IllegalArgumentException(resourceId + ": unknown ore_block " + rule.oreBlock());
        }
    }

    private static void reload(ResourceManager resourceManager) {
        try {
            Map<Identifier, Resource> resources = resourceManager.listResources(
                    DIRECTORY, id -> id.getPath().endsWith(".json"));
            MinerIncidentalOreCatalog next = parseAll(resources);
            catalog = next;
            TotemVillagers.LOGGER.info("Loaded {} Miner incidental-ore profiles", next.snapshot().size());
        } catch (RuntimeException exception) {
            TotemVillagers.LOGGER.error(
                    "Miner incidental-ore reload failed; retaining prior catalogue: {}", exception.getMessage());
        }
    }
}
