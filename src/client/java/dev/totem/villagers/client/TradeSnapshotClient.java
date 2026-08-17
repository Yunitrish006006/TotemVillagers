package dev.totem.villagers.client;

import dev.totem.villagers.network.TradeSnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Holds only the latest server packet for each open-menu id; it never infers trade availability. */
public final class TradeSnapshotClient {
    private static final int MAX_RETAINED_MENUS = 8;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<Integer, TradeSnapshotPayload> SNAPSHOTS = new LinkedHashMap<>();

    private TradeSnapshotClient() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ClientPlayNetworking.registerGlobalReceiver(TradeSnapshotPayload.TYPE,
                (payload, context) -> context.client().execute(() -> accept(payload)));
    }

    public static Optional<TradeSnapshotPayload.Offer> selectedOffer(int containerId, int offerIndex) {
        synchronized (SNAPSHOTS) {
            return Optional.ofNullable(SNAPSHOTS.get(containerId))
                    .flatMap(snapshot -> snapshot.offers().stream()
                            .filter(offer -> offer.index() == offerIndex)
                            .findFirst());
        }
    }

    public static Optional<TradeSnapshotPayload> snapshot(int containerId) {
        synchronized (SNAPSHOTS) {
            return Optional.ofNullable(SNAPSHOTS.get(containerId));
        }
    }

    public static void forget(int containerId) {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.remove(containerId);
        }
    }

    /**
     * Accepts one server-authoritative snapshot on the client thread.
     *
     * <p>Keeping packet dispatch separate from state retention also lets the
     * client GameTest exercise the same ingress path without fabricating UI
     * state inside the renderer.</p>
     */
    static void accept(TradeSnapshotPayload snapshot) {
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.remove(snapshot.containerId());
            SNAPSHOTS.put(snapshot.containerId(), snapshot);
            while (SNAPSHOTS.size() > MAX_RETAINED_MENUS) {
                Integer oldest = SNAPSHOTS.keySet().iterator().next();
                SNAPSHOTS.remove(oldest);
            }
        }
    }
}
