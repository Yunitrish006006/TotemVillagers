package dev.totem.villagers.work;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * Per-villager stock ledger.  All mutations are synchronized so a work completion and
 * trade completion cannot both spend the same produced item.
 */
public final class MerchantStock {
    private final Map<String, Integer> counts;
    private final Map<StockVariantKey, Integer> variantCounts;

    public MerchantStock() {
        this(Map.of());
    }

    public MerchantStock(Map<String, Integer> initialCounts) {
        this(initialCounts, Map.of());
    }

    public MerchantStock(Map<String, Integer> initialCounts, Map<StockVariantKey, Integer> initialVariantCounts) {
        this.counts = new LinkedHashMap<>();
        this.variantCounts = new LinkedHashMap<>();
        initialCounts.forEach((itemId, count) -> {
            new ItemAmount(itemId, count);
            counts.put(itemId, count);
        });
        initialVariantCounts.forEach((key, count) -> {
            new StockVariantAmount(key, count);
            variantCounts.put(key, count);
        });
    }

    public synchronized int available(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    public synchronized int available(StockVariantKey key) {
        Objects.requireNonNull(key, "key");
        return key.isBaseItem() ? available(key.itemId()) : variantCounts.getOrDefault(key, 0);
    }

    /** Adds one completed order output, clamped to that order's persistent stock cap. */
    public synchronized int recordCompletedWork(WorkOrder order) {
        Objects.requireNonNull(order, "order");
        StockVariantKey key = order.outputKey();
        int before = available(key);
        int after = (int) Math.min(order.stockCap(), (long) before + order.output().count());
        put(key, after);
        return after - before;
    }

    /** Adds an already physical stack while building a read-only inventory snapshot. */
    public synchronized void credit(StockVariantKey key, int count) {
        Objects.requireNonNull(key, "key");
        new ItemAmount(key.itemId(), count);
        put(key, Math.addExact(available(key), count));
    }

    /** Atomically debits one sell-side output only when all produced stock remains. */
    public synchronized boolean debitForTrade(ItemAmount soldStack) {
        Objects.requireNonNull(soldStack, "soldStack");
        return debitForTrade(StockVariantKey.base(soldStack.itemId()), soldStack.count());
    }

    public synchronized boolean debitForTrade(ItemStack soldStack, HolderLookup.Provider registries) {
        Objects.requireNonNull(soldStack, "soldStack");
        return debitForTrade(StockVariantKey.fromStack(soldStack, registries), soldStack.getCount());
    }

    public synchronized boolean debitForTrade(StockVariantKey key, int count) {
        Objects.requireNonNull(key, "key");
        new ItemAmount(key.itemId(), count);
        int before = available(key);
        if (before < count) {
            return false;
        }
        put(key, before - count);
        return true;
    }

    public synchronized Map<String, Integer> snapshot() {
        return Map.copyOf(counts);
    }

    public synchronized Map<StockVariantKey, Integer> variantSnapshot() {
        return Map.copyOf(variantCounts);
    }

    private void put(StockVariantKey key, int count) {
        if (count == 0) {
            if (key.isBaseItem()) {
                counts.remove(key.itemId());
            } else {
                variantCounts.remove(key);
            }
        } else if (key.isBaseItem()) {
            counts.put(key.itemId(), count);
        } else {
            variantCounts.put(key, count);
        }
    }
}
