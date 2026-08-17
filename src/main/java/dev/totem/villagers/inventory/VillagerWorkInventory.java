package dev.totem.villagers.inventory;

import dev.totem.villagers.work.ItemAmount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transactional adapter around one villager's persisted 27-slot work inventory. */
public final class VillagerWorkInventory implements WorkInventory {
    private final VillagerWorkInventorySavedData savedData;
    private final UUID villagerId;

    VillagerWorkInventory(VillagerWorkInventorySavedData savedData, UUID villagerId) {
        this.savedData = Objects.requireNonNull(savedData, "savedData");
        this.villagerId = Objects.requireNonNull(villagerId, "villagerId");
    }

    @Override
    public boolean canReserveExact(List<ItemAmount> requiredInputs) {
        synchronized (savedData) {
            return containsAll(savedData.copySlots(villagerId), aggregate(requiredInputs));
        }
    }

    @Override
    public Optional<Reservation> reserveExact(List<ItemAmount> requiredInputs) {
        Map<String, Integer> required = aggregate(requiredInputs);
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            if (!containsAll(slots, required)) {
                return Optional.empty();
            }
            List<RemovedStack> removed = removeExact(slots, required);
            savedData.replaceSlots(villagerId, slots);
            return Optional.of(reservation(removed));
        }
    }

    /**
     * Reserves one exact component-sensitive stack.  Enchanting equipment uses
     * this so a damaged or named lookalike can never be consumed in place of
     * the pristine item that was accepted for processing.
     */
    public Optional<Reservation> reserveExactMatchingItem(ItemStack requiredStack) {
        return reserveExactMatching(requiredStack, List.of());
    }

    /** Checks a component-sensitive batch without treating filled containers as pristine recipe inputs. */
    public boolean canReserveExactMatching(List<ItemStack> requiredStacks) {
        if (requiredStacks == null || requiredStacks.isEmpty()
                || requiredStacks.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
            return false;
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            return removeMatchingBatch(slots, requiredStacks) != null;
        }
    }

    /** Atomically reserves several exact item/component variants, including repeated Smithing Table inputs. */
    public Optional<Reservation> reserveExactMatching(List<ItemStack> requiredStacks) {
        if (requiredStacks == null || requiredStacks.isEmpty()
                || requiredStacks.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
            return Optional.empty();
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            List<RemovedStack> removed = removeMatchingBatch(slots, requiredStacks);
            if (removed == null) {
                return Optional.empty();
            }
            savedData.replaceSlots(villagerId, slots);
            return Optional.of(reservation(List.copyOf(removed)));
        }
    }

    /** Atomically reserves an exact stack together with ordinary counted inputs such as lapis lazuli. */
    public Optional<Reservation> reserveExactMatching(ItemStack requiredStack, List<ItemAmount> additionalInputs) {
        if (requiredStack == null || requiredStack.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> additional = aggregate(additionalInputs == null ? List.of() : additionalInputs);
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            int available = slots.stream()
                    .filter(stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, requiredStack))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (available < requiredStack.getCount()) {
                return Optional.empty();
            }
            int remaining = requiredStack.getCount();
            List<RemovedStack> removed = new ArrayList<>();
            for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, requiredStack)) {
                    continue;
                }
                int extracted = Math.min(remaining, stack.getCount());
                removed.add(new RemovedStack(slot, stack.copyWithCount(extracted)));
                stack.shrink(extracted);
                slots.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                remaining -= extracted;
            }
            if (remaining != 0) {
                throw new IllegalStateException("Work inventory changed during an exact component reservation");
            }
            if (!containsAll(slots, additional)) {
                return Optional.empty();
            }
            removed.addAll(removeExact(slots, additional));
            savedData.replaceSlots(villagerId, slots);
            return Optional.of(reservation(List.copyOf(removed)));
        }
    }

    private Reservation reservation(List<RemovedStack> removed) {
        return new Reservation() {
                private boolean open = true;

                @Override
                public List<ItemAmount> reservedInputs() {
                    return removed.stream().map(entry -> new ItemAmount(itemId(entry.stack()), entry.stack().getCount())).toList();
                }

                @Override
                public void commit() {
                    if (!commitWithReturns(List.of())) {
                        throw new IllegalStateException("An empty work-inventory return must always commit");
                    }
                }

                @Override
                public boolean commitWithReturn(ItemStack returnedItem) {
                    Objects.requireNonNull(returnedItem, "returnedItem");
                    return returnedItem.isEmpty() ? commitWithReturns(List.of()) : commitWithReturns(List.of(returnedItem));
                }

                @Override
                public boolean commitWithReturns(List<ItemStack> returnedItems) {
                    if (returnedItems == null || returnedItems.stream().anyMatch(stack -> stack == null || stack.isEmpty())) {
                        throw new IllegalArgumentException("Returned stacks must be non-null and non-empty");
                    }
                    synchronized (savedData) {
                        ensureOpen();
                        List<ItemStack> current = savedData.copySlots(villagerId);
                        if (!returnedItems.isEmpty() && !canInsertAll(current, returnedItems)) {
                            return false;
                        }
                        for (ItemStack returnedItem : returnedItems) {
                            ItemStack remaining = returnedItem.copy();
                            insertRemaining(current, remaining);
                            if (!remaining.isEmpty()) {
                                throw new IllegalStateException("Work inventory capacity changed during a synchronized return");
                            }
                        }
                        savedData.replaceSlots(villagerId, current);
                        open = false;
                        return true;
                    }
                }

                @Override
                public void rollback() {
                    synchronized (savedData) {
                        ensureOpen();
                        List<ItemStack> current = savedData.copySlots(villagerId);
                        for (RemovedStack entry : removed) {
                            ItemStack remaining = entry.stack().copy();
                            restoreOriginalSlot(current, entry.slot(), remaining);
                            insertRemaining(current, remaining);
                            if (!remaining.isEmpty()) {
                                throw new IllegalStateException("Could not restore a personal work-inventory reservation");
                            }
                        }
                        savedData.replaceSlots(villagerId, current);
                        open = false;
                    }
                }

                private void ensureOpen() {
                    if (!open) {
                        throw new IllegalStateException("Work inventory reservation is already closed");
                    }
                }
            };
    }

    private static List<RemovedStack> removeMatchingBatch(List<ItemStack> slots, List<ItemStack> requiredStacks) {
        List<RemovedStack> removed = new ArrayList<>();
        for (ItemStack requested : requiredStacks) {
            int remaining = requested.getCount();
            for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
                ItemStack available = slots.get(slot);
                if (available.isEmpty() || !ItemStack.isSameItemSameComponents(available, requested)) {
                    continue;
                }
                int extracted = Math.min(remaining, available.getCount());
                removed.add(new RemovedStack(slot, available.copyWithCount(extracted)));
                available.shrink(extracted);
                slots.set(slot, available.isEmpty() ? ItemStack.EMPTY : available);
                remaining -= extracted;
            }
            if (remaining > 0) {
                return null;
            }
        }
        return removed;
    }

    @Override
    public boolean canInsertExact(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        synchronized (savedData) {
            return hasRoomFor(savedData.copySlots(villagerId), stack);
        }
    }

    @Override
    public boolean insertExact(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            if (!hasRoomFor(slots, stack)) {
                return false;
            }
            ItemStack remaining = stack.copy();
            insertRemaining(slots, remaining);
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("Work inventory capacity changed during a synchronized insertion");
            }
            savedData.replaceSlots(villagerId, slots);
            return true;
        }
    }

    @Override
    public boolean canInsertAllExact(List<ItemStack> stacks) {
        synchronized (savedData) {
            return canInsertAll(savedData.copySlots(villagerId), stacks);
        }
    }

    @Override
    public boolean insertAllExact(List<ItemStack> stacks) {
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            if (!canInsertAll(slots, stacks)) {
                return false;
            }
            for (ItemStack stack : stacks) {
                ItemStack remaining = stack.copy();
                insertRemaining(slots, remaining);
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException("Work inventory capacity changed during an atomic gathered-yield insertion");
                }
            }
            savedData.replaceSlots(villagerId, slots);
            return true;
        }
    }

    public List<ItemStack> snapshot() {
        return savedData.snapshot(villagerId);
    }

    /**
     * Returns a copy of the first visible material stack without changing this
     * inventory. Callers can use it to check a destination before withdrawing.
     */
    public Optional<ItemStack> peekFirstStack() {
        synchronized (savedData) {
            return savedData.copySlots(villagerId).stream()
                    .filter(stack -> !stack.isEmpty())
                    .findFirst()
                    .map(ItemStack::copy);
        }
    }

    /**
     * Removes and returns the first visible material stack as one complete
     * stack. Active reservations are already absent from these slots, so they
     * cannot be withdrawn while another runtime owns them.
     */
    public Optional<ItemStack> takeFirstStack() {
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                slots.set(slot, ItemStack.EMPTY);
                savedData.replaceSlots(villagerId, slots);
                return Optional.of(stack.copy());
            }
            return Optional.empty();
        }
    }

    /** Returns up to {@code maximumCount} of the first visible stack with this exact item ID. */
    public Optional<ItemStack> peekFirstMatchingItem(String requiredItemId, int maximumCount) {
        if (requiredItemId == null || requiredItemId.isBlank() || maximumCount < 1) {
            return Optional.empty();
        }
        synchronized (savedData) {
            return savedData.copySlots(villagerId).stream()
                    .filter(stack -> !stack.isEmpty() && itemId(stack).equals(requiredItemId))
                    .findFirst()
                    .map(stack -> stack.copyWithCount(Math.min(stack.getCount(), maximumCount)));
        }
    }

    /**
     * Removes up to {@code maximumCount} from the first visible stack with this
     * item ID. Any active reservation is absent from these slots and therefore
     * cannot be moved by village logistics.
     */
    public Optional<ItemStack> takeFirstMatchingItem(String requiredItemId, int maximumCount) {
        if (requiredItemId == null || requiredItemId.isBlank() || maximumCount < 1) {
            return Optional.empty();
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            for (int slot = 0; slot < slots.size(); slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty() || !itemId(stack).equals(requiredItemId)) {
                    continue;
                }
                int moved = Math.min(stack.getCount(), maximumCount);
                ItemStack extracted = stack.copyWithCount(moved);
                stack.shrink(moved);
                slots.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                savedData.replaceSlots(villagerId, slots);
                return Optional.of(extracted);
            }
            return Optional.empty();
        }
    }

    /**
     * Counts material that matches a live vanilla trade cost, including its data
     * components. The supplied count is ignored so callers can compare the
     * available total with the cost's required batch size.
     */
    public int countMatchingItem(ItemStack requiredStack) {
        if (requiredStack == null || requiredStack.isEmpty()) {
            return 0;
        }
        synchronized (savedData) {
            return savedData.copySlots(villagerId).stream()
                    .filter(stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, requiredStack))
                    .mapToInt(ItemStack::getCount)
                    .sum();
        }
    }

    /**
     * Removes exactly one live vanilla trade-cost batch, potentially across
     * several slots. It returns empty without changing inventory when the full
     * component-sensitive batch is not present.
     */
    public Optional<ItemStack> takeExactMatchingItem(ItemStack requiredStack) {
        if (requiredStack == null || requiredStack.isEmpty()) {
            return Optional.empty();
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            int available = slots.stream()
                    .filter(stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, requiredStack))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (available < requiredStack.getCount()) {
                return Optional.empty();
            }
            int remaining = requiredStack.getCount();
            for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, requiredStack)) {
                    continue;
                }
                int removed = Math.min(stack.getCount(), remaining);
                stack.shrink(removed);
                slots.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                remaining -= removed;
            }
            if (remaining != 0) {
                throw new IllegalStateException("Work inventory changed during an exact material purchase");
            }
            savedData.replaceSlots(villagerId, slots);
            return Optional.of(requiredStack.copy());
        }
    }

    /**
     * Atomically replaces one component-sensitive material batch with an
     * output stack. This is used by compact workstations whose physical block
     * is an interaction site, while material ownership stays with the worker.
     */
    public boolean exchangeExactMatching(ItemStack input, ItemStack output) {
        if (input == null || input.isEmpty() || output == null || output.isEmpty()) {
            return false;
        }
        synchronized (savedData) {
            List<ItemStack> slots = savedData.copySlots(villagerId);
            int available = slots.stream()
                    .filter(stack -> !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, input))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            if (available < input.getCount()) {
                return false;
            }
            int remainingInput = input.getCount();
            for (int slot = 0; slot < slots.size() && remainingInput > 0; slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, input)) {
                    continue;
                }
                int removed = Math.min(stack.getCount(), remainingInput);
                stack.shrink(removed);
                slots.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                remainingInput -= removed;
            }
            if (remainingInput != 0 || !hasRoomFor(slots, output)) {
                return false;
            }
            ItemStack remainingOutput = output.copy();
            insertRemaining(slots, remainingOutput);
            if (!remainingOutput.isEmpty()) {
                throw new IllegalStateException("Work inventory capacity changed during an atomic material exchange");
            }
            savedData.replaceSlots(villagerId, slots);
            return true;
        }
    }

    private static boolean containsAll(List<ItemStack> slots, Map<String, Integer> required) {
        Map<String, Integer> available = new LinkedHashMap<>();
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                available.merge(itemId(stack), stack.getCount(), Math::addExact);
            }
        }
        return required.entrySet().stream().allMatch(entry -> available.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private static List<RemovedStack> removeExact(List<ItemStack> slots, Map<String, Integer> required) {
        List<RemovedStack> removed = new ArrayList<>();
        for (Map.Entry<String, Integer> requirement : required.entrySet()) {
            int remaining = requirement.getValue();
            for (int slot = 0; slot < slots.size() && remaining > 0; slot++) {
                ItemStack stack = slots.get(slot);
                if (stack.isEmpty() || !itemId(stack).equals(requirement.getKey())) {
                    continue;
                }
                int extracted = Math.min(remaining, stack.getCount());
                removed.add(new RemovedStack(slot, stack.copyWithCount(extracted)));
                stack.shrink(extracted);
                slots.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                remaining -= extracted;
            }
            if (remaining != 0) {
                throw new IllegalStateException("Work inventory changed during a synchronized reservation");
            }
        }
        return List.copyOf(removed);
    }

    private static void restoreOriginalSlot(List<ItemStack> slots, int slot, ItemStack remaining) {
        ItemStack existing = slots.get(slot);
        if (existing.isEmpty()) {
            int placed = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            slots.set(slot, remaining.copyWithCount(placed));
            remaining.shrink(placed);
        } else if (ItemStack.isSameItemSameComponents(existing, remaining)) {
            int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            if (moved > 0) {
                existing.grow(moved);
                slots.set(slot, existing);
                remaining.shrink(moved);
            }
        }
    }

    private static void insertRemaining(List<ItemStack> slots, ItemStack remaining) {
        for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = slots.get(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining)) {
                int moved = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    slots.set(slot, existing);
                    remaining.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
            if (!slots.get(slot).isEmpty()) {
                continue;
            }
            int placed = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            slots.set(slot, remaining.copyWithCount(placed));
            remaining.shrink(placed);
        }
    }

    private static boolean hasRoomFor(List<ItemStack> slots, ItemStack inserted) {
        long remaining = inserted.getCount();
        for (ItemStack existing : slots) {
            if (existing.isEmpty()) {
                remaining -= inserted.getMaxStackSize();
            } else if (ItemStack.isSameItemSameComponents(existing, inserted)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getCount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean canInsertAll(List<ItemStack> initialSlots, List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return false;
        }
        List<ItemStack> slots = new ArrayList<>(initialSlots.stream().map(ItemStack::copy).toList());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty() || !hasRoomFor(slots, stack)) {
                return false;
            }
            ItemStack remaining = stack.copy();
            insertRemaining(slots, remaining);
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("Work inventory capacity changed while checking an atomic gathered yield");
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
