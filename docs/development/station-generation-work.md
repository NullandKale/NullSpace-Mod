# Station generation work

This tracks implementation of [tiny ships and stations](../design/tiny-ships-and-stations.md). Existing station journeys, shared prerequisites and tiny ships are implemented and verified locally. Manufacturer layout generation and new room/feature content follow afterward.

- [x] Batch 1: existing station layouts in journey encounters, boarding/return, resume and lifecycle checks.
- [x] Batch 2: versioned station brief, stable saved IDs, reproducible preview selectors and compatibility checks.
- [x] Batch 3: tiny ships, start/beacon selection rules, compact-pad delivery/storage and actual walkthrough.
- [x] Batch 4: varied seeded manufacturer station layouts, floors and a comparison gallery including multiple seeds for the same brief. Implemented and verified locally (see below).
- [ ] Batch 5: room and feature generators.
- [ ] Batch 6: complete permanent station claims/navigation, condition/content, fixed population, player construction and reviewed core screens.
- [ ] Batch 7: final integration and CI release delivery.

## Progress checkpoint: November 12

Batches 1-3 are complete locally. The next implementation batch is manufacturer station structures and floors, followed by purpose-specific rooms. The journey adapter currently uses the existing compact harbor plan; it does not establish that the new station-generation or dungeon-content work is finished. Tiny ships have one shared two-room layout with three maker treatments.

| Scope | Current evidence | Delivery |
| --- | --- | --- |
| Existing station journeys and shared prerequisites | 10 focused unit cases and 27 native mod cases pass with retained results | Local changes |
| Tiny ships | 11 focused unit cases, 21 native mod cases and two graphical flows pass; actual builds reviewed | Local changes |
| Manufacturer station layouts, floors and new rooms | Not implemented | Pending |
| Full integration of the new feature batch | Not run; human playtest acceptance also remains | Pending |
| Latest published build | rc2 release pipeline passed; latest main-branch secret scan passed | Published rc2 excludes these local changes |

CI was checked after the tiny-ship slice: [latest main scan](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43326604820) and [latest release pipeline](https://github.com/NullandKale/NullSpace-Mod/actions/runs/43324931805) both succeeded. Their success does not validate the unpushed station/tiny changes.

The main implementation finding was that existing delivery, flight, docking and storage paths could carry the new tiny layouts without production travel changes. Compatibility still needed attention: expanded catalogs invalidated a test's assumed blank row, and an existing saved showroom would not automatically acquire new designs. The new showroom reserves a separate location and preserves earlier edits.

The unexpected verification cost came from test fixtures, including registering tick callbacks while Minecraft was already iterating them. That could repeat delivery and produce a misleading pad-reservation failure. The fixture now registers its state machine before ticking. Future slices should keep that pattern and collect combined outcomes before corrections; no additional performance work is authorized.

## Current changes

- Added a new abandoned-station encounter with saved ID 4, keeping IDs 0–3 and their meanings intact. Existing saved journeys continue using their recorded kind.
- The abandoned-station encounter first reused the compact Waypoint room generator; it now builds a planned, furnished station from a seeded brief ([`EncounterStations`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/EncounterStations.java)), and the Waypoint, Exchange, showcase and starter-station commands and builders are gone. Only the player's own module-built stations still use the tile layouts.
- Kept the signal-occurrence roll and cooldown unchanged. Current weights within signals are growing station 5%, new abandoned station 5%, damaged freighter 35%, salvage ship 35%, cargo pod 20%. These are tuning values; the two station kinds together occupy 10% of signals.
- Added a versioned `StationSpec`: existing maker, purpose, size, condition and seed. New station signals save the brief before construction and carry it into the placed station. Invalid selections and unsupported specification versions fail explicitly.
- Added reproducible shipyard preview selectors. The existing station-piece gallery remains available.
- Restricted salvage-to-ship claiming to the original ship/cargo encounter kinds. The new station must not become a movable ship through that path. Permanent station claiming/return navigation remains in the later gameplay-integration batch.

## Batch 4: manufacturer layouts and floors (November 12)

A new planner builds stations from a brief without the old 16-by-16 tiles. It is separate from the old tile generator, which still builds existing stations and the journey harbor.

- [`StationPlan`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationPlan.java): pure integer room boxes, doors and berths with their approach boxes, plus the structural checks (overlap, extent, reachability, approach clearance).
- [`StationProfiles`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationProfiles.java): per-purpose required rooms by size band and weighted optional pools that discourage repeating an optional use. Small rooms are up to 8 clear blocks and large rooms up to 16.
- [`StationMethods`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationMethods.java) and [`StationBuilder`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationBuilder.java): the layouts follow the station design rules (straight paths, mirrored rooms, a tree of doors). Every walking path is a straight carrier with rooms flush along it in mirrored columns, each door centered on its wall. Room widths and depths come from a few quantized steps. Levels are stacked decks: the arrival hall is a tall atrium with one stair flight to a gallery ring, and each upper arm sits over a lower one.
  - Rivet: a spine through (or ending at) the arrival hall, with straight docking piers every 34 blocks.
  - Halcyon: a hub with two arms, or four crossed arms on large stations, each clear of its neighbours' bays.
  - Bastion: a chain of halls joined by short passages, each hall with a mirrored pair of rooms; dock halls send piers out.
- [`StationPlanner`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationPlanner.java): separate seeded streams for the room program, layout and berth target; up to 64 bounded attempts; failure names the unmet requirement. Standard and large stations must reach two levels. Stations stop at two levels for now: three or four need a second stair flight.
- [`StationStructure`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationStructure.java): full-block shells, floors, the atrium flight and gallery ring, framed doors, docking ports, the Station Core, generators with batteries, one air supply per room and ceiling lights. Rooms are empty, and each records a `room` point.
- Berths reserve a 34-block clear box outside each port for a full-size ship. The preview marks each box with red corner posts.
- `/nullspace shipyard stations preview|compare|variants` place the generated structures (all purposes, sizes and makers). `compare` places the three makers from one seed; `variants` places four seeds of one brief. Derelict is recorded in the receipt but no damage pass exists yet.

### Style, windows, lighting and stairs

Done before the room generators, so rooms are judged inside finished shells.

- [`StationStyle`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationStyle.java): one style per maker with a small fixed palette. Walls use regular courses (Halcyon: a quartz-brick band every fourth row; Bastion: a polished band every third row), plus a skirting, a cornice and piers laid out per room (equal bays, mirrored, the same on all four walls; plain logs running floor to ceiling, dropped within two cells of a door or docking port; ceiling beams and windows follow the same bays). Only Rivet has random block variation, and only a sparse cracked brick. Each palette has a wood: spruce, birch or dark oak planks for floor inlays and edges, and a plain log for piers (full wall height, floor to ceiling) and ceiling beams.
- Windows: whole glass blocks on every exterior wall, never at doors or ports. Halcyon glazes in continuous bands with mullions every six cells, Rivet in wide grids between piers, Bastion in tall slits every other cell.
- Floors and roofs follow the maker: plated floors with a corridor lane stripe, inlaid rings and a bordered cross; parapet roofs with vents, stepped terraces with glass skylights and crenellated battlements with corner turrets.
- [`StationLighting`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationLighting.java): torches only, in rounds. A regular mirrored row of wall torches goes in first. Then it simulates block light, treating doors as opaque as the real engine does, and adds a torch wherever a standing cell is below light 8, preferring wall sites and using a floor torch last. It treats torches already in the world as sources, so it can run again after rooms are furnished.
- Stairs: the straight wall flight stays, with a flat landing at its foot, a stringer under the wall side only (open beneath the rest), a connected two-bar-tall rail along its open side, and a rail along the whole inner edge of the gallery ring (four cells wide on large halls). The separate stair-room code is gone. A two-level arrival hall is at least 11 wide so the flight lands.
### Heights by size

- [`StationBuilder.levelRange`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationBuilder.java): small stations stand 1-2 decks, medium 2-3 and large 3-4. [`StationPlanner`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationPlanner.java) picks the preferred count from the seed, builds that many decks, and rejects an attempt unless the top deck carries rooms; the last third of the attempts fall back to the range's lowest count. A medium station with three decks gets an 8-block-narrower footprint.
- The atrium has one flight per deck, alternating between the climb's wall and the wall opposite it, each landing on its own gallery ring. A ring is open only where the arriving flight rises through it and has a rail across the foot of that hole. Both flight walls stay free of doors in the layouts.
- Two flights on opposite walls cut a narrow ring in two, so a hall of three or more decks keeps its open well only when it is 15 or more across (a 3x3 well with a six-wide ring); narrower halls have full floors with just the stair holes. Two-deck halls have one cut and keep the well.
- The ring width grows with the hall so the well stays three cells across, and the Station Core stands on the centre line of a tall hall, under the well, facing the first doorway. The hall's other furnishing belongs to the room generators.
- Gallery cameras are still placed for two-deck halls; the upper rings and the hall centre of three- and four-deck halls have not been reviewed in screenshots.
### Verification

- [`StationPlannerTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/stations/StationPlannerTest.java), 12 unit cases: all 378 sampled briefs generate sound plans, the same seed reproduces exactly, different seeds give different layouts and room mixes, room order is not a fixed sequence, the room graph is a tree, corridors are straight and five wide, every door is centered on the wall it joins, most bay rooms have a mirrored twin, purpose decides the dominant rooms, sizes and berth counts scale, approach boxes are 34 blocks, and every level is reachable from the arrival.
- [`StationStructureGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/stations/StationStructureGameTest.java): for 126 stations (3 makers by 7 purposes by 3 sizes by 2 seeds) every room's air stays inside it with the doors closed, a real walking search through doors and stair steps reaches every room and port, and each has its core, ports, generator, battery and air supplies, inside the 128 by 32 by 128 limit. [`StationPreviewGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/StationPreviewGameTest.java) covers the commands. Both pass.
- [`StationGalleryClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/StationGalleryClientGameTest.java) placed the three makers on one brief, four Rivet seeds of one brief and two large stations, and took exterior, plan, arrival, port and stair screenshots, which were reviewed (`build/focused-tests/20261112-223521-StationGalleryClientGameTest-ddbe/screenshots/`).

Early failures were my checker treating explicit air entries as solid blocks, 8 of 378 compact briefs whose required rooms did not fit (compact rooms are now smaller), doorway clearances reserving every cell near a room's corners, and the gallery waiting for every chunk to mesh.

### What the review showed and what remains

- The first version placed rooms at random sizes and offsets with bent spines and random loops. It looked disordered and broke the station design rules, so the layout methods were rebuilt (above).
- The rebuilt layouts read as ordered: a crossed Halcyon hub is a clean four-fold cross, a Bastion chain is a row of halls with paired rooms, and a Rivet spine has mirrored bays and T piers. Four seeds of one brief still give four different plans. Standard Halcyon (two stacked arms) still reads as a compact block, and an odd number of rooms leaves one single-sided column, so some stations are not perfectly symmetric.
- The silhouettes are still plain boxes. Manufacturer identity comes mainly from layout and materials, and detail waits for batch 5.
- Berth clearance is verified as geometry only. Two large ships docking at once is not tested and belongs to the integration stage. Docks sit at the ends of piers and corridors, because a dock next to rooms cannot keep its 34-block approach box clear.
- Compact programs are smaller (at most four rooms plus the arrival and docks) so they fit a 32-block chain.
- Room contents, the condition and damage pass, journey and fixed-site use of the new planner, and station claiming remain for batches 5 and 6. The journey adapter still uses the old compact harbor.
- Light is verified with an independent flood fill over all 126 stations and with the real Minecraft light engine on a placed station of each maker. Walls, floors, windows and roofs were reviewed in the gallery screenshots only.
- No human playtest or full integration sweep has been run for this batch.

## Room designs and the room gallery (first pass, November 13)

First designs for every room in the agreed set, reviewed in game through the room gallery and, since the same day, placed in generated stations (see "Furnished stations in space").

- **Command:** `/nullspace shipyard rooms` builds both galleries; `rooms station [maker]` builds only the station rooms (all makers, or one) and `rooms ship` only the original ship room gallery. The station gallery places every room use at a small size (7 by 6 inside) and a large size (15 by 14), with stores in three variants, for each maker, in rows of six on a walkway, each behind a lobby with a pressure-plated door and a label. The ship gallery is unchanged: [`RoomGallery`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/RoomGallery.java) now starts both, and [`StationRoomGallery`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/StationRoomGallery.java) lays out the station rooms.
- **Designs:** [`RoomKit`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomKit.java) is the furnishing toolkit (a room frame relative to its door, mirrored runs along walls, tables and seats, and marked points for entities). The designs are in [`RoomsWork`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomsWork.java) (engineering, workshop, foundry, cargo hold, receiving bay, stores, maintenance), [`RoomsLiving`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomsLiving.java) (commons, kitchen, cabin, bunk room, growing bay, garden, infirmary) and [`RoomsCommand`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/RoomsCommand.java) (lab, archive, observation, lookout, operations, armory, EVA prep, brig); [`StationRooms`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationRooms.java) picks the design for a use.
- **What a room carries:** containers are empty. Wall item frames hold one handy item each, armor stands wear plain or armored space suits, and paintings and animals are placed too. These are entities, so a room writes marked points and [`RoomEntities`](../../src/main/java/com/nullandkale/nullspace/gameplay/shipyard/RoomEntities.java) turns them into entities; the gallery calls it directly, and the later spawn-once pass will reuse it. Growing bays use raised beds with sealed water channels, trees use leaves that never decay, and garden and pens use grass floors.
- **Planner:** the room list now matches the agreed set: the transfer bay is the receiving bay, crew quarters and barracks are the bunk room, the seed store is a stores variant, the office is gone, and EVA prep, infirmary and brig are added to the purpose profiles as optional rooms.
- **Doors:** every interior door in a generated station and every gallery room now has a stone pressure plate on each side. The structure checks and the light model treat plates (and carpet) as passable and see-through.
- **Checks:** [`RoomGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/stations/RoomGameTest.java) builds all 144 rooms (three makers, every use, variant and size) and checks plates on both sides of the door, a clear approach, furniture present, a walkable route to the back, every frame item real and on a wall, stands and animals in free space, and no dark standing cells; it also runs the gallery command and counts the frames, stands and animals it placed. These and the 126-station structure checks, the real-light-engine check and the ship gallery check pass together. Nothing here shows the rooms are well designed: that is the in-game review.
- **Found while checking:** the garden's first design closed off its small room, and flower beds had landed on animal cells. Both are fixed. The real-light check had a fixed 400-tick wait that failed when the server was busy; it now polls until the light settles.
- **First review round (small rooms), November 13:** the rules from the review are now in the toolkit and checked by [`RoomGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/stations/RoomGameTest.java). A table is placed only where a free cell goes all the way round it and its seats, and gets smaller or is left out when it cannot (kitchen, workshop, commons, bunk room). Item frames hang at eye height (the second block) and lighting no longer places a torch in a frame, stand, painting or animal cell. Containers stand on the floor in rows with real double chests, never in stacks. Costly blocks appear once per room (one scanner, one brewing stand in a small lab or infirmary, one enchanting table) unless the room is deliberately about them. Screens are one giant wall screen, never floating. Most rooms now have a custom floor, as the garden does. The bunk room has single beds in bays with a chest and a partition, the archive's enchanting ring is in a back corner, the observation room's small version has chairs at floor level, and the brig's bars meet solid posts and a lintel round each gate.
- **Second review round (large rooms), November 13:** every level now has a floor two blocks thick: a structural layer (the ceiling of the level below) and a floor surface that a room can replace with water or soil without opening the room under it. A level is therefore seven blocks (two of slab and five clear), the standing row moves up one, and the atrium flights are seven steps. The crossed Halcyon hub is two wider (19) so its centre door clears the longer flight. The growing bay's beds are sunk flush into the floor, with crops standing on them and one water cell in each bed (a bed nine long needs only one, since farmland drinks from water up to four cells away); the garden's pond is sunk too, and its large version has one big pen with a pair of every animal, a gate and only grass inside. The workshop gantry has log posts so its bars connect, the maintenance bay lost its iron spikes, EVA prep has at most three suited stands with empty stands otherwise, and the observation room has loungers facing the window with a low sill, in place of stair-block seating.
- **Not done:** derelict damage, the journey encounter (it still builds the old compact harbor), station claiming and navigation for these stations, and stocking the containers.

## Furnished stations in space (November 13)

- **Rooms in stations:** [`StationStructure`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationStructure.java) now furnishes every single-door room with its design, after the shell and before the utilities and lighting. A room that has its own air supply (and, in engineering, its own generator and battery) skips the generic utility machines. [`StationRooms.variantFor`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationRooms.java) picks the variant: a seed store for agriculture and a spares store for industry, and the seed chooses crops or orchard and pen or grove.
- **In space:** [`/nullspace station new <maker> <purpose> <size> [seed]`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/GeneratedStations.java) (gamemaster) plans, furnishes and queues a station beside the player's ship through the ordinary construction queue; use it at the helm in space, then dock to a port. The same brief and seed at the same place is the same station. The station is public and unowned.
- **Entities:** the points are saved with the station. When its build finishes, [`StationBuilds`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationBuilds.java) spawns the item frames, armor stands, paintings and animals once, and skips the old step that stocked the first container, so every chest and barrel stays empty.
- **Checks:** [`GeneratedStationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/station/GeneratedStationGameTest.java) builds a small station through the real queue in space and checks it is published ready, has a working port, its frames and stands appear and every container is empty. The 126-station structure check, the real light engine on placed stations, the room checks, the ship gallery and the whole existing station class pass together. The structure check's walk now starts inside the arrival hall's first doorway and accepts a room when any floor cell of it is reachable, because a table can fill a room's middle.
- **Not seen in game yet:** the stations in space, large stations (they take longer to build, up to a few hundred thousand blocks), and how the rooms look at the many sizes stations produce rather than the gallery's two.

## Orbital stations in space (November 13)

- **Rates:** [`OrbitalStations`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/OrbitalStations.java) gives each zone one seeded roll: 10 percent for a body in a star system, 50 percent for a feature (nebula, black hole, rogue planet, pulsar, asteroid field), none for Sol's home orbit. The same seed gives the brief: any maker and purpose, 45/40/15 percent compact/standard/large, operational.
- **Placement:** the station is a new site role (`orbital`) in [`SpacePopulationPlan`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SpacePopulationPlan.java), about 900 blocks out. [`SpacePopulation`](../../src/main/java/com/nullandkale/nullspace/worldgen/encounters/SpacePopulation.java) plans it on a background thread (a big one takes seconds and would otherwise stall the server), keeps clear space proportional to its size, builds it through the ordinary queue and marks it as furnished, so its entities appear and its containers stay empty. The ledger version is 3, and a region recorded earlier gets its roll the next time it is discovered. A brief that cannot be planned is dropped, not retried.
- **Checks:** [`OrbitalStationsTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/encounters/OrbitalStationsTest.java) samples 40 generated galaxies for the rates, determinism, Sol's exclusion, the size mix and that 150 chosen briefs all plan; [`OrbitalStationGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/encounters/OrbitalStationGameTest.java) enters a zone with one and checks it is built furnished with a working port, frames and empty containers. The existing population and reset tests pass.
- **Test fix:** three of my tests polled by rescheduling the same `Runnable` object from inside itself, which the test framework drops; they now pass a fresh lambda, as the older tests do. They had only passed because their first check succeeded.
- **Not done:** purpose and maker choices that fit the place (research at black holes, industry at asteroid fields), derelict damage, journeys and claiming, and large stations' build size in a saved world, which has not been measured.

## Batch 5 plan: room and feature generators

Prepared November 13 from reading the existing furnishing code. Nothing here is built yet.

### What already exists

- Ship rooms are furnished by [`Furnishings`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/Furnishings.java) through [`Frame`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/Frame.java): a room is a [`Space`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/Space.java) with an entry direction, laid out in mirrored pairs about the line from its door to its far wall, with the door approach reserved. Furniture is a block-free [`Kind`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/Kind.java) in a [`Grid`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/Grid.java), turned into blocks by [`ShipBlocks`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/ShipBlocks.java), so room layouts can be checked without the game's registries.
- The old 16-by-16 station tiles have their own [`StationFurnishings`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/StationFurnishings.java) with fixed cell positions. Those do not scale and are not reused.
- Content conventions already exist: a `loot` point becomes a barrel in [`Assembler`](../../src/main/java/com/nullandkale/nullspace/worldgen/piece/Assembler.java), `breach_ok` points steer breaches and every point is kept intact by the [`Condition`](../../src/main/java/com/nullandkale/nullspace/worldgen/condition/Condition.java) pass, which also never damages the mod's machines or doors. Batch 4 already records a `room` point per room.

### Design

- One adapter turns a station room into a `Space`: the interior box, with the entry direction taken from the room's single door. It lives in the `shipgen` package, where `Frame`, `Grid` and `Kind` are package-private, and is called from [`StationStructure`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationStructure.java) before the lighting pass, so torches see the furniture and the room-level lighting is judged on the furnished room.
- Rooms that have one door use the frame. Rooms with several (the arrival hall, corridors, dock rooms) get their own dressing: the hall's centre and galleries, corridor lanes and dock hardware.
- Station rooms map onto existing ship furnishers where the task matches, so no function is written twice, but the agreed room set (see the [design](../design/tiny-ships-and-stations.md#room-set-decided-november-13)) is richer than the ship rooms and each room is designed with the owner before it is built. Merges and additions change the planner's room list: the seed store becomes a stores variant, crew quarters and barracks become a bunk room, the office is cut, the transfer bay is renamed the receiving bay, and EVA prep, infirmary and brig are added.
- Ship rooms top out near 8 clear blocks. Station rooms go up to 16 by 16, so each furnisher must scale by repeating its bays and aisles rather than stretching one unit. The size classes stay a planner hint; a furnisher takes any size and says when it is too small to do its job.
- Pressure plates are part of the structure pass, not the room furnishers: [`StationStructure`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationStructure.java) places one on each side of every interior door. The structure checks treat a plate as a passable, transparent cell, and a new check confirms a plate opens its door and that no plate touches two doors.
- Manufacturer detail is a second, separate pass over furnished rooms: wood and lamp choices from the style, a trim at the door, and a maker-specific feature in the arrival hall. It does not change which fixtures a room has.
- Containers are placed empty, and displays are item frames, armor stands and paintings. Content, display and damage markers are written as points (`loot`, `breach_ok`, `frame` with the item, `stand`, `painting`, plus a per-room `content` point naming the use), so the provisioning and condition passes in batch 6 can fill and damage the rooms without rerolling them.

### Chunks

1. The seam and the shared furnishers: the adapter, ordering with lighting, the mapped ship rooms scaled to station sizes, hall centre and corridor dressing, and the marker points. Pure grid checks plus one native and graphical review of one station per purpose.
2. The station-only rooms (transfer bay, seed store, garden, operations, armory, lookout, office, maintenance bay, archive) and the large-room scaling for the dominant rooms: growing bays, cargo holds, foundries, barracks and laboratories.
3. Manufacturer details and the room gallery: cameras into each room use, side by side across makers, then the combined sweep. Judge rooms inside the generated structures, as the design requires.

### Checks

- Pure, on the grid with no game registries: for every use, size and maker, the door approach and an aisle to the far wall stay clear, every fixture touches a cell a person can stand in, the layout is mirrored about the entry axis, and each room has what its task needs (for example a growing bay has water, soil and crops; engineering has a generator, a battery and an air supply). Also: the same seed gives the same room, and no fixture sits in a reserved column.
- Native: the existing structure checks, unchanged, on furnished stations (sealed rooms, walking search to every room and port, light on every standing cell with the furniture in place), plus the real light engine on a placed station of each maker.
- Graphical: one gallery of every room use, in each maker, from the door and from the far wall.

### Decisions made

- The room set above is agreed, including trees and animals, with derelict hazards deferred until the operational rooms work.
- Rooms are designed one at a time with the owner, through story, generation and gameplay, before any is built. A room gallery command will place one of each room, small and large, for station rooms and the ship rooms for comparison; its layout is still to be agreed.
- Decided: containers stay empty in room generation (stocking belongs to batch 6). A room's purpose shows in wall item frames holding one handy item each (a tool, weapon, potion or emergency air canister, never bulk materials), armor stands with space suits, and paintings, all written as marked points that a spawn-once pass turns into entities. Every interior door gets a stone pressure plate on each side; mobs may trigger them, and the later damage pass may knock plates and doors out. Still open: the arrival hall's feature in the well; whether the infirmary and brig earn a function; how animals and trees are verified in the existing spawn and persistence paths.
## Preview command

```text
/nullspace shipyard stations preview <maker> <purpose> <size> <condition> [seed]
/nullspace shipyard stations preview rivet harbor compact derelict 419
/nullspace shipyard stations preview halcyon harbor compact operational 419
```

The maker choices are `rivet`, `halcyon` and `bastion`. The brief definitions include all seven planned purposes and three size bands, but this adapter currently places only `harbor compact`. Other combinations are refused before placement. Omitting the seed chooses one and reports it; an explicit seed reproduces the result.

These are existing Waypoint-layout previews. Maker changes the existing materials; the three new manufacturer placement algorithms and the new damage pass are not implemented. Operational/derelict labels are preserved as brief inputs, not evidence of a completed condition pass. Previews add no ambient-site/ownership records and do not stock survival loot.

The command uses fresh clear space, preserves the existing `/nullspace shipyard stations` gallery, and saves the selected brief/seed in a preview receipt. It does not overwrite existing structures.

## Verification

Compilation passes. Ten focused unit cases cover journey selection, stable encounter IDs/names, station rarity, brief parsing/round trips and rejection of invalid/future specifications. Formatting and content checks pass in `build/station12-prepare.log`.

The combined native batch `build/focused-tests/20261112-181741-JourneyGameTest-5c19/` completed 28 cases including the vanilla fixture, with two failures. The preview queried height before loading its chunks and attempted placement below terrain; it now loads the region first. The station journey fixture assumed an engineering locker, whereas this legacy plan supplies other rooms; it now follows actual saved supply markers and the connected return berth.

The affected two-class rerun `build/focused-tests/20261112-182204-JourneyGameTest-cff9/result.json` passes all nine cases including the vanilla fixture. It completes the new station journey through saved-state reload, automatic docking, boarding, actual fuel collection, return, paid resume and cleanup. The registered command places a preview, preserves the old gallery command, records the exact brief/seed, and refuses unsupported layouts before placement. Other station, condition/content, orientation and palette passes are retained.

The combined receipt `build/verification/station12-complete/result.json` records all **ten focused unit cases and 27 native mod cases passing**, with the original failures and affected corrections. Formatting/content checks pass. No graphical redesign, broad integration sweep or performance measurement was run for these two batches. Visual review of the new manufacturer layouts belongs to batch 3.

## Tiny ships

Three new catalog designs share a compact two-compartment survival program: Rivet Spark, Halcyon Wisp and Bastion Dart. Each is 9 wide, 5 high and 15 long, fits a 16-by-16 pad, and has one deck containing only bridge and engineering. The bridge provides controls, scanner, terminal/monitor/camera, bed, crafting table, furnace and a barrel. Engineering provides generators, batteries, engines and fuel storage. Both closed compartments have an air outlet and a clear central route.

The bridge interior is 7 by 6; engineering is 7 by 5. Existing maker materials, stripe colors, window treatments and Halcyon's skylight distinguish the three variants. This is a shared small layout, separate from the larger ship room-placement algorithms. Its footprint is 135 blocks, compared with 570 for the existing 19-by-30 small design.

New space-start settings default to `rivet_spark`. Explicit saved/configured choices and existing players' ships remain intact. All three tiny designs are selectable in the starting-ship picker and beacon catalog. Random ship/salvage selection explicitly excludes tiny presets. The shipyard command reserves a new gallery with all twelve designs; existing gallery structures and edits are preserved.

### Tiny verification

The combined batch covered eleven focused unit cases, 21 native mod cases and two graphical entrypoints. Final results pass with unaffected results retained. Compilation, formatting and content checks pass. The combined receipt is `build/verification/tiny-complete/result.json`.

Native checks cover all catalog hulls, clear equipment outlets, working docks, both closed tiny compartments, first-login supplies/identity, compact beacon delivery, existing start recovery, and storage/deployment on a 16-by-16 pad. The storage case preserves cargo and identity, consumes the matching title and verifies that the entrance faces the west Pad Computer.

The graphical purchase flow completes launch, walking through the real bulkhead door into engineering, station docking/boarding/return, undocking and landing. The separate creation flow selects Halcyon Wisp through the actual picker, logs in aboard it and reloads the same ship. Actual bridge, engineering and all three exterior screenshots were reviewed in `build/focused-tests/20261112-190829-ShipYardClientGameTest-757e/screenshots/`.

Initial failures were in the fixtures: the expanded catalog filled the row assumed blank; the storage fixture omitted authorization and used a raw ship ID instead of its recall selection ID; the walkthrough read a pre-transfer record. Registering repeated tick tasks from inside another running task also mutated Minecraft's scheduler and repeated delivery. Those fixture corrections pass in the affected reruns. Passing new-world/start results were retained, and graphical checks were not repeated for the later native-only scheduling correction.

Retained start checks are in `build/focused-tests/20261112-190431-ShipYardGameTest-5635/native-tests.xml`; the final shipyard check is `build/focused-tests/20261112-191403-ShipYardGameTest-c6d3/result.json`. The new-world graphical receipt is `build/focused-tests/20261112-190536-SpaceStartClientGameTest-38fc/result.json`.

Manual playthrough should still judge comfort and usefulness: sleep in the bed, craft and smelt, refuel, make an FTL journey, and repeat storage/deployment with personal cargo. Those are human acceptance checks, not claims established by the structural tests. The full integration sweep belongs to the final agreed feature batch. No performance measurements were run.

## Follow-up fixes: version, wet rooms and room selection

- [x] Add `/nullspace version` for ordinary players and console use. It reports the loaded mod version and build label. Future CI builds embed their release tag and commit; local builds explicitly report `development build`. The base mod version remains unchanged, so existing release metadata checks still apply.
- [x] Swap upper-floor hydroponics with a compatible first-floor room before furnishing. Preserve the room bag, room geometry, doors and special bridge/entry/engineering assignments. The swapped programs also receive their appropriate window treatments. Hydroponics is currently the ship room that places free water.
- [x] Give functional flooring priority over lower-deck ceiling lights in a shared slab. Soil, water and moss can replace an earlier structural light; later lighting preserves those materials.
- [x] Replace the fixed preferred-room prefix with seeded weighted draws by ship role. Keep the no-repeat bag: draw among fitting types used the fewest times, then normalize the role weights over those candidates. Give the nine larger catalog designs distinct fixed seeds, preserving reproducible copies while varying ordering between designs. Tiny layouts remain the explicit two-room program.

| Role | Relative room weights; unlisted optional rooms have weight 1 |
| --- | --- |
| Scout | Crew, cabin and storage: 4; crafting, galley and lab: 2 |
| Freighter | Cargo and storage: 8; crafting, smelting and mining: 4; crew and galley: 3 |
| Explorer | Lab, hydroponics, viewing, cabin and enchanting: 4; crew, brewing and mining: 2 |

These are relative draw chances, not fixed percentages of a finished ship. For example, with all fourteen optional types eligible, a freighter's cargo draw has weight 8 of 41, about 19.5%. Room fit, fixed reserved uses and earlier draws change the denominator. No type is excluded solely by role, and repeat avoidance takes priority over weight.

The combined follow-up passes **37 focused unit cases and 19 native mod cases**, plus formatting, content and compilation checks. The native batch is `build/focused-tests/20261112-201501-NullSpaceGameTest-e859/result.json`; the combined receipt is `build/verification/version-rooms-complete/result.json`. It exercises the public command, actual fluid ticks and soil preservation in planted catalog rooms, all catalog hulls/docks, the six larger role designs' routes/power/cargo, and compact storage/deployment. The role-fleet fixture now excludes tiny starter designs from its six-freighter/explorer expectations.

CI metadata was checked through the real resource-processing task with a temporary test tag/commit, then regenerated with normal local metadata. No release was published. These generation corrections affect new builds; existing physical ships are not rewritten or automatically repaired. No additional graphical sweep or performance measurement was run.

## Next batches

1. Build the three manufacturer placement methods, real floors/connections/shells/docks and basic utilities; expose comparable seeded examples through shipyard. Require meaningful same-purpose/maker/capacity variation across seeds, not one fixed floor plan per type. Room allocation uses purpose-weighted choices and functional capacity requirements, with deliberate repetition for dominant facilities.
2. Build purpose-specific room and feature generators inside those reviewed structures.
3. Complete condition/content, permanent station claiming/return navigation, rare fixed-site population, player construction and reviewed core screens.

Tiny ships are implemented locally. These changes are not part of the published rc2 asset; the new station structure/content and integration batches remain unfinished.
