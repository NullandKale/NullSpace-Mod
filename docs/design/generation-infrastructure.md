# Generation infrastructure: pieces, kits, assembly and condition

NullSpace has two ways to make a build: **generate** it from rules (ships and station tiles, see [the ship generator](ship-generator.md)), or **assemble** it from hand-made pieces. This document describes the second path: the code that reads authored pieces, joins them into a whole build, and wears the result down for a dangerous place. It is the foundation for content authors, and the way to override a generated design by hand. The code is in `worldgen/piece` and `worldgen/condition`, with the authoring commands in `dev/Authoring`. The commands themselves are in [authoring](../development/authoring.md).

## What exists, and what does not

| Part | State |
| --- | --- |
| Template codec | Built and used: reads and writes vanilla structure NBT |
| Piece library | Built and used: loads pieces, and a saved ship template overrides a generated ship |
| Markers (connectors and points) | Built |
| Assembler, kits, archetypes | Built and reachable from the authoring commands. **No kit or archetype content has been written yet.** `data/nullspace/kit/`, `archetype/` and `structure/` are empty |
| Condition pass | Built and reachable from authoring. Not yet applied to a generated derelict |

Today's shipyard ships and station tiles come from the generator, which replaced an earlier plan to convert hand-built Java ships into templates. The template path stayed as an override: a design saved with `author save ship <id>` replaces the generated ship under that id, and deleting the file returns to the generated one. Derelicts still take their shape from catalog ships, with loot and threats from [site loot](ship-rooms-and-resources.md) and [site threats](ftl-journeys.md#danger). The next content step is for a derelict archetype to replace that, using marked spots instead of guessed coordinates.

## Template codec

Vanilla's `StructureTemplate` needs a running server and cannot be built from a block map. `TemplateCodec` reads and writes the same NBT format directly (palette, blocks, block entities), and works wherever `Bootstrap` has run: the client (the shipyard screen draws ship previews with no server), the server, and unit tests. Templates stay editable with vanilla structure blocks.

## Pieces and markers

A **piece** is a template together with what its markers say. Authors place two kinds of vanilla block as markers, and they are removed when the piece is loaded:

| Marker | Block | Meaning |
| --- | --- | --- |
| **Connector** | Jigsaw | Faces out of the piece. As in vanilla its *name* is its type and its *target* is the type it accepts, with an empty target meaning its own type (a symmetric joint such as a spine). A pair such as `hull_side` and `wing_root` keeps hulls from joining side by side. Its *final state* is the block left there, air by default. A connector only aligns pieces, and an end left open is closed by a cap piece |
| **Point** | Structure block in DATA mode | Names a spot by its metadata: `loot:<table>`, `mob:<kind>`, `core`, `light`, `breach_ok` |

Pieces rotate in quarter turns, turning block states and markers with them. Mirroring is left out until a kit needs it.

`PieceLibrary` looks for a piece in the authoring folder of a development run (`-Dnullspace.authoringDir`), then in templates saved in the running world, then in the copy bundled in the JAR, so a piece saved in game is used at once without a rebuild.

## Kits and archetypes

A **kit** (`data/nullspace/kit/<id>.json`) is one manufacturer's piece set. It lists pieces, each with a template, a role (nose, hull, engine, wing, room or cap) and a weight, plus the kit's materials and accent colour.

An **archetype** (`data/nullspace/archetype/<id>.json`) says what to build from a kit:

```json
{ "kind": "ship", "start": "nose",
  "roles": [ { "role": "hull", "min": 1, "max": 3 }, { "role": "engine", "min": 1, "max": 1 },
             { "role": "wing", "min": 0, "max": 2 } ],
  "cap": "cap", "max": [25, 15, 31] }
```

Roles are in priority order: an open connector takes the first role that is still wanted and has a fitting piece. `kind` is `ship` (needs a computer, generator, engine and Air Supply to fly), `station` (needs a Station Core) or `site` (no required machines). `max` is the largest allowed size.

## Assembly

`Assembler` builds deterministically from a seed, within bounds, and never overlaps pieces.

1. Place the start piece.
2. Take an open connector. Pick a piece with an accepting connector whose role the archetype still wants, in weighted seeded order, rotate it to face the connector, and offset it so the two meet. Walls take one rotation, floors all four.
3. Reject it if any block overlaps or the size limit is passed, and try the next candidate.
4. When every role's minimum is met, close each remaining open connector with a cap piece or its final state.
5. Settle orientation (`BlueprintOrientation`) and return the build.

The whole assembly is tried up to eight times (`Assembler.ATTEMPTS`) with derived seeds, and after the last the final try is returned with its problems listed, so a bad kit is visible in review and never an infinite loop. The result is a `StationBlueprint.Plan(blocks, core, docks, points)`: points carry loot and mob spots through to `SitePoints`, so stocking and threats use the author's spots.

## Condition pass

`Condition.apply(plan, danger, seed)` is a pure function that wears a build down according to danger (0 to 3), and leaves danger 0 unchanged.

- **Breaches.** Clusters of exterior hull are removed, at `breach_ok` spots or anywhere on the exterior, in larger numbers with higher danger. They leak through the real air system.
- **Scorching.** Hull near a breach turns to blackened or cracked blocks (blackstone, black concrete, cracked brick variants).
- **Dead lights.** Some lamps are swapped for unlit blocks.
- **Missing sections.** At danger 2 (half the time) and 3 (always), one optional end piece is left out at assembly time (`omitSection`).
- **What is never touched.** Machines (this mod's blocks) and doors, docking bases with two blocks around them, and every point stay intact, so a damaged derelict can still be docked with and stocked.

Fuel, battery charge and fires need machine data in placement, which `Plan` does not carry yet. They come with derelict content.

## Station tiles

Station tiles are 16 × 16 pieces on a grid, with doorway slots where the generator puts them. They are generated, not assembled (see [station pieces](station-modules-and-systems.md)), though the assembler's connector idea applies to them too: a tunnel piece only has doorways on the sides it runs to, and the layout check and generator follow each piece's own doorway list instead of assuming four doors.

## Not yet

- Block-entity data in placement (sign text, machine fuel, preset container contents). Templates keep it, but placement ignores it.
- Mirroring and multi-tile station rooms.
- Kit and archetype content, and wiring derelicts to it.
- Planet piece sets.

## Where this is tested

These need registries, so they are game tests. `PieceGameTest` covers the codec round trip, markers becoming the right connectors and points and leaving the blocks, and four quarter turns returning the original. `AssemblerGameTest` uses a small test kit and checks the same build for the same seed, no overlaps, in bounds, every minimum met and no open connector left. `ConditionGameTest` covers the condition pass. `author check` flags a connector that is not on the piece's edge, an unknown point name or a palette violation.
