# Procedural generation: planets, ships and stations

Status: design, third pass (November 6). Nothing here is implemented yet. Items marked **proposal** still need the player's decision.

## Goal and limits

Make the galaxy worth exploring: sites that look good inside and out, vary from visit to visit, get more dangerous further from Sol, and reward the fuel spent reaching them.

Add as little to Minecraft as possible. Reuse vanilla systems wherever they fit: structure templates, loot tables, mobs with equipment. Add a few blocks and mobs only where vanilla cannot do the job.

## Decisions so far

| Topic | Decision |
| --- | --- |
| Exteriors | Exteriors matter as much as interiors, including the hand-built ships. |
| Threats | One robot mob, the repair bot, plus space-themed versions of existing mobs. No turrets. |
| Repair bot | Always hostile on derelicts. A friendly repair bot aboard the player's own ship comes later. |
| Authoring | Built in-game with the shipyard and pad tools. |
| Hull plating, scrap | No. Generated builds use cheap vanilla blocks. Demolishing a wreck is allowed; it just must not pay out valuable blocks. |
| New ores | None for now. Vanilla resources placed by planet type and rare vanilla loot do the job (below). |
| Nether and End | Both stay reachable by portal, as in vanilla: we add to Minecraft, never take away. |
| Site reset | A reset disposes of everything in the site's bounds, player blocks included. |
| Surface sites | One guaranteed site per landable planet, near its landing pad; more scattered across the surface. |
| Remote pads | A Pad Package item (crafted from a Pad Computer and one diamond), used from orbit of any landable body except the End, places a new registered pad at a random spot on the surface below, at least 1,000 blocks from any other pad. |
| End by ship | Ships can also fly to the End once someone has built and registered a pad there, and only from Sol / Terra. |
| Materials | Generated builds must not be a source of valuable blocks. Iron blocks and similar are out. |
| Danger | Grows with distance from Sol on the galaxy chart. |
| Planet content | All kinds of surface sites; planets need much more content. |
| New blocks and ores | Allowed, but very sparingly. |
| Persistence | Random sites between FTL locations do not persist. Fixed sites reset after 2-3 in-game days. |

## How content gets made

- **Hand-authored pieces.** Rooms, hull sections, surface buildings and ruins are built in-game on registered pads (a pad is already a 32 x 32 x 32 build volume) and saved as vanilla structure templates under `data/nullspace/structure/`.
- **Code-assembled sites.** Code picks pieces, joins them at marked doorways, rotates them and applies variation: damage, power, lighting, loot and mobs. Each visit or reset rolls a new variation of the same site.
- **Data-driven loot.** Containers use vanilla loot tables, one per archetype and danger level.
- **Hand-built ships move to templates.** The shipyard designs are currently built by Java code. They become saved templates too, so their exteriors can be fixed by editing them in-game rather than in code.

### Authoring tools (to build first)

| Command | What it does |
| --- | --- |
| `/nullspace author save <kind>/<name>` | At a pad computer: saves everything inside the pad volume as a piece or whole design. Marker blocks (doorway connectors, loot spots, mob spots, the controller) are recorded and removed from the saved blocks. |
| `/nullspace author load <kind>/<name>` | Places a saved piece on the pad for editing. |
| `/nullspace author preview <archetype> [danger] [seed]` | Assembles a generated site on an empty pad, so its look and variation can be reviewed and rerolled. |
| `/nullspace author gallery` | Like the shipyard showroom: every saved piece and a sample of each archetype on rows of pads, for review. |

A unit test checks every saved template against the cheap-material palette, so a valuable block cannot slip into generated content.

## One pipeline for every site

1. **Context.** World seed, place, zone and danger level 0-3 (chart distance from Sol; black holes and pulsars add one).
2. **Archetype.** What the site is (catalog below).
3. **Layout.** Pieces chosen and joined for that archetype. Ships and stations use manufacturer kits: nose, hull segments, engine section and wings, each in that manufacturer's style.
4. **Condition.** Damage, breaches that leak air, dead or failing power, sealed sections and fires, using the real air and power systems. More danger means worse condition.
5. **Contents and threats.** Loot tables and mobs for the archetype and danger.

## Exteriors

- A clear silhouette from 30 blocks: distinct nose, body and engine section. No plain boxes.
- Two or three exterior materials per manufacturer plus one accent colour, consistent across its ships and stations.
- Visible function: engine bells, cargo doors, masts and antennae, docking collars, rows of lit windows.
- Damage shows outside: torn plating, missing sections, scorch marks.
- Every hand-built ship gets an exterior pass against these rules before new content is built on top.

## Materials

Generated builds use a cheap vanilla palette: stone bricks, smooth stone, deepslate, terracotta and concrete colours, glass and lamps. No metal or gem blocks (iron, gold, copper, diamond, emerald, netherite), so tearing a wreck apart gives only cheap blocks. A unit test enforces this for every template and blueprint, including the shipyard designs, which currently use iron blocks.

## Threats

| Danger | Where | Threats |
| --- | --- | --- |
| 0 | Sol | None. At most dark rooms and a small leak |
| 1 | Near systems | Breaches, dead power, lost crew (suited zombies) in dark derelicts |
| 2 | Mid systems, nebulae | Adds suited skeletons and repair bots; bigger breaches, fires |
| 3 | Far systems, black hole, pulsar | More of everything: several repair bots, worst conditions, best loot |

- **Repair bot.** The one new mob. Floats through derelicts fixing breaches and restarting machines, and is always hostile to players there. Makes wrecks feel alive and can undo your progress. A friendly version for the player's own ship is a later feature.
- **Lost crew.** Vanilla zombies and skeletons spawned with space suits and a crew name. Same AI, new look. They do not need air and occasionally drop suit parts.
- No turrets.

## Rewards

Fuel is the main reward everywhere. Everything else is vanilla, made rare by where it comes from. Agreed with the player.

**Planet resources** (vanilla generation features added to each planet type's biomes; no new blocks):

| Planet type | Resources |
| --- | --- |
| Lava | Ancient debris (netherite), nether quartz, gilded blackstone |
| Barren moons | Plentiful amethyst geodes; occasional diamonds |
| Ice | Blue ice, powder snow, emeralds in mountains |
| Ocean | Prismarine ruins, sponges, heart of the sea, nautilus shells |
| Desert | Archaeology: suspicious sand with pottery sherds, sniffer eggs, trim templates |
| Jungle | Cocoa, bamboo, melons; a breathable garden world |

Lava worlds are a second source of netherite, quartz and other Nether materials, alongside the Nether itself.

**Site loot** (vanilla loot tables per archetype and danger):

| Danger | Loot on top of fuel, food and parts |
| --- | --- |
| 1-2 | Enchanted books, golden apples, diamonds, shulker shells, armor trim templates |
| 3 | Echo shards, totems of undying, the netherite upgrade template, enchanted golden apples, music discs |
| Black hole, pulsar | Very rarely: elytra and nether stars |

**Progression.** System upgrades take materials from far away: tier 2 needs amethyst (moons), tier 3 needs netherite scrap (lava worlds) or echo shards (far derelicts). Since engine range comes from upgrade tier, reaching further needs materials from further out.

## The Nether and the End

- Both stay reachable by portal exactly as in vanilla.
- **End by ship:** once someone builds and registers a pad in the End, the End appears as a landing destination for ships in Sol / Terra orbit, and only there. Ships launched from an End pad return to Terra orbit. Until a pad exists, there is no ship route to the End.

## Site catalog

| Where | Archetypes |
| --- | --- |
| Orbit zones | Derelict freighters and survey ships, abandoned stations, mining outposts at asteroid fields, research stations near nebulae and pulsars, smuggler hideouts in far zones |
| Planet surfaces | Crashed ships, abandoned outposts and landing sites, research domes, mining camps, ruins, supply caches in caves. Each planet type has its own set (lava-world foundries, ice-world research domes, jungle overgrown ruins, desert buried outposts) |
| Between FTL locations | Random signals during journeys: distress calls, drifting cargo, ambushes at higher danger |

**Surface density.** Each landable planet gets one guaranteed site within about 200 blocks of its landing pad, so every landing has something to do. Further sites sit on a coarse grid across the surface (roughly one per 300-500 blocks), reached on foot or with a Pad Package.

## Pad Package

A crafted item for reaching new ground on a planet without walking there.

- Use it aboard a ship in the orbit of any landable body, Terra included. It does not work for the End. It places a new registered pad, with its Pad Computer, at a random spot on the surface below, at least 1,000 blocks from every existing pad on that surface.
- The pad uses the same site finder as starter pads: the flattest dry ground near the chosen spot, levelled, with clear sky above. It is named after the planet (for example "Kelidia II Outpost 2") and becomes the ship's destination, so Land takes you straight there.
- The package is used up. It fails with a clear message (and is not used up) if the ship is not in orbit of a landable body or no clear site is found.
- Recipe: a Pad Computer and one diamond, plus cheap materials (for example cobblestone), so each new pad costs a diamond and a computer.


## Persistence and reset

- **Random journey sites** exist only while their signal is live, in the journey's temporary space. They are never saved.
- **Fixed sites** (orbit and surface) reset 2-3 in-game days after a player last left them. A reset rebuilds the blocks and rolls a new variation of damage, loot and mobs.
- A site never resets while a player is in or near it.
- A reset disposes of everything inside the site's bounds, including blocks and items players left there. Player-built stations and bases outside site bounds are never touched.
- A derelict a player claims (the existing Claim action) becomes their ship and stops being a site.

## Build order

| Step | What | Who | Playable result |
| --- | --- | --- | --- |
| 1 | **Authoring tools**: save, load, preview and gallery commands; marker blocks; palette test | Claude builds | Pieces can be built and saved in-game |
| 2 | **Foundations**: cheap-palette rule, danger level from the chart, loot tables per archetype and danger, the reset system for fixed sites, End pads reachable from Terra, upgrade tiers needing amethyst and netherite or echo shards | Claude builds | Loot and danger scale with distance; fixed sites reset; the End is a ship destination |
| 3 | **Shipyard exteriors**: move the hand-built ships to templates, then improve their exteriors | Claude converts and drafts; player reviews and edits in-game | Shipyard ships look like spaceships from outside |
| 4 | **Orbit sites**: manufacturer kits (nose, hull segments, engines, wings, rooms); derelicts and stations assembled from them with condition; lost crew and the repair bot | Claude drafts kits and code; player reviews | Derelicts vary, look good and fight back |
| 5 | **Planet sites**: piece sets per planet type; one site near each landing pad plus a sparse grid; vanilla resources per planet type; the Pad Package | Claude drafts; player reviews | Every landing has something to explore and something to mine |
| 6 | **Journey sites**: random signals that vanish afterward, including ambushes by lost crew and repair bots | Claude builds | Journeys have surprises |
| Later | Friendly ship repair bot; hostile ships | | |

Each step ends in a playtest. Steps 3-5 are mostly content: drafts are built with the authoring tools and reviewed in the gallery before code depends on them.

## Later ideas

- **Ship weapons use materials as ammo:** copper, iron, gold and diamond, each doing more damage than the last. Fighting becomes expensive and deliberate. Planned for after hostile ships exist.
- **Friendly repair bot** aboard the player's own ship.
- **Hostile ships** with an AI pilot.

## Open questions

None for steps 1-2.
