# Ship beacons and preset ships

Implemented October 27, 2026. A Ship Beacon calls a preset ship onto an existing registered landing pad. Purchases are free: no money, XP, items or materials are required. There is no economy or delivery animation yet.

## Using a beacon in survival

1. Register a grass/dirt pad with its ordinary pad computer. Keep the cobblestone ring complete.
2. Place a **Ship Beacon** on another block of that ring. Its facing does not matter. Keep both controllers on the ground.
3. Stand beside the ring and right-click the beacon. Select a design with arrows/Enter or the mouse to read its size and equipment.
4. Select **Call selected**. The server checks the actual pad again, then constructs that ship centered on it.
5. Walk up the entry stair, open the oak door, enter and close it. Larger ships have a bridge upstairs. Inspect and launch at the helm; the delivery pad is its home landing destination.

The beacon needs the chosen ship's width and depth as pad interior dimensions; its size readout is **width × height × depth**. It never rotates a hull to make it fit. Every delivery needs clear sky over the entire pad. Existing blocks, fluids and ships are preserved. Players, creatures, items and other entities must be outside the new hull's volume. Entity data must finish loading before construction starts. If the beacon says the area is loading, wait a moment and call again.

The beacon cannot replace an existing ship, merge two ships, or clear a damaged ship for you. A ground allocation that still belongs to another ship is refused even if its visible hull has been removed. Launch an existing ship or choose a different empty pad. Physical ship transfers temporarily block delivery and new shipyard generation. A larger pad is discovered through the existing automatic pad refresh; a stacked pad's ring can limit height and obstruct the launch column.

## Recipe

```text
Glass       Redstone torch   Glass
Copper      Computer base    Copper
Rough stone Machine casing   Rough stone
```

This is a shaped recipe (`GTG / CKC / SHS`), making one beacon. Rough stone accepts cobblestone or cobbled deepslate. The native recipe book and newly obtained field guides include the real item-grid recipe. Crafting subcomponents uses the existing recipes. `/give @s nullspace:ship_beacon` is available with command permission.

## Catalog

| Ship | Width × height × depth | Decks | Role |
| --- | --- | --- | --- |
| Sparrow | 9 × 5 × 11 | 1 | White starter, one engine, compact cabin and workshop |
| Skylark | 11 × 5 × 15 | 1 | Cyan scout, twin engines and broad windows |
| Mule | 13 × 5 × 17 | 1 | Orange freighter, separate cargo hold and empty barrels |
| Wayfarer | 15 × 10 × 21 | 2 | Blue explorer, internal stairs, rooms and upper bridge |
| Aegis | 17 × 10 × 23 | 2 | Red patrol ship, twin engines and a forward cannon |
| Atlas | 23 × 15 × 29 | 3 | Green expedition ship, stairways, workshops and cargo rooms |

All include exactly one helm, a generator, battery, air supply, door control, systems controller, shell terminal, camera, monitor and both scanners. Installed finite power/air systems are enabled on delivery. Batteries start empty and charge from surplus generation. The generator and engine follow the mod's current power rules; the proposed combustible fuel progression remains separate. Camera/scanner allocations can be adjusted in engineering. Cargo barrels start empty. Rooms are separated by ordinary doors, making them useful for air-management tests. Airless surfaces still require a suit until the relevant rooms have been supplied with air.

Blueprints are deterministic code in `shipyard/ShipBlueprints.java`; the showroom and delivery service build the same block-state maps. The current blueprint version is 1. New equipment and hull layouts should be added here, with launch/pressure/path tests, rather than maintaining a second showroom build.

## Visiting the shipyard

Operators can run **`/nullspace shipyard`**. This creates a lit platform with three empty 32 × 32 delivery pads and six registered showroom pads containing the catalog ships. Showroom ships can be entered, inspected and launched. The yard is generated only on request, in the overworld, above existing terrain and outside registered pad footprints. No survival save is modified by installing the feature.

Generation reserves its site in `nullspace-shipyard-v1.nbt` before writing blocks. Returning reads that marker and preserves edits, purchased ships and emptied display pads. An incomplete generation marker refuses automatic rebuilding; it never clears the site and starts over. The bounded search does not guarantee a site in every heavily built or height-limited world. A large opt-in construction and its save can briefly stall the server.

## Ownership and interrupted deliveries

The beacon uses the existing expiring server terminal sessions. It only authorizes catalog/detail and purchase actions. Clients send stable preset IDs; the server rechecks the block, dimension, range, interaction permissions, pad, size, obstructions and entities. A forged client cannot use a beacon session to launch, land or operate engineering. Requests serialize on the server thread, and a second buyer sees the first ship as occupied terrain.

Before the first block is placed, the order is persisted in `ships.nbt` under `shipyardOrders`. It records the preset/version, original pad, beacon and construction origin. At completion, the hull passes the same scan as a player-built ship, receives ordinary ship identity and finite systems, and its chunks/metadata are saved before the intent is removed.

An interrupted delivery can resume on server startup, with bounded retries for entity loading, or by selecting the same design at the same beacon and calling it again. Resume fills missing planned blocks and keeps matching blocks and their contents. Conflicting player edits, a removed beacon, a changed ring or a different order are refused. Restore those conditions to resume. The order count is bounded at 64. The system does not rotate, move or overwrite a conflicting build during recovery.

## Validation

Unit checks cover stable catalog IDs, fit failures, centered placements across positive/negative coordinates and the separate quote/purchase keyboard actions. Native tests cover every blueprint's full launch scan, pressure and bridge-to-exit path, equipment, free persistent delivery, rejected obstructions/fluids/entities, session authorization, interrupted construction and conflict-preserving recovery, and showroom revisit preservation. A graphical client scenario purchases a ship through actual block use and typed packets, rejects a duplicate purchase, then launches and returns that purchased ship.

These checks use disposable worlds. `shipyard-crash-test.ps1` exercises actual process termination followed by another JVM opening the same test save. Its seven checkpoints cover the saved intent, partial unsaved and saved hulls, full hull, ship metadata, flushed terrain and cleared order. Verification requires automatic startup recovery, exactly one ship, preserved committed ship/device identities, a complete launchable hull, saved cargo and untouched blocks outside the pad. Repeated recovery must remain harmless. The ordinary server suite also tests storage failures before construction and when clearing a completed order.

`multiplayer-test.ps1` includes two independent graphical clients purchasing the same preset onto the same empty pad through their own authenticated terminal sessions. Exactly one client must receive delivery and the other an occupied-pad error; the server must retain one ship and no pending order, without charging either buyer. The earlier multiplayer launch, reconnect, engineering and remote-camera checks still run.

```powershell
# Full delivery crash matrix, each stage in its own disposable save
.\shipyard-crash-test.ps1
# A targeted checkpoint while developing recovery
.\shipyard-crash-test.ps1 -Stages WORLD_SAVED
# Two real clients and a loopback-only dedicated server
.\multiplayer-test.ps1
```

The [final verification receipt](shipyard-verification.json) records **454 passing unit tests, 12 existing known-bug skips, 112 passing server tests (111 mod plus one optional vanilla), all 16 graphical entrypoints, all seven delivery crash/restart cases, and the two-client purchase race**, plus assembly, assets and formatting. All evidence certifies the same source fingerprint. The separate fourteen-case launch/landing crash matrix was not rerun. Status is `passed-with-known-skips`; the strict no-skipped-tests gate remains unsatisfied.

Native captures: [beacon terminal](images/ship-beacon-catalog.png), [purchased ship in space](images/purchased-ship-in-space.png), [six-ship showroom](images/shipyard-showroom.png), [multiplayer purchase](images/ship-beacon-multiplayer.png). The showroom fixture waits for visible meshes in both display rows before capture.
