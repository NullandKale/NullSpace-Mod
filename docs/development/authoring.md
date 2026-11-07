# Authoring generated content

How to build the pieces that generated ships, stations and planet sites are assembled from. Part of the [procedural generation design](../design/procedural-generation.md). The commands need operator permission.

## Commands

Stand on a registered pad (its 32-block build volume is the workspace).

| Command | What it does |
| --- | --- |
| `/nullspace author save <kind> <name>` | Saves everything on the pad, trimmed to the blocks you built, as the template `nullspace:<kind>/<name>`. Reports its size, its markers, and any valuable blocks (generated content must use cheap blocks). |
| `/nullspace author load <kind> <name>` | Clears the pad and places the saved piece at its corner, for editing. Save again to update it. |
| `/nullspace author preview <archetype> [danger] [seed]` | Builds a generated site on the pad to review its look: `growing_station`, `derelict_freighter`, `derelict_survey`, or a shipyard design as `ship:<id>` (for example `ship:sparrow`). Containers get the site loot for that danger (0-3). Change the seed to see variations. |
| `/nullspace author gallery [kind]` | Builds a grid of pads at 30000, 100, 30000 in the overworld, one per saved piece (optionally only one kind), and takes you there. |

`<kind>` and `<name>` use lowercase letters, digits and `_`. Suggested kinds: `hull_rivet`, `hull_halcyon`, `hull_bastion` (ship and station kits), `room` (interiors), `surface_<planet type>` (planet sites), `ship` (whole shipyard designs).

In a development run (`scripts/run/dev.ps1`, `scripts/run/playtest.ps1`, `scripts/run/lab.ps1`), saved pieces are also written into `src/main/resources/data/nullspace/structure/`, so they ship with the mod; commit them like any other resource. On a normal server they are saved in the world only.

## Markers

Markers are vanilla blocks placed inside a piece. They are saved with it and read by the assembler (built in steps 3 and 4).

| Marker | Block | Meaning |
| --- | --- | --- |
| Doorway connector | Jigsaw block, facing out through the doorway | Where this piece joins another. Name it by doorway size, for example `door_2x3`. |
| Loot spot | Structure block in Data mode, metadata `loot` (or `loot:<table>`) | A container placed here gets site loot. Without a table, it gets the room's table. |
| Mob spot | Structure block in Data mode, metadata `mob:crew` or `mob:repair_bot` | Where lost crew or repair bots spawn (step 4). |
| Controller | Structure block in Data mode, metadata `controller` | Where a generated ship or station's controller goes. |

## Rules for pieces

- Cheap blocks only: no iron, gold, copper, diamond, emerald, netherite, lapis, redstone or coal blocks. Functional pieces such as iron doors are fine.
- Exteriors: a readable silhouette, two or three exterior materials plus one accent colour per manufacturer, visible function (engines, masts, docking collars, lit windows).
- Leave doorways open where a connector sits; the assembler fills unused doorways.
