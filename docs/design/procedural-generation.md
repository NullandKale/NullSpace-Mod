# Procedural generation: design and status

The galaxy should be worth exploring: sites that look good inside and out, vary between visits, get more dangerous further from Sol and reward the fuel it costs to reach them. This document records the design decisions behind generated content, the pipeline every site follows, and what is built and what is not. The pieces are described in [orbital population](space-world-generation.md), [FTL journeys](ftl-journeys.md), [the ship generator](ship-generator.md) and [generation infrastructure](generation-infrastructure.md), and the tools in [authoring](../development/authoring.md).

The brief is to add as little to Minecraft as possible: reuse structure templates, loot tables and mobs with equipment wherever they fit, and add a few blocks and mobs only where vanilla cannot do the job.

## Decisions

| Topic | Decision |
| --- | --- |
| Exteriors | Exteriors matter as much as interiors |
| Threats | One new robot mob (the repair bot), plus space-themed versions of existing mobs. No turrets |
| Repair bot | Always hostile on derelicts. A friendly one aboard the player's own ship is later work |
| Hull plating and scrap | None. Generated builds use cheap vanilla blocks. Demolishing a wreck is allowed and must not pay out valuable blocks |
| New ores | None for now. Vanilla resources placed by planet type, and rare vanilla loot, do the job |
| Nether and End | Both stay reachable by portal as in vanilla. The game adds to Minecraft and never takes away |
| Danger | Grows with distance from Sol on the [galaxy chart](star-systems.md#danger) |
| Site reset | A reset disposes of everything in the site's bounds, player blocks included. Player stations and bases outside the bounds are never touched |
| Persistence | Random sites between FTL locations vanish. Fixed sites reset after 2.5 in-game days with nobody in the zone |
| New blocks and ores | Allowed, very sparingly |

## One pipeline for every site

1. **Context.** World seed, place, zone and danger 0 to 3.
2. **Archetype.** What the site is.
3. **Layout.** Pieces chosen and joined for that archetype, in a manufacturer's style.
4. **Condition.** Damage, breaches that leak air, dead power and sealed sections, using the real air and power systems. More danger is worse condition.
5. **Contents and threats.** Loot tables and mobs for the archetype and danger.

Today ships and station tiles stand in for steps 2 and 3 by being *generated* (see [the ship generator](ship-generator.md)), and steps 4 and 5 run on derelicts as described below. Step 3 by assembling hand-made pieces, and step 4's condition pass on derelicts, are built as tools but not yet used by content (see [generation infrastructure](generation-infrastructure.md#what-exists-and-what-does-not)).

## Exteriors and materials

A good exterior has a clear silhouette from 30 blocks (a distinct nose, body and engine section, not a plain box), two or three exterior materials per manufacturer plus one accent colour, visible function (engine bells, cargo doors, antennae, docking collars, rows of lit windows) and damage that shows outside (torn plating, missing sections, scorch marks). Generated hulls are bevelled with engine pods and an accent stripe; swept wings, fins and landing gear are not built (see [the ship generator](ship-generator.md#not-yet)).

Generated builds use a **cheap vanilla palette**: stone bricks, smooth stone, deepslate, terracotta and concrete colours, glass and lamps. No metal or gem blocks (iron, gold, copper, diamond, emerald, netherite), so tearing a wreck apart yields only cheap blocks. [`GenerationPalette`](../../src/main/java/com/nullandkale/nullspace/worldgen/blueprint/GenerationPalette.java) enforces this, and a test checks it for every template and generated ship.

## Threats and danger

| Danger | Where | Threats |
| --- | --- | --- |
| 0 | Sol | None |
| 1 | Near systems | Lost crew (zombies in space suits) |
| 2 | Mid systems, nebulae | Adds a skeleton and a repair bot |
| 3 | Far systems, black holes, pulsars | Three zombies, two skeletons, two repair bots |

**Lost crew** are vanilla zombies and skeletons that wear the plain space suit (8% drop chance per piece) and carry a crew name. They keep their ordinary AI and do not need air. **The repair bot** is the one new mob: a small hovering drone with 16 health and a 3-damage attack that flies but cannot pass through walls, and patches itself and nearby wounded crew for 2 health every 2 seconds. It drops iron nuggets and sometimes redstone or copper. Repairing the derelict's blocks is a later addition. Threats appear once, when a player first reaches an unclaimed derelict's zone, and persist. The counts are in [FTL journeys](ftl-journeys.md#danger).

## Rewards

Fuel is the main reward everywhere. Everything else is vanilla, made rare by where it comes from.

**Site loot** comes from vanilla loot tables per room use and per danger (see [rooms and resources](ship-rooms-and-resources.md#searching-a-derelict)): treasure at danger 1 to 2 adds enchanted books, golden apples, diamonds, shulker shells and armor trim templates, danger 3 adds echo shards, totems of undying, the netherite upgrade template, enchanted golden apples and music discs, and black holes and pulsars add elytra and nether stars.

**Progression.** Upgrade tiers need materials from far away: a System Upgrade needs iron, circuit boards and redstone (Tier I), an Advanced one needs amethyst (Tier II), and an Exotic one needs netherite scrap or echo shards (Tier III). Since engine range comes from upgrade tier ([fuel and power](ship-fuel-and-power-design.md#upgrades)), reaching further needs materials from further out.

**Planet resources** (planned, not built): vanilla generation features per planet type, so that each type is worth visiting for something. Lava worlds would give ancient debris, nether quartz and gilded blackstone; barren moons amethyst geodes and occasional diamonds; ice worlds blue ice, powder snow and emeralds; ocean worlds prismarine ruins, sponges and hearts of the sea; deserts suspicious sand with pottery sherds and trim templates; jungles cocoa, bamboo and melons.

## The Nether and the End

Both stay reachable by portal exactly as in vanilla. **The End by ship:** once someone builds and registers a pad in the End, it appears as a landing destination for ships in Sol / Terra orbit, and only there ([`PlanetSurfaces.surfaceOf`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetSurfaces.java)). A ship launched from an End pad returns to Terra orbit. Until a pad exists, there is no ship route to the End.

## Site catalog

| Where | Archetypes | Status |
| --- | --- | --- |
| Orbit zones | Derelict freighters and survey ships | Built |
| Orbit zones | Abandoned stations, mining outposts at asteroid fields, research stations near nebulae and pulsars, smuggler hideouts | Not built |
| Between FTL locations | Distress calls, drifting cargo, an abandoned growing station | Built |
| Planet surfaces | Crashed ships, outposts, research domes, mining camps, ruins and supply caches, with a set for each planet type | Not built |

For surfaces the intended density is one guaranteed site within about 200 blocks of each landing pad, and further sites on a coarse grid of roughly one per 300 to 500 blocks.

### Pad Package (not built)

An item for reaching new ground without walking. Used aboard a ship in orbit of any landable body (not the End), it places a new registered pad with its Pad Computer at a random spot on the surface below, at least 1,000 blocks from every other pad, using the starter-pad site finder, named after the planet and set as the ship's destination. It is consumed, and fails without being consumed if no body or clear site is available. The planned recipe is a Pad Computer and one diamond with cheap materials, so each new pad costs a diamond and a computer.

## Persistence and reset

- **Journey sites** exist only while a signal is live, and vanish afterward. ([`EncounterSites.vanish`](ftl-journeys.md#how-a-signal-exists).)
- **Fixed sites** reset 2.5 in-game days after a player last left their zone, rebuilding the blocks and rolling a new variation of damage, loot and mobs ([orbital population](space-world-generation.md#persistence-removal-and-reset)). A site never resets while a player is in its zone.
- A derelict a player claims becomes their ship and stops being a site.

## Status

| Step | What | State |
| --- | --- | --- |
| 1 | Authoring tools: save, load, check, preview and gallery commands, marker blocks, palette test | Built |
| 2 | Foundations: cheap palette, chart-based danger, loot tables by room and danger, site reset, End pads, upgrade materials | Built |
| 3 | Shipyard exteriors | Superseded: ships are generated. Further exterior variety is open |
| 4 | Orbit sites from manufacturer kits, with condition | Infrastructure built (kits, assembler, condition), content not written. Station tiles and derelicts are generated meanwhile |
| 5 | Planet sites, resources by planet type, the Pad Package | Not built |
| 6 | Journey sites with ambushes | Built |

Later ideas: ship weapons using copper, iron, gold and diamond as ammunition (each stronger and costlier), a friendly repair bot on the player's own ship, and hostile ships with an AI pilot.
