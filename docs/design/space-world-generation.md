# Orbital population

Space is not empty. Each orbital region holds a few places a player can find: a Waypoint Station, a Manufacturer Exchange, and derelict ships to salvage. This document explains how those places are decided, saved, built and reset. The code is `worldgen/encounters` (`SpacePopulationPlan`, `SpacePopulation`, `EncounterSites`) and `GalaxyPlan.population`.

The guiding rule is that **a menu only ever discovers places; it never creates them.** Opening the destination list, the scanner or a station screen changes nothing in the world. Places appear because a player is in the region, and they are the same places whoever arrives first.

## Which regions have what

The [galaxy chart](star-systems.md) decides, once per world, how many places each region starts with (`GalaxyPlan.population`). Generated places are sparse on purpose, because players build their own stations.

| Region | Starts with |
| --- | --- |
| Sol's home orbit | A Waypoint Station, a Manufacturer Exchange and a derelict |
| Two or three other zones | A Waypoint *or* an Exchange, alternating. These are orbit-only zones of other systems, or stepping-stone features along long links |
| One or two features | Derelicts |
| Every other zone | Nothing |

Zones you cannot land in are good places to put your own station. The chart picks orbit-only zones for the generated ones so landable worlds stay free for players.

### The places

| Place | Purpose |
| --- | --- |
| Waypoint Station | Arrival, living space, a workshop and engineering, with two exterior berths. The layout comes from `StationLayout.generate(seed)` (see [stations](stations.md)) |
| Manufacturer Exchange | Three wings that show the Rivet, Halcyon and Bastion design styles, with berths on opposite sides. A fixed layout |
| Derelict ship | An uncrewed, salvageable ship from the ship catalog. One is an *abandoned freighter* (Rivet Hauler, Halcyon Courier or Bastion Tender, with its interior cargo door blocked by cobblestone that a pickaxe clears). The other is a *silent survey ship* (one of the six smaller designs) |

Derelicts keep their controller as a station core until a player claims them (see [ship ownership](ship-recall.md)). They hold finite supplies, so salvaging them is a one-time gain.

## One saved plan per region

World seed and region address determine every site's identity and seed independently of the player. `SpacePopulationPlan.sites` derives:

- a stable id for each role by hashing the world seed, region and role, so the same place has the same identity in every save of that world;
- a position candidate in a ring around the arrival origin, at 640 blocks for a Waypoint, 1,100 for an Exchange and 1,600 for a derelict. Each retry rotates the angle by 45° and, every eight attempts, pushes the ring out by 384 blocks. Sites therefore sit outside the area where ships arrive.

The first time a player is in a region (`discover`, checked once a second for every region occupied by a player in space), the plan is written to the `spacePopulation` section of `nullspace/ships.nbt` before any block exists. Each site starts as `planned`. Later visits never replan, so reloading, restarting or changing which places a version of the mod would choose cannot reroll a region's inventory. Plans are versioned, and regions saved by a newer version are left alone.

## Building a site

`populate` runs on the server tick, and queues **at most one place at a time**. It is skipped while any station is already being built, and it also finishes the saved regions of players who have left, so a region someone discovered is completed even after they go elsewhere.

For a `planned` site it:

1. Adopts an existing ship with that identity if one is already there (older worlds, or a retry after a crash), and marks it `placed`.
2. Builds a list of obstacles in the region: planets at their current orbital position, asteroid fields, and every registered vessel with a margin around it.
3. Tries up to 32 candidate positions and takes the first one where `DockingMotion.clear` finds a clear volume. If none is clear it simply waits; occupied space is an ordinary condition, not an error.
4. Generates the blocks for the role and queues them as an *ambient build* (`StationBuilds.queueAmbient`). Placement is budgeted across ticks, so building a station never stalls the server. Travel and active construction take priority over queuing another site.

Physical storage for these places is separate from their simulated orbital position, and lives in a reserved high range so it does not disturb the ordinary launch allocator.

Completed stations expose real door nodes and docking ports through the normal discovery path. Construction elsewhere does not block a player who is already boarding.

## Persistence, removal and reset

- **Revisiting or restarting never repairs damage, resets supplies or creates a second copy.** The place exists in the world with its saved identity.
- **Removal is remembered.** If a registered place is gone (dismantled, salvaged away or its record removed), its site is marked `removed` and is never regenerated. Only an explicit administrator regeneration request (a `nullspace/station-regeneration-request.json` file in the world) does that.
- **Derelicts reset.** A derelict that nobody has claimed is disposed of and planned again with a new seed once nobody has been in its region for 2.5 in-game days (60,000 ticks). Everything in its bounds, including entities and block entities, is removed, and the next population pass builds a fresh variation. A site that was never visited waits; a site only resets after someone was there and left. A claimed derelict becomes the player's ship and never resets. Service stations never reset.

Resets are what let the same few derelicts stay worth finding on a long-lived server without piling up wrecks.

## Failure handling

Faults that are normal (no clear site yet, no free storage section) wait and retry without logging. Real faults, such as an I/O problem or a failure to commission a station, are logged and retried after 60 seconds for that region only, so one bad region cannot stop the rest.

Legacy station creation commands remain as development tools, and do not appear in normal navigation menus.

## Where this is tested

`SpacePopulationPlanTest` (unit) checks that the plan is deterministic, which roles each region gets, and that identities do not depend on which places were chosen. `SpacePopulationGameTest` and the station tests cover discovery without creating, placement, removal records and resets; the graphical suite includes automatic discovery and a complete docking round trip.
