# Ship beacons and the shipyard

A **Ship Beacon** calls a catalog ship onto a registered landing pad. This document explains how delivery works and why it is built to survive interruption and abuse. The code is `gameplay/shipyard` (`ShipYardService`, `ShipYard`), `worldgen/ships/ShipBlueprints`, and the beacon's screen. The ships themselves are generated, see [the ship generator](ship-generator.md).

Deliveries are free for now: no money, XP, items or materials are required, and there is no economy or delivery animation. The beacon is a way to get a ship onto a pad, not a shop.

## Using a beacon in survival

1. Register a grass or dirt pad with its Pad Computer, and keep the cobblestone ring complete (see [pads](pad-previews-and-hatches.md)).
2. Place a **Ship Beacon** on another block of the ring. Its facing does not matter, and both controllers stay on the ground.
3. Stand beside the ring and right-click the beacon. Select a design in the catalog to read its size and what it has.
4. Select **Deliver ship**. The server checks the real pad again, then constructs the ship centered on it.
5. Walk up the entry ramp, open the door and enter. Larger ships have a bridge upstairs. Inspect and launch at the Ship Controller, and the delivery pad becomes its home landing destination.

The ship's pad footprint must fit its width and depth, and its size is shown as **width × height × depth**. The beacon never rotates a hull to make it fit. Every delivery needs clear sky over the whole pad. Existing blocks, fluids and ships are preserved, and players, creatures, items and other entities must be outside the new hull's volume. Entity data must finish loading before construction begins. If the beacon says the area is loading, wait a moment and try again.

The catalog has three pages, one for each role (scouts, freighters, explorers), with manufacturer and role beside the specification. Selection, the footprint preview and **Deliver ship** stay on one screen.

### Recipe

```text
Glass       Redstone torch   Glass
Copper      Computer base    Copper
Rough stone Machine casing   Rough stone
```

A shaped recipe making one beacon. Rough stone accepts cobblestone or cobbled deepslate. The recipe is in the recipe book and the field guide. `/give @s nullspace:ship_beacon` works with command permission.

### Delivering again onto a used pad

The beacon cannot clear a damaged ship or merge two ships. Once a ship is completely dismantled, the next delivery releases its empty saved allocation and creates a fresh ship identity, with no need to replace the beacon. The whole old area must hold no blocks or occupants, and offline crew and dock links must be resolved first. Physical ship transfers temporarily block delivery. A larger pad is picked up by the automatic pad refresh, and a stacked pad's ring can limit the height and obstruct the launch column.

## The catalog

| Ship | Shape | Pieces | Role |
| --- | --- | --- | --- |
| Rivet Bolt | Spine, one deck | Small | Scout: bridge, bunk rooms, a store, engineering. The default starter |
| Rivet Hauler | Spine, two decks | Medium | Freighter: cargo holds below, bunks and a viewing room above |
| Rivet Ranger | Spine, three decks, stepped | Large | Explorer: workshops with every workbench, a lounge, bunks, a viewing room |
| Halcyon Swift | Dome | Small | Scout: round commons under a glass dome, bunks, engineering |
| Halcyon Courier | Twin hull | Medium | Freighter: two hulls joined by a forward bridge, cargo and storage in both |
| Halcyon Horizon | Dome | Large | Explorer: a wide commons with workshops either side |
| Bastion Ward | Twin hull | Small | Scout: armoured twin hull with bunks and stores |
| Bastion Tender | Dome | Medium | Freighter: round hull with a cargo hold either side |
| Bastion Pioneer | Twin hull | Large | Explorer: workshops, stores and mining bays in both hulls |

Designs are entries in `catalog/ships.json` with the generator settings that build them. `ShipBlueprints` loads them, and the showroom and the delivery service build the same block-state maps. To hand-edit a design, load it with the [authoring tools](../development/authoring.md#editing-a-shipyard-design) and save: a saved template replaces the generated ship.

The six box-shaped ships from earlier versions (Sparrow, Skylark, Mule, Wayfarer, Aegis, Atlas) were removed when ships became generated. `ShipBlueprints.get` maps each to its nearest replacement, so an old reference to a preset resolves (Sparrow to Rivet Bolt, Mule to Rivet Hauler, and so on). The space-start setting validates against the current catalog directly and does not apply this mapping (see [starting in space](space-start.md)).

Every design includes one Ship Controller, generators, batteries, an air supply, a programmable terminal, a Scanner, a small mounted camera, and monitors on the bridge. Each has a framed compact docking door on a Docking Base. Each generator gets one finite stack of coal, and batteries start empty and charge from surplus. Delivery needs no separate upgrade step. Cargo storage starts empty. Doors and room air are physical, so airless surfaces need a suit until the cabin has air.

Existing ships and showroom builds keep their construction and edits. Retrofit them with the [docking instructions](stations.md#add-a-port-to-your-ship).

## The ramp

The docking door sits two blocks above the ground, because the hull has a floor layer under it. The generator adds a three-wide flight of stairs up to the door as part of the ship, with solid under each step, so a delivery works on any pad. It takes the front block of the 32-block depth, and `HullScan` ignores the pad's own computer and a delivery beacon even when the ramp's first step touches one.

## Why delivery is journaled

Building a ship means placing thousands of blocks, and a server can stop at any time. Delivery is therefore written to be resumable and never destructive.

**Intent first.** Before the first block, the order is persisted in `ships.nbt` under `shipyardOrders`: the preset and version, the original pad, the beacon and the construction origin. At completion the hull passes the same scan as a player-built ship, receives its ordinary identity and finite systems, and its chunks and metadata are saved *before* the order is removed. At most 64 orders may be pending.

**Resume.** An interrupted delivery resumes on server startup, with bounded retries while entities load, or when the player selects the same design at the same beacon and delivers again. Resume fills in missing planned blocks and keeps matching blocks and their contents. Conflicts stop it: player edits in the way, a removed beacon, a changed ring or a different order are refused with a message saying what to restore. The system never rotates, moves or overwrites a conflicting build during recovery.

**Authorization.** The beacon uses the ordinary expiring server terminal session. It only authorizes catalog, detail and delivery actions. A forged client cannot use a beacon session to launch, land or operate engineering. Clients send stable preset ids, and the server rechecks the block, dimension, range, interaction permission, pad, size, obstructions and entities. Requests serialize on the server thread, so a second buyer on the same pad sees the first ship as occupied terrain.

## The shipyard showroom

Operators can run **`/nullspace shipyard`**. It builds a lit platform with three empty 32 × 32 delivery pads (each with a beacon) and one registered showroom pad for each catalog ship, where the ships can be entered, inspected and launched. The yard is generated only on request, in the Overworld, above existing terrain and outside registered pad footprints.

Generation reserves its site in `nullspace-shipyard-v3.nbt` before writing any block. Returning reads that marker and preserves edits, delivered ships and emptied display pads. A new version of the gallery reserves a fresh site, leaving older ones unchanged. An incomplete marker refuses automatic rebuilding, since it never clears a site to start over. The search is bounded and does not promise a site in a heavily built or height-limited world, and a large build can briefly stall the server.

Three more operator commands build review galleries: `shipyard random [seed]`, `shipyard rooms` and `shipyard stations`, described in [the ship generator](ship-generator.md#random-gallery).

## Where this is tested

`ShipYardGameTest` covers every blueprint's full launch scan, pressure and bridge-to-exit path, equipment, persistent free delivery, rejected obstructions, fluids and entities, session authorization, interrupted construction and conflict-preserving recovery, and showroom revisits. `ShipYardClientGameTest` purchases a ship through real block use and typed packets, rejects a duplicate purchase, then launches and returns it.

Two scripts test what only a real process can. `scripts/test/shipyard-crash-test.ps1` kills the JVM at each of seven checkpoints (saved intent, partial unsaved and saved hulls, full hull, ship metadata, flushed terrain, cleared order) and has another JVM open the same save. It requires automatic recovery, exactly one ship, preserved committed identities, a complete launchable hull, saved cargo and untouched blocks outside the pad, and repeated recovery must be harmless. `scripts/test/multiplayer-test.ps1` includes two real clients buying the same preset onto the same pad: exactly one gets the ship and the other an occupied-pad error.

```powershell
.\scripts\test\shipyard-crash-test.ps1                      # full crash matrix
.\scripts\test\shipyard-crash-test.ps1 -Stages WORLD_SAVED  # one checkpoint
.\scripts\test\multiplayer-test.ps1                         # two clients, loopback server
```

Captures: [page-three catalog](../images/manufacturer-beacon-catalog.png), [purchased explorer in space](../images/manufacturer-explorer-in-space.png), [showroom](../images/shipyard-manufacturer-fleet.png), [multiplayer purchase](../images/ship-beacon-multiplayer.png).
