package dev.totem.villagers.workshop;

import dev.totem.villagers.work.ItemAmount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe in-memory Work Chest adapter used by the commit authority and tests. */
public final class MapWorkChestInventory implements WorkChestInventory {
    private final Map<String, Integer> available = new LinkedHashMap<>();

    public MapWorkChestInventory(Map<String, Integer> initialContents) {
        initialContents.forEach((item, count) -> available.put(new ItemAmount(item, count).itemId(), count));
    }

    @Override
    public synchronized boolean canReserveExact(List<ItemAmount> requiredInputs) {
        Map<String, Integer> requested = aggregate(requiredInputs);
        return requested.entrySet().stream().allMatch(entry -> available.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    @Override
    public synchronized Optional<Reservation> reserveExact(List<ItemAmount> requiredInputs) {
        Map<String, Integer> requested = aggregate(requiredInputs);
        if (!canReserveExact(requiredInputs)) {
            return Optional.empty();
        }
        requested.forEach((item, count) -> {
            int remaining = available.get(item) - count;
            if (remaining == 0) available.remove(item); else available.put(item, remaining);
        });
        List<ItemAmount> reserved = requested.entrySet().stream().map(entry -> new ItemAmount(entry.getKey(), entry.getValue())).toList();
        return Optional.of(new Reservation() {
            private boolean active = true;

            @Override
            public List<ItemAmount> reservedInputs() {
                return reserved;
            }

            @Override
            public void commit() {
                finish(false);
            }

            @Override
            public boolean commitWithReturn(ItemStack returnedItem) {
                if (returnedItem == null) {
                    throw new NullPointerException("returnedItem");
                }
                synchronized (this) {
                    if (!active) {
                        throw new IllegalStateException("Work Chest reservation is already closed");
                    }
                    synchronized (MapWorkChestInventory.this) {
                        if (!returnedItem.isEmpty()) {
                            String itemId = BuiltInRegistries.ITEM.getKey(returnedItem.getItem()).toString();
                            available.merge(itemId, returnedItem.getCount(), Math::addExact);
                        }
                    }
                    active = false;
                    return true;
                }
            }

            @Override
            public void rollback() {
                finish(true);
            }

            private synchronized void finish(boolean restore) {
                if (!active) {
                    throw new IllegalStateException("Work Chest reservation is already closed");
                }
                active = false;
                if (restore) {
                    synchronized (MapWorkChestInventory.this) {
                        reserved.forEach(input -> available.merge(input.itemId(), input.count(), Math::addExact));
                    }
                }
            }
        });
    }

    @Override
    public synchronized boolean canInsertExact(ItemStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Override
    public synchronized boolean insertExact(ItemStack stack) {
        if (!canInsertExact(stack)) {
            return false;
        }
        available.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Math::addExact);
        return true;
    }

    @Override
    public synchronized boolean canInsertAllExact(List<ItemStack> stacks) {
        return stacks != null && !stacks.isEmpty()
                && stacks.stream().allMatch(stack -> stack != null && !stack.isEmpty());
    }

    @Override
    public synchronized boolean insertAllExact(List<ItemStack> stacks) {
        if (!canInsertAllExact(stacks)) {
            return false;
        }
        stacks.forEach(stack -> available.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), Math::addExact));
        return true;
    }

    public synchronized Map<String, Integer> snapshot() {
        return Map.copyOf(available);
    }

    private static Map<String, Integer> aggregate(List<ItemAmount> inputs) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (ItemAmount input : inputs) {
            requested.merge(input.itemId(), input.count(), Math::addExact);
        }
        return requested;
    }
}
