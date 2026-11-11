# Authoring generated content

How to build the pieces that shipyard designs, generated ships, stations and planet sites are made from. Part of the [procedural generation design](../design/procedural-generation.md); the code behind it is described in [generation infrastructure](../design/generation-infrastructure.md). The commands need operator permission.

## Commands

Stand on a registered pad (its 32-block build volume is the workspace).

| Command | What it does |
| --- | --- |
| `/nullspace author save <kind> <name>` | Saves everything on the pad, trimmed to the blocks you built, as the template `nullspace:<kind>/<name>`. Reports its size, its markers, and any valuable blocks. A shipyard design (`ship`) keeps the pad corner as its origin and never shrinks, so its helm and rooms keep their coordinates. |
| `/nullspace author load <kind> <name>` | Clears the pad and places the saved piece at its corner, for editing. Save again to update it. |
| `/nullspace author check <kind> <name>` | Lists the piece's connectors and points, and flags problems: a connector not on the face it points through, an unknown point name, valuable blocks. |
| `/nullspace author preview <what> [danger] [seed]` | Builds something on the pad to review: a site (`growing_station`, `derelict_freighter`, `derelict_survey`), a shipyard design (`ship:<id>`), or a kit assembly (`kit:<kit>:<archetype>`). A kit assembly is damaged for the danger (0-3) and reports its pieces, breaches and problems. Change the seed to see variations. |
| `/nullspace author gallery [kind]` | Builds a grid of pads at 30000, 100, 30000 in the overworld, one per saved piece (optionally only one kind), and takes you there. |

`<kind>` and `<name>` use lowercase letters, digits and `_`. A kind may use dots for folders: `kit.rivet` saves `nullspace:kit/rivet/<name>`.

| Kind | What |
| --- | --- |
| `ship` | A shipyard design edited by hand, replacing the generated ship of the same id (see [ship generator](../design/ship-generator.md)) |
| `kit.<maker>` | A manufacturer's kit pieces (nose, hull, engine, wing, room, cap) |
| `surface.<planet type>` | Planet site pieces (step 5) |

In a development run ([`scripts/run/dev.ps1`](../../scripts/run/dev.ps1), [`scripts/run/playtest.ps1`](../../scripts/run/playtest.ps1), [`scripts/run/lab.ps1`](../../scripts/run/lab.ps1)), saved pieces are also written into [`src/main/resources/data/nullspace/structure/`](../../src/main/resources/data/nullspace/structure) and used straight away, so they ship with the mod; commit them like any other resource. On a normal server they are saved in the world, and check, preview and kits still see them.

## Editing a shipyard design

Shipyard ships are [generated](../design/ship-generator.md); you only edit one to change a detail the generator can't.

1. `/nullspace author load ship <id>` on an empty 32-block pad. A generated design is built for you to edit.
2. Edit it in game. Keep exactly one Computer (the helm), a Generator, an Engine, an Air Supply and the docking door, and the engines' exhaust clear.
3. `/nullspace author save ship <id>`.

The saved template replaces the generated ship under that id (delete its file to go back to the generated one). The shipyard, delivery and showroom use it after a restart. A hand-edited design no longer follows the generator's rules (symmetry, no blind spots), so the unit tests do not cover it.

## Markers

Markers are vanilla blocks placed inside a piece. They are removed when the piece is used.

| Marker | Block | Meaning |
| --- | --- | --- |
| Connector | Jigsaw block, facing out of the piece | Where this piece joins another. Its **name** is its type; its **target** is the type it accepts (leave it empty for a symmetric joint such as `spine`). Its **turns into** block (final state) is what the connector becomes; air leaves an opening. |
| Loot spot | Structure block in Data mode, metadata `loot` or `loot:<table>` | A barrel is placed here and gets site loot (`cargo` without a table). The first loot spot of a site gets its treasure. |
| Mob spot | Structure block in Data mode, metadata `mob` or `mob:<kind>` | Where lost crew or repair bots spawn. |
| Core | Structure block in Data mode, metadata `core` | Fallback core position when the piece has no Station Core or Computer block. |
| Breach spot | Structure block in Data mode, metadata `breach_ok` | Damage prefers to tear the hull open here. |

Connectors only align pieces. An end left open after assembly is closed by a `cap` piece, so give a kit a cap for each connector type.

Station pieces are generated, not authored: see the [station generator](../design/ship-generator.md#stations).

## Kits and archetypes

A kit is `data/nullspace/kit/<maker>.json`: its pieces with a role and weight, and an optional material swap so pieces built in neutral blocks serve several makers.

```json
{ "name": "Rivet Works",
  "pieces": [ { "template": "kit/rivet/nose_a", "role": "nose", "weight": 2 },
              { "template": "kit/rivet/hull_a", "role": "hull" },
              { "template": "kit/rivet/cap_spine", "role": "cap" } ],
  "materials": { "minecraft:white_concrete": "minecraft:stone_bricks" } }
```

An archetype is `data/nullspace/archetype/<id>.json`: what to build, from which start role, how many of each role (in priority order) and the largest size.

```json
{ "kind": "ship", "start": "nose",
  "roles": [ { "role": "hull", "min": 1, "max": 3 }, { "role": "engine", "min": 1, "max": 1 },
             { "role": "wing", "min": 0, "max": 2 } ],
  "cap": "cap", "max": [25, 15, 31] }
```

A ship archetype must end up with one Computer, a Generator, an Engine and an Air Supply; a station needs one Station Core. Preview reports anything missing.

## Rules for pieces

- Cheap blocks only: no iron, gold, copper, diamond, emerald, netherite, lapis, redstone or coal blocks. Functional pieces such as iron doors are fine.
- Exteriors: a readable silhouette, two or three exterior materials plus one accent colour per manufacturer, visible function (engines, masts, docking collars, lit windows).
- Every pressurised space needs its own Air Supply.
- Put required machines in pieces that are never optional: at high danger an optional end piece can be left out.
