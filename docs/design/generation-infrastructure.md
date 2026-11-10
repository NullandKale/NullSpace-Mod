# Generation infrastructure for steps 3 and 4

The code that [procedural generation](procedural-generation.md) steps 3 (shipyard exteriors) and 4 (orbit sites, station building) need before any content is drafted.

**Status (November 8):** G1 to G5 are built. Station rooms come from templates, identical block for block to the Java builders they replaced. The shipyard designs have since moved on from templates to a [ship generator](ship-generator.md), which replaced the fifteen hand-built ships with nine generated ones; the template path remains as an override. How to author pieces, kits and archetypes is in [authoring](../development/authoring.md).

## Where we are

- The shipyard designs, station rooms, derelicts and encounter pods are all built by Java code (`ShipBlueprints`, `ManufacturerShips`, `ManufacturerRoleShips`, `ShipRooms`, `StationBlueprint`, `EncounterSites`).
- `/nullspace author save|load|gallery` already saves pad builds as vanilla structure templates, but nothing reads a template back for gameplay. There is no `data/nullspace/structure/` folder yet.
- Everything downstream takes a block map: `Map<BlockPos, BlockState>` in local coordinates, settled by `BlueprintOrientation`, wrapped in `StationBlueprint.Plan(blocks, core, docks)`.
- Fixed coordinates leak out: a design's helm, `ShipRooms` landmarks, station tiles of 16 x 16 with doorways at fixed edge midpoints.
- The shipyard screen builds ship previews **on the client**, so templates must load without a server.

## What gets built

Five pieces of code, all under `worldgen`, plus tests and authoring commands.

| Piece | Package | Does |
| --- | --- | --- |
| **Template codec** | `worldgen/piece` | Reads and writes vanilla structure NBT directly (palette, blocks, block entities). Works on client, server and in unit tests. |
| **Piece library** | `worldgen/piece` | Loads `nullspace:<kind>/<name>` from the mod's resources (or the authoring folder in a dev run), turns markers into connectors and points, and rotates pieces. |
| **Design catalog** | `worldgen/design` | Shipyard designs as data: one JSON per design naming its template, manufacturer, role and description. Replaces the hard-coded `Preset` list. |
| **Assembler** | `worldgen/piece` | Joins kit pieces at matching connectors into a whole ship or station, deterministic per seed, bounded, never overlapping. |
| **Condition pass** | `worldgen/condition` | Damages an assembled build by danger level: breaches, scorching, dead lights, missing sections. |

### Template codec

Vanilla's `StructureTemplate` needs a server (`StructureTemplateManager`) and has no way to build a template from a block map. The codec reads and writes the same NBT format itself, using `NbtUtils` for block states. Templates stay editable with vanilla structure blocks, and the codec works anywhere `Bootstrap` has run.

### Piece library and markers

A **piece** is a template plus what its markers say. Markers are vanilla blocks the author places, and they are removed when the piece is loaded:

| Marker | Vanilla block | Meaning |
| --- | --- | --- |
| **Connector** | Jigsaw block | Facing is the jigsaw's orientation. As with vanilla jigsaws, its *name* is its type and its *target* the type it accepts; an empty target accepts its own type (`spine`), while a pair such as `hull_side` and `wing_root` keeps hulls from joining side by side. Its *final state* is the block it becomes (default air, an opening). Ends left open are closed by cap pieces. |
| **Point** | Structure block, DATA mode | Its metadata names the spot: `loot:<table>`, `mob:<kind>`, `core`, `light`, `breach_ok`. |

Pieces rotate in quarter turns, turning block states and markers with them. Mirroring is left out until a kit needs it.

The library looks in the authoring folder of a development run (`-Dnullspace.authoringDir`) first, then in templates saved in the running world, then in the copy bundled in the JAR, so a piece saved in game is used straight away without a rebuild.

### Design catalog (step 3)

`data/nullspace/design/<id>.json`:

```json
{ "name": "Rivet Hauler", "manufacturer": "Rivet Works", "role": "Freighter",
  "description": "Cargo racks, crew mess and a dedicated repair shop.",
  "template": "nullspace:ship/rivet_hauler", "color": "orange", "armed": false, "cargo": true,
  "rooms": [ { "name": "Hold", "use": "cargo", "walk": [12, 1, 18], "supplies": [12, 1, 20] } ] }
```

The size comes from the template, and the helm comes from the controller block in it. Room landmarks move into the JSON for converted ships. Pieces built later use point markers instead.

**Migration, without changing what players get:**

1. A one-off exporter writes every Java design to `structure/ship/<id>.nbt` with its full declared bounds, so every coordinate stays the same.
2. A test proves each template's blocks equal the Java builder's blocks.
3. `ShipBlueprints` switches to the catalog and templates. The Java builders are then deleted: the classic generator, `ManufacturerShips`, `ManufacturerRoleShips` and the furnishing half of `ShipRooms`.
4. The palette test now covers the templates, and its known iron-block exceptions become the to-do list for the exterior pass.

After that, a design's exterior is fixed by `author load ship <id>`, editing in game, then `author save ship <id>`. No code changes.

### Assembler (step 4)

A **kit** is one manufacturer's piece set: `data/nullspace/kit/<manufacturer>.json` lists its pieces with a role (nose, hull, engine, wing, room, cap) and a weight, plus its materials and accent colour.

An **archetype recipe** says what to build from a kit: the start role, counts per role (for example "hull 2-4, wing 0-2"), the largest allowed size, and whether it is a ship (needs a controller, generator, engine and Air Supply) or a station.

Assembly:

1. Place the start piece.
2. Take an open connector. Pick a piece with a matching connector the recipe still wants, weighted and seeded. Rotate it to face the open connector, then offset it so the two connectors meet.
3. Reject it if any block overlaps or it leaves the size limit. Try the next candidate, with a fixed number of attempts.
4. When the recipe's minimums are met, close each remaining open connector with a cap piece or its final state.
5. Settle orientation (`BlueprintOrientation`) and return the build.

The output is `Plan`, extended with the points: `Plan(blocks, core, docks, points)`. Loot and mobs then come from marked spots instead of guessed coordinates (`SiteLoot`, `SiteThreats`).

### Condition pass (step 4)

A pure function on a `Plan`: `condition(plan, danger, seed)`.

- **Breaches:** clusters of exterior hull removed, at `breach_ok` spots or anywhere exterior, more with danger. These leak air through the real air system.
- **Scorching:** hull near breaches swapped to blackened blocks (blackstone, black concrete, cracked variants).
- **Dead lights:** some lamps swapped for unlit blocks.
- **Missing sections:** at danger 2-3, a non-essential piece is left out at assembly time.

Fuel, battery charge and fires need machine data in placement, which `Plan` does not carry yet; they come with derelict content.

### Station rooms

Stations keep the 16 x 16 tile grid (decided November 8). The tile grid, saved `roomPieces`, docking and air sections all stay as they are. Each tile holds one **station piece** with door connectors only at the standard doorway spots it actually uses. There are three shapes:

| Shape | What it is | Doorways |
| --- | --- | --- |
| **Room** | A full tile box, 9 high, furnished by role (arrival, workshop, habitation, engineering) | All four sides |
| **Tunnel** | A narrow pressurised corridor across the tile, open space around it: straight, corner, T and cross | Only the sides it runs to |
| **Dome** | A domed room, glazed roof, up to the full 32-block station height: garden, observation, hub | All four sides |

`StationBlueprint.build` places pieces instead of drawing boxes. A joint between two tiles needs a doorway on both facing sides. A tunnel only joins where it runs, so the layout check and the generator respect each piece's connectors instead of assuming four doors.

"Placing rooms by doorway" picks an open doorway on the station plus a piece; the tile behind the doorway follows from the grid, and the piece is rotated so one of its doorways faces back. Rooms spanning two tiles can follow later without a format change.

## Authoring additions

| Command | Does |
| --- | --- |
| `author check <kind>/<name>` | Lists a piece's connectors and points and flags problems: a connector not on the piece's edge, an unknown point name, a palette violation. |
| `author preview kit:<manufacturer>:<archetype> [danger] [seed]` | Assembles and conditions a site on the pad. Re-run with a new seed to reroll. |
| `author gallery kit:<manufacturer>` | Every piece of a kit, plus three seeded samples of each archetype. |

## Tests

- Codec round trip: write, then read, gives the same blocks, block entities and size.
- Markers: a test piece's jigsaws and data blocks become the right connectors and points, and are gone from the blocks.
- Rotation: four quarter turns give the original piece.
- Conversion: each design's template equals its Java build (removed together with the Java builders).
- Assembler, with a small test kit: same seed gives the same build; no overlaps; within bounds; every recipe minimum met; no open connector left.
- Every bundled template passes the palette check, and every ship design or assembled ship has a controller, generator, engine, Air Supply and dock.
- Existing game tests keep passing on the converted designs. That is the proof that nothing changed for players.

## Slices

| Slice | Contents | Playable result |
| --- | --- | --- |
| **G1** | Template codec, piece library, markers, rotation, unit tests | None (foundation) |
| **G2** | Design catalog, exporter, equivalence test, `ShipBlueprints` on templates, Java builders deleted | Shipyard unchanged, but exteriors are editable in game (step 3 can start) |
| **G3** | Kit and recipe format, assembler, test kit, `author check` and kit preview | Kits can be drafted and previewed |
| **G4** | `Plan` points, condition pass, loot and mobs from points | Assembled derelicts look damaged and are stocked from marked spots |
| **G5** | Station pieces (rooms, tunnels, domes), connector-aware layouts, pieces placed by doorway | Station pieces are editable in game (step 4 station building can start) |

All five slices are built before content work (decided November 8). Each slice ends with focused tests; the integration sweep runs once after G5.

## Not in this round

- Block-entity data in placement: sign text, machine fuel, preset container contents. Templates keep it; placement ignores it until a slice needs it.
- Mirroring, multi-tile station rooms, planet piece sets (step 5).
- Kit samples in the gallery: `author gallery kit` shows a kit's pieces; samples of each archetype are reviewed with `author preview kit:...` and a changing seed.
- Wiring encounters to kits: `EncounterSites` still builds its derelicts from shipyard designs. Once step 4 kits exist, a derelict archetype replaces `EncounterSites.plan`, and saving its points with `SitePoints` makes stocking and threats use the marked spots.
- The starter tunnels and domes are plain quartz and glass, generated once; they are first in line for an in-game style pass.
