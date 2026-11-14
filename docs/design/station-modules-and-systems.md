# Station pieces, doorway graph and systems

A station is a set of room pieces on a grid, joined by doors, running on the same power, air and door systems as a ship. This document explains how a station is described and generated, how its doors form a graph, and how its systems share that structure. The player's view is in [stations](stations.md), and the rules for access, building and regeneration are in [the station reference](station-reference.md). The code is [`worldgen/stations`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations) ([`StationLayout`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationLayout.java), [`StationPieces`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationPieces.java), [`StationBlueprint`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationBlueprint.java)), `worldgen/shipgen/StationGenerator` and [`gameplay/station`](../../src/main/java/com/nullandkale/nullspace/gameplay/station) ([`StationGraph`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationGraph.java), [`StationSpace`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationSpace.java)).

## The idea

Build one complete station from reusable room pieces, then use the same pieces and doors for generated stations and for player construction. A piece is a collection of ordinary Minecraft blocks, and after it is placed the player can edit its hull, rooms, doors and equipment like any other build. The game keeps no hidden "station" other than what the blocks do.

## Space: tiles, sections and the envelope

A station occupies a **build envelope**: 32 × 32 × 32 to start, expandable at the Station Core to **128 × 32 × 128**. The grid is made of 16 × 16 **tiles** ([`StationLayout.TILE`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationLayout.java)), so a full station is 8 × 8 tiles and holds up to 64 pieces. Rendering is divided into 32-block **sections** ([`StationSpace.SECTION`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationSpace.java)), at most 16 of them. A section boundary is a rendering matter only: collision, boarding, gas flow and power all treat the station as one thing, with one identity, one air grid and one power account.

Ships still use a 32-block limit. Station size is not a ship feature.

## Pieces

A piece ([`catalog/station_pieces.json`](../../src/main/resources/data/nullspace/catalog/station_pieces.json)) fills one tile. It names the **kit** that furnishes it ([`StationGenerator.Kit`](../../src/main/java/com/nullandkale/nullspace/worldgen/shipgen/StationGenerator.java)), its window level, its role, and the sides that have doorway slots. Every piece exists in a *few windows* and a *many windows* form (`<id>_bright`).

| Piece | Role | Doorway sides |
| --- | --- | --- |
| Entrance (`arrival`) | Arrival, holds the Station Core and its terminal | All four |
| Workshop | Benches, storage | All four |
| Bedroom (`habitation`) | Beds, lockers | All four |
| Power room (`engineering`) | Generators, batteries, air | All four |
| Tunnel, corner, junction, crossing | Walkways between rooms | Two, two, three, four |
| Garden dome | Hydroponics under glass | All four |
| Observation dome | A viewing hall under glass | All four |

A piece can be turned in quarter turns, which turns its doorway sides with it (`Room.turn`). A dome is always roofed in glass, with mostly solid walls in its few-windows form and glass all round in its many-windows form.

Pieces come from the same generator as ships, so they follow the same [ship generator](ship-generator.md#stations) rules: bright, one task per room, mirrored shell, walkable and with no hidden air. Each tile is **two stories** over a solid base. Each story is six blocks tall with a slab between them, and a flight of stairs runs along the south and north walls. Each side of a tile has two doorway slots per story.

## Joining pieces

A **layout** ([`StationLayout.Layout`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationLayout.java)) is a list of rooms and a list of joints. It validates itself: rooms must be on distinct tiles inside the grid, each joint must connect two neighbouring tiles that both have a doorway facing the other, and the whole must be one connected graph. A station may hold **at most one closed loop** (`StationLayout.loops`): the Waypoint is a ring of four, the harbor is one perimeter ring, and generated starters are rings or trees. Several loops make a station a maze, and the single loop still gives a second route if one door is shut.

`StationLayout.place` places a new piece *by doorway*: it must adjoin an existing tile, and is turned until one of its doorway sides meets an open doorway of a neighbour. The first piece goes on tile 0,0. A piece with no doorway that can meet its neighbour is refused with a message naming the problem.

When two tiles join, the shared wall is cut open ([`StationBlueprint.connect`](../../src/main/java/com/nullandkale/nullspace/worldgen/stations/StationBlueprint.java)): a closed iron door goes in the first tile's wall and the second tile's wall is cut open behind it, so each shared edge has four doors, two per story. Unused doorway slots stay solid wall. A piece placed on its own therefore holds air, and joining it later opens exactly the doors that were agreed.

### Presets

- **Waypoint**: four tiles in a ring, one of each of the four room types.
- **Generated starter** (`StationLayout.generate`): the same four room types in shuffled order, each in a seeded few-windows or many-windows form, with one of the four links removed at random, so some starters are a ring and some a chain.
- **Harbor**: a walkable perimeter ring of 28 pieces on an 8 × 8 grid, with eight outward-facing berths spread around it.

## Doorway nodes and the graph

Every ordinary framed door is a **node** ([`StationGraph.nodes`](../../src/main/java/com/nullandkale/nullspace/gameplay/station/StationGraph.java)). Discovery works from the real blocks: a framed door, or a Docking Base with its door above it. A Docking Base also makes the node a dock. Each node has a stable id saved by its position in the station, a facing, and the set of walkable standing positions behind and in front of it.

Two nodes are connected in the **route graph** when their standing areas touch. The graph is for *finding routes* only: it lists which doors lead where, so the game can say how to walk from one place to another. It never decides pressure or power:

- A closed bulkhead blocks gas and walking, but does not cut the power bus carried by the surrounding structure.
- Opening a door does not join two electrical supplies.
- Breaking a door, wall or floor updates the affected links on the next inspection. An opening that no node marks still changes the real compartment topology. Missing nodes never make an unmarked breach airtight.

Node identities survive a reload. Replacing a door creates a new node and invalidates references to the old one. Scanning is bounded (a compartment is limited to 16,384 cells), and a larger chamber is reported as one to seal rather than searched forever.

## How station systems use the layout

| System | Behavior |
| --- | --- |
| Station Core | Owns the station's identity, permissions, map and diagnostics. It stores no replacement hull, so a station keeps its identity if the core breaks |
| Electricity | One shared bus for the station, using the same generator, battery and allocation model as ships |
| Life support | Air pumps supply real compartments. Doors let gas through locally and breaches vent real gas. Air crosses a section boundary by the same finite flux as any other face and is never copied between records |
| Doors | Nodes identify controlled bulkheads. Power and ownership decide who can cycle them |
| Docking | Dock nodes expose berths and approach clearances. All ship maneuvering is automatic (see [docking](space-encounters-and-docking.md)) |
| Navigation | The node graph finds routes. Blocked doors, permission, pressure and suit needs constrain a usable route |
| Cargo and workshops | Ordinary inventories and machines. Adjacency never transfers items |

Docking two vessels adds a controlled boarding connection. It does not merge their air or power. A station has no engines, ship controller or launch inspection and needs none.

## Where generation is deterministic

A station's layout is chosen from a seed and a version when it is first planned, saved, and then **the placed world is authoritative**. Loading rebuilds caches from the saved record and the blocks, never from a new random layout, so saved stations keep loading when templates change. The generator keeps the original room roles as a record, without restoring player edits.

Placement is queued as a replayable list of blocks and placed in bounded work per tick (see [the reference](station-reference.md#how-construction-runs)). It is unavailable for docking until placement, inventories, systems and collision data are committed.

## Not built

- Rotated or vertical kit placement beyond quarter turns of a tile.
- Utility sharing between docked vessels, and continuous rendering across docked ships' frames (boarding uses a short transition instead).
- Full manufacturer-specific harbors: piers, rings and docking arms for a Rivet Exchange, Halcyon Observatory and Bastion Watchpost (see [procedural ships](procedural-ships.md)).

## Where this is tested

[`StationLayoutTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/stations/StationLayoutTest.java) (unit) checks the loop limit, validation, placement by doorway and the presets. [`StationGeneratorTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/StationGeneratorTest.java) and [`RoomKitTest`](../../src/test/java/com/nullandkale/nullspace/worldgen/shipgen/RoomKitTest.java) check each piece against the generator rules. [`StationPiecesGameTest`](../../src/gametest/java/com/nullandkale/nullspace/worldgen/stations/StationPiecesGameTest.java) checks every piece against its catalog entry, that unused doorways are closed wall, and that a joined group of tiles is sealed from space. The station native and graphical tests cover building, expansion, regeneration and docking.
