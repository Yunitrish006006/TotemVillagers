# Verification record — 2026-08-05

The final verification pass used Java 25 and the release artifacts in
`build/libs/`.

- `openspec validate add-work-backed-villager-trading --strict --no-interactive` — passed.
- `./gradlew runGameTest` — 51 required server GameTests passed, including the
  playable Farmer loop: current vanilla offer → exact player-recipe materials
  → enough work-backed stock for one real sale, while materials for an absent
  offer remain untouched. The server trade snapshot also exposes the exact
  recipe inputs for a live mapped offer. Every currently registered adult
  villager profession can purchase work-backed food from another Farmer. A
  work-inventory withdrawal returns one complete visible stack while an active
  reservation remains unavailable until it is rolled back. Nearby village
  material market buys physical wheat at the recipient's live vanilla
  material-for-emerald offer, then produces bread stock; an unfunded buyer
  moves nothing and a supplier retains its own next recipe inputs before
  selling surplus. An opt-in resource village fills unemployed residents in
  Farmer → Miner → Lumberjack order and persists the exact specialist Zones. A
  newly generated vanilla village receives one finite capital grant and records
  only a generation-time validated single-tree Lumberjack Zone; no-tree villages
  receive no such Zone.
- `xvfb-run -a ./gradlew runClientGameTest` — passed. The test opens a real
  merchant screen, supplies a server snapshot, captures the diagnostics and
  recipe-material line with the 27-slot work-inventory panel, and checks that
  the panel is rendered.
- `./gradlew runServer` — dedicated server started, loaded 188 work orders and
  one Guard defence order, then stopped cleanly.
- `./gradlew test` — passed.
- `./gradlew jar sourcesJar` — passed.

## Incremental verification — 2026-08-07

- `./gradlew test` — passed on Java 25 after the generated-village endowment
  ledger advanced to data version 4.
- `./gradlew runGameTest` — all 59 required server GameTests passed. The new
  mobile-villager integration test uses the production schedulers rather than
  direct action calls: Farmer, Miner and Lumberjack each find a reachable world
  target, navigate into range, complete the configured work duration and place
  exactly wheat, cobblestone and oak logs in their personal inventories.
- Generated-village regression coverage now proves late-loading residents get
  their own one-time eight-emerald and FoodLevel-16 endowment, repeated
  bootstrap does not pay anyone twice, and a founding Miner never replaces the
  village's only existing Farmer.
- The mobile integration test also proves persisted Totem specialist
  assignments survive vanilla `ResetProfession`; Miner and Lumberjack no longer
  silently become unemployed on their next normal AI tick.
- `./gradlew jar sourcesJar` — passed for version `0.1.1`.

## Incremental verification — 2026-08-07 (Librarian enchanting-table books)

- `./gradlew test` — passed on Java 25: 29 suites, 56 tests, zero failures,
  errors or skips. Coverage includes the five profession-level mapping
  (6/12/18/24/30), tiered lapis input and bounded treasure probabilities.
- `./gradlew runGameTest` — all 59 required server GameTests passed. The new
  Librarian loop deposits a book and lapis into personal inventory, performs a
  real 120-tick nearby Enchanting Table action, creates one exact
  component-aware stock entry and adds only that book as a dynamic trade offer.
  The regression test also proves that a legacy generated enchanted-book offer
  is removed even if stale stock happens to have an identical component key.
- `./gradlew jar sourcesJar` — passed for version `0.1.1`.

## Incremental verification — 2026-08-07 (Fisherman buckets and Librarian equipment)

- `./gradlew test` — passed on Java 25: 56 tests, zero failures, errors or skips.
- `./gradlew runGameTest` — all 62 required server GameTests passed. The
  Fisherman test proves a real cod catch alone maps to the cod-bucket order;
  the end-to-end Librarian test supplies a pristine fishing rod and three
  lapis, runs the full nearby Enchanting Table cycle, and verifies that both
  inputs are consumed only for one exact component-bearing enchanted output.
  It also proves a generated enchanted-equipment row is removed from its old
  profession rather than bypassing the Librarian's work-backed stock.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive` — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Toolsmith empty buckets)

- `./gradlew test` — passed on Java 25: 56 tests, zero failures, errors or skips.
- `./gradlew runGameTest` — all 63 required server GameTests passed. The new
  Toolsmith loop begins with an out-of-stock fixed two-emerald bucket row,
  supplies exactly three iron ingots to its personal inventory, runs its native
  Smithing Table cycle, verifies the ordinary vanilla bucket recipe, consumes
  those ingots, credits one bucket and unlocks exactly that sale.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive` — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Cartographer explorer maps)

- `./gradlew test` — passed on Java 25: 58 tests, zero failures, errors or skips.
  The new unit coverage locks the Cartographer profession-tier chance curve at
  0%/1%/2%/4%/8% and verifies the mansion destination is unavailable before
  level 5.
- `./gradlew runGameTest` — all 64 required server GameTests passed. The
  Cartographer regression creates a real component-bearing `filled_map` and
  proves a pre-generated explorer-map offer is removed even when matching
  variant stock is present; only the Cartographer's own completed work may
  publish that exact map.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive` — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Miner lapis and Lumberjack apples)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 67 required server GameTests passed. The new
  coverage proves that a Miner obtains Minecraft's actual lapis and diamond
  ore drops from the configured all-vanilla-ore tag, an oak Lumberjack harvest
  atomically stores its live native log and leaf-loot results (with no
  guaranteed apple), and both specialist sale rows open only for their real
  physical batch then debit it exactly once on trade.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Miner personal pickaxes)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 68 required server GameTests passed. The new
  Miner coverage proves that a carried diamond pickaxe is returned to its
  personal inventory with exactly one durability consumed after mining diamond
  ore, while a carried wooden pickaxe cannot mine that target, gains no output
  and leaves both the ore and its own durability untouched.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Specialist merchant access and gathered-material sales)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 72 required server GameTests passed. The new
  interaction coverage opens a real merchant session for both Miner and
  Lumberjack custom professions. Player purchase coverage proves both displayed
  input costs enter the work inventory only after a successful trade and a full
  inventory locks the offer first. Specialist-sale coverage proves the dynamic
  physical rows and prices for 16 cobblestone per emerald, one diamond per six
  emeralds and eight oak logs per emerald, then debits those exact materials.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-07 (Farmer field crops and physical crop sales)

- `./gradlew compileJava compileGametestJava test` — passed on Java 25.
- `./gradlew runGameTest` — all 75 required server GameTests passed. The new
  coverage proves mature carrots, potatoes and beetroots are harvested and
  replanted at age zero alongside wheat, using the live crop loot path. It also
  proves a Farmer only publishes its 20-carrots-for-one-emerald and
  32-surplus-wheat-seeds-for-one-emerald rows when those exact physical batches
  are present, then debits and removes both rows after a successful sale.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-09 (catalogue coverage)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 82 required server GameTests passed. The
  catalogue coverage now iterates every reloaded static workshop order and
  validates it through Minecraft's current recipe registry at its matching job
  site. A second all-profession gate test injects stock for every live vanilla
  sell result and proves that a result without a matching static or offer-bound
  order remains locked.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-09 (timber-framed mine head)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 83 required server GameTests passed. The new
  mine-head regression proves the generated shaft has a moss-accented stone
  foundation, stripped-oak frame, gated safety rail, shallow pitched spruce
  roof, open skylight and hanging lantern without obstructing the first stair
  or central shaft. The casing regression also proves the wider eaves do not
  clear surrounding surface water or replace existing underground terrain.
- `xvfb-run -a ./gradlew runClientGameTest` — passed. The visual fixture now
  uses the production Mine placement directly and captured both
  `totem-villagers-mineshaft-5x5-spiral` and the clean surface view
  `totem-villagers-minehead-surface`.
- `./gradlew jar sourcesJar` — passed for the refreshed `0.1.1` artifacts.

## Incremental verification — 2026-08-09 (physical inventory economy)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 84 required server GameTests passed. Coverage
  now proves one-shot base and profession starter kits, physical emerald
  payments, physical food consumption, physical workshop/enchanting outputs,
  physical inter-villager material exchange and exact trade debits.
- `xvfb-run -a ./gradlew runClientGameTest` — passed for the Miner appearance,
  held-tool/work-arm animation and generated mine views. The custom inventory
  trade panel is no longer registered because player interaction is vanilla-only.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build` — passed for the versioned `0.1.2` artifacts.

## Incremental verification — 2026-08-10 (autonomous village longevity and natural-world bootstrap)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 87 required server GameTests passed. New
  coverage includes an independent 20-day Farmer/Miner/Lumberjack food and
  emerald cycle, own-inventory food consumption, recipe-authoritative bread,
  durable specialist professions and a nearby founding Furnace claim despite
  a transient navigation-path miss.
- `xvfb-run -a ./gradlew runClientGameTest` — passed for the Miner appearance,
  held tools, work animation and generated mine views.
- A fresh unmodified world using seed `-6874573966989366352` produced six
  savanna villagers as two Farmers, one `totem:miner`, one `totem:lumberjack`
  and two unemployed adults without `/totemvillagers start`. Both specialist
  professions remained intact after another 400 server ticks.
- The same natural village created its guarded spiral mine and in-zone Furnace
  after the complete structure search area loaded. Recovery accepts harmless
  vegetation, dirt/grass/path terrain, overworld base stone and vanilla ore,
  while continuing to reject fluids, containers, buildings and decorations.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build -x runGameTest` — passed for version `0.1.3` after the same
  87/87 GameTest task was run independently; the standalone GameTest JVM was
  terminated only after it reported completion because Minecraft remained in
  its final world-save process.

## Incremental verification — 2026-08-11 (player-style villager nutrition)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 90 required server GameTests passed. New
  coverage verifies live food-component nutrition and saturation, saturation
  consumption before food, player-timed natural regeneration and starvation,
  plus a 30-day Farmer/Miner/Lumberjack cycle that reaches physical internal
  food trading after consuming starter saturation and rations.
- The standalone GameTest JVM was terminated only after reporting 90/90
  completion because Minecraft again remained in its final world-save process.
- `xvfb-run -a ./gradlew runClientGameTest` — passed; existing Miner appearance,
  held-tool, work-animation and generated-mine visual coverage remains intact.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build -x runGameTest` — passed for version `0.1.4` after the same
  90/90 server GameTest task was run independently.

## Incremental verification — 2026-08-11 (complete inventory-driven trade mode)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 91 required server GameTests passed. New
  coverage starts all 13 vanilla professions with no randomly rolled sell row,
  proves lawful physical stock creates a row, unrelated inventory never does,
  and player-to-villager purchase rows remain intact.
- The standalone GameTest JVM was terminated only after reporting 91/91
  completion because Minecraft again remained in its final world-save process.
- `xvfb-run -a ./gradlew runClientGameTest` — passed; Miner helmet, held-tool,
  work-arm animation and generated-mine visual coverage remains intact.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build -x runGameTest` — passed for version `0.1.5`.
- The configurable longevity soak probe was also run beyond its normal 30-day
  regression horizon. The renewable Farmer/Miner/Lumberjack founding group
  remained fed and work-capable through day 64, then the Miner could not buy
  food on day 65. At failure the Farmer still held 22 bread and all 24 founding
  emeralds, while Miner and Lumberjack each held zero bread and zero emeralds.
  This proves the present limit is currency concentration, not food production:
  the three-role economy has no autonomous path that pays the Farmer's food
  income back to either resource worker.

## Incremental verification — 2026-08-11 (Toolsmith replacement-tool circulation)

- `./gradlew test` — passed on Java 25.
- `./gradlew runGameTest` — all 93 required server GameTests passed. The two new
  end-to-end cases prove physical Lumberjack log purchase, live log/plank/stick
  recipes, Miner raw-iron smelting, physical iron or cobblestone purchase,
  Smithing Table hoe forging, Farmer emerald payment and one real durability
  point consumed on the next harvest. The fallback case also proves a spruce
  log is accepted through the vanilla log tag and a stone hoe is made when no
  smelted metal exists.
- The standalone GameTest JVM was terminated only after reporting 93/93
  completion because Minecraft again remained in its final world-save process.
- `xvfb-run -a ./gradlew runClientGameTest` — passed; Miner helmet, held-tool,
  work-arm animation and generated-mine visual coverage remains intact.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build -x runGameTest` — passed for version `0.1.6`.

## Incremental verification — 2026-08-11 (one-year four-role circulation)

- `./gradlew test` — passed on Java 25.
- Default `./gradlew runGameTest` — all 93 required server GameTests passed.
  The release regression now starts Farmer, Miner, Lumberjack and Toolsmith with
  no Farmer hoe, requires repeated physical stone-hoe procurement and wear,
  directly counts internal ration trades, and bounds retained wheat and seeds.
- `TOTEM_VILLAGE_LONGEVITY_DAYS=365 ./gradlew runGameTest` — all 93 required
  server GameTests passed after one simulated year. The four-role soak covers
  repeated replacement hoes, physical emerald circulation, five-bread-equivalent
  advance ration restocking, live bread work, surplus seed/wheat composting and
  physical bone-meal reuse. The probe supplies 64 previously mined cobblestone
  and 64 previously chopped logs; it proves one-year economy/capacity stability,
  not infinite world-resource renewal.
- Both standalone GameTest JVMs were terminated only after reporting 93/93
  completion because Minecraft remained in its final world-save process.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew build -x runGameTest` — passed for version `0.1.7`.

## Incremental verification — 2026-08-11 (optional Remnant backpack smithing)

- `./gradlew compileJava compileGametestJava` — passed on Java 25.
- `./gradlew runGameTest` with TotemRemnant 0.1.8 on the GameTest runtime — all
  94 required server GameTests passed. The new cross-module case validates all
  four live Smithing Transform recipes, exact pristine input reservations,
  physical Toolsmith output stock, material purchase rows and deterministic
  8/16/32/64-emerald sales. A renamed Bundle is explicitly rejected as an
  empty recipe input.
- The standalone GameTest JVM was terminated only after reporting 94/94
  completion because Minecraft remained in its final world-save process.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew test build -x runGameTest` — passed for version `0.1.8`.

## Incremental verification — 2026-08-11 (fourth-priority generated Toolsmith)

- `./gradlew compileJava compileGametestJava test` — passed on Java 25.
- `./gradlew runGameTest` — all 95 required server GameTests passed. The fixed
  Lumberyard now contains exactly one Smithing Table beside its Woodcutter, and
  a four-adult workforce becomes Farmer, Miner, Lumberjack and Toolsmith while
  retaining that strict priority. The Toolsmith binds the physical generated
  station; a three-adult village still reserves every candidate for the first
  three roles.
- The legacy recovery Lumberyard adds the table only on loaded vacant terrain
  with sturdy support, so an unsafe fourth block never prevents its original
  tree and Woodcutter recovery path.
- The standalone GameTest JVM was terminated only after reporting 95/95
  completion because Minecraft remained in its final world-save process.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew test build -x runGameTest` — passed for version `0.1.9`.

## Incremental verification — 2026-08-11 (core resource-tool replacement)

- `./gradlew compileJava compileGametestJava test` — passed on Java 25.
- `./gradlew runGameTest` — all 96 required server GameTests passed. The new
  end-to-end case exhausts the founding Miner pickaxe and Lumberjack axe, then
  proves the Toolsmith buys physical log and cobblestone inputs, validates the
  live stone-tool recipes, receives physical emerald payment and transfers the
  exact replacement tools. The Miner then mines with that pickaxe and the
  Lumberjack fells a complete tree with that axe; both tools return with one
  real durability point consumed.
- Existing Lumberjack world-work coverage now supplies a physical axe and
  asserts exact durability wear; tree mutation is committed atomically with
  the reserved axe and complete live-loot return.
- `xvfb-run -a ./gradlew runClientGameTest` — passed; Miner helmet, role-tool
  fallback rendering, work-arm animation and generated-mine views remain intact.
- `openspec validate add-work-backed-villager-trading --strict --no-interactive`
  — passed.
- `./gradlew test build -x runGameTest` — passed for version `0.1.10`.

## Incremental verification — 2026-08-14 (four-role fishing village)

- `./gradlew compileJava compileGametestJava test` — passed on Java 25.
- `TOTEM_FISHERMAN_VILLAGE_DAYS=95 ./gradlew runGameTest` with TotemRemnant
  0.2.9 — all 100 required server GameTests passed. The physical simulation
  produced 380 cooked vanilla-loot fish, completed 15 internal food purchases,
  bought five Toolsmith fishing rods, mined 380 stone and harvested/replanted
  95 trees while preserving all 32 founding emeralds.
- A 120-day probe reached its first hard stop on day 96. The Miner held 375
  cobblestone but only one emerald after the Toolsmith bought the next
  three-stone input; the completed four-emerald stone pickaxe therefore could
  not transfer. This identifies currency distribution, not food or material
  production, as the first remaining longevity limit. Toolsmith starter string
  was also exhausted by that horizon and remains the next finite constraint.
- Internal food sellers now include loaded Farmers, Fishermen and Butchers with
  physical edible surplus, while every producer retains a 20-nutrition ration.
  The Toolsmith's own founding iron pickaxe is no longer mistaken for internal
  replacement stock.
- `./gradlew build -x runGameTest` — passed for version `0.1.11`.

## Incremental verification — 2026-08-14 (renewable four-role steady state)

- `./gradlew test` — passed on Java 25: 31 suites, 66 tests, zero failures,
  errors or skips.
- Final default `./gradlew runGameTest` — all 115 required server GameTests
  passed. Coverage includes the generated deep seam, renewable lower-vine
  trellis, rooted generated oak nursery, finite manual Lumberyard saplings and
  a nearer outside-village stick demand that cannot redirect the generated
  Lumberjack from its own resident's plank demand.
- Three independent 10,000-day runs (`0x5EEDBEEF`, `0xC0FFEE` and
  `0x1234ABCD`) completed with all 32 founding emeralds conserved after every
  phase, bounded food/material/slot usage, intact renewable source blocks and
  fresh food trades, mining, logging, fibre clipping, charcoal, Furnace
  replacement, fuel trades and replacement-tool work in the final 500 days.
  Across the three runs the village completed 1,935–1,944 food trades,
  240–242 fishing-rod purchases, 288–294 stone cycles, 490–492 tree cycles,
  1,152–1,164 fibre clips, 228–229 charcoal batches, 28 Furnace replacements
  and 1,568 fuel trades.
- `openspec validate add-work-backed-villager-trading --strict
  --no-interactive` — passed.
- The independently completed 115/115 server regression was followed by
  `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.16`.
  Minecraft's already-successful GameTest JVM was terminated only during its
  non-terminating final world-save stage.

## Incremental verification — 2026-08-14 (natural Mangrove village)

- `./gradlew compileGametestJava test` — passed on Java 25: 66 unit tests,
  zero failures, errors or skips.
- `./gradlew runGameTest` — all 118 required server GameTests passed. The new
  coverage validates the registered Mangrove-Swamp-only Jigsaw structure and
  start pool, its 45×45 raised layout, four beds, Barrel/Campfire fishing site,
  casting basin, renewable Mangrove Lumberyard, Smithing Table, Furnace and all
  sixteen walkable spiral-mine stairs. A second case proves the exact Swamp
  Fisherman/Miner/Lumberjack/Toolsmith founding group, starter supplies, home
  and job memories, persisted one-shot flag and no duplicate bootstrap. The
  already-successful GameTest JVM was interrupted only after 90 seconds in its
  known non-terminating final world-save stage.
- A fresh dedicated world using seed `96874758687607637` naturally generated
  `totem:mangrove_village` at `[-8688, ~, 32]`, an independently verified
  random-spread candidate inside `minecraft:mangrove_swamp`. Direct block and
  POI checks found the Bell, Fisherman Barrel/Campfire, Woodcutter, Smithing
  Table, Furnace, first stair and sixteenth stair. The loaded village contained
  exactly one Fisherman, Lumberjack, Toolsmith and Miner; restarting the same
  world retained those four and created no duplicates. The final placement
  produced no unsafe cross-chunk terrain-read warning.
- Three new 10,000-day four-role runs (`0x5EEDBEEF`, `0xC0FFEE` and
  `0x1234ABCD`) retained all 32 founding emeralds and fresh final-window
  activity. Across them the villages completed 12,522–12,554 catches,
  1,905–1,966 food trades, 244–245 fishing-rod purchases, 288–291 stone
  cycles, 491–493 tree cycles, 1,149–1,221 fibre clips, 227–228 charcoal
  batches, 28 Furnace replacements and 1,566–1,571 fuel trades.
- `openspec validate add-work-backed-villager-trading --strict
  --no-interactive` — passed.
- `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.17`.

## Incremental verification — 2026-08-15 (Mangrove water-village architecture)

- Replaced the box-shaped Mangrove settlement with a Southeast-Asian-style
  water village: steep bamboo roofs with Mangrove ridge and eave trim, framed
  longhouses, a tiered Bell pavilion, an open smokehouse and drying rack,
  patterned lantern piers, a roofed working Lumberyard and a mud-brick Mine
  head. The four exact work sites, four beds, casting water and all sixteen
  spiral stairs remain at their established runtime coordinates.
- Expanded generated-Mine discovery to accept the Mangrove Mine's mud-brick
  masonry and bamboo safety rails while retaining the Furnace, hollow shaft
  and Cobblestone-stair validation shared with the ordinary village Mine.
- `./gradlew test compileGametestJava` — passed on Java 25 with zero failures.
- Final `./gradlew runGameTest` — all 118 required server GameTests passed,
  including the Mangrove founding-population, workstation, fishing, renewable
  Lumberyard and complete spiral-Mine coverage. The successful test JVM was
  interrupted only during its known non-terminating final world-save stage.
- The dedicated 1920×1080 Mangrove client showcase completed successfully and
  captured the complete generated settlement from the closer inspection
  camera.
- `openspec validate add-work-backed-villager-trading --strict
  --no-interactive` — passed.
- `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.18`.

## Incremental verification — 2026-08-15 (Mangrove roof direction correction)

- Reversed both sides of every steep Mangrove-village roof so Bamboo Mosaic
  stairs rise inward toward the ridge instead of outward. The tiered Bell
  pavilion now likewise rises toward its centre, and the converted Mine eaves
  follow the same convention.
- Added a server GameTest assertion for the west/east longhouse slopes and the
  north Bell-pavilion slope so a future palette or structure edit cannot
  silently invert them again.
- `./gradlew test compileGametestJava` — passed with zero failures, followed by
  all 118 required server GameTests passing.
- The dedicated 1920×1080 Mangrove client showcase completed successfully; the
  inspected capture shows continuous inward-rising roof planes.
- `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.19`.

## Incremental verification — 2026-08-15 (seed-varied Mangrove residences)

- Expanded the Mangrove village's advertised footprint from 45×45 to 73×73
  while preserving the exact Bell, Fisherman, Lumberyard, Toolsmith and Mine
  coordinates in its fixed economic core.
- Added eight non-overlapping residential sites. World seed plus structure
  origin deterministically selects three to six sites, chooses a cottage,
  family house or longhouse at each one, mirrors its furnishings, and emits
  only the corresponding supported boardwalk branches. Reprocessing another
  intersecting chunk therefore recomputes the identical layout.
- Optional homes contain one crafting table and one or two beds but no
  profession POI. Total village capacity is 7–16 beds while the persisted
  founding population remains exactly four workers.
- Added GameTest coverage for stable same-seed signatures, variation across 32
  seeds, the 3–6 residence and 7–16 bed bounds, origin sensitivity, and exact
  agreement between selected homes, generated crafting tables and bed heads.
- `./gradlew test compileGametestJava` — passed with zero failures, followed by
  all 119 required server GameTests passing.
- The dedicated 1920×1080 client showcase passed and captured both a closer
  six-home oblique view and a plan view; visual inspection confirmed that all
  selected homes connect to the central settlement and both roof axes rise
  inward.
- `openspec validate add-work-backed-villager-trading --strict
  --no-interactive` — passed.
- `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.20`.

## Incremental verification — 2026-08-15 (naturalised Mangrove residential edge)

- Each selected residence now receives a deterministic lateral offset of up to
  two blocks, a seed-selected high-gable, deep-eave or roofed-side-gallery
  treatment, and a two-turn supported branch whose bend also varies by seed.
  The layout signature covers all of those choices, preserving identical
  cross-chunk recomputation for a given world seed and structure origin.
- The east-south branch now starts before the Mine and passes beyond its
  southern eaves instead of crossing the fenced Mine head. The central
  longhouses have real north-south and east-west breezeways, and the fishing
  basin uses a two-wide rim with its sparse railing on the outer lane.
- The complete-village GameTest now performs a four-direction search over
  unobstructed deck blocks from the plaza and requires a reachable interior
  floor beside every optional home's crafting table. That assertion exposed
  and verified the longhouse, Mine and fishing-basin clearance repairs rather
  than merely counting visually connected floor blocks. It also directly
  asserts the fixed core breezeway and basin clearances.
- `./gradlew test` passed with zero failures, and the final complete
  `./gradlew runGameTest` passed all 119 required server GameTests.
- `xvfb-run -a ./gradlew runClientGameTest` passed. The inspected 854×480
  six-home oblique and plan captures show distinct roof/gallery silhouettes,
  lateral house offsets and the new multi-turn pier approaches; both captures
  are retained under `artifacts/screenshots/` with `0.1.21` filenames.
- A separate default-world client case recreated seed `96874758687607637`
  with structures enabled and without direct feature placement or terrain
  edits. Natural random-spread generation placed the village Bell at
  `[-8688, 66, 32]` inside `minecraft:mangrove_swamp`; the Fisherman Barrel,
  Lumberjack Woodcutter and Miner Furnace also matched their production
  offsets. Its isolated `xvfb-run -a ./gradlew runClientGameTest` passed and
  retained natural oblique and aerial evidence under `artifacts/screenshots/`.
- `openspec validate add-work-backed-villager-trading --strict
  --no-interactive` — passed.
- `./gradlew build sourcesJar -x runGameTest` — passed for version `0.1.21`.

## SHA-512 artifacts

| Artifact | SHA-512 |
| --- | --- |
| `totem-villagers-0.1.0.jar` | `019d3ff05656e54ed7dd3a1576903422d3fc9a10c78b1f21a425e580f7313811ff9dbe3513be052f746d5f1e7220715557386b860ba395eacb0c2af1e75e592d` |
| `totem-villagers-0.1.0-sources.jar` | `98b408d3642cbff2bf4e8dc6dc61a29f10ccfe492a007134a9d7f5f20ef136cc2ab0403dc6961981d594feb7f31b9369a724dfe827865bb205d280ec3fc44b9f` |
| `totem-villagers-0.1.1.jar` | `9c982ac2663463976dd59efc9cda22daca37320d5399403e36216d6be9a4071f2552a2f1b0906c6c6ac630352315a89e7aedc1322b59323bcba1d7c73e4d354c` |
| `totem-villagers-0.1.1-sources.jar` | `3167163a7be008f62601b0686a411f0836202bc975ed6903896c070aa29d89a4407914acd2f35ac545afe868ac0ed69fba2c10515743da3c0ed338d21db40b9b` |
| `totem-villagers-0.1.2.jar` | `26d95087fe480c4ccba43b8aeb00a9b26940010ab6566d317c89acc21f48910c87f86260677e13d6c633da1dd55d44425efcdee08c81266f9f13d6d47637f134` |
| `totem-villagers-0.1.2-sources.jar` | `baa875d4bc7f17928757183cb522b829022010120242f08031e45324f6216fd9ae89766659b64b9bba7a05b0e336df3aec8310c2c04e805052dfbbedf7479518` |
| `totem-villagers-0.1.3.jar` | `139e489cdfe553b27857ecea421c8ce545e316797061d2bb20d024f25ce3c95b5afc8959e0cfff8253ef7f186808c63f02d2ec3ae480aeac4940029edab7afab` |
| `totem-villagers-0.1.3-sources.jar` | `f2180b3505d2871fa881d1ee0c9d49254a5b492c3ab8d4a5026745571e81e87a1474945b182dc458991bebef3e4f1f42c9f59619ad39cbc16bdda04c4ce89331` |
| `totem-villagers-0.1.4.jar` | `a4b24f90a8af30e75460c698178a33e1cd24e68041b2affb1c0406ae287ff3c3bff036e9d599efa4043ff13a2a8b26256467140d591836c52a0f8819313d3237` |
| `totem-villagers-0.1.4-sources.jar` | `e1c56222e5cc6e974d60a504d906f8275ceddc3cba57918458400662265fc9400fd035c171ea453e23f289fed67a0f760b3297038b77e5f91ffc470ae78b6bb1` |
| `totem-villagers-0.1.5.jar` | `c95aa59f6ee3db13ea1ba54d085d4e2c8b5254282808fc6c3eae31e58e25a6e9d9b0e26d897b80fa2e4240e29a773ce92cda6f02922d46ec29b53411c955ccf2` |
| `totem-villagers-0.1.5-sources.jar` | `31307ac5c7a9427dd8bf49d79815506eb5865060ad843e1be9c721fba7ad9f82b3e4abe1f7886a4aadef5db94e731cbd576eb123a525991031c68aa475d8f34a` |
| `totem-villagers-0.1.6.jar` | `43408c224f94ee02c2a11e27209ae6a2e251734850f6ff99d801618b78dd4d6e1b0eb7732734010afc5bedcd29203eae5aaadea63d5f1aff33a78e735e802c80` |
| `totem-villagers-0.1.6-sources.jar` | `2203670724f6b97abc8840cb8afa230e7c5ead8b2aa39cbd32a55375c7feb507a5a4d44872ba4af6e383cbc945956054e9825442f35907f9e4a582763e3ea135` |
| `totem-villagers-0.1.7.jar` | `18dc0eaf04c0988dc79373d103028844ba4388471759b264008a913851b6debfad37ec92bc20867172f428d96e4b126de96d5d84c2aca84a8507fbd63760ebbc` |
| `totem-villagers-0.1.7-sources.jar` | `e181ed5d5db92ec53fec1f8a7546d16647bcf21818862fc9fb325c6289f3fcff056cebc278e9119b3645f57747519f97d007fa53b54088d47d2e118c5f43ed1c` |
| `totem-villagers-0.1.8.jar` | `9c1c76c3a3257ac7005e681941dbb6b4b291a8fc5f22b29162fefc87fb40aaf83c747027273c5e95d2a575486dd40e20390bda74ed95afcf28227d8057fe2531` |
| `totem-villagers-0.1.8-sources.jar` | `e1b391f3c61517d805185947fe888f42bf253753704e0e8c0821207e730863641dbf3ee5ad739c45986829fbfb18568dd8e4a5e2bfff3acd79b1089faa18f2bd` |
| `totem-villagers-0.1.9.jar` | `6b58933bbfd3deadd4f7120efd735b904fdae1f0f18625a53f9756d1acef6aa5186e99d246937b183ddd3842fef40b5c1984208ffebdd5ef60d0805725decd61` |
| `totem-villagers-0.1.9-sources.jar` | `97595fab407dcfae8114faea234b0659f5541f69e2295666afca2617d49fb0404aa4f058ef9d9bd43c6c9cd7b0420b45b1cc5b3e130a3c80546643913e0e8210` |
| `totem-villagers-0.1.10.jar` | `0654caee5bddd6566eff42b5061a03abe294833fe080ac8661d0547461bbcbd5d3886ef5187c86e6c319629cdd9c1515afcc13b0f84c52d57c7ceb4a5c474ce5` |
| `totem-villagers-0.1.10-sources.jar` | `97692a2d6afd94a07e40772b14bfa3adcb43bfcf003dc736a9b500a3ddeb56a6c4dd3ea45634e16009684f42cd87a382c383b833e9ef9caeb5978d0b8d97dbfe` |
| `totem-villagers-0.1.11.jar` | `dc312499805a5d3b20bdec4efc2464678e940094be86a99b98274be572d89201e5a61449f1743c9728eb9dc7f755f7bde106fff9ddb0bc08d09e13b9b672b6e6` |
| `totem-villagers-0.1.11-sources.jar` | `d72280905e7954a02cdbc351569548a7dfdb8de7d472a1692926b1524af061224fb76482ebb9ee75640c9b733797c8a8d9c50ba3ad14557180b6aa8571b733b3` |
| `totem-villagers-0.1.16.jar` | `e8a97bc65d1607f4633e8a58beb62dffee774cf2c54ed8b948b777f621dbdbc7798a47fd783679904b67eb1e23e921d67759df16bb41af2b0d7c5b51edf2571e` |
| `totem-villagers-0.1.16-sources.jar` | `97c557724f325d80661d6ee231226584d99d5443fe91c32f01e45bb2c100c66b4ae906fbc452a42c3b75bb536ae6dc81b1adf74ecbcc5fde96c3622fb1e9dd89` |
| `totem-villagers-0.1.17.jar` | `51d6c531c9380ff63190c5d8cb03233c21a5bb679bf9ac0bbd9f771a2de707a8a5a65ffe14f8b81f0f6cd09c7b8f72ab88669a83f12fb00f629ee5c2bdb770e9` |
| `totem-villagers-0.1.17-sources.jar` | `0276fd17404eace069659db73e380585fc6a2e64ea3db8ba143390634cf70e5d01efc3a68ba38ea4623b97cffdc48a169c0549b5e69510d9a04a6f2df6452a88` |
| `totem-villagers-0.1.18.jar` | `f46aadc5d037dbd8205b07f490cbb6cb22eb2266e89cd71f039b44f0d56c01ca780a7622bbe4bc284dd80df3becb8dacf2afefbde0e9e89e645b53eb1b845b22` |
| `totem-villagers-0.1.18-sources.jar` | `0d369535e46700e9e8b8bc3f5545ce5269b2736f7086c64ee70f6c3c91ceef3e8c234fbd38a3118aad5c1041dbbe158f541e9b263d4aed556adf11b5a34b59b0` |
| `totem-villagers-0.1.19.jar` | `0bf3aea09eedd49133ae9d8e5a89918819261fb5d358279490a43ef863d6ba97cdc7246ee032baa0d59a73fb641657ad827576a1f0125c57531b03992a4710f5` |
| `totem-villagers-0.1.19-sources.jar` | `fea3f706e4dddc3fa04c03ec73c204ef319c99122d309c8f0f180dd96d32393143b730264ec35a2718ab916f9a822f9993dd2ff63a2c8b4f19427b348e1fbd7a` |
| `totem-villagers-0.1.20.jar` | `e7698fd74c922e6332313131f1acec77fa8fab341dd92f27ae52dd969c6172750a4a8cca83380232d1ee8213c60410f1e91f147ef2af6ece192b706a4d55edbb` |
| `totem-villagers-0.1.20-sources.jar` | `5a129a2e7983077b2e22de98961001ea8bf2bfaf737a39ae206d757ee1416bce541b3c048afd08559639fcf39a1bfd5d87977d7ea7408d9225cdd03851124946` |
| `totem-villagers-0.1.21.jar` | `39cd4b11407415c8298c5e0c71e336d4a7ee113780d756064c5fc553dd9bb7c692e512026a421123bde4c47644ceb3685c003d8c3d88df158357a52a85e054de` |
| `totem-villagers-0.1.21-sources.jar` | `d2da207cb9361df527eb0ea70c99dc60e9dc6eb90892042d34ef44d85e142c689a037fc4734152bce56fc28828e96cb18a11611ef20374677dbf8e1388cb2449` |
