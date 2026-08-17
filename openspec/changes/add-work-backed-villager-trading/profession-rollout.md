# Vanilla profession rollout

> **Storage migration note (2026-08-05):** references below to a Work Chest
> describe the retired storage model. Runtime work now uses the owning
> villager's 27-slot personal work inventory; historic detail remains here only
> as the profession-production record.

This is the implementation baseline for task 1.3. Every sell-side output of a
listed profession needs a data-driven order and a validated action before the
profession is declared covered. An order that is absent remains out of stock in
`enforced` mode; this document is not a free-stock fallback.

All processed outputs must be made through the running server's player recipe
registry. Removing or changing a recipe through a data pack disables the old
work path. Gatherers may collect only their real world yield into a personal
inventory; they never relabel it into a finished sell output. Trade-only
results with no player recipe intentionally remain unmapped, except bounded
native generators that record an exact component-bearing output (the
Enchanting Table and Cartographer explorer-map action).

| Profession | Safe primary path | Current state |
| --- | --- | --- |
| Farmer | Bounded mature-wheat field supply plus Composter recipe work | Mature wheat harvest/replant → linked Work Chest → bread implemented; pumpkin pie, cookies, cake, golden carrots and glistering melon slices remain supported through player-supplied Composter inputs; apple and any suspicious-stew offer that has no exact vanilla flower recipe remain pending |
| Fisherman | Bounded fishing target plus campfire/crafting work | Autonomous vanilla-loot fishing, Campfire `cod → cooked_cod` / `salmon → cooked_salmon`, exact campfire/vanilla boat recipe orders, and personal-bucket `cod → cod_bucket` work are implemented. Empty buckets are supplied by the Toolsmith's three-iron work order. The old enchanted fishing rod row is made by Librarians instead. |
| Shepherd | Linked, nearby flock shearing plus Loom recipes | All vanilla sell outputs are implemented: flock-colour wool, carpet, bed, banner and dye orders, plus shears and painting; dyes use exact vanilla flower/mineral, mixing or cactus-smelting recipes; generated-offer coverage is locked by `CompletedProfessionCoverageGameTest` |
| Fletcher | Fletching Table recipe work | Vanilla arrow, unenchanted bow and unenchanted crossbow recipes implemented; generated enchanted bow/crossbow rows are made by Librarians instead. Tipped-arrow offers are produced only from eight arrows and one component-matching lingering potion. Remaining unmapped offers stay locked. |
| Librarian | Lectern recipe work plus bounded Enchanting Table work | Validated bookshelf, lantern, glass, clock, compass, red-candle and yellow-candle production implemented. Enchanted books and all vanilla generated enchanted equipment are made from a pristine personal input plus tiered lapis at a nearby loaded Enchanting Table; level 1–5 use player powers 6/12/18/24/30 and roll non-curse treasure outcomes at 0%/1%/2%/4%/8%. The exact output creates its own stock-backed offer; legacy random enchanted rows are removed. |
| Cartographer | Cartography Table recipe work plus bounded explorer-map generation | Empty-map, every plain colour banner and item-frame work implemented. Each completed empty-map recipe rolls explorer output at levels 1–5 with 0%/1%/2%/4%/8% chance, selects an unlocked vanilla structure destination, and creates a stock-backed exact filled-map offer. The trade-only globe banner pattern remains unmapped. |
| Cleric | Brewing Stand and potion/bottle validation | Glowstone is implemented through its exact vanilla four-dust recipe; redstone, lapis, ender pearls and experience bottles remain unmapped because no safe production path is implemented |
| Armorer | Blast Furnace and Smithing Table work | Vanilla iron armour and shield recipes implemented; generated enchanted diamond armour is made by Librarians instead. Chainmail remains pending. |
| Weaponsmith | Grindstone/Smithing Table work | Vanilla unenchanted iron axe and iron sword recipes implemented; generated enchanted iron/diamond weapons are made by Librarians instead. |
| Toolsmith | Smithing Table work | Vanilla stone tools, iron axe/shovel/pickaxe, iron and diamond hoes, and the three-iron empty-bucket recipe are implemented. For a Farmer, Miner or Lumberjack missing its usable core tool, the Toolsmith buys physical logs and iron ingots or cobblestone, validates live player recipes, then sells the forged hoe, pickaxe or axe for physical emeralds. The empty bucket has a fixed stock-backed 2-emerald sale; generated enchanted iron/diamond tools are made by Librarians instead. |
| Butcher | Linked livestock/cooking work | All vanilla sell outputs are implemented: Smoker `mutton → cooked_mutton`, `chicken → cooked_chicken`, `porkchop → cooked_porkchop`, and both exact vanilla rabbit-stew recipes; generated-offer coverage is locked by `CompletedProfessionCoverageGameTest` |
| Leatherworker | Cauldron/tanning and leather crafting work | All vanilla sell outputs are implemented: vanilla saddle plus offer-bound dyed leather armour and horse armour; generated-offer coverage is locked by `CompletedProfessionCoverageGameTest` |
| Mason | Stonecutter and furnace work | All vanilla sell outputs are implemented: brick, chiseled stone bricks, polished andesite/diorite/granite, quartz pillar/block, dripstone blocks, and every dyed/glazed terracotta variant; generated-offer coverage is locked by `CompletedProfessionCoverageGameTest` |
| Builder (Totem) | Material-backed final vanilla village-house templates | Persistent, bounded construction from shipped `minecraft:village/*/houses/*` templates implemented; no custom schematics, NBT/loot/entity copying or non-air replacement |

## Specialist material sales

The custom Miner and Lumberjack roles retain real gathered materials in their
personal inventory rather than converting them to merchant stock. A Miner mines
ordinary stone and every block in the configurable `totem:miner_ores` tag using
the best capable vanilla pickaxe carried in its own work inventory, with one
durability consumed only when a mine succeeds. Without a capable carried
pickaxe it leaves the target intact. The shipped tag lists every vanilla ore,
while data packs may add more; all current loot-table drops remain authoritative.
It offers three real `lapis_lazuli` for one emerald. An oak Lumberjack resolves
every harvested log and bounded canopy leaf through Minecraft's live block loot
tables, so apples remain normal probabilistic leaf drops; it offers four apples
for one emerald. These rows are available only while the exact physical sale
batch remains, and the post-trade authority consumes that batch directly; no
virtual restock is created.

A generated-village Mine never restores a worked face. Each successful source
commit attempts to append one deeper covered tread using the original 5×5
spiral geometry and persists the Work Zone's new lower boundary. Unsafe,
protected, occupied, fluid, unloaded and below-world construction is rejected
without rebuilding the source already mined; player-created Mine Zones never
gain automatic shaft construction.

## Village hunger and food market

Totem nutrition persists the same food, saturation, exhaustion and timer state
used by player hunger while remaining separate from vanilla breeding food. In
enforced mode, loaded adults receive one exhaustion pulse per 6,000 ticks;
exhaustion above four consumes saturation before food. Live item `FOOD`
components determine both nutrition and saturation. Natural regeneration and
difficulty-sensitive starvation use the player timers and limits. Adults become
food buyers at eight food points or below.
They first consume edible stacks from their own protected inventory. If that is
empty, a successful internal purchase transfers only the needed physical food
from a Farmer, Fisherman, or Butcher for one physical emerald. At or below that
threshold, every Totem-managed workshop, gathering, fishing, flock and Builder
task cancels its active work before a stock commit and remains paused until the
villager is fed.

The buyer can only select a loaded, reachable adult food producer within 32 blocks.
The producer keeps a 20-point emergency ration, while the transferred food and
one-emerald payment move atomically between personal inventories. This exchange
does not depend on a random player-facing food row. A Farmer can craft the
`totem:farmer_bread` reserve without that row, but the exact current player
crafting recipe remains authoritative. A world-generated village resident
receives one finite base kit of eight emeralds and six bread at 20/20 hunger.
A bred child receives the same physical amount only through an atomic equal
split from its two parents; an underfunded birth receives none and is never
eligible for a later world grant. A profession kit later adds one appropriate
tool and one current sell-offer batch. The durable ledger prevents repeated
grants on chunk reload. No unloaded-villager lookup occurs.

## Required model extension

`WorkOrder.output` identifies the item and amount; the optional
`output_component_patch` holds the canonical component-patch SNBT emitted by
Minecraft for a component-bearing result. `WorkOrder.outputKey()` combines
these into `item ID + component patch`, and normal outputs with an empty patch
continue using the legacy base-item stock map for compatibility.

At a work commit, the producing action must call `matchesOutput` against its
actual server-created `ItemStack`. At offer refresh and post-trade debit, the
same key is recreated from the actual offer result. A different dye colour,
enchantment level/list, potion contents, map identity, banner pattern, or any
other component patch is therefore unmapped rather than being satisfied by the
same bare item ID. Component-bearing entries persist independently in
`variant_merchant_stock`; schema-1 base-only stock remains readable.

The component key infrastructure and dyed-equipment GameTest are implemented.
Individual profession actions remain responsible for generating and validating
their own deterministic special output before their orders are enabled.

### Librarian implementation

The Librarian's bookshelf, lantern, glass, clock, compass and coloured-candle
orders run only when the current server recipe registry assembles the exact
result from its personal work inventory. Enchanted books use a separate,
bounded Enchanting Table action: a loaded Librarian within reach reserves one
book and the tier's lapis cost, then applies Minecraft's server enchantment
selection at player-equivalent power 6/12/18/24/30. Level 1 uses only
`minecraft:in_enchanting_table`; levels 2–5 first roll a 1%/2%/4%/8% chance to
replace that ordinary pool with `minecraft:treasure` minus `minecraft:curse`.
The produced component-bearing book atomically creates one matching merchant
stock entry and its own deterministic-price offer. The same action accepts the
pristine base items behind every vanilla generated enchanted-equipment row:
fishing rod, bow, crossbow, iron sword/axe/pickaxe/shovel and diamond
boots/leggings/helmet/chestplate/sword/axe/pickaxe/shovel. Each item's minimum
Librarian tier follows its former vanilla row, while the current Librarian tier
sets the lapis cost and enchanting power. Old vanilla random book and equipment
offers are removed in enforced mode, so an unmade result can never be sold.

### Cartographer implementation

A linked Cartographer at its native Cartography Table can create the vanilla
empty-map offer by consuming eight paper and one compass. Plain banners and
item frames likewise use their real crafting grids. Each action asks the live
server recipe registry for the exact result before stock is credited. After a
validated empty-map recipe completes, profession levels 1–5 roll 0%, 1%, 2%,
4% and 8% respectively to replace that output with an explorer map. The roll
selects only destinations unlocked at that level: variant villages, jungle
temples and swamp huts from level 2; ocean monuments and trial chambers from
level 3; and woodland mansions from level 5. The server invokes Minecraft's
native structure tags and 100-chunk locator, then uses the vanilla map scale,
preview, marker and localized name. A missing target or failed roll leaves the
ordinary empty-map result unchanged.

Each generated `filled_map` retains its complete map-ID and decoration
components as its stock identity and creates a one-use dynamic offer with the
same emerald-plus-compass price as the corresponding vanilla destination.
Pre-generated explorer-map rows are removed in enforced mode. The trade-only
globe banner pattern remains unmapped.

### Farmer implementation

At a linked Composter, the Farmer can now validate vanilla recipes for bread,
pumpkin pie, cookies, cake, golden carrots and glistering melon slices. Shaped
recipes use their actual 3×3 layouts: cookies place cocoa between wheat, cake
uses its three milk buckets above sugar/egg/sugar and wheat rows, while carrots
and melon slices are surrounded by gold nuggets. Cake's three empty buckets are
returned atomically to the Work Chest at input commit. Suspicious stew remains
offer-bound: the server enumerates real vanilla flower recipes and accepts an
order only when the assembled stew's complete component key equals that
Farmer's current live offer. Trade-only effect/duration combinations with no
matching flower recipe remain unmapped rather than being relabelled as a plain
stew.

For a renewable bread path, the linked Farmer also considers only mature wheat
within a bounded 24-block radius of that same loaded Composter. It checks normal
interaction/protection permission, navigation and available space in its linked
Work Chest before harvesting. The exact one wheat enters that Work Chest and the
crop is reset to age zero; no merchant stock is created at harvest time. Three
such wheat then pass through the existing exact vanilla bread recipe before
bread stock can be sold or consumed. This preserves both the raw-material stage
and the existing stock authority rather than relabelling a crop directly into
food stock.

### Material-worker delivery

An assigned Miner or Lumberjack must now also be linked to a loaded Village Work
Chest that allows the exact output item. A permitted stone target yields one
physical cobblestone into that Chest; a permitted mature oak tree yields its
validated four-log bundle and then replants its sapling. The storage capacity is
checked before the source changes, and a failed insertion restores the source.
These material cycles intentionally do not also credit merchant stock, so the
same mined block or tree cannot be withdrawn from the warehouse and sold a
second time through a virtual ledger.

### Fisherman implementation

A Fisherman requires its native Campfire and uses its own personal work
inventory. While the relevant chunks are already loaded, the runtime searches
only a bounded 24-block area around that Campfire for tagged
`totem:fishing_water`; it neither force-loads chunks nor searches indefinitely.
A world-work cycle preserves one reachable water target until it resolves or
fails.

At completion, the action rechecks normal interaction permission and resolves a
single result using Minecraft's vanilla `FISHING` loot table and a physical fishing rod from the Fisherman's
personal inventory. A successful credited catch consumes one durability; a broken rod is removed.
Raw cod or raw salmon that passes the corresponding vanilla Campfire recipe can
credit cooked-fish stock. A personal empty bucket may instead be reserved for
the cod-bucket order; only a real cod catch consumes it and credits one cod
bucket. Junk, treasure, pufferfish, tropical fish, or an unavailable matching
recipe create no stock. The existing personal-inventory recipe path remains
available when raw cod or salmon have already been supplied.

A Fisherman with no usable rod stops world fishing and seeks a nearby Toolsmith.
The Toolsmith validates the current player crafting recipe for three sticks plus
two string, crafts the rod at its Smithing Table, and sells that exact item for
three physical emeralds. Natural Toolsmiths receive twelve finite starter string,
enough for six replacement rods; this is a bootstrap reserve, not free recurring stock.

### Toolsmith empty-bucket implementation

The Toolsmith publishes one fixed two-emerald empty-bucket row. That row starts
out of stock and is governed by the ordinary merchant-stock gate. At its native
Smithing Table, a 60-tick work cycle reserves exactly three personal iron
ingots, validates Minecraft's normal U-shaped bucket crafting recipe, then
credits one bucket. The Fisherman's carried-bucket fishing order can therefore
be supplied entirely through villager work rather than free stock.

### Toolsmith replacement-tool supply chain

A Farmer may harvest crops only while its protected personal inventory contains
a usable hoe. Each successful harvest returns that exact tool with one point of
real durability consumed, or removes it if it breaks.

When a nearby core worker has no hoe, pickaxe or axe, the Toolsmith buys one
physical log from a Lumberjack for four emeralds, then processes it into planks
and sticks through the current crafting registry. It buys the exact two or
three physical iron ingots from a Miner for one emerald; a Miner with a valid
Furnace can first reserve four raw iron plus one coal and validate the current
smelting recipe to produce four. The Toolsmith combines them with two sticks at
its Smithing Table. If iron is unavailable, it may buy the corresponding two
or three cobblestone for one emerald. Iron/stone hoes sell for four/three
emeralds; Miner and Lumberjack iron/stone tools sell for five/four. A Miner
wears its exact pickaxe by one point per block, and a Lumberjack now reserves
and wears its exact axe by one point per complete tree. Every exchange is atomic,
component-sensitive and limited to living, fed villagers within the bounded
village search range; no item or currency is minted by this path.

### Fletcher implementation

At a linked Fletching Table, the Fletcher can turn one flint, one stick and one
feather from the linked Work Chest into the vanilla four-arrow output. The
action lays the ingredients out as the actual vertical shaped recipe and asks
Minecraft's crafting recipe registry to produce and validate the output. The
same strict grid validation covers a three-stick/three-string unenchanted bow,
and a three-stick/two-string/iron-ingot/tripwire-hook unenchanted crossbow.
Stock is counted per arrow, not per work cycle: a vanilla 16-arrow offer
therefore requires four successful four-arrow jobs before it becomes available.
The generated enchanted bow/crossbow rows are removed and produced by the
Librarian's component-aware Enchanting Table action. A live tipped-arrow offer
is produced as Minecraft's eight-arrow recipe using exactly one lingering
potion with the same component payload; another potion effect can never be
substituted.

### Mason implementation

At a linked Stonecutter, the Mason can execute a limited set of exact vanilla
recipes that directly back its vanilla sell offers. A clay ball is checked
against the vanilla smelting recipe for one brick; stone, andesite, diorite,
granite and quartz block are checked against their respective Stonecutter
recipes for chiseled stone bricks, polished stone and quartz pillars; four
quartz and four pointed dripstone are placed in their true 2×2 crafting grids
for quartz and dripstone blocks. Every colour-specific terracotta order uses
its real 3×3 eight-terracotta-plus-one-dye layout, and the corresponding glazed
variant validates the vanilla smelting result from that exact coloured input.
Each job credits its real recipe output amount, so larger vanilla offer stacks
require multiple completed jobs. Remaining Mason offers remain unmapped until
they have their own validated processing paths.

### Leatherworker implementation

At a linked Cauldron, a Leatherworker can turn three leather and one iron ingot
from its Work Chest into a vanilla saddle, using the true shaped recipe before
stock is credited. For live coloured leather armour and horse-armour offers,
the server derives one deterministic one-to-three-dye combination from the
offer's exact vanilla RGB component. It validates the base leather-equipment
recipe, then Minecraft's armour-dye recipe, and credits only the reconstructed
component patch. The dynamic order belongs to that Leatherworker and that live
offer; a different colour cannot consume its stock. Non-armour Leatherworker
offers remain unmapped.

### Builder implementation

The Totem Builder is intentionally separate from merchant stock. An owner
registers one house site at a time using a final Minecraft village-house
template (`minecraft:village/<plains|desert|savanna|taiga|snowy>/houses/<name>`),
an anchor, a linked Village Work Chest and an assigned Builder Work Zone. The
server reads the structure supplied by the running Minecraft version, chooses
its deterministic palette at the anchor, and persists the next material-backed
block index. It never accepts streets, jigsaw starts, zombie templates,
arbitrary data-pack structures or user-defined schematics.

At every step the exact target must be already loaded, in the assigned Zone,
air and allowed by the normal interaction/protection hook. The Builder walks to
a reachable nearby stand position, reserves exactly one matching block item
from the linked Work Chest and commits only after `setBlock` succeeds. Existing
matching blocks advance without consuming material; different non-air blocks
pause the site instead of being modified. The two states in a vanilla door or
bed consume only its one player item. Block entity NBT, loot tables and
template entities are deliberately excluded, so generated houses cannot be
used to mint container loot or entities.

## Delivery order

1. Add generic linked-job-site workshop action adapters where a vanilla recipe
   can be verified server-side.
2. Implement deterministic special outputs (maps, enchantments, potions) with
   their own validation before enabling their trade orders.
3. Require catalogue coverage against the vanilla sell-offer registry in a
   data-driven test, then enable the profession only after its GameTests pass.
