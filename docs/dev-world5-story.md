# World 5: Horizon exploration playtest

This development profile starts Player451 aboard a Halcyon Horizon, the two-deck Halcyon explorer. Engines, generators, batteries and the scanner have three System Upgrades installed. The normal space-start food, tools and suit remain the starting supplies. Fuel is real coal in the generators.

The world uses seed `20261104`. You start above **Elos II**, with **Elos I** selected in the controller. That route has a **Damaged freighter** signal. With the upgraded engines, the journey takes about **28 seconds** of travel, plus its short loading transitions.

[Prepared Horizon bridge](images/world5-horizon-start.png).

## First story

**I explore from my ship and bring back something useful.**

1. Look around the observation bridge. Your food and tools are in your inventory; the Field Guide explains the controls. The lower deck holds the workshop and engineering equipment.
2. Open the **Ship Controller**. Elos I is already selected. Press **Jump**.
3. Stay aboard or walk around while the countdown runs. When the damaged-freighter signal appears, open the controller and press **Investigate signal**. You can also ignore it and continue.
4. Choose the freighter's docking port under **Destination**, then press **Dock**. The ship handles the approach and alignment.
5. Open the docking door and walk through. The freighter's outer entrance is intact. Two cobblestone blocks obstruct its interior cargo doorway; use your pickaxe to clear them.
6. Find the supplied cargo barrel. Take coal, food or materials, then return through the docking connection. The contents are real items and remain depleted.
7. **Undock**, then **Resume journey**. Your original Elos I destination returns, remaining progress is preserved and there is no second jump charge.

If the signal passes before you investigate, finish the trip and jump back along the route. The same site persists. The developer profile gives access to the richest ship; this is a walkthrough of the exploration loop, rather than a measurement of basic-ship survival balance.

## Home and recovery

There is an empty 32-block **Home** landing pad on **Sol / Terra**, with a lit walkway and a Pad Computer. The pad sits above generated terrain with a clear landing column. Its computer is at **-1, 102, 16**; your recovery spawn is at **-3, 102, 16**. These coordinates apply to this seeded profile.

Choose **Home** as the destination, Jump to Terra, then Land. If you die, respawn by the Home computer and use its **Recall ship** controls to recover your empty ship. Other crew must leave before recall; a captured dock must be released first. Your ship's ownership, cargo and installed upgrades persist.

## What to record

- The first moment you cannot tell what to do next.
- Whether you can find fuel, food, the controller, the workshop and the docking doorway without reading a technical document.
- Whether the signal gives you enough time and a clear reason to investigate.
- Whether choosing a port, boarding, returning and resuming feel like one outing.
- Whether returning Home or recovering after a mistake is understandable.

## Reset and persistence

`dev.ps1` reopens the active dev world as Player451. An explicit `dev.ps1 -ResetWorld` prepares this seeded space-start profile, validates a save/reopen, backs up the closed previous World 5 under `run/backups`, then installs the new world. `-StartingShip <preset>` changes the ship for that explicit reset. Use `dev.ps1 --restart` instead to back up the active world and choose your own generation settings in the native creation UI. Missing saves also open that UI. A world created there has its own chosen seed and settings rather than this prepared profile.

Preparation uses an isolated test client under `build/dev-world-preparation`. Installed worlds need only the ordinary mod. Test helpers are excluded from the release JAR. The old World 5 and its previous character-recovery backups remain separate from disposable test worlds.

The [repository review](repo-review-2026-11-04.md) records the fixes and remaining design work. [Verification](world5-story-verification.json) records the exact backup, profile and checks for this installation.
