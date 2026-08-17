## 1. Module and persistent model

- [x] 1.1 Scaffold the standalone TotemVillagers Fabric module, entrypoints,
  localisation, exact TotemCore dependency and dedicated-server smoke test.
- [x] 1.2 Define versioned persistent records for work orders, merchant stock,
  active jobs, 27-slot personal work inventories and server-synchronised trade
  diagnostics; safely migrate legacy Work Chest links.
- [x] 1.3 Add the data-driven vanilla profession sell-order catalogue, with a
  complete coverage test that rejects free/unmapped sell offers. (The runtime
  gate is now exercised against every current vanilla profession; individual
  work-order production paths remain tracked in `profession-rollout.md`.)
  See `profession-rollout.md` for the required all-profession delivery matrix
  and component-validation dependency.
- [x] 1.4 Add registry-backed worker professions, bounded dimension-specific
  Work Zones and assignment data; register Miner, Lumberjack and Builder and
  extend the vanilla Shepherd with flock-work behaviour without duplicating its
  profession; register Guard and its persistent managed-village state.
- [x] 1.5 Add Guard Post registration and a data-driven defence order whose
  initial materials match the vanilla iron-golem structure.

## 2. Work acquisition and safety

- [x] 2.1 Replace Village Work Chest registration and linking with protected
  27-slot personal inventories and authoritative transactional commits; remove
  direct player deposits and withdrawals so vanilla trading is the only interaction.
- [x] 2.2 Implement bounded autonomous world actions for the eligible renewable
  profession targets, with navigation, protection hooks, role-specific Work
  Zones and no forced loading.
- [x] 2.3 Implement Miner ore/stone work and Lumberjack tree/replant work only
  inside assigned zones, plus Shepherd flock work through the existing vanilla
  profession.
- [x] 2.3a Implement persistent, material-backed Builder construction from
  bounded shipped vanilla village-house templates only, with personal work
  inventory reservations, Work Zone checks, no forced chunk loading and no
  overwrite.
- [x] 2.3b Use an independent persistent hunger value and add a bounded Farmer
  food market backed by physical food and emerald items.
- [x] 2.3c Make hunger pause and safely cancel Totem-managed work before it can
  commit stock, resuming only after the food market restores a safe food level.
- [x] 2.3d Add bounded Farmer mature wheat/carrot/potato/beetroot harvest and
  real replanting into its personal work inventory, feeding validated recipes
  and physical crop-sale rows without direct crop-to-stock relabelling; let
  lone Farmers ration only their own debited food stock.
- [x] 2.3e Route Miner and Lumberjack validated world yields into their personal
  work inventories, requiring capacity before any
  world mutation and never duplicating the same yield into merchant stock.
- [x] 2.3f Upgrade independent village nutrition to player-style persisted food,
  saturation, exhaustion and timers, including live food-component values,
  natural regeneration and difficulty-sensitive starvation.
- [x] 2.3g Add advance five-bread-equivalent ration restocking plus bounded
  Farmer composting of surplus seeds/wheat and physical bone-meal reuse.
- [x] 2.4 Implement Guard threat/quota evaluation, per-village material
  reservation and visible one-at-a-time iron-golem construction at Guard Posts;
  suppress automatic golem spawning only for the corresponding managed village.
- [x] 2.5 Implement workshop work actions that consume personal-inventory raw inputs, perform the
  linked profession task and produce order outputs without direct item relabels.
- [x] 2.6 Implement cancellable per-villager scheduling that yields to danger,
  sleep, raids and higher-priority vanilla behaviour.

## 3. Trading and UI

- [x] 3.1 Gate all offers on physical personal-inventory merchandise, currency
  and capacity; atomically move the real stacks when a trade succeeds.
- [x] 3.2 Preserve Minecraft's original villager trade screen without a custom
  inventory or work-status panel.
- [x] 3.3 Keep the 27 protected slots server-owned and inaccessible to players.
- [x] 3.4 Add Work Zone assignment and boundary feedback to the relevant
  villager/administrator UI.
- [x] 3.5 Add Guard Post defence demand, reservation and construction progress
  to the relevant villager/administrator UI.
- [x] 3.6 Add the world enablement and rollback configuration with safe legacy
  villager initialisation.
- [x] 3.7 Replace random live sell-row authority with complete profession
  catalogue authority: lawful physical inventory creates and removes rows,
  full vanilla level 1–5 data supplies prices only, purchase rows remain intact,
  and survival food/tools are reserved from sale.
- [x] 3.8 Add a physical replacement-hoe supply chain: Miner smelting and
  stone/metal sale, Lumberjack log sale, live-recipe Toolsmith processing and
  forging, Farmer emerald payment, and real hoe durability during harvest.
- [x] 3.9 Add optional TotemRemnant backpack work: exact pristine Smithing
  Transform inputs, four-tier physical output stock, vanilla-screen material
  purchase rows and deterministic 8/16/32/64-emerald sales without a hard
  runtime dependency.
- [x] 3.10 Put one Smithing Table in the fixed generated Lumberyard and recruit
  a Toolsmith only as the fourth founding role after Farmer, Miner and
  Lumberjack, without overwriting careers or unsafe legacy terrain.
- [x] 3.11 Extend physical replacement-tool circulation to Miner pickaxes and
  Lumberjack axes; make complete-tree work reserve and wear the exact carried
  axe, and pause either resource worker when its usable tool is exhausted.
- [x] 3.12 Close the unattended generated-village loop with generated Mine
  stone, bounded live-profile incidental ore, persistent iron-drought
  protection, renewable trellis fibre, live-recipe shears/string/rod work,
  renewable charcoal and physically conserved pooled emerald payments.
- [x] 3.13 Bind a generated Lumberjack's Woodcutter demand to its own persisted
  village residents so a nearby settlement cannot redirect its processing.
- [x] 3.14 Add a Mangrove-Swamp-only raised village with a Barrel-backed
  Fisherman smokehouse, renewable Mangrove Lumberyard, Toolsmith station,
  walkable spiral Mine and one persisted four-profession founding population;
  generate it through a chunk-safe registered Jigsaw structure in new terrain.
- [x] 3.15 Expand the Mangrove settlement with three to six deterministic,
  seed-selected cottages, family houses or longhouses and generate only the
  supported pier branches needed by the selected sites, without adding random
  job-site blocks or changing the four-worker founding population.
- [x] 3.16 Naturalise the residential edge with seed-selected lateral offsets,
  high-gable/deep-eave/side-gallery silhouettes and multi-turn supported
  branches; prove every selected home is actually walkable from the plaza and
  repair core longhouse, Mine and fishing-basin clearance blockers.
- [x] 3.17 Fund every bred villager's initial eight emeralds and six bread by
  atomically transferring equal four-emerald and three-bread shares from both
  parents; persist birth provenance so insufficient funds, reloads and
  generated-village bounds can never mint or repeat the endowment.
- [x] 3.18 Replace restored generated-Mine faces with physical progressive
  excavation: consume the visible source, append one safe tread using the
  original covered 5×5 spiral, persist the Zone's lower boundary, and leave
  manual zones finite without automatic construction.

## 4. Verification and delivery

- [x] 4.1 Add unit tests for order coverage, stock accounting, persistence,
  source selection, cancellation and duplicate-prevention invariants.
- [x] 4.2 Add Fabric GameTests for every profession order, autonomous gathering,
  Miner zone restrictions, Lumberjack replanting, Shepherd flock work,
  Guard material reservation/construction and managed-village spawn suppression,
  workshop processing, empty-stock trade rejection, concurrent trade/work,
  protection rejection, chunk unload and server restart.
- [x] 4.3 Add client visual GameTests for stock, source, progress and blocked
  state, plus personal work-inventory rendering.
- [x] 4.4 Run unit tests, server/client GameTests, dedicated-server smoke tests,
  strict OpenSpec validation and release builds; record artifact hashes.
- [x] 4.5 Run the four-role generated-village steady-state probe for 10,000 days
  on three ore seeds, asserting every-phase emerald conservation, bounded food,
  material and slot usage, renewable non-mine sources, a controlled progressive-
  mining supply and fresh activity in the final 500-day window.
