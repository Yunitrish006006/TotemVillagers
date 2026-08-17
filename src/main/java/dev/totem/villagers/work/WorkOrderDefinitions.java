package dev.totem.villagers.work;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.totem.villagers.TotemVillagers;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomically reloads data-pack work-order documents; an invalid reload keeps the prior catalogue live. */
public final class WorkOrderDefinitions {
    private static final String DIRECTORY = "totem_villagers/work_orders";
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath("totem-villagers", "work_orders");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile WorkOrderCatalog catalog = new WorkOrderCatalog(List.of());

    private WorkOrderDefinitions() {
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
                WorkOrderDefinitions.reload(resourceManager);
            }
        });
    }

    public static WorkOrderCatalog catalog() {
        return catalog;
    }

    static WorkOrderCatalog parseAll(Map<Identifier, Resource> resources) {
        List<WorkOrder> loaded = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                WorkOrder order = WorkOrder.CODEC.parse(JsonOps.INSTANCE, json)
                        .getOrThrow(message -> new IllegalArgumentException(entry.getKey() + ": " + message));
                loaded.add(order);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read work order " + entry.getKey(), exception);
            }
        }
        return new WorkOrderCatalog(loaded);
    }

    private static void reload(ResourceManager resourceManager) {
        try {
            Map<Identifier, Resource> resources = resourceManager.listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
            WorkOrderCatalog next = parseAll(resources);
            catalog = next;
            TotemVillagers.LOGGER.info("Loaded {} work-backed villager orders", next.snapshot().size());
        } catch (RuntimeException exception) {
            TotemVillagers.LOGGER.error("Work-order reload failed; retaining prior catalogue: {}", exception.getMessage());
        }
    }
}
