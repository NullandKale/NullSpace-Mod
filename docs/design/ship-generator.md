# Ship generator

Shipyard ships are generated, not hand built. A **spec** (maker, shape, tier, role, seed) always builds the same ship, so a catalog entry is a short recipe and a derelict or encounter can ask for a random one. The code is in [`worldgen/shipgen`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen).

Newer, planned ships (small, medium and large, with the room designs and floors of the stations) are described in [tiny ships and stations](tiny-ships-and-stations.md#porting-the-station-generator-to-ships-built-november-14); the six full-size catalog designs are now planned ships, and the generator below still builds the tiny ships and the room gallery's ship rooms.

The nine shipyard designs are generated. The rules below are enforced by tests on every combination of shape, tier and role. Stations use the same machinery (see [Stations](#stations)).

## What a spec chooses

| Choice | Options | Decides |
| --- | --- | --- |
| **Shape** | Spine, Dome, Twin hull | How rooms are arranged and the silhouette |
| **Tier** | Small, Medium, Large | The size of the *pieces*: room depth, ceiling height and number of decks |
| **Role** | Scout, Freighter, Explorer | Which rooms the ship gets |
| **Maker** | Rivet Works, Halcyon Aerospace, Bastion Shipbuilding | Materials and accent colour |
| **Seed** | any number | Which of the allowed rooms, in what order |

Tiers are piece size, not ship count:

| Tier | Side rooms | Ceiling | Decks (spine) |
| --- | --- | --- | --- |
| Small | 5 deep × 5 long | 3 | 1 |
| Medium | 7 deep × 7 long | 4 | 2 |
| Large | 9 deep × 7 long | 4 | 3, the top deck stepped in |

A ship must fit 32 × 32 × 32, so the generator keeps as many room slots as fit and drops the rest.

## Shapes

- **Spine.** One straight corridor, nose to tail, with a room either side of it at every slot. The bridge is at the nose and engineering at the tail. Tall ships stack decks joined by a stairwell, where the corridor swells to seven wide with a flight up in each outer lane.
- **Dome.** A round commons with a stepped glass roof, and four straight arms leading to the bridge (nose), engineering (tail) and a room on each side.
- **Twin hull.** Two parallel hulls, each a corridor with rooms on its outer side, with the bridge in a pod between them at the nose and engineering across the tail. The gap between the hulls stays open.

Every shape is symmetric across its centre line. Side rooms come as mirrored pairs, and every room is furnished in mirrored pairs about the line from its entry to its far wall.

## Rooms and what each is for

Every room is one complete task: it holds everything that task needs and nothing from another task.

| Room | Holds |
| --- | --- |
| Bridge | Helm in front of the docking door, one terminal and one scanner (one each side), forward monitors, the forward camera |
| Airlock | The docking door (on tall ships), EVA lockers, one map table, the air supply |
| Engineering | Generators with batteries stacked on them, fuel barrels, air supply, engines in the stern wall |
| Cargo hold | Barrels stacked two high along both walls from the back corner, with the door end open |
| Storage | Double chests stacked two high along both walls from the back corner, with the door end open |
| Crew quarters | A cubicle for each bed: the bed on the wall with a wall between it and the next and a locker by the pillow, plus a footlocker by the door |
| Captain's cabin | A bed on the centre line with clear floor either side, a store unit down one wall, and down the other a terminal, a screen and a desk with its chair and bookshelf |
| Workshop | One of every bench in units: a chest and benches down each side wall, and three a side in a medium room and four in a large one, so a large workshop has all eight |
| Smelting bay | A chest of fuel with furnaces down one wall and blast furnaces down the other: two a side in a small or medium room, three in a large one |
| Mining bay | A chest with blast furnaces and a grindstone down one wall, a chest with ore bins down the other |
| Galley | Storage (a chest and barrels down one wall), prep (a chest of food with smokers and a cauldron down the other) and, in a large room, a dining table with chairs by the door |
| Viewing room | A seven-by-three monitor wall, rows of seats facing it, a media terminal |
| Enchanting library | An enchanting table inside a full ring of bookshelves, an anvil, a lectern, a chest of lapis and books by the door |
| Brewing lab | A chest with brewing stands down one wall, a chest with cauldrons down the other |
| Hydroponics | Farmland sunk into the floor in beds down both sides, three crops in bands, a water channel sunk into the floor between two moss paths (closed at both ends, so it cannot spill), grow lights over every bed, composters, seed barrels by the door |
| Lounge | Bookshelves, seats, a continuous bench |
| Science lab | Charts, a lectern and a terminal down one wall, sample barrels down the other, screens at the back |
| Commons | A round hall with round tables, seats and planters, under the dome |

The role sets the wish list (a freighter wants cargo, a scout wants bunks and a store, an explorer wants workshops, labs and hydroponics) and the seed varies the rest. A room is only offered if it fits: a workshop needs seven blocks of wall, an enchanting library nine by seven.

Not built, left for later: armoury, trophy lounge, animal bay, redstone workshop. Mob farms, villager halls and portals are deliberately not on ships.

## Rules

The generator is checked, on every shape, tier and role, against these ([`ShipGeneratorTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/ShipGeneratorTest.java), which needs no game):

- **Symmetry.** The shell mirrors across the centre line: hull, walls, windows, doors, stairs and lights, with facings mirrored. So do the ship's systems (engines, generators, batteries, air supply). Furniture does not have to: a room has one scanner, one terminal, one of each workbench, not a copy on each side.
- **Light.** Interiors are bright: every standing cell has block light of at least 7 (sea lanterns at 15 in the ceiling, or in the deck above's floor on lower decks, and lamps in the commons), and every crop has at least 9 and farmland within four blocks of water.
- **One task.** A room holds only the machines and furniture of its one task, and nothing from another room's. A room that works with items keeps a little storage. [`RoomKitTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/RoomKitTest.java) checks every kit.
- **Walking paths.** Every room can be walked to from the docking door, up and down stairs, and every machine can be reached from a standing spot. No standing cell is a single-block-wide corridor or a blind nook.
- **High ceilings.** No room under three blocks.
- **No hidden air.** Every pocket of air is either a room a person can enter or open to space. Hull fills from the rooms outward with cheap blocks, then any pocket sealed inside the hull is filled too.
- **White space.** Furnishing leaves floor open. Nothing is put in only to fill a corner or a gap: no pillars, no dividers between beds, no walls closing off a lane. A dead-end corner beside a unit is normal floor. Only a passage that pinches to one block wide counts as a fault.
- **Doors.** Every doorway between a room and a walkway holds a closed, framed door in the middle (trim fills the rest of the opening), built like the docking door so a hull inspection accepts it. Open them to walk through.
- **Vents.** Every room has a pair of air vents, copper grates set high into the two side walls (waxed, so they never weather).
- **Room tree.** Rooms joined by doors and stairs form a tree: one way between any two rooms, no loops, checked by [`RoomTreeTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/RoomTreeTest.java) (the mirrored stair lanes count as one connection). The twin hull is the one exception, on purpose: its two walkways meet at the bridge end and at engineering, so it has a single ring. Keep it.
- **Stairs.** Each flight has a landing cell in front of its first step, so a person walks in line with it. A stair's tall half fills the far half of its cell, so stepping onto one from the side takes a jump. [`StairWalkGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/StairWalkGameTest.java) walks a real player up a flight.
- **Machines.** One helm, one docking base and door, a camera, generators, batteries, engines in pairs, air supply, a terminal, a scanner and a monitor.
- **Fit.** At most 32 in each direction, and the same ship every time for the same spec.

The ship tests in the game then check what the unit tests cannot: sealed, powered and breathable in the real air system, a real walking route from the dock to every room (including the stairs), supplies in real containers, the engines' exhaust clear, and the bow found from the engines.

## How a ship is built

1. **Layout.** The shape lays out rooms (sets of cells with a frame for furniture), the openings between them and any stairs.
2. **Walls.** Every cell touching an interior but not part of it becomes wall, floor or ceiling.
3. **Hull.** The cells within the hull thickness of that become hull, bevelled so the edges are cut, and the outer layer is the visible shell with a maker accent stripe. Pockets are filled.
4. **Openings and windows.** Doorways are three wide with a trim frame. Windows are glass tunnels through wall and hull in a pattern that mirrors; a room's window count (few or many) comes from its use.
5. **Furnishing.** Each room is laid out in its own frame in mirrored pairs, with a clear aisle from entry to far wall.
6. **Machines.** The docking door sits in a framed recess in the hull, with the camera above it. Engines are mounted in the stern wall with an engine pod behind each and a clear run for the exhaust.
7. **Clean-up.** Corners left between a wall and furniture are filled with a pillar, and any fixture nobody can reach is removed.
8. **Blocks.** The result is cell kinds with no game blocks in it. A mapper turns the kinds into blocks in the maker's materials.

## Using it

[`catalog/ships.json`](../../src/main/resources/data/nullspace/catalog/ships.json) entries carry a `generate` block with the maker, shape, tier, role and seed. [`ShipBlueprints`](../../src/main/java/com/nullandkale/nullspace/worldgen/ships/ShipBlueprints.java) builds the ship at startup (it needs no block registries, so sizes and helm positions are known to the unit tests and the client).

To change a design by hand, `/nullspace author load ship <id>` builds the generated ship on a pad, and `/nullspace author save ship <id>` saves your edit as a template. A saved template replaces the generated ship under that id; delete the template file to go back. Other uses (derelicts, encounters) can call `ShipGenerator.build(spec)` with a random seed.

## Furnishing in units

A room is built first and then furnished with a few workstation units, not by filling every wall cell. A unit is a row of blocks against a side wall, anchored in the back corner: a chest first (single when the wall allows three cells, double when longer), then the machines that use it. The wall is the room's depth minus one cell, the cells nearest the door stay open (one, two or three, by piece size), and the unit takes the rest, so a bigger room gets a bigger unit and always has open floor beside it. Floors in rooms are plain; only corridors have the accent path.

## The dock ramp

The docking door sits two blocks above the ground, because the hull has a floor layer under it. The generator adds a three-wide flight of stairs up to the door as part of the ship (solid under each step), so it works on any pad. The ramp takes the front block of the 32-block depth, and [`HullScan`](../../src/main/java/com/nullandkale/nullspace/gameplay/ship/structure/HullScan.java) ignores a pad's own computer and a delivery beacon even when the ramp's first step touches one.

## Random gallery

`/nullspace shipyard random [seed]` (operators) builds a walk-through gallery of random generated ships: one row per shape and tier (spine, dome, twin; small to large), one column per role plus a wild card, each with a random maker and seed. Every ship is on its own registered, powered, fuelled pad with a sign giving its role, maker, size and seed. Each run builds a new gallery at a new site and never touches earlier ones.

## Room kit gallery

`/nullspace shipyard rooms` (operators) builds every destination room on its own, to audit the kits: one row per use going south, and columns going east for small, medium and large pieces, each with few and then many windows. Each room sits behind a short corridor with an open doorway on its north face and a sign by the door. [`RoomKitTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/RoomKitTest.java) runs the walking rules on every kit and writes any breaks to `build/room-kit-audit.txt`.

## Not yet

- **Armed ships.** No design has a weapon yet.
- **Exterior variety.** Hulls are bevelled with engine pods and a stripe; swept wings, fins and landing gear would make the silhouettes more distinct.
- **Mirrored room variety.** Side rooms are exact mirror pairs, so a pair always has the same use.

## Stations

Station tiles are generated by the same machinery ([`StationGenerator`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/StationGenerator.java), [`StationFurnishings`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/StationFurnishings.java)), so every ship rule above applies. A station is a grid of 16 x 16 tiles, and each tile is one piece: an entrance, a workshop, a bedroom, a power room, a tunnel (promenade), a garden dome or an observation dome. Every piece comes in a few-windows and a many-windows version (`<id>_bright`); a dome is always roofed in glass, with mostly solid walls when it has few windows and glass all round when it has many.

- **Two stories.** Each story is six blocks tall with a one-block slab between, over a solid two-block floor, so the tile is 16 high and has no basement void. A flight of stairs runs along the south wall and another along the north, each with its landing, and the space under each step is solid. The slab is open over the last steps, where a person's head would meet it. [`StairWalkGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/shipyard/StairWalkGameTest.java) walks a real player up every kit's flights.
- **Doors.** Each side of a tile has two doorway slots, three blocks in from either corner, on each story. A slot is solid wall until a neighbour is joined, when a closed iron door goes in the first tile's wall and the second tile's wall is cut open behind it: four doors on every shared edge. The door approaches stay clear of furniture.
- **Furnishing.** Each hall is furnished in units against the west and east walls (the stairs hold the south and north), with open cells at the ends of each unit and a table to meet at in the middle. Every tile has an air supply (a power room has two, mirrored), the entrance holds the station core and its terminal at a reception counter, and a hall has at most one terminal and one scanner. Halls are lit by ceiling lanterns, wall lanterns at head height and flush floor lanterns, because the ceiling is six blocks up.
- **Loops.** A station's tile graph holds at most one closed loop: the Waypoint is a ring of four, the harbor is one perimeter ring, and generated starters are rings or trees. [`StationLayout.loops`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationLayout.java) counts them and [`StationLayoutTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/stations/StationLayoutTest.java) enforces it.
- **Review.** `/nullspace shipyard stations` builds every piece (few windows on one row, many on the next, each with a walk-in opening and a sign).

