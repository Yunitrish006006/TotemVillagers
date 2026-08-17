# Totem Villagers

Totem Villagers is a standalone Fabric module that makes villager sell stock
the result of observable, server-authoritative work. It targets Minecraft 26.2
with Java 25 and requires TotemCore 0.6.0.

Work-backed trading is enabled by default on each world. Sell-side offers
require stock made by a validated work order; legacy vanilla stock is never
imported for free.

## Current operator flow

All commands require the Minecraft administrator permission.

```text
/totemvillagers mode <disabled|enforced|vanilla_rollback>
/totemvillagers mode status
/totemvillagers start
/totemvillagers role <villager UUID> <totem:miner|totem:lumberjack|totem:builder|totem:guard>
/totemvillagers work-zone create <totem:miner|totem:lumberjack|totem:builder> <min> <max>
/totemvillagers work-zone assign <villager UUID> <zone UUID>
/totemvillagers work-zone status <villager UUID>
/totemvillagers builder-site register <builder UUID> <anchor pos> <template>
/totemvillagers builder-site status <builder UUID>
/totemvillagers builder-site cancel <builder UUID>
/totemvillagers needs <villager UUID>
/totemvillagers guard-post register <guard UUID> <construction-pad pos>
/totemvillagers guard-post status <village UUID>
/totemvillagers guard-post unregister <village UUID>
```

### First playable loop: physical village economy

1. A new world starts in `enforced` mode automatically; no setup command is
   required. `/totemvillagers start` remains available if the world was
   intentionally disabled.
2. Every initialized adult worker starts with a finite profession tool and one
   deterministic merchandise batch. A generated-village founder receives the
   finite world endowment described below; a bred child instead receives its
   starting eight emeralds and six bread only by an exact physical transfer of
   four emeralds and three bread from each parent. Open its normal Minecraft
   trade screen: sell rows exist only for lawful goods physically present in
   that villager's personal inventory.
3. Vanilla player-to-villager purchase rows remain available in `enforced`
   mode. Selling raw goods is the player's only way to place materials into the
   economy: after Minecraft accepts the trade, the exact input stack enters that
   villager's protected inventory. A purchase closes before the trade if the
   complete batch cannot fit or the villager lacks the physical emerald payment.
4. Keep the worker and its native workstation loaded. It chooses from all
   profession work orders backed by the running server's player-accessible
   recipes, regardless of which sell rows Minecraft randomly rolled. Completed
   output creates its sell row; exhausting the physical batch removes it again.
   Minecraft's complete level 1–5 trade data supplies familiar prices where a
   matching template exists, but never decides which products may be made.

`start` never grants free goods or imports old vanilla stock. It simply enables
the same per-world mode used by `/totemvillagers mode enforced`.

Every villager has a separate, persistent 27-slot Totem work inventory. It is
not the vanilla villager inventory, so vanilla AI keeps its normal eight slots.
Players cannot deposit into, withdraw from or view those protected slots;
normal Minecraft trading is the only player interaction. Villagers exchange
needed materials directly with nearby villagers using physical emeralds. A
villager drops its unreserved protected materials on death, while active
transactional reservations are safely resolved by their owning task. Existing
Work Chest records are ignored when next saved.

### World rollout and rollback

The setting is stored in the world save, not a server-global file. New worlds
default to `enforced`. On first load after this update, a pre-existing world
that still has the old default `disabled` setting is migrated once to
`enforced`; a later explicit `disabled` choice is preserved. Enabling
`enforced` scans only already-loaded villagers and gives each one an empty
Totem work state: professions, vanilla inventories, offer identity and existing
offers are preserved, while no vanilla sell stock is imported. Villagers that
load later receive the same zero-stock state. Switching to `vanilla_rollback`
immediately restores the vanilla use counters of already-loaded offer sets and
leaves persisted merchant stock, personal materials and assignments intact for
a later re-enable; it never force-loads villagers or chunks. Use
`/totemvillagers mode status` to inspect the current world setting.

Workshop work uses a villager's native job site and personal work inventory.
Farmer recipes, Butcher smoking, Fletcher crafting, Mason cutting,
Leatherworker recipes, Cartographer map/banner work and non-enchanted
Librarian merchandise validate their normal vanilla recipe before stock is
credited. Recipe inputs and remainders are reserved and committed atomically
from the same 27-slot inventory. Explorer maps are the explicit Cartographer
exception: after that same empty-map recipe is validated, the server uses the
native structure-location and map-generation path to make the exact filled map.

### Librarian enchanting-table books and equipment

A Librarian's enchanted-book rows are not pre-generated vanilla offers. Give
the Librarian books and lapis lazuli in its personal work inventory and keep a
reachable, loaded Enchanting Table within 16 blocks. It walks to that table and
performs one 120-tick work cycle; the book and lapis are removed only when the
server commits one exact enchanted book to stock, so an interrupted cycle leaves
them untouched. The actual result is then
added to the trade list, so the Librarian sells only books it has really made.
Sold-out rows disappear rather than being silently restocked.

The five vanilla profession levels use player-equivalent enchanting powers 6,
12, 18, 24 and 30. They consume 1, 2, 2, 3 and 3 lapis respectively. Normal
work uses the server's current `minecraft:in_enchanting_table` tag. At levels
2–5, a 1%, 2%, 4% or 8% roll instead selects from the non-curse treasure pool;
level 1 never makes treasure books. This is deliberately a replacement roll,
not a bonus added to a normal result. Prices are deterministic from the exact
stored enchantments (including an additional treasure premium), range from 8
to 64 emeralds, and also require one book as the second trade payment. Vanilla
reputation discounts continue to apply, while demand inflation is disabled.

The same table also replaces every vanilla generated enchanted-equipment row:
fishing rod, bow, crossbow, iron sword/axe/pickaxe/shovel, diamond
boots/leggings/helmet/chestplate/sword/axe/pickaxe/shovel. Put one pristine,
unenchanted supported item and the current-tier lapis amount in the Librarian's
personal work inventory. Its original profession's generated enchanted row is
removed; after the real table cycle, the Librarian alone sells that exact
component-bearing result. The minimum Librarian tier follows the old vanilla
row's progression (iron sword at 1; iron tools and fishing rod at 3; bows,
diamond boots/leggings/axe/shovel at 4; crossbow and the remaining diamond
gear at 5). Its price is the former vanilla base price plus the exact
enchantment-quality surcharge, capped at 64 emeralds.

### Cartographer explorer maps

When a Cartographer completes the normal eight-paper-and-one-compass empty-map
recipe, its result rolls for an explorer map instead at profession levels 1–5
with chances 0%, 1%, 2%, 4% and 8%. A successful roll chooses only a map type
unlocked at that level: level 2 can find variant villages, jungle temples and
swamp huts; level 3 adds ocean monuments and trial chambers; level 5 adds
woodland mansions. The server uses the same vanilla structure tags, 100-chunk
search radius, map scale, map marker and localized map name as the original
trade. If no matching structure is found, the result remains an ordinary empty
map.

The filled map's complete component identity is kept in separate merchant
stock and creates a single-use dynamic row with its normal emerald-and-compass
price. Pre-generated vanilla explorer-map rows are removed, so a Cartographer
can sell only maps it actually made. The globe banner pattern remains unmapped.

### Fisherman cod buckets and Toolsmith empty buckets

Every completed vanilla fishing-loot roll is now accounted for. A catch matching
the scheduled cooked-fish order is validated against the current Campfire
recipe. Non-matching food, reusable fishing rods, and pristine items consumed by
a currently loaded villager work order are retained in the Fisherman's 27-slot
inventory; bycatch with no economic use is ignored instead of filling every slot
with component-distinct junk. Every nonempty roll consumes one real fishing-rod
durability point. In particular, caught string and fishing rods can re-enter the
Toolsmith replacement loop. When that bycatch is insufficient, the Toolsmith
uses physical shears on the generated Lumberyard's two-segment vine trellis.
Only the lower mature segment is clipped; the upper mother vine and visible
trellis remain. Three physical plant-fibre drops become one string only through
the running server's current player recipe, and six clippings supply the two
strings for a fishing rod. Each clipping consumes one real shears durability;
replacement shears use two physical iron ingots and the current player recipe.

The vanilla Fisherman cod-bucket row is now real fishing work. Put an empty
bucket in the Fisherman's personal work inventory and give it a reachable,
loaded tagged water target near its Campfire. A completed fishing cycle uses
the normal vanilla fishing loot table; only an actual cod catch fills that
carried bucket and credits one cod bucket to stock. A non-cod catch, danger,
or interruption leaves the empty bucket untouched.

Empty buckets come from a Toolsmith with three physical iron ingots and a loaded
native Smithing Table. The iron can arrive through ordinary village material
trade instead of player insertion. After one 60-tick cycle it validates
Minecraft's normal U-shaped iron-bucket recipe, stocks one empty bucket, and
unlocks its fixed 2-emerald bucket sale. It cannot sell a bucket before that
work has completed.

### Toolsmith replacement-tool supply chain

A Farmer now needs a real usable hoe in personal inventory before harvesting.
Each successful crop harvest consumes one durability point; when the tool
breaks, crop work pauses until another hoe is obtained.

For a nearby core worker without its work tool, a Toolsmith autonomously buys
one physical log from a Lumberjack for two emeralds and validates the world's
current log-to-planks and planks-to-sticks recipes. It then buys the exact two
or three ingots needed from a Miner. The Miner may turn a four-item batch of raw
iron or raw copper plus one coal into four matching ingots through the current
Furnace recipe. At its Smithing Table, the Toolsmith validates the current
player crafting recipe and prefers iron, then copper, then the corresponding
two or three physical cobblestone.

Requests are served in survival order: Farmer hoe, Miner pickaxe, then
Lumberjack axe. Iron/copper/stone hoes cost four/four/three emeralds;
iron/copper/stone pickaxes and axes cost five/five/four. The mineral supplier
receives three/three/two emeralds for an iron/copper/stone hoe batch and
four/four/three for an iron/copper/stone pickaxe or axe batch. The purchased
tool moves into the worker's protected inventory.
Mining consumes one durability per block, harvesting consumes one per crop,
and chopping consumes one durability from the exact carried axe per complete
tree. A broken tool disappears and the worker pauses until the next physical
purchase. No material, tool or emerald is created by the exchange, and altered
or removed player recipes block the corresponding step.

### Optional TotemRemnant backpack smithing

When TotemRemnant 0.1.8 or newer is installed, a Toolsmith learns all four of
its player-craftable backpack recipes at the native Smithing Table: Basic,
Standard, Advanced and Netherite. This is optional registry integration;
TotemVillagers still loads by itself and does not register substitute backpack
items when Remnant is absent. Each job queries the server's live Smithing
Transform recipe, so removing or replacing that recipe through a data pack
immediately blocks production.

The Toolsmith accepts backpack-only materials through the ordinary Minecraft
trade screen: two empty Bundles for one emerald, four leather for one emerald,
one Netherite Upgrade Smithing Template for eight emeralds, or one Netherite
Ingot for eight emeralds. Iron and diamonds can continue to arrive from the
Miner economy or existing vanilla purchase rows. Template, base and addition
are reserved as exact pristine component variants; a named, filled or otherwise
modified Bundle/backpack is never consumed as an empty crafting ingredient.
Successful work places the real backpack in the Toolsmith's protected
inventory and creates a normal sell row only while that stock exists. Basic,
Standard, Advanced and Netherite backpacks cost 8, 16, 32 and 64 emeralds.

### Farmer field harvest and crop sales

A Farmer with a reachable loaded Composter harvests mature wheat, carrots,
potatoes and beetroots within its field. Each harvest uses Minecraft's live crop
loot table, retains one real seed or crop for replanting, and puts only the
remaining actual drops into that Farmer's personal work inventory. This means
Fortune and data-pack loot changes apply normally, while a missing replanting
item leaves the crop untouched. Wheat can still supply the existing bread
workshop cycle. Once the Farmer has retained 64 seeds and 192 wheat, further
surplus is inserted into its loaded job-site Composter through the normal
compost chance. Finished bone meal returns to the same protected inventory and
is physically consumed on newly replanted crops, preventing permanent seed and
wheat accumulation without deleting the overflow.

The Farmer also sells only its physically harvested crop surplus. A dynamic row
appears at 16 wheat/carrots/potatoes, 12 beetroots, 32 wheat or beetroot seeds,
or 16 poisonous potatoes; each complete batch costs one emerald and is removed
from the personal work inventory when sold. The row disappears immediately if
the stored amount falls below its batch, just like Miner and Lumberjack material
rows. These resale batches are deliberately smaller than the corresponding
vanilla purchase batches, so a round trip leaves the Farmer with a material
margin instead of giving the player a free trade cycle.

### Miner lapis and Lumberjack apples

A Miner mines every reachable block in the `totem:miner_ores` tag, as well as
its ordinary stone targets, using the current server loot table and the best
capable vanilla pickaxe in its own personal work inventory. A successful mine
consumes one durability from that exact pickaxe; without a capable pickaxe, the
target remains untouched. The shipped tag covers all vanilla coal, raw-metal,
redstone, diamond, emerald, lapis and Nether ores, including ancient debris;
data packs can add their own ores to the same tag. Every result enters the
Miner's personal work inventory in its real drop quantity. The Miner sells
only physical material currently held in that inventory. Its dynamic material
rows are balanced as follows:

Ordinary mining also has one bounded incidental-ore roll. It is legal only for
Minecraft's live `stone_ore_replaceables` base family (stone, granite, diorite
and andesite) or its `deepslate_ore_replaceables` family (deepslate and tuff),
and the selected family always produces the matching normal or deepslate ore
variant. Height uses the same uniform/triangular bands as vanilla generation:
surface work favours coal, copper and iron, while redstone, gold, lapis and the
rare diamond roll move toward the bottom of the Overworld. Emerald is restricted
to `minecraft:is_mountain`, and the extra gold band to `minecraft:is_badlands`.
Only one ore can be selected per base block. Its own live loot table and the
same pickaxe's harvest tier are then applied, so a low-tier pickaxe cannot turn
a rare roll into an otherwise unobtainable item. Base drop, ore drop and worn
tool commit atomically; insufficient personal-inventory capacity preserves the
source block and exact tool.

The initial combined discovery chance is 4.85% at Y=64, 6.06% at Y=16 and
3.78% at Y=-54 in an ordinary biome. Each ore's exact per-10,000 chance and
height curve is independently reloadable under
`data/totem/totem_villagers/incidental_ores/*.json`, allowing later simulation
results to tune the economy without a Java change.

A stone face is renewable only when it belongs to the persisted Miner Zone of
a world-generated village. A successful operation restores that exact visible
stone face after resolving its live loot, incidental-ore roll and one real
pickaxe durability point. Player-created Miner Zones and every non-stone target
remain ordinary finite terrain. To prevent an unlucky sequence from eventually
removing the iron needed for replacement shears and tools, a generated Miner
that commits 15 consecutive eligible mines without iron uses the currently
loaded, height- and substrate-valid iron profile on its 16th mine. Removing or
changing that data-pack profile removes or changes the safety result; it is not
a hidden hard-coded item grant. Each incidental material is capped at 64 items
in personal storage so an unattended mine cannot grow inventory without bound.

| Miner material | Sale batch | Price |
| --- | ---: | ---: |
| Cobblestone, cobbled deepslate, stone, deepslate | 16 | 1 emerald |
| Coal, raw copper | 8 | 1 emerald |
| Raw iron, quartz | 4 | 1 emerald |
| Iron ingot | 3 | 1 emerald |
| Raw gold, redstone, lapis lazuli | 3 | 1 emerald |
| Gold ingot | 2 | 1 emerald |
| Gold nuggets | 16 | 1 emerald |
| Diamond | 1 | 6 emeralds |
| Ancient debris | 1 | 12 emeralds |

Silk-Touch ore-block drops are also saleable at 2–8 emeralds according to the
ore tier. The three-lapis sale remains the village source for the Librarian's
enchanting-table lapis, not a Cleric restock.

Each complete oak tree harvested by a Lumberjack requires the best usable axe
in that villager's protected inventory and consumes one real durability point.
It resolves both the logs and its bounded leaf canopy through Minecraft's live
loot tables using that exact axe. This means apples, saplings and sticks are
gathered only when the actual leaf-drop rolls produce them; a data pack's
altered tree loot applies automatically. A Lumberjack sells
one log for one emerald, eight saplings for one emerald, 16 sticks for one
emerald, and four apples for one emerald. The log and stick prices keep a
village-positive spread after player crafting against Fletcher stick and
Fisherman boat purchase rows. Its dynamic log and sapling rows also
cover the other native wood variants when a data pack extends its forest-work
tag. Both specialist rows deduct their actual stored items when a player trades,
then disappear immediately when their physical supply is insufficient.

Specialist fallback rows never expose emerald operating currency or edible
survival reserves as merchandise. The server also removes any legacy offer
that asks for emeralds and returns emeralds, before it can be traded.

Only the persisted Lumberjack Zone of a generated village is a rooted nursery:
after an atomic harvest it guarantees the order-declared sapling remains planted
even when that one canopy roll yielded none. In a player-created Lumberjack Zone,
replanting must instead consume either a matching live leaf drop or one matching
physical sapling already carried by the worker; otherwise the complete tree is
left untouched. This prevents manual zones from becoming free tree generators.

Miner and Lumberjack are custom professions without a vanilla trade set. In
`enforced` mode, a normal right-click always opens their merchant screen with
their own physical-material row (locked until the required gathered material
exists). Sneak-right-click remains the direct way to place a pickaxe or any
other work material into their personal inventory.

### Woodcutter

`totem:woodcutter` is a Lumberjack-themed, Stonecutter-style wood-processing
station. Craft it with five planks and one iron ingot, place it, add one kind of
wood input, then select an available result with the arrow controls and take it
from the output slot.

The station never uses a static conversion list. For each selection it checks
the live server player-crafting registry, including the original recipe layout,
the exact number of inputs, the produced stack and crafting remainders. A data
pack that removes or changes a normal player recipe therefore immediately
removes or changes the matching Woodcutter selection as well. Its accepted
inputs and results are data tags: `totem:woodcutter_inputs` and
`totem:woodcutter_outputs`.

A loaded Lumberjack also uses a reachable Woodcutter automatically when a
nearby loaded villager has a genuine missing workshop material that one of the
Lumberjack's current wood inputs can make. It walks to the station, rechecks
the same live player recipe, and atomically exchanges the exact input count for
the real output in its own 27-slot inventory. No demand, unloaded or unreachable
station, full inventory, or changed recipe leaves the gathered wood untouched.
The result is material available to the existing village logistics system, not
free merchant stock. A generated village with a valid Lumberjack Zone records
one safe founding Woodcutter as that worker's preferred station; manually
configured Lumberjacks fall back to a player-placed station within 16 blocks.

### Village material market

Every second, a loaded, fed villager whose current live sell offer is missing
recipe inputs requests its first missing ingredient from loaded villagers in
the same dimension within 32 blocks. The requester walks to the selected
supplier and makes a direct emerald purchase: the buyer's own current vanilla
purchase offer for that material must have one material input, no side input and
a pure-emerald result. The buyer pays that displayed emerald result from its
persistent wallet and receives the offer's complete material batch; the supplier
receives exactly the same emerald amount. Both price and batch therefore follow
a live data-pack-modified offer, and removing the offer prevents the autonomous
purchase. Only physically present, unreserved material can be sold; the
recipient must have space before the transaction, no chunk is force-loaded, and
no item or emerald is fabricated. A supplier first retains the inputs for its
own next live, recipe-valid workshop order, then may sell its surplus.

### Resource-workforce priority

Register at least one Miner or Lumberjack Work Zone to opt a nearby loaded
village into automatic resource-workforce staffing. Every five seconds, only
unemployed adult villagers without any manual assignment are considered. The
runtime fills nearby unstaffed roles in strict order: Farmer (a reachable loaded
Composter), Miner (an unstaffed Miner Zone plus a loaded Furnace inside it),
Lumberjack (an unstaffed Lumberjack Zone), then Toolsmith (a reachable unclaimed
Smithing Table). It assigns the exact Zone to the specialist and never replaces
an existing career, a manual specialist assignment, Builder or Guard. When a
Miner or Lumberjack dies, its Zone assignment is released for a later replacement.

Miner and Lumberjack require their assigned, same-dimension Work Zone and use
normal interaction permission plus a protection veto hook. A Miner also needs a
loaded Furnace job site inside that Zone; automatic staffing binds an unclaimed
Furnace and a manual Miner claims one in the Zone when available. If the Furnace is missing or
destroyed, its world work pauses. Their exact validated world yields go into their
own inventory, never directly to merchant stock; if the inventory is full, the target remains unchanged. A Miner's lapis, a Lumberjack's gathered material and a Farmer's harvested crops are physical-material sale rows: each player sale consumes that same stored material rather than merchant stock. A Farmer harvests
mature wheat, carrots, potatoes and beetroots near its native Composter into its
personal inventory before a later recipe or physical crop sale consumes them. Shepherd and Fisherman world work requires only
their native job site, personal work inventory and a permitted nearby target;
no shared-container link is involved.

The ordinary trade screen remains unchanged. Administrators can inspect a
specialist's server-confirmed Work Zone, inclusive coordinates, stale
role/dimension links and current in-zone state with `/totemvillagers work-zone
status <villager UUID>`; the command never loads an unloaded chunk to answer it.

### Generated-village bootstrap

When a newly generated `minecraft:village_*` structure first loads, the server
persists its exact structure bounds and, once its adult residents load, grants
each resident a one-time physical kit of eight emeralds and six bread and starts
its independent Totem hunger at 20/20.
The per-resident ledger also covers residents whose chunks load later without
paying the first group twice. This is a finite world-generation endowment, not
an ongoing restock or a grant for player-built bells, beds or villages.

A newly bred child is permanently distinguished from a world-generated
resident. In `enforced` mode, each parent must be able to contribute exactly
four emeralds and three bread from its protected inventory. Only when both
complete shares and space for the combined child endowment are available does
one atomic transfer give the child eight emeralds and six bread. If either
parent is short, no parent is debited and the child receives no partial or
later world-generation grant; reloading the world cannot repeat the transfer.

Once residents load, the bootstrap first finds the two generated resource
areas. After the complete facility search area is loaded, a missing or invalid
facility receives a safe in-bounds recovery site rather than leaving the core
workforce permanently incomplete. A Lumberyard contains a mature oak
tree that must match the current Lumberjack world order, its Woodcutter, a
protected two-segment vine-fibre trellis and one Smithing Table for a
fourth-priority Toolsmith; its
Work Zone covers exactly the trunk, and the Lumberjack replants the same plot
after harvesting. A Miner starter contains one Furnace and a covered 5×5 spiral
descending mineshaft. Every landing has a retained raw-stone mining face, a
cobblestone stair ramp, three blocks of head clearance and a solid cobblestone
roof, so the Miner can keep descending without mining away its own path. Its
Miner Work Zone covers the complete shaft. The custom `totem:miner` profession
has a dedicated Miner profession skin with a yellow safety helmet and lamp;
it never consumes a work-inventory slot or becomes a death drop.

On clients, working villagers also show their role tool in crossed hands: Miner
uses an iron pickaxe, Lumberjack an iron axe, Builder an iron shovel, Guard an
iron sword, and Farmer an iron hoe. These are visual fallbacks: the Farmer's
protected personal-inventory hoe remains the authoritative working tool and
owns its real durability, while a real main-hand item equipped by another
system always takes rendering precedence.
Successful mining, chopping, farming, building and Guard construction broadcast
a normal hand-swing event, so the crossed hands and displayed tool visibly lift
and strike while work completes.

Every placed block must be in a loaded structure cell and outside any entity
volume. The recovery shaft may replace air, harmless vegetation, dirt/grass/path
terrain, base overworld stone and vanilla overworld ore while trying all four
horizontal directions. It still refuses fluids, containers, village buildings,
decorations, unloaded terrain and player-built structures. A nearby in-zone
Furnace may be claimed within 16 blocks even when uneven terrain causes one
transient pathfinding miss; ordinary movement continues to retry afterward.
The generated Mine's marked stone faces are deep seams and remain renewable
while their persisted village/Zone identity remains valid; manually created
zones still consume terrain. The generated vine trellis likewise supports
bounded lower-vine clipping while preserving its mother segment. These
facilities are not repaired if a player breaks their Furnace, Woodcutter,
trellis or other required blocks. If a village has no safe space, the
Lumberjack may use an already-valid mature tree inside its own
structure bounds; otherwise that starter role is simply not created. The normal
unemployed-only Farmer → Miner → Lumberjack → Toolsmith allocation immediately uses newly
generated zones without overwriting existing careers or manual assignments. In
particular, bootstrap never converts the village's only Farmer into a Miner;
an unstaffed core site waits for a future unemployed adult instead. A village
with only three candidates never sacrifices those first three roles to create a
Toolsmith.
Persisted Totem specialist assignments also remain authoritative when vanilla
AI checks professions: because Miner, Lumberjack, Builder and Guard intentionally
claim no vanilla POI, the runtime restores their assigned career instead of
letting `ResetProfession` silently turn a mobile worker back into unemployed.

#### Mangrove village

`totem:mangrove_village` is a dedicated natural structure restricted to the
vanilla Mangrove Swamp biome. Its random-spread structure set uses spacing 8
and separation 4 so compact Mangrove patches still have a practical chance to
contain a settlement. It appears only while generating new terrain; installing
the mod does not retrofit already-generated chunks.

The settlement has a 73×73 maximum footprint and a fixed, physically complete
economic core, while its residential edge varies like a vanilla village. Each
structure origin and world seed deterministically selects three to six of eight
non-overlapping home sites, then independently chooses a compact cottage,
family house or longhouse for each site. Every selected home also receives a
small lateral offset and one of three visible treatments: a taller gable, deep
eaves with a broad porch, or a roofed side gallery. Entrances face the
settlement, homes can run north-south or east-west, and supported
Mangrove-and-bamboo branch piers use seed-varied two-turn approaches instead of
repeating one radial L shape. Branches are generated only for selected sites;
the Mine-side branch passes around the shelter's southern edge. Reloading the
same world keeps the same layout; a different seed or structure origin can
change its house count, positions, offsets, styles, roof/gallery treatment,
mirrored furnishings and road bends.

The fixed core retains two steep-roofed longhouses, an open Fisherman
smokehouse and drying rack, a tiered Bell pavilion, rooted stilts and lantern
posts above the wet ground. The core longhouses now act as true through-house
breezeways where the north-south and production piers cross, while the fishing
basin keeps a clear inner lane behind its outer railing. Its four founding beds
remain available regardless of the random residences. Every optional home
contributes one crafting table and one or two additional beds, so a complete
village contains 7–16 beds while still founding exactly four initial workers.
No optional home contains a job site, preventing random housing from stealing
one of the four economic roles.
The economy is physically complete at generation time: a Fisherman's vanilla
Barrel and nearby Campfire, a guaranteed shoreline fishing basin, a renewable
Mangrove Lumberyard with a bamboo processing shed, Woodcutter, Smithing Table,
log stacks and vine trellis, plus a mud-brick-and-bamboo covered sixteen-step
spiral Mine with Furnace and renewable deep seam. The
Fisherman claims the Barrel POI; the Campfire is a required nearby processing
block. Existing worlds whose Fishermen directly remembered a Campfire remain
compatible.

After the complete structure first loads, the bootstrap creates exactly four
adult Swamp-type founding residents: one Fisherman, one Miner, one Lumberjack
and one Toolsmith. It assigns beds and work sites, starts hunger at 20/20, and
gives each villager the same finite food, emerald, tool and merchandise supply
used by the physical economy. The founding-population flag is persisted only
after all four entities enter the world, preventing a crash from recording a
partial settlement. Once completed it never spawns a second founding group;
dead villagers, destroyed workstations and consumed supplies are not silently
replaced.

### Verified four-role steady state

With a loaded generated village, intact facilities and unchanged live recipes,
the Fisherman → food market → Toolsmith → Lumberjack/Miner loop has no modeled
finite resource countdown. Fish renews food; the deep seam renews stone and
bounded incidental metal; the generated rooted oak plot renews logs; logs and the founding
ignition reserve establish charcoal; the trellis renews fibre; iron renews
shears and tools; and every internal payment moves existing emerald items.
Production stops at bounded stock targets and resumes only after consumption.
An assigned generated-village Lumberjack also resolves Woodcutter demand only
from that village's persisted resident ledger (or the exact saved structure
bounds for a legacy record), so a closer neighbouring settlement cannot steer
its limited wood-processing capacity.

This is enforced by a configurable long GameTest rather than inferred from a
short successful run. Three different ore sequences (`0x5EEDBEEF`, `0xC0FFEE`
and `0x1234ABCD`) each completed 10,000 simulated days with all 32 founding
emeralds conserved at the end of every phase, no stock or slot bound exceeded,
the stone face and vine mother source intact, and new food, mining, logging,
charcoal, Furnace replacement, fuel and tool activity in the final 500 days.
"Steady state" assumes the village stays loaded and alive: chunk unloading,
death, player damage, protection vetoes or a data pack removing a required
recipe correctly pauses the affected work instead of bypassing it.

### Builder village-house blueprints

A Builder does not use a custom schematic. Its `builder-site register` template
must be one of Minecraft's final shipped village house templates, for example
`minecraft:village/plains/houses/plains_small_house_1`. The supported sources
are `village/<plains|desert|savanna|taiga|snowy>/houses/<name>` only; streets,
town centres, zombie villages, jigsaw starts and arbitrary datapack structures
are rejected.

Before registration, the Builder must have an owner-matching Builder Work Zone,
and every blueprint target must already be loaded and inside that zone.
Construction is persistent and proceeds
one reachable block at a time. The Builder reserves the exact material only
when it can place the block, commits that reservation only after placement, and
never force-loads chunks or replaces a non-air block. Doors and beds consume
one normal item for their paired template blocks. A block already matching the
blueprint is counted without consuming another item. Template block-entity
NBT, loot tables and entities are intentionally not copied: a house chest is
constructed as an empty normal chest, never as a source of generated loot.

### Guard Posts and managed iron golems

First assign a villager `totem:guard`, let the physical village economy supply
four `minecraft:iron_block` and one `minecraft:carved_pumpkin` to its protected
inventory, then register the Guard Post with a loaded, clear construction-pad block. In
`enforced` mode, an awake and fed Guard keeps one managed golem for a village
with residents, adds one target for every four monsters within 32 blocks of the
pad, and caps the target at three golems. It reserves exactly four iron blocks
and one carved pumpkin before construction, then visibly places the vanilla
five-block pattern one block at a time. The vanilla result is recorded by UUID
as the village's managed golem.

If the Guard, pad, permission, or reservation becomes invalid before
completion, unplaced materials return to the Guard's inventory (or become a
visible drop only when it has no room). The runtime never force-loads a
chunk. Only the 48-block radius of a correctly linked Guard Post suppresses
unmanaged, non-player-created automatic golems; player-built golems remain
untouched, and villages without a registered Guard Post retain vanilla
spawning.

The ordinary trade screen stays vanilla-only. Administrators can inspect live
managed golems versus defence demand, nearby threats, Post coordinates,
construction progress and active protected reservations with
`/totemvillagers guard-post status <village UUID>`; an unloaded Post is reported
as unavailable rather than loaded merely for the query.

### Villager hunger and food market

TotemVillagers persists player-style food, saturation, exhaustion and food-timer
values, separate from Minecraft's breeding `FoodLevel`. Every 6,000 loaded ticks
adds one metabolism pulse: exhaustion above four spends saturation first, then
one food point, exactly like player `FoodData`. Every edible item contributes its
current `FOOD` component's nutrition and saturation; data-pack changes therefore
apply without a Totem food table. At 8 food or less an adult first eats physical
food already in its 27-slot personal work inventory. At 18 food or less, when
its carried ration contains at most 20 nutrition points, it may restock a
five-bread-equivalent physical ration from a reachable Farmer, Fisherman or Butcher within 32 blocks
for one physical emerald and keeps whatever it does not immediately eat. This
internal market does not depend on the producer randomly rolling a player-facing
food offer, and the producer preserves a 20-point emergency ration. With natural
regeneration enabled, 18+ food heals by the same
timers and exhaustion cost as a player; at zero food, starvation damage follows
the world's Peaceful/Easy/Normal/Hard health limits. While hungry the worker cancels and pauses every
Totem-managed workshop, gathering, fishing, flock and Builder task; it cannot
create or replenish stock before eating. A Farmer may craft physical village
bread from wheat at its Composter even without a bread trade, but only while
Minecraft's current player crafting recipe accepts the exact inputs and output.
Removing or changing that recipe immediately blocks the work. No food,
emerald, stock or unloaded Farmer is fabricated as a fallback. Use
`/totemvillagers needs <villager UUID>` to inspect food, saturation, exhaustion
and physical emeralds.

Personal work inventories, reservations, material deliveries, stock, active
work targets, Zones, specialist role assignments, Builder sites, hunger,
wallets, Guard Posts and managed-village state are server-persistent. No work
path force-loads chunks or accesses arbitrary containers.

## Component-aware trade outputs

Ordinary orders use their item ID and count as before. An order that sells a
component-bearing item can additionally declare the canonical server component
patch in `output_component_patch`:

```json
{
  "output": { "item": "minecraft:leather_helmet", "count": 1 },
  "output_component_patch": "{...canonical Minecraft component patch...}"
}
```

The patch is part of the stock identity, not display metadata. The work action
must prove its actual output stack matches it; offer refresh and trade debit
then recreate the same identity from the vanilla offer. Thus a red dyed helmet
cannot consume blue-helmet stock, and an enchanted book, potion, map or banner
cannot be substituted merely because its base item ID matches. Component-free
outputs leave this field absent and remain compatible with existing stock.

### Player-recipe authority

Every processed trade output must be reproducible through the running
server's player-accessible vanilla recipe registry. Crafting, cooking, smelting
and stonecutting actions query that registry at work completion, so a data pack
that removes or changes a recipe immediately prevents the old work order from
creating stock. Gathering jobs still collect only their real world yield into a
personal inventory; they never relabel it into a finished trade good.

Trade-only results with no player recipe stay unmapped even when old saved
stock has the same component key. Pre-generated explorer-map rows and the globe
banner pattern are not synthesized from inventory; Cartographers may instead
create exact explorer maps through their bounded, material-backed map action.
Enchanted books and the former vanilla enchanted equipment rows are another
deliberate exception: the Librarian's bounded,
material-backed Enchanting Table action uses Minecraft's server enchantment
selection and records the exact component-bearing result.
