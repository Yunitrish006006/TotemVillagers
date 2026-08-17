package dev.totem.villagers.work;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable work-order catalogue that makes an unmapped sell offer a startup error. */
public final class WorkOrderCatalog {
    private final Map<String, WorkOrder> orders;

    public WorkOrderCatalog(Collection<WorkOrder> orders) {
        Objects.requireNonNull(orders, "orders");
        Map<String, WorkOrder> byId = new LinkedHashMap<>();
        for (WorkOrder order : orders) {
            WorkOrder previous = byId.putIfAbsent(order.id(), order);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate work order id: " + order.id());
            }
        }
        this.orders = Map.copyOf(byId);
    }

    public WorkOrder require(String orderId) {
        WorkOrder order = orders.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Unmapped sell-side work order: " + orderId);
        }
        return order;
    }

    /** Fails data reload/startup when any known vanilla sell offer has no work source. */
    public void requireCoverage(Set<String> requiredOrderIds) {
        Objects.requireNonNull(requiredOrderIds, "requiredOrderIds");
        Set<String> missing = new java.util.TreeSet<>(requiredOrderIds);
        missing.removeAll(orders.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unmapped sell-side offers: " + String.join(", ", missing));
        }
    }

    public Map<String, WorkOrder> snapshot() {
        return orders;
    }
}
