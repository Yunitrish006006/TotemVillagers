package dev.totem.villagers.workshop;

import dev.totem.villagers.inventory.WorkInventory;

/**
 * A server-side adapter around a registered Work Chest. Reserving moves the exact
 * raw inputs out of normal availability; callers must commit or roll back it once.
 */
@Deprecated
public interface WorkChestInventory extends WorkInventory {
}
