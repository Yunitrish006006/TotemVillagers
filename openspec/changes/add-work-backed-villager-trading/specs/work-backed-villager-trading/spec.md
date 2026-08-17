## ADDED Requirements

### Requirement: Work-backed sell stock

The system SHALL derive each vanilla profession's sell rows from lawful
work-order outputs physically present in its persistent personal inventory.
Minecraft's complete level 1–5 profession trade data MAY provide the matching
price and cost shape, but a randomly generated live offer list SHALL NOT decide
whether a lawful product can be made or sold. A villager SHALL NOT freely
generate sell stock during restocking.

#### Scenario: Villager has no produced stock

- **WHEN** a player opens a trained villager's trade screen before the villager
  completes a matching work order
- **THEN** no row for that depleted product appears; the player sees Minecraft's
  ordinary trade screen and receives no free stock.

#### Scenario: Completing and selling produced stock

- **WHEN** a villager completes a valid work order and a player completes its
  matching sell-side trade
- **THEN** the server adds the exact produced output once and atomically debits
  it once when the trade succeeds
- **AND** the matching row appears while a complete sale batch remains and is
  removed once physical stock becomes insufficient.

#### Scenario: Random trade roll does not restrict lawful inventory

- **WHEN** a vanilla-profession villager's generated offer list contains no
  sell row for a component-free, recipe-authorised profession output
- **AND** a complete physical batch of that output exists in its personal inventory
- **THEN** the server publishes a matching sell row without fabricating stock
- **AND** an unrelated physical item with no profession order remains unsellable.

#### Scenario: Survival equipment is not liquidated

- **WHEN** a villager's physical food or profession tool would otherwise match a
  lawful sell output
- **THEN** the server reserves one profession tool and a total 20 nutrition
  points before calculating saleable surplus.

#### Scenario: Player sale stores received materials

- **WHEN** a player completes a villager purchase offer that returns emeralds
- **THEN** the server stores the exact displayed input cost in that villager's
  personal work inventory after vanilla accepts the trade
- **AND WHEN** the complete input batch cannot fit in that inventory
- **THEN** the purchase offer is unavailable before the player can lose items.

### Requirement: Dual-source villager work

The system SHALL let a villager produce trade stock through both a
profession-appropriate permitted world action and its persistent personal
27-slot work inventory that contains the order's required raw inputs. The
vanilla villager inventory SHALL remain separate. A permitted renewable world
harvest MAY place its validated raw output into the personal work inventory when
a subsequent validated workshop recipe is the sell-stock production step.

#### Scenario: Autonomous world work

- **WHEN** a villager finds a reachable permitted world target for its order
- **THEN** the server validates and commits that work action before adding the
  resulting output to the personal inventory.

#### Scenario: Farmer field supplies recipe inputs and crop sales

- **WHEN** a Farmer reaches a permitted mature wheat, carrot, potato or beetroot
  crop near its Composter and its personal work inventory can hold the actual
  harvest remainder
- **THEN** the server resolves the live Minecraft crop loot table, retains one
  real seed or crop item to replant, and atomically deposits the remaining
  drops into that work inventory
- **AND THEN** the crop is replanted at age zero, with no merchant stock minted
  until a later validated recipe consumes its inputs or a complete physical crop
  sale batch is traded.

#### Scenario: Specialist world work supplies a material warehouse

- **WHEN** an assigned Miner or Lumberjack reaches a permitted world target and
  its personal work inventory has room for the exact output
- **THEN** the server atomically commits the mined or harvested material into
  that inventory and does not also credit merchant stock
- **AND WHEN** the inventory is full or changes during the commit
- **THEN** the world target is left unchanged or restored without an output.

### Requirement: Specialist physical material sales

The system SHALL allow a custom specialist to sell configured gathered
materials only while the exact result batch is physically present in that
specialist's personal work inventory. A successful trade SHALL consume that
physical batch and SHALL NOT mint or debit separate merchant stock. Miner rows
SHALL include its stone and configured ore-drop materials: common stone sells
in 16-item batches for one emerald, coal/raw copper in 8-item batches for one,
raw iron/quartz in 4-item batches for one, raw gold/redstone/lapis in 3-item
batches for one, diamond/emerald singly for six, and ancient debris singly for
twelve. Lumberjack rows SHALL include its actual logs, saplings, sticks and
apples at 8/8/32/4 items for one emerald respectively.

#### Scenario: Miner supplies Librarian lapis

- **WHEN** a Miner completes permitted work on a block in the configurable
  `totem:miner_ores` tag and has at least three stored lapis lazuli
- **THEN** its one-emerald, three-lapis sale becomes available
- **AND WHEN** a player completes that sale
- **THEN** exactly three physical lapis are removed and the row is unavailable
  until the Miner gathers enough again.

#### Scenario: Specialist merchant screen is available

- **WHEN** a player normally interacts with a Miner or Lumberjack while
  work-backed trading is enforced
- **THEN** the server creates its specialist material row before vanilla checks
  for an empty offer list, so the merchant screen opens even though the custom
  profession has no vanilla trade set.

#### Scenario: Lumberjack supplies apples

- **WHEN** a Lumberjack harvests an eligible oak tree
- **THEN** the tree's logs and bounded canopy leaves resolve through the live
  Minecraft block loot tables and the complete resulting yield is inserted
  atomically into its personal inventory
- **AND WHEN** four stored apples are sold for one emerald
- **THEN** exactly four physical apples are removed from that Lumberjack.

#### Scenario: Specialist material offer follows physical stock

- **WHEN** a Miner has a complete physical diamond sale batch or a Lumberjack
  has a complete physical log sale batch in its work inventory
- **THEN** its merchant list includes the matching 1-diamond-for-6-emerald or
  8-logs-for-1-emerald row
- **AND WHEN** that row is traded until the batch is insufficient
- **THEN** the exact material is removed from the personal inventory and the
  dynamic row is removed from the merchant list.

#### Scenario: Workshop processing

- **WHEN** a villager's personal work inventory contains the raw inputs for an order and the
  villager completes the linked job-site action
- **THEN** the server consumes those inputs and inserts only the exact order
  output into the same personal inventory.

### Requirement: Farmer physical crop sales

The system SHALL allow a Farmer to sell harvested wheat, wheat seeds, carrots,
potatoes, poisonous potatoes, beetroots and beetroot seeds only while the
complete result batch is physically present as surplus beyond its total
20-nutrition survival reserve. A successful trade SHALL consume that exact
batch and SHALL NOT mint or debit separate merchant stock. Wheat, carrots and potatoes SHALL sell in
20-item batches for one emerald; beetroots in 15-item batches; wheat and
beetroot seeds in 32-item batches; and poisonous potatoes in 16-item batches.

#### Scenario: Farmer crop offer follows physical stock

- **WHEN** a Farmer has at least 20 physical carrots plus another stored
  20-nutrition survival reserve in its work inventory
- **THEN** its merchant list includes a 20-carrot-for-1-emerald row
- **AND WHEN** the row is traded
- **THEN** exactly 20 carrots are removed and that row is removed until the
  Farmer harvests enough carrots again.

### Requirement: Local villager material market

The system SHALL allow a loaded, fed villager with a live work-order material
shortage to walk to a loaded, fed supplier in the same dimension within 32
blocks and purchase physical unreserved material from that supplier's personal
work inventory. The recipient's current vanilla purchase offer SHALL be the
only price authority: the offer must require exactly that one material, have no
side input and return pure emeralds. The recipient SHALL receive the offer's
complete material batch only when it has capacity and sufficient physical
emerald items; the same exact emerald stack SHALL move from the recipient's
personal inventory to the supplier's. No item, emerald, purchase offer or
chunk load SHALL be fabricated by this process.

#### Scenario: Data-pack purchase rate changes

- **WHEN** a data pack changes or removes a villager's material-for-emerald
  purchase offer
- **THEN** the autonomous material market uses the new complete batch and
  emerald result, or makes no purchase when no eligible offer remains.

#### Scenario: Buyer cannot afford a material purchase

- **WHEN** a villager has insufficient emerald items for its eligible
  live purchase offer
- **THEN** its supplier retains the physical material and neither inventory changes.

### Requirement: Generated vanilla-village bootstrap

While work-backed trading is enforced, the system SHALL recognise only a newly
generated vanilla `minecraft:village_*` structure as eligible for autonomous
founding supplies. It SHALL persist that structure identity and bounds, give
each loaded adult resident exactly once a finite stack of eight physical
emeralds and six bread, and start its independent Totem hunger at 20/20 so the
settlement can perform its first work. A per-resident persistent
ledger SHALL grant the same endowment to an adult whose village chunk loads
later without paying an earlier resident twice. The system SHALL never treat
player-built beds, bells or a later player settlement as generated villages,
and SHALL NOT grant an endowment to a village discovered while the feature is
disabled.

After the complete generated-facility search area is loaded, the system MAY
establish a missing recovery site only in safe vacant cells inside the generated
structure bounds. A Lumberyard SHALL contain a tree satisfying the current live
Lumberjack order, its Woodcutter, a Smithing Table and a supported two-segment
vine trellis. A Miner starter SHALL contain a Furnace and a covered, walkable
descending shaft whose retained stone faces satisfy the current live Miner
order. If either complete site cannot be placed safely, that role SHALL remain
absent rather than overwriting village or player blocks.

Only a retained stone face inside the persisted Miner Zone of that generated
village SHALL act as a renewable deep seam. Its successful live-loot commit
SHALL preserve the same stone face while consuming real pickaxe durability;
ordinary and player-created Miner Zones SHALL consume terrain normally. The
Toolsmith MAY repeatedly clip only the lower segment of the generated trellis,
crediting its live shears drop and consuming one real shears durability while
preserving the upper mother segment. Three physical plant fibres SHALL become
string only through the current player recipe.

The persisted Lumberjack Zone of a generated village SHALL act as a rooted
nursery and may preserve its order-declared replacement sapling after a valid
atomic harvest even when that canopy's live loot contains none. A non-generated
or player-created Lumberjack Zone SHALL instead require that matching sapling
from the live drops or the worker's physical inventory before changing the
tree.

An assigned generated-village Lumberjack SHALL choose Woodcutter outputs only
for residents recorded in that generated village's persistent resident ledger.
A legacy generated-village record without a ledger SHALL use its exact saved
structure bounds. A resident or material demand belonging only to a nearby
village SHALL NOT redirect that Lumberjack's processing.

After the sites are established, the system SHALL immediately allocate only
unemployed adults in Farmer → Miner → Lumberjack order. It SHALL NOT replace an
existing career or manual assignment; in particular it SHALL leave a Miner site
unstaffed rather than convert the village's only Farmer.

#### Scenario: Fresh village finds a generation-time tree

- **WHEN** an enabled, newly generated vanilla village has a valid mature tree
  in a nearby newly generated chunk
- **THEN** its adult residents receive founding capital once and its Lumberjack
  Zone is persisted as exactly the validated trunk column for normal workforce
  assignment and replanting.

#### Scenario: A resident loads after its neighbours

- **WHEN** a second adult resident of the same generated village loads after
  the first resident has already received founding capital
- **THEN** the second resident receives its own eight emeralds and founding
  20/20 Totem hunger once while the first resident receives nothing again.

#### Scenario: Fresh village has no valid generated tree

- **WHEN** no valid tree is available during generation but the complete
  facility search area and a safe vacant in-bounds recovery cell are loaded
- **THEN** the system establishes one tree, Woodcutter and fibre-trellis recovery site;
  otherwise the role remains absent without overwriting blocks.

#### Scenario: Generated deep seam remains physical and renewable

- **WHEN** a generated village Miner successfully works a retained stone face
  inside its persisted generated Mine Zone
- **THEN** its live cobblestone and bounded incidental-ore results enter the
  personal inventory, its exact pickaxe loses one durability, and the visible
  stone face remains available for a later demand-driven cycle.

#### Scenario: Manual mine remains finite

- **WHEN** the same Miner works stone in a player-created or non-generated Zone
- **THEN** the source block is consumed normally and is never restored by the
  generated-village deep-seam rule.

#### Scenario: Generated trellis closes the fishing-rod loop

- **WHEN** a Toolsmith needs string, has usable physical shears and can reach
  the lower segment beneath a generated mother vine
- **THEN** one live vine drop and one shears durability are committed while both
  trellis segments remain, and only a current three-fibre player recipe may
  convert those drops into string.

#### Scenario: Manual Lumberyard cannot create a free sapling

- **WHEN** a Lumberjack in a non-generated Zone reaches a valid tree whose live
  drops contain no matching replacement and carries no matching sapling
- **THEN** the tree, axe and personal inventory remain unchanged
- **AND WHEN** the worker carries the exact replacement sapling
- **THEN** that physical sapling is atomically consumed by the successful
  harvest and remains planted at the tree base.

#### Scenario: A nearer neighbouring village cannot steal Woodcutter capacity

- **WHEN** a generated Lumberjack can process one log into planks requested by
  its own resident while a closer outsider requests sticks from carried planks
- **THEN** the Lumberjack serves its own resident, leaves the outsider's demand
  untouched and creates no cross-village material.

### Requirement: Independent sustainable villager hunger

The system SHALL persist player-style 0–20 food, saturation, exhaustion and
food-timer state independently of vanilla's breeding `FoodLevel`. A loaded adult
SHALL receive one metabolism exhaustion pulse per 6,000 ticks. Exhaustion above
four SHALL consume saturation before food, and physical food SHALL apply the
item's current `FOOD` component nutrition and saturation. Natural regeneration
and zero-food starvation SHALL use the player's timers, exhaustion costs,
gamerule and difficulty health limits. The adult SHALL pause Totem work at eight
food points or below and first consume physical edible stacks from its own
personal work inventory. At 18 food or below with at most 20 carried nutrition
points, it MAY pay one physical emerald to a reachable Farmer for one physical
five-bread-equivalent ration pack and retain the uneaten remainder. The Farmer
SHALL preserve a 20-point emergency ration, and this internal exchange SHALL
NOT require a randomly generated player-facing food offer.

A Farmer MAY produce physical `totem:farmer_bread` for village use without a
matching player-facing bread offer, but only when Minecraft's current player
crafting recipe accepts the order's exact inputs and output. A removed or
changed recipe SHALL block production without consuming inputs.

A Farmer with a loaded reachable Composter MAY preserve 64 seeds and 192 wheat,
then submit further seed or wheat surplus through Minecraft's current Composter
chance. A completed Composter SHALL return one physical bone meal to that
Farmer, and the Farmer MAY consume it on a newly replanted crop. Overflow SHALL
NOT be silently deleted.

#### Scenario: A worker has starter bread

- **WHEN** any adult profession becomes hungry while edible food remains in its
  personal work inventory
- **THEN** it consumes that exact physical food before spending an emerald.

#### Scenario: Saturation absorbs a metabolism pulse

- **WHEN** a loaded adult has exhaustion above four and positive saturation
- **THEN** exactly one saturation point is spent before its food value can fall.

#### Scenario: Hunger affects health

- **WHEN** an injured adult remains at 18 or more food, or an adult remains at
  zero food for 80 ticks
- **THEN** natural regeneration or starvation follows the corresponding player
  rule, including the natural-regeneration gamerule and world difficulty.

#### Scenario: Novice Farmer rolled no food sale

- **WHEN** a reachable Farmer has physical surplus food but its generated
  player offers contain only crop purchases
- **THEN** another ration-low villager can still exchange one physical emerald
  for its five-bread-equivalent pack while the Farmer's emergency ration remains.

### Requirement: Safe specialist worker professions

The system SHALL provide registry-backed specialist worker professions. Miner
and Lumberjack SHALL be new Totem professions with bounded assigned work zones;
the existing vanilla Shepherd SHALL perform real flock work without registering
a duplicate Shepherd profession. A persisted Miner, Lumberjack, Builder or
Guard assignment SHALL remain authoritative when vanilla AI performs its normal
profession-reset check, because Totem professions intentionally claim no
arbitrary vanilla POI.

#### Scenario: Miner receives an assigned mine zone

- **WHEN** an assigned Miner searches for an ore or stone work-order source
- **THEN** it considers only reachable, permitted targets inside its assigned
  Mine Work Zone and adds stock only after a valid server-side work commit.

#### Scenario: Miner uses a personal capable pickaxe

- **WHEN** an assigned Miner reaches an eligible target while its personal
  work inventory contains a capable vanilla pickaxe
- **THEN** the server evaluates the target's live loot table with that carried
  tool, atomically returns the tool with one durability consumed and stores the
  resulting drops; without a capable pickaxe, it leaves the target unchanged.

#### Scenario: Lumberjack completes a tree work cycle

- **WHEN** an assigned Lumberjack processes a permitted mature tree inside its
  Forest Work Zone
- **THEN** it gathers only order-eligible wood, replants a valid sapling and
  records the completed work before offering resulting stock.

#### Scenario: Shepherd works a permitted flock

- **WHEN** a vanilla Shepherd has a compatible wool work order and reaches a
  permitted flock target
- **THEN** it performs the configured flock action and produces stock without a
  second conflicting Shepherd profession being registered.

### Requirement: Guard-built managed iron golems

The system SHALL provide a Guard profession and Guard Post. A managed village
with a linked Guard SHALL replace automatic village iron-golem spawning with a
data-driven defence order that visibly constructs a managed iron golem from
reserved personal work-inventory materials. The default order SHALL use four iron blocks and
one carved pumpkin.

#### Scenario: Managed village needs a replacement golem

- **WHEN** a managed village's defence demand exceeds its managed-golem quota
  and its Guard's personal work inventory contains the default defence-order materials
- **THEN** its Guard reserves the materials and places the iron-golem structure
  one block at a time at the Guard Post before the resulting golem is recorded
  as managed.

#### Scenario: Guard construction is interrupted

- **WHEN** a Guard dies, the construction pad changes, the chunk unloads or a
  required material reservation becomes invalid before the final placement
- **THEN** construction is cancelled without a duplicate golem or lost
  unplaced material.

#### Scenario: Unmanaged village or player-built golem

- **WHEN** a village has no linked Guard and Guard Post, or a player builds an
  iron golem outside a managed Guard construction result
- **THEN** vanilla village spawning remains unchanged and the player-built
  golem is not claimed as managed.

### Requirement: Safe intelligent work scheduling

The system SHALL schedule bounded villager work without forced chunk loading,
arbitrary container access or bypassing protection, and SHALL cancel unsafe or
invalid work before it can mutate stock.

#### Scenario: Source becomes invalid during work

- **WHEN** a source container changes, a target unloads, the villager dies or
  its profession/job site changes before work commits
- **THEN** the active job is cancelled without consuming inputs or inserting
  physical output.

### Requirement: Physical Toolsmith replacement-tool circulation

The system SHALL let a Toolsmith obtain physical wood from a Lumberjack and
physical stone or metal from a Miner, validate the world's current player
recipes while processing those materials, and transfer a forged hoe, pickaxe
or axe to a Farmer, Miner or Lumberjack for physical emerald payment. Each core
resource worker SHALL require and wear its real personal tool while working.

#### Scenario: Iron replacement hoe completes the village chain

- **WHEN** a nearby Lumberjack has one log, a Miner has four raw iron and one
  coal plus a valid Furnace, a Toolsmith has a valid Smithing Table and founding
  emeralds, and a Farmer with four emeralds has no usable hoe
- **THEN** the Miner smelts four and sells two iron ingots, the Lumberjack sells
  the log for two emeralds, the Toolsmith validates the live plank, stick and
  iron-hoe recipes, and the Farmer receives the physical iron hoe only after
  paying four emeralds.

#### Scenario: Stone replacement hoe is used when metal is unavailable

- **WHEN** the same Toolsmith demand exists but no iron batch is available and
  a nearby Miner has two cobblestone
- **THEN** the Toolsmith may buy those exact inputs for one emerald, validate
  and forge a stone hoe with two sticks, then transfer it for three Farmer
  emeralds.

#### Scenario: Farmer works with the purchased hoe

- **WHEN** a Farmer harvests one mature crop with a usable physical hoe in its
  protected personal inventory
- **THEN** the crop's live loot and replanting rules apply and that exact hoe is
  returned with one durability consumed, or removed when it breaks.

#### Scenario: Miner and Lumberjack receive replacement tools

- **WHEN** a nearby Miner has no usable pickaxe or a Lumberjack has no usable
  axe, and the Toolsmith can physically obtain one log, three iron ingots or
  three cobblestone, two sticks and a valid Smithing Table
- **THEN** the Toolsmith validates the corresponding live player recipe and
  transfers the physical iron or stone tool only after the worker pays its
  five- or four-emerald price.

#### Scenario: Resource workers wear their purchased tools

- **WHEN** a Miner mines one permitted block or a Lumberjack fells one complete
  permitted tree with a physical personal pickaxe or axe
- **THEN** the exact tool is atomically returned with one durability consumed,
  or removed when it breaks, and work pauses while no usable replacement exists.

#### Scenario: A required player recipe is changed or removed

- **WHEN** a data pack changes or removes the matching smelting, plank, stick,
  hoe, pickaxe or axe recipe
- **THEN** the affected villager processing step produces no substitute output
  and consumes none of its reserved inputs.

### Requirement: Vanilla-only player interaction

The system SHALL leave the player-facing interaction as Minecraft's original
villager trade screen. It SHALL NOT expose deposit, withdrawal or a custom
personal-inventory panel.

#### Scenario: Work is blocked

- **WHEN** an offer lacks stock because no permitted world target or workshop
  inputs are available
- **THEN** the vanilla trade is unavailable and an administrator can inspect
  the server-owned blocked reason through commands.
