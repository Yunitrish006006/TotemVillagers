package dev.totem.villagers.workshop;

import dev.totem.villagers.work.ItemAmount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Transactional adapter for a registered vanilla container. Exact raw stacks leave
 * the chest during a reservation, so another worker cannot spend the same inputs.
 */
public final class ContainerWorkChestInventory implements WorkChestInventory {
    private static final Map<WorkChestKey, Object> LOCKS = new ConcurrentHashMap<>();

    private final WorkChestKey key;
    private final Supplier<Container> resolver;
    private final Consumer<ItemStack> recoveryDrop;

    public ContainerWorkChestInventory(WorkChestKey key, Supplier<Container> resolver, Consumer<ItemStack> recoveryDrop) {
        this.key = java.util.Objects.requireNonNull(key, "key");
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.recoveryDrop = java.util.Objects.requireNonNull(recoveryDrop, "recoveryDrop");
    }

    @Override
    public boolean canReserveExact(List<ItemAmount> requiredInputs) {
        Map<String, Integer> required = aggregate(requiredInputs);
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            return container != null && containsAll(container, required);
        }
    }

    @Override
    public Optional<Reservation> reserveExact(List<ItemAmount> requiredInputs) {
        Map<String, Integer> required = aggregate(requiredInputs);
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            if (container == null || !containsAll(container, required)) {
                return Optional.empty();
            }
            List<RemovedStack> removed = removeExact(container, required);
            container.setChanged();
            return Optional.of(new Reservation() {
                private boolean open = true;

                @Override
                public List<ItemAmount> reservedInputs() {
                    return removed.stream().map(entry -> new ItemAmount(itemId(entry.stack()), entry.stack().getCount())).toList();
                }

                @Override
                public void commit() {
                    if (!commitWithReturn(ItemStack.EMPTY)) {
                        throw new IllegalStateException("An empty Work Chest return must always commit");
                    }
                }

                @Override
                public boolean commitWithReturn(ItemStack returnedItem) {
                    if (returnedItem == null) {
                        throw new NullPointerException("returnedItem");
                    }
                    synchronized (lock) {
                        if (!open) {
                            throw new IllegalStateException("Work Chest reservation is already closed");
                        }
                        if (!returnedItem.isEmpty()) {
                            Container current = resolver.get();
                            if (current == null || !hasRoomFor(current, returnedItem)) {
                                return false;
                            }
                            ItemStack remaining = returnedItem.copy();
                            insertRemaining(current, remaining);
                            if (!remaining.isEmpty()) {
                                throw new IllegalStateException("Work Chest capacity changed during a synchronized return");
                            }
                            current.setChanged();
                        }
                        open = false;
                        return true;
                    }
                }

                @Override
                public void rollback() {
                    close(true);
                }

                private void close(boolean restore) {
                    synchronized (lock) {
                        if (!open) {
                            throw new IllegalStateException("Work Chest reservation is already closed");
                        }
                        open = false;
                        if (!restore) {
                            return;
                        }
                        Container current = resolver.get();
                        if (current == null) {
                            removed.forEach(entry -> recoveryDrop.accept(entry.stack().copy()));
                            return;
                        }
                        for (RemovedStack entry : removed) {
                            ItemStack remaining = entry.stack().copy();
                            restoreOriginalSlot(current, entry.slot(), remaining);
                            insertRemaining(current, remaining);
                            if (!remaining.isEmpty()) {
                                recoveryDrop.accept(remaining.copy());
                            }
                        }
                        current.setChanged();
                    }
                }
            });
        }
    }

    /**
     * Adds the whole produced stack only when the registered container can hold
     * all of it. World-work producers use this to avoid destroying a source block
     * and then spilling an unaccounted output when a Work Chest is full.
     */
    public boolean insertExact(ItemStack produced) {
        if (produced == null || produced.isEmpty()) {
            return false;
        }
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            if (container == null || !hasRoomFor(container, produced)) {
                return false;
            }
            ItemStack remaining = produced.copy();
            insertRemaining(container, remaining);
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("Work Chest capacity changed during a synchronized insertion");
            }
            container.setChanged();
            return true;
        }
    }

    /** Read-only capacity check paired with {@link #insertExact(ItemStack)}. */
    public boolean canInsertExact(ItemStack produced) {
        if (produced == null || produced.isEmpty()) {
            return false;
        }
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            return container != null && hasRoomFor(container, produced);
        }
    }

    @Override
    public boolean canInsertAllExact(List<ItemStack> produced) {
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            return container != null && hasRoomForAll(container, produced);
        }
    }

    @Override
    public boolean insertAllExact(List<ItemStack> produced) {
        Object lock = LOCKS.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Container container = resolver.get();
            if (container == null || !hasRoomForAll(container, produced)) {
                return false;
            }
            for (ItemStack stack : produced) {
                ItemStack remaining = stack.copy();
                insertRemaining(container, remaining);
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException("Work Chest capacity changed during an atomic gathered-yield insertion");
                }
            }
            container.setChanged();
            return true;
        }
    }

    private static boolean containsAll(Container container, Map<String, Integer> required) {
        Map<String, Integer> available = new LinkedHashMap<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                available.merge(itemId(stack), stack.getCount(), Math::addExact);
            }
        }
        return required.entrySet().stream().allMatch(entry -> available.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private static List<RemovedStack> removeExact(Container container, Map<String, Integer> required) {
        List<RemovedStack> removed = new ArrayList<>();
        for (Map.Entry<String, Integer> requirement : required.entrySet()) {
            int remaining = requirement.getValue();
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !itemId(stack).equals(requirement.getKey())) {
                    continue;
                }
                ItemStack extracted = container.removeItem(slot, Math.min(remaining, stack.getCount()));
                if (!extracted.isEmpty()) {
                    removed.add(new RemovedStack(slot, extracted));
                    remaining -= extracted.getCount();
                }
            }
            if (remaining != 0) {
                throw new IllegalStateException("Work Chest contents changed during a synchronized reservation");
            }
        }
        return List.copyOf(removed);
    }

    private static void restoreOriginalSlot(Container container, int slot, ItemStack remaining) {
        ItemStack existing = container.getItem(slot);
        if (existing.isEmpty()) {
            int placed = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            container.setItem(slot, remaining.copyWithCount(placed));
            remaining.shrink(placed);
        } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
            int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            if (moved > 0) {
                existing.grow(moved);
                remaining.shrink(moved);
                container.setItem(slot, existing);
            }
        }
    }

    private static void insertRemaining(Container container, ItemStack remaining) {
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)
                    && container.canPlaceItem(slot, remaining)) {
                int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize());
                int moved = Math.min(remaining.getCount(), limit - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                    container.setItem(slot, existing);
                }
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (!container.getItem(slot).isEmpty() || !container.canPlaceItem(slot, remaining)) {
                continue;
            }
            int placed = Math.min(remaining.getCount(), Math.min(remaining.getMaxStackSize(), container.getMaxStackSize()));
            container.setItem(slot, remaining.copyWithCount(placed));
            remaining.shrink(placed);
        }
    }

    private static boolean hasRoomFor(Container container, ItemStack produced) {
        long remaining = produced.getCount();
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.canPlaceItem(slot, produced)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                remaining -= Math.min(produced.getMaxStackSize(), container.getMaxStackSize());
            } else if (ItemStack.isSameItemSameComponents(existing, produced)) {
                remaining -= Math.max(0, Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount());
            }
        }
        return remaining <= 0;
    }

    private static boolean hasRoomForAll(Container container, List<ItemStack> produced) {
        if (produced == null || produced.isEmpty()
                || produced.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
            return false;
        }
        List<ItemStack> simulated = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            simulated.add(container.getItem(slot).copy());
        }
        for (ItemStack stack : produced) {
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < simulated.size() && !remaining.isEmpty(); slot++) {
                ItemStack existing = simulated.get(slot);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)
                        && container.canPlaceItem(slot, remaining)) {
                    int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize());
                    int moved = Math.min(remaining.getCount(), limit - existing.getCount());
                    if (moved > 0) {
                        existing.grow(moved);
                        remaining.shrink(moved);
                        simulated.set(slot, existing);
                    }
                }
            }
            for (int slot = 0; slot < simulated.size() && !remaining.isEmpty(); slot++) {
                if (!simulated.get(slot).isEmpty() || !container.canPlaceItem(slot, remaining)) {
                    continue;
                }
                int placed = Math.min(remaining.getCount(), Math.min(remaining.getMaxStackSize(), container.getMaxStackSize()));
                simulated.set(slot, remaining.copyWithCount(placed));
                remaining.shrink(placed);
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> aggregate(List<ItemAmount> inputs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (ItemAmount input : inputs) {
            result.merge(input.itemId(), input.count(), Math::addExact);
        }
        return result;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private record RemovedStack(int slot, ItemStack stack) {
    }
}
