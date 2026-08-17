package dev.totem.villagers.guard;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomically reloads Guard construction orders and keeps the validated prior set on errors. */
public final class GuardDefenceOrderDefinitions {
    private static final String DIRECTORY = "totem_villagers/guard_defence_orders";
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath("totem-villagers", "guard_defence_orders");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Map<String, GuardDefenceOrder> orders = Map.of();

    private GuardDefenceOrderDefinitions() {
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
                GuardDefenceOrderDefinitions.reload(resourceManager);
            }
        });
    }

    public static Optional<GuardDefenceOrder> get(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public static Map<String, GuardDefenceOrder> snapshot() {
        return orders;
    }

    static Map<String, GuardDefenceOrder> parseAll(Map<Identifier, Resource> resources) {
        Map<String, GuardDefenceOrder> loaded = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                GuardDefenceOrder order = GuardDefenceOrder.CODEC.parse(JsonOps.INSTANCE, json)
                        .getOrThrow(message -> new IllegalArgumentException(entry.getKey() + ": " + message));
                if (loaded.putIfAbsent(order.id(), order) != null) {
                    throw new IllegalArgumentException("Duplicate Guard defence order: " + order.id());
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read Guard defence order " + entry.getKey(), exception);
            }
        }
        GuardDefenceOrder defaultOrder = loaded.get(GuardDefenceOrder.VANILLA_IRON_GOLEM.id());
        if (defaultOrder == null || !defaultOrder.matchesVanillaIronGolemMaterials()) {
            throw new IllegalArgumentException("The default Guard order must use four iron blocks and one carved pumpkin");
        }
        return Map.copyOf(loaded);
    }

    private static void reload(ResourceManager resourceManager) {
        try {
            Map<Identifier, Resource> resources = resourceManager.listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
            Map<String, GuardDefenceOrder> next = parseAll(resources);
            orders = next;
            TotemVillagers.LOGGER.info("Loaded {} Guard defence orders", next.size());
        } catch (RuntimeException exception) {
            TotemVillagers.LOGGER.error("Guard defence-order reload failed; retaining prior orders: {}", exception.getMessage());
        }
    }
}
