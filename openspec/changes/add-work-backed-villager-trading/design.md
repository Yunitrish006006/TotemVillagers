## Context

The requested rule is stronger than vanilla's job-site restock animation:
every item handed from a villager to a player in a sell-side trade must be
present in that villager's persistent personal inventory, and stock may enter only
after real, validated work.  Two production paths are required:

1. The villager independently performs a profession-appropriate world task.
2. A player supplies raw materials to a linked workshop and the villager
   processes them through a profession-appropriate task.

## Goals / Non-Goals

- Goals:
  - Cover every vanilla profession sell-side offer with a data-driven work
    order; a missing order means that offer cannot restock.
  - Add safe, extensible specialist professions whose work produces real trade
    stock, beginning with Miner and Lumberjack.
  - Make a completed work action, consumed inputs and produced stock observable
    and persistent.
  - Support autonomous gathering and workshop processing without dupes,
    griefing or forced chunk loading.
  - Preserve vanilla profession identity and normal player-to-villager payment
    offers.
- Non-Goals:
  - Verify that a player personally earned items used to pay a villager.
  - Let villagers take arbitrary items from unrelated player containers.
  - Let villagers break player-built blocks, force-load chunks or bypass claim
    and protection decisions.
  - Rebalance vanilla prices, experience or profession tables in the first
    release.

## Decisions

### Data-driven work orders

Each sell-side offer resolves to a versioned work order containing the
profession, output stack, required inputs, one or more valid work actions,
time budget and stock cap.  Orders cover material transformations (for example
smelting, crafting, brewing, cartography and enchanting) as well as legitimate
world work (for example harvesting mature crops, fishing and shearing).

A work order MUST include a validated action. A workshop cannot merely move a
finished trade item into sale stock; inventory-backed orders
consume appropriate raw inputs and complete a profession action at the linked
job site.

### Worker professions and work zones

TotemVillagers adds a registry-backed worker-profession model so new roles are
not hard-coded into the scheduler. Initial roles are:

- **Miner** (new): obtains ore and stone-order inputs only from an explicitly
  assigned Mine Work Zone. It never mines arbitrary player blocks; every target
  must satisfy the configured natural-source and protection rules. A persisted
  world-generated village Mine consumes its exposed face and safely appends one
  tread following the original covered 5×5 spiral, then persists the Zone's new
  lower boundary. Manually configured zones remain finite terrain and do not
  create structures.
- **Lumberjack** (new): gathers logs and saplings only from an explicitly
  assigned Forest Work Zone, processes reachable mature trees one at a time,
  and replants a valid sapling before considering the tree cycle complete.
- **Shepherd** (existing vanilla profession): becomes a flock worker that
  safely shears/breeds permitted animals and processes wool orders. It is not
  registered again as a conflicting second Shepherd profession.
- **Guard** (new): protects an explicitly managed village. When its managed
  golem quota is below the defence demand, it reserves four iron blocks and a
  carved pumpkin from its personal work inventory, carries/places them one at a time at
  a Guard Post construction pad, and lets the vanilla iron-golem structure
  completion create the golem. Demand is one for a resident village plus one
  for every four nearby monsters, capped at three. The resulting golem is
  marked as managed for quota and replacement accounting.

Work zones are owner-configured, dimension-bound and bounded. They are an
explicit permission boundary in addition to claims/protection hooks. A role
falls back to a compatible personal-inventory workshop order when a world zone
has no safe, reachable source.

A Guard Post is the explicit centre and construction pad for a managed village.
Only a village with a linked Guard and Guard Post suppresses vanilla's automatic
village iron-golem spawning; unconfigured villages keep vanilla behaviour.
Guard construction uses a data-driven defence order whose initial material
cost matches the vanilla iron-golem structure. Inputs leave the Guard's personal
work inventory only after a durable reservation is saved; all unplaced material returns
on cancellation. Player-built golems and golems outside the managed
construction result are never claimed, removed or counted as Guard output.

### Personal work inventories

Every managed villager owns a persistent 27-slot work inventory, separate from
Minecraft's vanilla villager inventory. The vanilla inventory remains wholly
available to vanilla food, farming and breeding AI. The work inventory is the
only material source for Totem gathering, workshop, Builder and Guard actions;
Totem never reads an arbitrary player container.

The inventory is transactional: a work action reserves exact stacks, validates
its workstation or world mutation, and then commits once. A cancelled action
restores its reservation. A full personal work inventory pauses gathering
before the world changes. Direct inventory access is not exposed. Players interact through Minecraft's
ordinary villager trading screen only; autonomous delivery moves real stacks
between villagers without a shared warehouse.

World gathering is restricted to order-defined renewable or legitimate targets
and assigned work zones; no generic block breaking is introduced. Profession
coverage not naturally gatherable in the world remains completable through an
appropriate work-order transformation using Workshop inputs.

Miner and Lumberjack are material suppliers rather than a second source of
sellable duplicate stock. Before a stone block or complete tree is changed, the
server checks that the worker's personal work inventory can hold the exact
output. It commits the material only after the world action succeeds, restoring
the source if the insertion cannot complete. Later workshop and Builder work
can consume those physical materials; no merchant-stock entry is created by the
same mining or logging cycle.

Generated villages additionally receive two explicit long-running sources. A
Mine action consumes its exposed face after live loot, bounded incidental ore
and pickaxe wear commit atomically. It then appends one deeper stair, headroom,
roof and inner rail using the original spiral geometry and persists the Zone
lower bound. Construction refuses fluids, containers, live entities, protected
or unloaded cells, unsafe materials and the world floor. The Lumberyard carries
a two-segment vine trellis whose lower segment can be repeatedly trimmed with
physical shears while its upper mother segment remains. Both paths remain
demand-driven and stop if their generated-village identity or physical facility
is removed. This gives unattended settlements progressive excavation without
turning player-created work zones into generic resource or structure generators.

The generated Lumberyard is also a persisted rooted nursery. It may restore
the work order's replacement sapling after an otherwise valid atomic harvest
even when that canopy's live loot did not contain one. A manually configured
Lumberjack Zone has no such root stock: it must consume a matching sapling from
the current live drops or the worker's personal inventory before changing the
tree. Thus deterministic village renewal does not broaden manual work-zone
resource authority.

Woodcutter demand follows the same persisted village identity. A modern
generated worker considers only UUIDs in its village's founding resident
ledger; an old record without that ledger falls back to its exact structure
bounds. Spatial proximity alone therefore cannot make two settlements consume
one another's limited processing capacity.

### Physical stock and trading

The same persistent 27 slots hold food, tools, raw materials, merchandise and
emeralds. A successful work commit removes its inputs and atomically inserts
the exact server-verified output stack, including all data components. A
sell-side trade debits that physical result; a purchase offer requires and
debits the villager's physical emeralds before paying the player. Player costs
and inter-villager payments enter the recipient's slots as real items. The old
merchant-stock and wallet records are migration-only compatibility data.

### Intelligent scheduling and safety

Villagers prioritise a paid sell order that lacks stock, then choose a reachable
valid work action, navigate to it, perform the action, and return to normal
behaviour.  They yield to danger, sleep, raid and vanilla high-priority
behaviour.  Per-tick scan/action budgets, job cancellation on villager death,
profession/job-site changes, source changes, chunk unload and server stop, and
claim/protection hooks are mandatory.

### Player interaction

The player-facing interaction remains Minecraft's original villager trade
screen. The protected inventory has no deposit, withdrawal or custom trade
panel; its contents can reach a player only through a valid trade or the
villager's normal death drops.

## Risks / Trade-offs

- Full profession coverage is a large catalogue.  Data drives the mappings so
  additions and balance changes do not require AI rewrites.
- Autonomous collection can conflict with player builds. Role-specific,
  owner-configured work zones, explicit permission checks and workshop fallback
  keep the system useful without generic griefing.
- Existing villagers may initially sell nothing.  This is deliberate: it
  enforces the no-free-stock rule and makes diagnostics important.
- Guard construction must not turn a danger spike into duplicated golems.
  Per-village reservations, bounded construction pads and managed-golem IDs
  make demand, placement and replacement idempotent across reloads.

## Migration Plan

1. Ship the module disabled per world until an operator enables work-backed
   trading and creates Work Zones and optional Guard Posts. Villagers then
   receive empty personal work inventories automatically.
2. On enablement, preserve profession and offer identity but initialise sell
   stock as empty; complete work orders fill it.
3. Persist stock, active jobs and personal work inventories; old Work Chest
   links remain readable but are ignored and safely removed on the next save.
4. Provide a config rollback switch that restores vanilla restocking without
   deleting accumulated merchant stock.

## Open Questions

- None for the first proposal: autonomous collection and player-supplied
  workshop processing are both required.
