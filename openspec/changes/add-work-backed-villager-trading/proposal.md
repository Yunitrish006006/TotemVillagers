## Why

Vanilla villager offers restock without the merchant first obtaining or
producing the sold items.  Villagers should instead be useful village workers:
their sale stock must come from work they perform in the world or from raw
materials supplied to a village workshop.

## What Changes

- Create the standalone TotemVillagers module with server-authoritative,
  work-backed villager trading.
- Replace free sell-offer restocking with physical personal-inventory stock
  produced by profession-specific work orders.
- Support both autonomous, permitted world gathering and player-supplied
  workshop input processing through a persistent 27-slot inventory owned by
  each villager. The vanilla villager inventory remains reserved for vanilla AI.
- Add an extensible worker-profession framework. The first new Totem
  professions are Miner and Lumberjack; the existing vanilla Shepherd gains a
  real flock-work role rather than a duplicate profession.
- Add a Guard profession that constructs managed iron golems from reserved
  materials when its managed village needs defence, replacing free village
  iron-golem spawning in that explicitly managed area.
- Provide data-driven work-order definitions for every vanilla profession's
  sell-side offers, including their required inputs, work action and output.
- Preserve Minecraft's original villager trade UI; keep work diagnostics in
  administrator commands rather than a player inventory panel.

## Impact

- Affected capability: `work-backed-villager-trading` (new)
- Affected systems: villager offer generation/restocking, AI scheduling,
  persistent villager data and personal work inventories, protected world
  interactions, workshop processing, worker professions and work zones, client
  vanilla trade interaction and server/client tests
- **Compatibility:** existing sell offers become unavailable until the villager
  completes a valid work order; no free legacy stock is grandfathered.
