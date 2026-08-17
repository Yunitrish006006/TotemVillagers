package dev.totem.villagers.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers the common wire type once; the client receiver is registered by its client entry point. */
public final class TradeSnapshotNetworking {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private TradeSnapshotNetworking() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            PayloadTypeRegistry.clientboundPlay().register(TradeSnapshotPayload.TYPE, TradeSnapshotPayload.CODEC);
        }
    }
}
