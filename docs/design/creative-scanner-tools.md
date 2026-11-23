# Creative Scanner tools: redesign

Status: design, not built. Written November 14 from a design talk; the decisions are marked as decided or proposed.

## Where it stands

The Creative System Upgrade (`nullspace:creative_system_upgrade`, no recipe) makes a Scanner tier 4 and adds spawn tools. They need a working ship, because every spawn is reached by an ordinary jump or FTL. Today the tools are crammed into the Scanner's block screen ([`MachineScreen`](../../src/client/java/com/nullandkale/nullspace/ui/screen/MachineScreen.java)) under "Open scanner": unlabeled cycle buttons for kind, danger, story, ship, maker, purpose and size, a seed box, and two action buttons (Start FTL, New system). Nothing says which buttons are settings and which are actions, and the settings have about 112 pixels each.

The "scanner" is really two screens:

- **The block screen**, a grey container screen: On, Name, Open scanner, power, the upgrade slots and the creative controls.
- **The terminal screen**, the blue monospace star map ([`TerminalScreen`](../../src/client/java/com/nullandkale/nullspace/ui/screen/TerminalScreen.java)): the scrolling list (places here, this system's planets, the star map), Search, View, Clear, Set destination, Upgrades and Close. It is the same look as the ship controller and has far more room.

The server side is [`CreativeSpawns`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/CreativeSpawns.java): `system` adds one generated star system within one hop of the ship with a random seed (the Seed box is ignored), and `journey` starts FTL to a temporary encounter with every setting chosen ([`CreativeEncounter`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/CreativeEncounter.java)).

## Decided

- It stays an upgrade in the Scanner, because it needs a working ship.
- A new button on the Scanner opens the tools on a page of their own, and both the Scanner UI and the upgrade UI are redesigned together. The settings get far more space, and the left-side buttons must be clear and visibly different from each other.
- A system is spawned either **random** or **blank** (a star only), and planets are then spawned into a system one at a time. Landable planets need their own dimension, created while the world is running.

## Proposed

**Block screen.** Keeps On, Name, Power, the upgrade slots and Open scanner. The creative controls move off it. The Upgrades panel says what each installed upgrade does (for example "Range 16384", "Creative tools"). With the upgrade installed a **Creative tools** button appears beside Open scanner; it is disabled with a stated reason when the scanner has no power or no working ship.

**Terminal page.** The star map gets a `[ Creative ]` button next to `[ Upgrades ]`, shown only with the upgrade and a working ship. It opens a page in the terminal's own style, with tabs like the ship controller's (`[ Journey ] [ System ] [ Planet ]`, the selected one highlighted). Settings are rows with the label on the left and the value on the right, stepped with arrows. Actions use the terminal's green (as `[ Engage ]` does). Settings that do not apply to the current choice are hidden, not greyed out.

```
SCANNER / CREATIVE        [ Journey ] [ System ] [ Planet ]

Kind          [ < Salvage ship > ]
Danger        [ < 3 > ]
History       [ < Breach > ]
Design        [ < by seed > ]
Seed          [ random            ]

Salvage ship, danger 3, breach, seed random
[ Start FTL ]                                   [ Back ]
```

- **Journey tab:** today's encounter builder (kind, danger, history, ship design, maker, purpose, size, seed).
- **System tab:** `[ Random system ]` and `[ Blank system ]` (name, star class, seed), and a list of the systems made with a delete button each.
- **Planet tab:** the target is the system selected on the star map. Settings: type (the eight in [`GalaxyPlan.BodyType`](../../src/main/java/com/nullandkale/nullspace/model/galaxy/GalaxyPlan.java)), size, orbit, moon-of, name and seed; the surface variant is optional. A list of that system's bodies with a delete button.
- After a spawn the page shows the result line and a `[ Set destination ]` button.

## Landable planets at run time

[`PlanetDimensions`](../../src/main/java/com/nullandkale/nullspace/gameplay/navigation/PlanetDimensions.java) builds a `ServerLevel` for every landable saved body, once, from a mixin on server start (`PlanetDimensionsMixin`), using the terrain template for the planet's type and the body's seed. The saved galaxy is the source of truth, so a planet added to it is recreated on the next start.

Runtime creation is a refactor: pull the single-body part into `create(server, body)` and call it right after the planet is added. Care points:

- **Thread.** The server's level list changes while the server ticks, so the call runs as a queued server task, not inside the tick loop.
- **Connected clients.** A player online learns the dimension list at login. Teleporting into a late-added dimension is expected to work, because the client builds the level from the respawn packet, but this is the first thing to test.
- **Disk.** Every landable planet is a dimension with its own region files. Cap spawned planets (about 32) and allow deleting one.
- Gas giants are not landable and need no dimension.

## Open questions

1. Is the terminal the right home for the creative page? (Recommended; not yet confirmed.)
2. Does the Creative button appear on both screens?
3. Which planet settings are exposed: is type, size, orbit, moon-of, name and seed enough, and should the terrain variant (a to f per type) be selectable?
4. Does a blank system let you choose the star class?
5. Is a cap of about 32 spawned planets with delete acceptable?

## Tests it needs

[`CreativeScannerClientGameTest`](../../src/gametest/java/com/nullandkale/nullspace/gameplay/navigation/CreativeScannerClientGameTest.java) clicks the old block-screen buttons and must be rewritten to click the terminal page. Add a native test for runtime dimension creation (the level exists, a player can enter it, it is recreated after a save and reload) and one for the cap and delete.
