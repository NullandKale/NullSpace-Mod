# Ship beacons and preset ships

Implemented October 27, 2026. A Ship Beacon calls a preset ship onto an existing registered landing pad. Purchases are free: no money, XP, items or materials are required. There is no economy or delivery animation yet.

The [manufacturer review fleet](procedural-ships.md#current-review-set) adds nine ships across scout, freighter and explorer roles, plus a compact station template showroom. Seeded generation remains planned.

## Using a beacon in survival

1. Register a grass/dirt pad with its ordinary pad computer. Keep the cobblestone ring complete.
2. Place a **Ship Beacon** on another block of that ring. Its facing does not matter. Keep both controllers on the ground.
3. Stand beside the ring and right-click the beacon. Select a design in the catalog to read its size and equipment.
4. Select **Deliver ship**. The server checks the actual pad again, then constructs that ship centered on it.
5. Walk up the entry stair, open the oak door, enter and close it. Larger ships have a bridge upstairs. Inspect and launch at the Ship Controller; the delivery pad is its home landing destination.

The beacon needs the chosen ship's width and depth as pad interior dimensions; its size readout is **width × height × depth**. It never rotates a hull to make it fit. Every delivery needs clear sky over the entire pad. Existing blocks, fluids and ships are preserved. Players, creatures, items and other entities must be outside the new hull's volume. Entity data must finish loading before construction starts. If the beacon says the area is loading, wait a moment and call again.

The beacon cannot clear a damaged ship or merge two ships. After you completely dismantle a ship, delivery automatically releases its empty saved allocation and creates a fresh ship identity. The entire old area must contain no blocks or occupants; offline crew and dock links must be resolved first. Replacing the beacon is unnecessary. Preview only checks readiness; the old assignment is removed together with the saved delivery intent. Physical ship transfers temporarily block delivery and new shipyard generation. A larger pad is discovered through the existing automatic pad refresh; a stacked pad's ring can limit height and obstruct the launch column.

The catalog has three pages. Use the arrow buttons beneath the ship list to reach the manufacturer scouts, freighters and explorers. Manufacturer and role appear beside the ship specification. Selection, the footprint preview and Deliver ship remain on the same screen.

## Recipe

```text
Glass       Redstone torch   Glass
Copper      Computer base    Copper
Rough stone Machine casing   Rough stone
```

This is a shaped recipe (`GTG / CKC / SHS`), making one beacon. Rough stone accepts cobblestone or cobbled deepslate. The native recipe book and newly obtained field guides include the real item-grid recipe. Crafting subcomponents uses the existing recipes. `/give @s nullspace:ship_beacon` is available with command permission.

## Catalog

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

These nine are **generated**, not hand built: see [ship generator](ship-generator.md). Sizes come from the generator and are shown in the beacon's quote. The six original box ships (Sparrow, Skylark, Mule, Wayfarer, Aegis, Atlas) were removed; saves, settings and servers that still name them get the nearest replacement (Sparrow becomes Rivet Bolt, Mule Rivet Hauler, and so on).

All nine include one Ship Controller, generators, batteries, air supply, a programmable terminal, a Scanner, a small mounted camera and monitors on the bridge. Each has a framed compact docking door on a Docking Base. Each new generator receives one finite stack of coal; batteries start empty and charge from surplus generation. Normal delivery does not require a separate systems-upgrade step. Cargo storage starts empty. Doors and room air remain physical; airless surfaces require a suit until the relevant cabin has air.

Existing yards make new deliveries using the current blueprints. Already placed ships and showroom builds retain their construction and edits; retrofit them using the [short docking instructions](stations.md#add-a-port-to-your-ship). The native catalog test checks every design with the real docking and pressure validators.


Designs are listed in `catalog/ships.json` with the generator settings that build them, and loaded by `worldgen/ships/ShipBlueprints.java`; the showroom and delivery service build the same block-state maps. To hand-edit one, load it with the [authoring tools](../development/authoring.md#editing-a-shipyard-design) and save: a saved template replaces the generated ship. The ship tests check every design is launchable, sealed, powered and walkable.

## Visiting the shipyard

Operators can run **`/nullspace shipyard`**. This creates a lit platform with three empty 32 × 32 delivery pads and nine registered showroom pads containing the catalog ships. Showroom ships can be entered, inspected and launched. The yard is generated only on request, in the overworld, above existing terrain and outside registered pad footprints. No survival save is modified by installing the feature.

Generation reserves its site in `nullspace-shipyard-v3.nbt` before writing blocks. Returning reads that marker and preserves edits, purchased ships and emptied display pads. The v3 fifteen-ship gallery reserves a fresh site and leaves the older galleries unchanged. An incomplete generation marker refuses automatic rebuilding; it never clears the site and starts over. The bounded search does not guarantee a site in every heavily built or height-limited world. A large opt-in construction and its save can briefly stall the server.

## Ownership and interrupted deliveries

The beacon uses the existing expiring server terminal sessions. It only authorizes catalog/detail and purchase actions. Clients send stable preset IDs; the server rechecks the block, dimension, range, interaction permissions, pad, size, obstructions and entities. A forged client cannot use a beacon session to launch, land or operate engineering. Requests serialize on the server thread, and a second buyer sees the first ship as occupied terrain.

Before the first block is placed, the order is persisted in `ships.nbt` under `shipyardOrders`. It records the preset/version, original pad, beacon and construction origin. At completion, the hull passes the same scan as a player-built ship, receives ordinary ship identity and finite systems, and its chunks/metadata are saved before the intent is removed.

An interrupted delivery can resume on server startup, with bounded retries for entity loading, or by selecting the same design at the same beacon and calling it again. Resume fills missing planned blocks and keeps matching blocks and their contents. Conflicting player edits, a removed beacon, a changed ring or a different order are refused. Restore those conditions to resume. The order count is bounded at 64. The system does not rotate, move or overwrite a conflicting build during recovery.

## Validation

Unit checks cover stable catalog IDs, fit failures, centered placements across positive/negative coordinates and the separate quote/purchase keyboard actions. Native tests cover every blueprint's full launch scan, pressure and bridge-to-exit path, equipment, free persistent delivery, rejected obstructions/fluids/entities, session authorization, interrupted construction and conflict-preserving recovery, and showroom revisit preservation. A graphical client scenario purchases a ship through actual block use and typed packets, rejects a duplicate purchase, then launches and returns that purchased ship.

These checks use disposable worlds. `scripts/test/shipyard-crash-test.ps1` exercises actual process termination followed by another JVM opening the same test save. Its seven checkpoints cover the saved intent, partial unsaved and saved hulls, full hull, ship metadata, flushed terrain and cleared order. Verification requires automatic startup recovery, exactly one ship, preserved committed ship/device identities, a complete launchable hull, saved cargo and untouched blocks outside the pad. Repeated recovery must remain harmless. The ordinary server suite also tests storage failures before construction and when clearing a completed order.

`scripts/test/multiplayer-test.ps1` includes two independent graphical clients purchasing the same preset onto the same empty pad through their own authenticated terminal sessions. Exactly one client must receive delivery and the other an occupied-pad error; the server must retain one ship and no pending order, without charging either buyer. The earlier multiplayer launch, reconnect, engineering and remote-camera checks still run.

```powershell
# Full delivery crash matrix, each stage in its own disposable save
.\scripts\test\shipyard-crash-test.ps1
# A targeted checkpoint while developing recovery
.\scripts\test\shipyard-crash-test.ps1 -Stages WORLD_SAVED
# Two real clients and a loopback-only dedicated server
.\scripts\test\multiplayer-test.ps1
```

The original delivery verification receipt records **454 passing unit tests, 12 existing known-bug skips, 112 passing server tests (111 mod plus one optional vanilla), all 16 graphical entrypoints, all seven delivery crash/restart cases, and the two-client purchase race**, plus assembly, assets and formatting. That evidence certifies the original source fingerprint. The separate fourteen-case launch/landing crash matrix was not rerun. Status is `passed-with-known-skips`; the strict no-skipped-tests gate remains unsatisfied.

The manufacturer role verification records the expanded fifteen-design catalog: 477 passing unit tests, 12 existing skips, 148 passing server tests and all 21 graphical entrypoints. It includes a page-three Pioneer purchase, launch and return, and walking up and down its stairs in survival. The crash matrix and two-client purchase race were not rerun for the role additions.

Current captures: [page-three catalog](../images/manufacturer-beacon-catalog.png), [purchased explorer in space](../images/manufacturer-explorer-in-space.png), [fifteen-ship showroom](../images/shipyard-manufacturer-fleet.png). The earlier [multiplayer purchase](../images/ship-beacon-multiplayer.png) capture belongs to the original delivery verification.
